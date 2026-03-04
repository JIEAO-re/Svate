package com.immersive.ui.agent

import android.content.Context
import com.immersive.ui.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

class TelemetryReporter(
    private val context: Context,
) {
    private val enabled = BuildConfig.MOBILE_AGENT_TELEMETRY_ENABLED
    private val endpoint = BuildConfig.MOBILE_AGENT_BASE_URL.trimEnd('/') + "/api/mobile-agent/telemetry"
    private val worker = Executors.newSingleThreadExecutor()

    fun report(
        sessionId: String,
        traceId: String,
        turnIndex: Int,
        eventType: String,
        payload: JSONObject,
    ) {
        if (!enabled) return
        worker.execute {
            try {
                val event = JSONObject().apply {
                    put("trace_id", traceId.ifBlank { "trace_${System.currentTimeMillis()}" })
                    put("session_id", sessionId)
                    put("turn_index", turnIndex)
                    put("event_type", eventType)
                    put("payload", payload)
                    put("ts", Instant.now().toString())
                }
                val requestBody = JSONObject().apply {
                    put("events", JSONArray().apply { put(event) })
                }
                postJson(endpoint, requestBody)
            } catch (_: Throwable) {
                // Never block agent flow on telemetry failure.
            }
        }
    }

    private fun postJson(urlText: String, payload: JSONObject) {
        val url = URL(urlText)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${com.immersive.ui.BuildConfig.MOBILE_AGENT_AUTH_TOKEN}")
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText)
    }
}
