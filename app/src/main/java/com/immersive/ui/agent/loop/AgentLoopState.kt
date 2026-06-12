package com.immersive.ui.agent.loop

import org.json.JSONArray
import org.json.JSONObject

/**
 * Holds the running Gemini-style conversation plus loop budgets and the live
 * permission mode.
 *
 * Token discipline (agent-loop.md section 2): only the *latest* observation
 * carries an inline image; earlier observations degrade to text (foreground
 * package + pruned UI nodes). This is enforced at serialization time so callers
 * can append observations freely without tracking which one is newest.
 */
class AgentLoopState(
    initialMode: PermissionMode = PermissionMode.ASK,
    val maxTurns: Int = 24,
    val maxConsecutiveFailures: Int = 4,
) {
    /** Live permission mode; the loop mirrors the UI toggle into here. */
    @Volatile
    var mode: PermissionMode = initialMode

    /** One entry in the running conversation. */
    private sealed interface Entry {
        fun toContent(includeImage: Boolean): JSONObject
        val isObservation: Boolean
    }

    /** A plain user/model/function text or function-call/response entry. */
    private class RawEntry(
        val role: String,
        val parts: JSONArray,
    ) : Entry {
        override val isObservation: Boolean = false
        override fun toContent(includeImage: Boolean): JSONObject {
            return JSONObject().apply {
                put("role", role)
                put("parts", parts)
            }
        }
    }

    /**
     * A screen observation: a text block (foreground + pruned nodes) and an
     * optional inline image. The image is only serialized when this is the latest
     * observation in the conversation.
     */
    private class ObservationEntry(
        val role: String,
        val text: String,
        val imageBase64: String?,
        val mimeType: String,
    ) : Entry {
        override val isObservation: Boolean = true
        override fun toContent(includeImage: Boolean): JSONObject {
            val parts = JSONArray()
            parts.put(JSONObject().put("text", text))
            if (includeImage && !imageBase64.isNullOrBlank()) {
                parts.put(
                    JSONObject().apply {
                        put("inline_image_base64", imageBase64)
                        put("mime_type", mimeType)
                    },
                )
            }
            return JSONObject().apply {
                put("role", role)
                put("parts", parts)
            }
        }
    }

    private val entries = mutableListOf<Entry>()

    private var turnCount: Int = 0
    private var consecutiveFailures: Int = 0

    /** Append a user text message (e.g. the initial goal, or an ask_user answer). */
    fun appendUserText(text: String) {
        entries.add(RawEntry("user", JSONArray().put(JSONObject().put("text", text))))
    }

    /** Append the model's narration text for this turn (kept for context). */
    fun appendModelText(text: String) {
        if (text.isBlank()) return
        entries.add(RawEntry("model", JSONArray().put(JSONObject().put("text", text))))
    }

    /** Append the model's function_call so the next turn sees the prior call. */
    fun appendFunctionCall(name: String, args: JSONObject) {
        val part = JSONObject().put(
            "function_call",
            JSONObject().apply {
                put("name", name)
                put("args", args)
            },
        )
        entries.add(RawEntry("model", JSONArray().put(part)))
    }

    /** Append a function_response carrying the tool's structured result. */
    fun appendFunctionResponse(name: String, response: JSONObject) {
        val part = JSONObject().put(
            "function_response",
            JSONObject().apply {
                put("name", name)
                put("response", response)
            },
        )
        entries.add(RawEntry("function", JSONArray().put(part)))
    }

    /**
     * Append a fresh screen observation. Once appended, any previously appended
     * observation will serialize as text only (its image is dropped to bound tokens).
     */
    fun appendObservation(text: String, imageBase64: String?, mimeType: String = "image/jpeg") {
        entries.add(ObservationEntry("user", text, imageBase64, mimeType))
    }

    /**
     * Serialize the full conversation as a Gemini Content[] array, keeping the
     * inline image only on the most recent observation entry.
     */
    fun toContents(): JSONArray {
        val lastObservationIndex = entries.indexOfLast { it.isObservation }
        val arr = JSONArray()
        entries.forEachIndexed { index, entry ->
            arr.put(entry.toContent(includeImage = index == lastObservationIndex))
        }
        return arr
    }

    // ===== Budgets =====

    /** Record that a turn was consumed and report whether the budget remains. */
    fun beginTurn(): Boolean {
        turnCount += 1
        return turnCount <= maxTurns
    }

    fun turnsUsed(): Int = turnCount

    /** Reset the consecutive-failure counter after a successful tool. */
    fun recordToolSuccess() {
        consecutiveFailures = 0
    }

    /** Increment the consecutive-failure counter and report budget exhaustion. */
    fun recordToolFailure(): Boolean {
        consecutiveFailures += 1
        return consecutiveFailures < maxConsecutiveFailures
    }

    fun consecutiveFailures(): Int = consecutiveFailures
}
