package com.immersive.ui.agent.loop

import com.immersive.ui.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** A single tool call requested by the model in one turn. */
data class TurnToolCall(
    val id: String,
    val name: String,
    val args: JSONObject,
)

/**
 * Adapter metadata mirroring the frozen wire `meta` contract (see
 * docs/agent-loop.md §2 and schemas/agent-turn.ts). All flags default to false
 * when the server omits the `meta` object.
 *
 * @param toolCallsUnsupported the active provider could not deliver function
 *   declarations as tool calls (OpenAI-compat degraded path); the loop cannot
 *   execute tools this turn.
 * @param truncated the model's output was cut off (MAX_TOKENS); the turn must be
 *   treated as a failed turn, never a completion.
 * @param visionInputMissing one or more image inputs never reached the model.
 */
data class TurnMeta(
    val toolCallsUnsupported: Boolean = false,
    val truncated: Boolean = false,
    val visionInputMissing: Boolean = false,
)

/** The model's candidate for one turn: optional narration text plus tool calls. */
data class AgentTurnResponse(
    val traceId: String,
    val model: String,
    val latencyMs: Int,
    val text: String?,
    val toolCalls: List<TurnToolCall>,
    val finished: Boolean,
    val meta: TurnMeta = TurnMeta(),
)

/**
 * Thin client for `POST /api/mobile-agent/agent-turn`.
 *
 * Mirrors CloudDecisionClient exactly: BuildConfig base url/token/timeout, a
 * Bearer auth header only when the token is non-blank, an https requirement that
 * is relaxed only for emulator loopback hosts, defensive errorStream reads, and a
 * finally-disconnect. Serialization is hand-rolled with org.json; no extra
 * dependency is introduced. The blocking I/O runs on Dispatchers.IO through
 * runInterruptible with an outer timeout.
 */
class AgentTurnClient {
    private val baseUrl = BuildConfig.MOBILE_AGENT_BASE_URL.trimEnd('/')

    // Allow up to 60s to match the server's maxDuration = 60; the configured
    // default still comes from BuildConfig.MOBILE_AGENT_TIMEOUT_MS, this only
    // widens the clamp so a legitimately slow turn is not cut short.
    private val timeoutMs = BuildConfig.MOBILE_AGENT_TIMEOUT_MS.coerceIn(3_000, 60_000)
    private val authToken: String = BuildConfig.MOBILE_AGENT_AUTH_TOKEN

    /**
     * Run one agent turn against the cloud proxy.
     *
     * @param sessionId stable session id (^[A-Za-z0-9_-]{1,128}$ on the server).
     * @param traceId per-turn trace id.
     * @param systemInstruction the full system prompt built on device.
     * @param contents Gemini-style running conversation (Content[] JSON array).
     * @param tools tool declarations from ToolRegistry.toDeclarations().
     */
    suspend fun runTurn(
        sessionId: String,
        traceId: String,
        systemInstruction: String,
        contents: JSONArray,
        tools: JSONArray,
        temperature: Double = 0.2,
        maxOutputTokens: Int = 2048,
    ): AgentTurnResponse {
        val url = URL("$baseUrl/api/mobile-agent/agent-turn")
        requireSecureBaseUrl(url)

        val payload = JSONObject().apply {
            put("session_id", sessionId)
            put("trace_id", traceId)
            put("system_instruction", systemInstruction)
            put("contents", contents)
            put("tools", tools)
            put(
                "generation",
                JSONObject().apply {
                    put("temperature", temperature)
                    put("max_output_tokens", maxOutputTokens)
                },
            )
        }

        val payloadText = payload.toString()

        // One bounded retry: a single slow/failed response should not kill the
        // whole task, but coroutine cancellation must still abort immediately and
        // is never retried (rethrown below).
        var lastError: Throwable? = null
        repeat(2) {
            try {
                // Bound the whole blocking exchange; runInterruptible converts
                // cancellation into a thread interrupt so a stuck socket read
                // unwinds cleanly.
                val body = withTimeout(timeoutMs.toLong() + 5_000L) {
                    runInterruptible(Dispatchers.IO) {
                        postJson(url, payloadText)
                    }
                }
                return parseResponse(body)
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                // A per-attempt timeout is retryable: it is the loop's own bound
                // tripping, not the caller cancelling the task.
                lastError = timeout
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                // Genuine coroutine cancellation: stop immediately, never retry.
                throw cancel
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("agent-turn failed")
    }

    /** Perform the blocking POST and return the response body text. */
    private fun postJson(url: URL, payload: String): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // Inject the auth header only when a token is configured; a blank token
            // must not produce a "Bearer " header.
            if (authToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload)
            }
            val code = connection.responseCode
            // errorStream may be null when the connection failed before producing a
            // body, so read it defensively just like CloudDecisionClient.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("agent-turn failed: HTTP $code | $text")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    /** Map the wire response to the domain AgentTurnResponse. */
    private fun parseResponse(body: String): AgentTurnResponse {
        val root = JSONObject(body)
        val assistant = root.optJSONObject("assistant") ?: JSONObject()
        val text = assistant.optString("text", "").takeIf { it.isNotBlank() }

        val toolCalls = mutableListOf<TurnToolCall>()
        val callsArr = assistant.optJSONArray("tool_calls")
        if (callsArr != null) {
            for (i in 0 until callsArr.length()) {
                val callObj = callsArr.optJSONObject(i) ?: continue
                val name = callObj.optString("name", "").trim()
                if (name.isBlank()) continue
                val id = callObj.optString("id", "").ifBlank { "call_${i}_${System.nanoTime()}" }
                val args = callObj.optJSONObject("args") ?: JSONObject()
                toolCalls += TurnToolCall(id = id, name = name, args = args)
            }
        }

        // The server sets finished=true when the turn is complete; treat an absent
        // flag as finished only when there are also no tool calls to run.
        val finished = assistant.optBoolean("finished", toolCalls.isEmpty())

        // Optional adapter metadata (snake_case on the wire). Absent object or
        // absent flags default to false.
        val metaObj = root.optJSONObject("meta")
        val meta = if (metaObj == null) {
            TurnMeta()
        } else {
            TurnMeta(
                toolCallsUnsupported = metaObj.optBoolean("tool_calls_unsupported", false),
                truncated = metaObj.optBoolean("truncated", false),
                visionInputMissing = metaObj.optBoolean("vision_input_missing", false),
            )
        }

        return AgentTurnResponse(
            traceId = root.optString("trace_id", ""),
            model = root.optString("model", ""),
            latencyMs = root.optInt("latency_ms", 0),
            text = text,
            toolCalls = toolCalls,
            finished = finished,
            meta = meta,
        )
    }

    /**
     * Reject plain-http endpoints except emulator/loopback debug hosts, so auth
     * tokens and screen content are never sent in cleartext to a production
     * server. Identical policy to CloudDecisionClient.
     */
    private fun requireSecureBaseUrl(url: URL) {
        if (url.protocol.equals("https", ignoreCase = true)) return
        val host = url.host.orEmpty().lowercase()
        val isDebugLoopbackHost = host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1"
        if (!isDebugLoopbackHost) {
            throw IllegalStateException(
                "MOBILE_AGENT_BASE_URL must use https; plain http is only allowed " +
                    "for emulator debug hosts (10.0.2.2/localhost/127.0.0.1), got: $baseUrl",
            )
        }
    }
}
