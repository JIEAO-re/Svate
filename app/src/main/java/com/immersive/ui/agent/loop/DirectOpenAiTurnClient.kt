package com.immersive.ui.agent.loop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Device-direct turn client: drives one agent-loop turn by calling the user's
 * own OpenAI-compatible `/chat/completions` endpoint with real function calling.
 * No project backend and no Gemini default — the device talks to the configured
 * base URL + model + key straight from [EndpointConfig].
 *
 * Mirrors the backend's runAgentTurnViaOpenAI: the Gemini-style running
 * conversation is converted to OpenAI `messages` (roles preserved, function_call
 * → assistant tool_calls paired positionally, function_response → tool messages,
 * inline images → image_url), tool declarations are sent, and the candidate's
 * tool_calls are mapped back to the loop's domain model.
 */
class DirectOpenAiTurnClient(private val config: EndpointConfig) : TurnClient {

    private val timeoutMs = 60_000

    override suspend fun runTurn(
        sessionId: String,
        traceId: String,
        systemInstruction: String,
        contents: JSONArray,
        tools: JSONArray,
        temperature: Double,
        maxOutputTokens: Int,
    ): AgentTurnResponse {
        val url = URL("${config.normalizedBaseUrl()}/chat/completions")
        requireSecureUrl(url)

        val payload = JSONObject().apply {
            put("model", config.model)
            put("messages", buildMessages(systemInstruction, contents))
            val openAiTools = buildTools(tools)
            if (openAiTools.length() > 0) {
                put("tools", openAiTools)
                put("tool_choice", "auto")
            }
            put("temperature", temperature)
            put("max_tokens", maxOutputTokens)
        }
        val payloadText = payload.toString()

        // One bounded retry, matching AgentTurnClient; cancellation aborts immediately.
        var lastError: Throwable? = null
        repeat(2) {
            try {
                val body = withTimeout(timeoutMs.toLong() + 5_000L) {
                    runInterruptible(Dispatchers.IO) { postJson(url, payloadText) }
                }
                return parseResponse(traceId, body)
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                lastError = timeout
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                lastError = t
            }
        }
        throw lastError ?: IllegalStateException("direct openai turn failed")
    }

    // ===== Request construction =====

    /**
     * Convert the Gemini-style Content[] running conversation into OpenAI chat
     * messages. The device sends each function_call as its own `model` turn
     * immediately followed by the matching `function` turn (agent-loop.md §2), so
     * tool-call ids are paired positionally with a FIFO queue.
     */
    private fun buildMessages(systemInstruction: String, contents: JSONArray): JSONArray {
        val messages = JSONArray()
        if (systemInstruction.isNotBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", systemInstruction))
        }

        val pendingToolIds = ArrayDeque<String>()
        var counter = 0

        for (i in 0 until contents.length()) {
            val content = contents.optJSONObject(i) ?: continue
            val role = content.optString("role")
            val parts = content.optJSONArray("parts") ?: JSONArray()

            when (role) {
                "function" -> {
                    for (j in 0 until parts.length()) {
                        val fr = parts.optJSONObject(j)?.optJSONObject("function_response") ?: continue
                        val toolCallId = if (pendingToolIds.isNotEmpty()) pendingToolIds.removeFirst() else "call_${counter++}"
                        messages.put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", toolCallId)
                                .put("content", (fr.optJSONObject("response") ?: JSONObject()).toString()),
                        )
                    }
                }

                "model" -> {
                    val toolCalls = JSONArray()
                    val textBuf = StringBuilder()
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        val fc = part.optJSONObject("function_call")
                        if (fc != null) {
                            val id = "call_${counter++}"
                            pendingToolIds.addLast(id)
                            toolCalls.put(
                                JSONObject()
                                    .put("id", id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", fc.optString("name"))
                                            .put("arguments", (fc.optJSONObject("args") ?: JSONObject()).toString()),
                                    ),
                            )
                        } else if (part.has("text")) {
                            textBuf.append(part.optString("text"))
                        }
                    }
                    val msg = JSONObject().put("role", "assistant")
                    if (toolCalls.length() > 0) {
                        msg.put("content", if (textBuf.isEmpty()) JSONObject.NULL else textBuf.toString())
                        msg.put("tool_calls", toolCalls)
                    } else {
                        msg.put("content", textBuf.toString())
                    }
                    messages.put(msg)
                }

                else -> { // "user"
                    val contentArr = JSONArray()
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        when {
                            part.has("text") ->
                                contentArr.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                            part.has("inline_image_base64") -> {
                                val b64 = part.optString("inline_image_base64")
                                val mime = part.optString("mime_type", "image/jpeg")
                                contentArr.put(
                                    JSONObject().put("type", "image_url").put(
                                        "image_url",
                                        JSONObject().put("url", "data:$mime;base64,$b64"),
                                    ),
                                )
                            }
                        }
                    }
                    val msg = JSONObject().put("role", "user")
                    val only = if (contentArr.length() == 1) contentArr.optJSONObject(0) else null
                    if (only != null && only.optString("type") == "text") {
                        msg.put("content", only.optString("text"))
                    } else {
                        msg.put("content", contentArr)
                    }
                    messages.put(msg)
                }
            }
        }
        return messages
    }

    /** Map the wire tool declarations to OpenAI `tools` (function) format. */
    private fun buildTools(tools: JSONArray): JSONArray {
        val arr = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            arr.put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject()
                        .put("name", t.optString("name"))
                        .put("description", t.optString("description"))
                        .put(
                            "parameters",
                            t.optJSONObject("parameters_json_schema") ?: JSONObject().put("type", "object"),
                        ),
                ),
            )
        }
        return arr
    }

    // ===== Networking + parsing =====

    private fun postJson(url: URL, payload: String): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            if (config.apiKey.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            }
        }
        try {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(payload) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("chat/completions failed: HTTP $code | ${text.take(300)}")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResponse(traceId: String, body: String): AgentTurnResponse {
        val root = JSONObject(body)
        val choice = root.optJSONArray("choices")?.optJSONObject(0) ?: JSONObject()
        val message = choice.optJSONObject("message") ?: JSONObject()
        val finishReason = choice.optString("finish_reason", "")

        val text = message.optString("content", "").takeIf { it.isNotBlank() }

        val toolCalls = mutableListOf<TurnToolCall>()
        val rawCalls = message.optJSONArray("tool_calls")
        if (rawCalls != null) {
            for (i in 0 until rawCalls.length()) {
                val tc = rawCalls.optJSONObject(i) ?: continue
                val fn = tc.optJSONObject("function") ?: continue
                val name = fn.optString("name", "").trim()
                if (name.isBlank()) continue
                val args = parseArgs(fn.opt("arguments"))
                val id = tc.optString("id", "").ifBlank { "call_${i}_${System.nanoTime()}" }
                toolCalls += TurnToolCall(id = id, name = name, args = args)
            }
        }

        // A "length" finish means the output was cut off; treat as a failed turn.
        val truncated = finishReason == "length"
        val modelName = root.optString("model", config.model)

        return AgentTurnResponse(
            traceId = traceId,
            model = modelName,
            latencyMs = 0,
            text = text,
            toolCalls = toolCalls,
            finished = if (truncated) false else toolCalls.isEmpty(),
            meta = TurnMeta(truncated = truncated),
        )
    }

    /** OpenAI returns tool-call arguments as a JSON string; tolerate an object too. */
    private fun parseArgs(raw: Any?): JSONObject = when (raw) {
        is JSONObject -> raw
        is String -> if (raw.isBlank()) JSONObject() else runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        else -> JSONObject()
    }

    private fun requireSecureUrl(url: URL) {
        if (url.protocol.equals("https", ignoreCase = true)) return
        val host = url.host.orEmpty().lowercase()
        val isLoopback = host == "10.0.2.2" || host == "localhost" || host == "127.0.0.1"
        if (!isLoopback) {
            throw IllegalStateException(
                "Model endpoint base URL must use https; plain http is only allowed for " +
                    "loopback debug hosts (10.0.2.2/localhost/127.0.0.1), got: ${config.baseUrl}",
            )
        }
    }
}
