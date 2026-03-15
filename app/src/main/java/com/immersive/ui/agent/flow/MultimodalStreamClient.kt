package com.immersive.ui.agent.flow

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * P2 multimodal streaming transition: streaming client implemented with HTTP long polling.
 *
 * Architecture:
 * - Prepare the stack for the Gemini Multimodal Live API
 * - Simulate bidirectional streaming with HTTP long polling for now
 * - Upgrade to WebSocket or WebRTC later
 *
 * Protocol design (JSON-RPC style):
 * Client -> Server:
 *   POST /stream/frame { "session_id": "...", "frame": { ... } }
 *   GET /stream/poll?session_id=...
 *
 * Server -> Client:
 *   { "type": "action", "action": { "intent": "CLICK", ... } }
 *   { "type": "error", "message": "..." }
 *
 * TODO P2: upgrade to a WebSocket implementation once OkHttp or Java-WebSocket is added.
 */
class MultimodalStreamClient(
    private val scope: CoroutineScope,
    private val config: StreamClientConfig = StreamClientConfig(),
) {
    companion object {
        private const val TAG = "MultimodalStreamClient"
    }

    // ========== Output stream ==========
    private val _actions = MutableSharedFlow<StreamAction>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val actions: SharedFlow<StreamAction> = _actions.asSharedFlow()

    private val _connectionState = MutableStateFlow(StreamConnectionState.DISCONNECTED)
    val connectionState: StateFlow<StreamConnectionState> = _connectionState.asStateFlow()

    private val _errors = MutableSharedFlow<StreamError>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<StreamError> = _errors.asSharedFlow()

    // ========== Internal state ==========
    private var pollingJob: Job? = null
    private var heartbeatJob: Job? = null

    private val isConnecting = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var sessionId: String = ""
    private var authToken: String? = null

    /**
     * Connect to the streaming service and start long polling.
     */
    fun connect(sessionId: String, authToken: String? = null) {
        if (isConnecting.getAndSet(true)) return
        this.sessionId = sessionId
        this.authToken = authToken

        _connectionState.value = StreamConnectionState.CONNECTING

        // Start long polling.
        pollingJob = scope.launch(Dispatchers.IO) {
            try {
                _connectionState.value = StreamConnectionState.CONNECTED
                isConnecting.set(false)
                reconnectAttempts.set(0)

                while (isActive && _connectionState.value == StreamConnectionState.CONNECTED) {
                    try {
                        pollForActions()
                    } catch (e: Exception) {
                        Log.w(TAG, "Polling error", e)
                    }
                    delay(config.pollIntervalMs)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Polling loop failed", e)
                _connectionState.value = StreamConnectionState.DISCONNECTED
                isConnecting.set(false)

                _errors.emit(StreamError(
                    code = "connection_failed",
                    message = e.message ?: "Unknown error",
                ))

                if (config.autoReconnect) {
                    scheduleReconnect()
                }
            }
        }

        // Start the heartbeat loop.
        startHeartbeat()
    }

    /**
     * Disconnect.
     */
    fun disconnect() {
        pollingJob?.cancel()
        heartbeatJob?.cancel()
        pollingJob = null
        heartbeatJob = null
        _connectionState.value = StreamConnectionState.DISCONNECTED
        isConnecting.set(false)
        reconnectAttempts.set(0)
    }

    /**
     * Push frame data.
     */
    fun pushFrame(frame: StreamFrame): Boolean {
        if (_connectionState.value != StreamConnectionState.CONNECTED) return false

        scope.launch(Dispatchers.IO) {
            try {
                sendFrame(frame)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to push frame", e)
            }
        }
        return true
    }

    /**
     * Send the user's confirmation result.
     */
    fun sendConfirmation(actionId: String, confirmed: Boolean): Boolean {
        if (_connectionState.value != StreamConnectionState.CONNECTED) return false

        scope.launch(Dispatchers.IO) {
            try {
                sendConfirmationRequest(actionId, confirmed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send confirmation", e)
            }
        }
        return true
    }

    private suspend fun pollForActions() {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("${config.httpEndpoint}/stream/poll?session_id=$sessionId")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = config.connectTimeoutMs.toInt()
            connection.readTimeout = config.pollTimeoutMs.toInt()
            authToken?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                if (response.isNotBlank()) {
                    handleMessage(response)
                }
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun sendFrame(frame: StreamFrame) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("${config.httpEndpoint}/stream/frame")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = config.connectTimeoutMs.toInt()
            connection.readTimeout = config.writeTimeoutMs.toInt()
            connection.doOutput = true
            authToken?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }

            val payload = JSONObject().apply {
                put("session_id", sessionId)
                put("frame", JSONObject().apply {
                    put("image_base64", frame.imageBase64)
                    put("ui_tree", frame.uiTreeText)
                    put("foreground_package", frame.foregroundPackage)
                    put("timestamp", frame.timestampMs)
                    frame.gcsUri?.let { put("gcs_uri", it) }
                    frame.somMarkers?.let { markers ->
                        put("som_markers", JSONArray().apply {
                            markers.forEach { marker ->
                                put(JSONObject().apply {
                                    put("id", marker.id)
                                    put("text", marker.text)
                                    put("bounds", JSONArray(marker.bounds.toList()))
                                })
                            }
                        })
                    }
                })
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            connection.responseCode // Trigger the request
        } finally {
            connection?.disconnect()
        }
    }

    private fun sendConfirmationRequest(actionId: String, confirmed: Boolean) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("${config.httpEndpoint}/stream/confirmation")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = config.connectTimeoutMs.toInt()
            connection.readTimeout = config.writeTimeoutMs.toInt()
            connection.doOutput = true
            authToken?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }

            val payload = JSONObject().apply {
                put("session_id", sessionId)
                put("action_id", actionId)
                put("confirmed", confirmed)
            }

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            connection.responseCode
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "action" -> {
                    val actionJson = json.getJSONObject("action")
                    val action = parseStreamAction(actionJson)
                    _actions.emit(action)
                }

                "error" -> {
                    val message = json.optString("message", "Unknown error")
                    _errors.emit(StreamError(code = "server_error", message = message))
                }

                "tool_call" -> {
                    val toolCall = json.getJSONObject("tool_call")
                    val functionName = toolCall.optString("name")
                    val args = toolCall.optJSONObject("arguments")
                    if (functionName == "execute_action" && args != null) {
                        val action = parseStreamAction(args)
                        _actions.emit(action)
                    }
                }

                else -> {
                    Log.w(TAG, "Unknown message type: ${json.optString("type")}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: $text", e)
        }
    }

    private fun parseStreamAction(json: JSONObject): StreamAction {
        val spatialCoords = json.optJSONArray("spatial_coordinates")
            ?.takeIf { it.length() == 2 }
            ?.let { arr ->
                floatArrayOf(
                    arr.optDouble(0).toFloat(),
                    arr.optDouble(1).toFloat(),
                )
            }

        return StreamAction(
            actionId = json.optString("action_id", ""),
            intent = json.optString("intent", "WAIT"),
            targetDesc = json.optString("target_desc", ""),
            spatialCoordinates = spatialCoords,
            inputText = json.optString("input_text").takeIf { it.isNotBlank() },
            packageName = json.optString("package_name").takeIf { it.isNotBlank() },
            elderlyNarration = json.optString("elderly_narration", ""),
            reasoning = json.optString("reasoning", ""),
            riskLevel = json.optString("risk_level", "SAFE"),
            requiresConfirmation = json.optBoolean("requires_confirmation", false),
        )
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && _connectionState.value == StreamConnectionState.CONNECTED) {
                delay(config.heartbeatIntervalMs)
                // Heartbeats are implicitly handled by polling.
            }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts.get() >= config.maxReconnectAttempts) {
            Log.w(TAG, "Max reconnect attempts reached")
            return
        }

        scope.launch(Dispatchers.IO) {
            val attempt = reconnectAttempts.incrementAndGet()
            val delayMs = config.reconnectBaseDelayMs * (1 shl (attempt - 1).coerceAtMost(5))
            Log.d(TAG, "Scheduling reconnect in ${delayMs}ms (attempt $attempt)")

            delay(delayMs)

            _connectionState.value = StreamConnectionState.RECONNECTING
            connect(sessionId, authToken)
        }
    }
}

// ========== Data classes ==========

data class StreamClientConfig(
    val httpEndpoint: String = "https://stave-api.example.com/api",
    val connectTimeoutMs: Long = 10_000L,
    val writeTimeoutMs: Long = 10_000L,
    val pollIntervalMs: Long = 500L,
    val pollTimeoutMs: Long = 30_000L,
    val heartbeatIntervalMs: Long = 25_000L,
    val autoReconnect: Boolean = true,
    val maxReconnectAttempts: Int = 5,
    val reconnectBaseDelayMs: Long = 1_000L,
)

enum class StreamConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

data class StreamFrame(
    val imageBase64: String,
    val uiTreeText: String,
    val foregroundPackage: String?,
    val timestampMs: Long,
    val gcsUri: String? = null,
    val somMarkers: List<StreamSomMarker>? = null,
)

data class StreamSomMarker(
    val id: Int,
    val text: String,
    val bounds: IntArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StreamSomMarker
        return id == other.id && text == other.text && bounds.contentEquals(other.bounds)
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + text.hashCode()
        result = 31 * result + bounds.contentHashCode()
        return result
    }
}

data class StreamAction(
    val actionId: String,
    val intent: String,
    val targetDesc: String,
    val spatialCoordinates: FloatArray?,
    val inputText: String?,
    val packageName: String?,
    val elderlyNarration: String,
    val reasoning: String,
    val riskLevel: String,
    val requiresConfirmation: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StreamAction
        return actionId == other.actionId
    }

    override fun hashCode(): Int = actionId.hashCode()
}

data class StreamError(
    val code: String,
    val message: String,
)
