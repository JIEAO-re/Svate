package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.UiNode
import com.immersive.ui.agent.UiNodePruner
import com.immersive.ui.agent.UiTreeParser
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * Shared helpers for the v1 tools: callback->suspend bridging, JSON schema
 * building, and a compact text rendering of the UI tree.
 *
 * Kept internal to the tools package so each tool file stays focused on one
 * capability.
 */
internal object ToolSupport {

    /** Default timeout for a single gesture dispatch. */
    const val GESTURE_TIMEOUT_MS = 3_000L

    /**
     * Max UI nodes per observation. The pruner ranks by importance and returns at most
     * this many — but only as many as actually exist, so on a normal screen (well under
     * this) it behaves like "no truncation". Set high so complex apps (WeChat/Taobao) do
     * not lose actionable elements like the send button. Trade-off: a genuinely dense
     * screen yields a large observation, and observation text accumulates across the
     * task's turns, so very high values raise token cost / latency.
     */
    const val OBSERVATION_NODE_CAP = 1000

    /**
     * Bridge a callback-style gesture (`fun(x, cb)`) into a suspend boolean.
     * Returns false on timeout so a stuck dispatch never hangs the loop.
     */
    suspend fun awaitGesture(
        timeoutMs: Long = GESTURE_TIMEOUT_MS,
        register: ((Boolean) -> Unit) -> Unit,
    ): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                register { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        } ?: false
    }

    /** Build a JSON Schema object string with the given properties and required keys. */
    fun objectSchema(
        properties: JSONObject,
        required: List<String> = emptyList(),
    ): String {
        val schema = JSONObject()
        schema.put("type", "object")
        schema.put("properties", properties)
        if (required.isNotEmpty()) {
            schema.put("required", JSONArray(required))
        }
        // The model must not invent extra fields it cannot honor.
        schema.put("additionalProperties", false)
        return schema.toString()
    }

    /**
     * Secret-like tokens (API keys) that must never reach the model. The agent reads
     * the UI tree of whatever is on screen; if a screen ever shows the user's API key
     * (Svate's own settings field, or a search box a prior mis-type left it in), the
     * raw key would otherwise enter the model's context and could be echoed back into
     * a device action. Redacting here — the single chokepoint every observation passes
     * through — keeps secrets out of context and breaks that self-reinforcing loop.
     * Pure function (no android.* calls) so renderNodes stays JVM-unit-testable.
     */
    // sk- keeps its own replacement token: it is the most common leak (OpenAI /
    // Anthropic style) and tests/operators recognise the "sk-‹redacted›" marker.
    private val SK_TOKEN = Regex("sk-[A-Za-z0-9_-]{12,}")

    // Other provider key/token shapes. Kept specific (prefix + length) so ordinary
    // on-screen text the agent must read is not over-redacted.
    private val OTHER_SECRET_TOKENS = listOf(
        Regex("AIza[0-9A-Za-z_-]{20,}"),            // Google API keys
        Regex("gh[pousr]_[A-Za-z0-9]{20,}"),        // GitHub tokens
        Regex("(?i)bearer\\s+[A-Za-z0-9._~+/=-]{12,}"), // Authorization: Bearer <token>
    )

    fun redactSecrets(text: String): String {
        if (text.isEmpty()) return text
        var out = SK_TOKEN.replace(text, "sk-‹redacted›")
        for (re in OTHER_SECRET_TOKENS) out = re.replace(out, "‹redacted›")
        return out
    }

    /** A single property descriptor, e.g. prop("string", "the text to type"). */
    fun prop(type: String, description: String): JSONObject {
        return JSONObject().apply {
            put("type", type)
            put("description", description)
        }
    }

    /** An empty object schema for parameterless tools. */
    fun emptySchema(): String = objectSchema(JSONObject())

    /**
     * Read and prune the current UI tree from the running service.
     * Returns an empty list when the service is unavailable.
     */
    fun readPrunedNodes(service: AgentAccessibilityService?): List<UiNode> {
        if (service == null) return emptyList()
        val raw = UiTreeParser.parse(service.getRootNode())
        return UiNodePruner.prune(raw, maxNodes = OBSERVATION_NODE_CAP).nodes
    }

    /**
     * Render pruned nodes into a compact, token-bounded text block for the model.
     * Uses Rect field arithmetic only (no Rect helper methods) so the same render
     * path is safe to exercise from JVM unit tests.
     */
    fun renderNodes(nodes: List<UiNode>, maxNodes: Int = OBSERVATION_NODE_CAP): String {
        if (nodes.isEmpty()) return "(no readable UI nodes)"
        val sb = StringBuilder()
        sb.append("UI nodes (").append(minOf(nodes.size, maxNodes)).append("):\n")
        for (node in nodes.take(maxNodes)) {
            val type = node.className.substringAfterLast('.')
            // Password/secure fields: never forward the actual on-screen value to the
            // model. Keep the field visible (so the agent can target it) but mask text.
            val label = if (node.isPassword) {
                "\"‹password›\""
            } else buildString {
                if (node.text.isNotBlank()) append('"').append(redactSecrets(node.text)).append('"')
                if (node.contentDesc.isNotBlank()) {
                    if (isNotEmpty()) append(' ')
                    append("desc=\"").append(redactSecrets(node.contentDesc)).append('"')
                }
            }.ifBlank { "(no text)" }
            val attrs = buildList {
                if (node.isClickable) add("clickable")
                if (node.isEditable) add("editable")
                if (node.isScrollable) add("scrollable")
                if (node.isPassword) add("password")
            }.joinToString(",")
            sb.append('[').append(node.index).append("] ")
                .append(type).append(' ').append(label)
                .append(" bounds=[")
                .append(node.bounds.left).append(',').append(node.bounds.top).append(',')
                .append(node.bounds.right).append(',').append(node.bounds.bottom).append("] ")
                .append(attrs)
            if (node.packageName.isNotBlank()) {
                sb.append(" pkg=").append(node.packageName)
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }
}
