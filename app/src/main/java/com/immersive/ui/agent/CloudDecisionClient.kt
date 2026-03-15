package com.immersive.ui.agent

import com.immersive.ui.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class CloudCheckpoint(
    val expectedPackage: String,
    val expectedPageType: String,
    val expectedElements: List<String>,
)

data class CloudDecisionResult(
    val traceId: String,
    val action: AgentAction,
    val checkpoint: CloudCheckpoint,
    val reviewerVerdict: String,
    val plannerLatencyMs: Int,
    val reviewerLatencyMs: Int,
    val blockReason: String?,
)

data class SomMarkerPayload(
    val id: Int,
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val className: String,
    val packageName: String,
    val bounds: IntArray,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
)

data class UiNodeStatsPayload(
    val rawCount: Int,
    val prunedCount: Int,
)

class CloudDecisionClient {
    private val baseUrl = BuildConfig.MOBILE_AGENT_BASE_URL.trimEnd('/')
    private val timeoutMs = BuildConfig.MOBILE_AGENT_TIMEOUT_MS.coerceIn(3_000, 30_000)
    private val mode = BuildConfig.MOBILE_AGENT_MODE.lowercase()
        .takeIf { it == "shadow" || it == "active" }
        ?: "active"
    /** Rollout flag controlling whether observation includes the compatibility screenshot_base64 field. */
    private val sendScreenshotBase64 = BuildConfig.SEND_SCREENSHOT_BASE64
    /** P0: Auth token for cloud API authentication */
    private val authToken: String = BuildConfig.MOBILE_AGENT_AUTH_TOKEN

    fun nextStep(
        ctx: AgentContext,
        uiNodes: List<UiNode>,
        frames: List<CapturedFrame>,
        foregroundPackage: String?,
        observationReason: ObservationReason,
        somAnnotatedImageBase64: String? = null,
        somMarkers: List<SomMarkerPayload> = emptyList(),
        uiNodeStats: UiNodeStatsPayload? = null,
        frameFingerprint: String? = null,
        uiTreeXml: String? = null,
        screenDescription: String? = null,
    ): CloudDecisionResult {
        if (frames.isEmpty()) {
            throw IllegalArgumentException("at least one frame is required for cloud decision")
        }

        val url = URL("$baseUrl/api/mobile-agent/next-step")
        val payload = buildRequestPayload(
            ctx = ctx,
            uiNodes = uiNodes,
            frames = frames,
            foregroundPackage = foregroundPackage.orEmpty(),
            observationReason = observationReason,
            somAnnotatedImageBase64 = somAnnotatedImageBase64,
            somMarkers = somMarkers,
            uiNodeStats = uiNodeStats,
            frameFingerprint = frameFingerprint,
            uiTreeXml = uiTreeXml,
            screenDescription = screenDescription,
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // P0 FIX: Inject auth header to prevent 401 rejection in production
            if (authToken.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $authToken")
            }
        }
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
        if (code !in 200..299) {
            throw IllegalStateException("cloud next-step failed: HTTP $code | $body")
        }

        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) {
            throw IllegalStateException("cloud next-step returned success=false: $body")
        }

        val checkpointObj = root.optJSONObject("checkpoint") ?: JSONObject()
        val checkpoint = CloudCheckpoint(
            expectedPackage = checkpointObj.optString("expected_package", ""),
            expectedPageType = checkpointObj.optString("expected_page_type", ""),
            expectedElements = parseStringArray(checkpointObj.optJSONArray("expected_elements")),
        )
        val reviewerObj = root.optJSONObject("reviewer") ?: JSONObject()
        val reviewerVerdict = reviewerObj.optString("verdict", "")
        val reviewerReason = reviewerObj.optString("reason", "")
        val finalActionObj = root.optJSONObject("final_action")
            ?: throw IllegalStateException("missing final_action in response")
        val action = parseAction(finalActionObj, reviewerReason, checkpoint)

        val plannerLatency = root.optJSONObject("planner")?.optInt("latency_ms", 0) ?: 0
        val reviewerLatency = reviewerObj.optInt("latency_ms", 0)
        val blockReason = root.optJSONObject("guard")
            ?.optString("block_reason", "")
            ?.takeIf { it.isNotBlank() }

        return CloudDecisionResult(
            traceId = root.optString("trace_id", ""),
            action = action,
            checkpoint = checkpoint,
            reviewerVerdict = reviewerVerdict,
            plannerLatencyMs = plannerLatency,
            reviewerLatencyMs = reviewerLatency,
            blockReason = blockReason,
        )
    }

    private fun buildRequestPayload(
        ctx: AgentContext,
        uiNodes: List<UiNode>,
        frames: List<CapturedFrame>,
        foregroundPackage: String,
        observationReason: ObservationReason,
        somAnnotatedImageBase64: String? = null,
        somMarkers: List<SomMarkerPayload> = emptyList(),
        uiNodeStats: UiNodeStatsPayload? = null,
        frameFingerprint: String? = null,
        uiTreeXml: String? = null,
        screenDescription: String? = null,
    ): JSONObject {
        val previous = ctx.history.lastOrNull()
        val previousResult = when {
            previous == null -> "NOT_EXECUTED"
            previous.success -> "SUCCESS"
            else -> "FAILED"
        }
        val historyTail = JSONArray().apply {
            for (item in ctx.history.takeLast(8)) {
                put(
                    JSONObject().apply {
                        put("action_intent", item.action.intent.name)
                        put("target_desc", item.action.targetDesc)
                        put("result", item.resultSummary)
                    },
                )
            }
        }
        return JSONObject().apply {
            put("session_id", if (ctx.traceId.isNullOrBlank()) "sess_${System.currentTimeMillis()}" else ctx.traceId)
            put("turn_index", ctx.stepIndex)
            put("mode", mode)
            put("goal", ctx.globalGoal)
            put(
                "task_spec",
                JSONObject().apply {
                    put("mode", ctx.taskSpec.mode.name)
                    put("search_query", ctx.taskSpec.searchQuery)
                    put("ask_on_uncertain", ctx.taskSpec.askOnUncertain)
                },
            )
            put(
                "observation",
                JSONObject().apply {
                    put("observation_reason", observationReason.name)
                    // Server schema alignment: current_app is primary, foreground_package is kept for compatibility.
                    put("current_app", foregroundPackage)
                    put("foreground_package", foregroundPackage)
                    put(
                        "media_window",
                        JSONObject().apply {
                            put("source", "SCREEN_RECORDING")
                            put("frames", toFramesJson(frames))
                        },
                    )
                    if (!somAnnotatedImageBase64.isNullOrBlank()) {
                        put("som_annotated_image_base64", somAnnotatedImageBase64)
                    }
                    if (somMarkers.isNotEmpty()) {
                        put("som_markers", toSomMarkersJson(somMarkers))
                    }
                    if (uiNodeStats != null) {
                        put(
                            "ui_node_stats",
                            JSONObject().apply {
                                put("raw_count", uiNodeStats.rawCount)
                                put("pruned_count", uiNodeStats.prunedCount)
                            },
                        )
                    }
                    if (!frameFingerprint.isNullOrBlank()) {
                        put("frame_fingerprint", frameFingerprint)
                    }
                    // Server schema alignment: ui_tree_xml contains the UI tree serialized as XML text.
                    if (!uiTreeXml.isNullOrBlank()) {
                        put("ui_tree_xml", uiTreeXml)
                    }
                    // Server schema alignment: screen_description contains the screen summary.
                    if (!screenDescription.isNullOrBlank()) {
                        put("screen_description", screenDescription)
                    }
                    // Rollout flag controlling the compatibility screenshot_base64 field.
                    // After the server migration is complete, disable it via BuildConfig.SEND_SCREENSHOT_BASE64.
                    if (sendScreenshotBase64) {
                        frames.last().inlineImageBase64OrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { put("screenshot_base64", it) }
                    }
                    put("ui_nodes", toUiNodesJson(uiNodes))
                    put("previous_action_result", previousResult)
                    put("previous_checkpoint_match", previous?.checkpointMatched ?: false)
                },
            )
            put("history_tail", historyTail)
        }
    }

    private fun parseAction(
        actionObj: JSONObject,
        reviewerReason: String,
        checkpoint: CloudCheckpoint,
    ): AgentAction {
        val intent = try {
            ActionIntent.valueOf(actionObj.optString("intent", "WAIT").uppercase())
        } catch (_: Exception) {
            ActionIntent.WAIT
        }
        val risk = try {
            RiskLevel.valueOf(actionObj.optString("risk_level", "SAFE").uppercase())
        } catch (_: Exception) {
            RiskLevel.SAFE
        }
        val selector = actionObj.optJSONObject("selector")?.let { parseSelector(it) }
        val legacyBbox = actionObj.optJSONArray("target_bbox")
            ?.takeIf { it.length() == 4 }
            ?.let { arr -> IntArray(4) { idx -> arr.optInt(idx, 0) } }
        val targetSomId = actionObj.optInt("target_som_id", -1).takeIf { it > 0 }
        val intentSpec = actionObj.optJSONObject("intent_spec")?.let { parseIntentSpec(it) }

        // ========== P1 addition: parse Spatial Grounding coordinates ==========
        val spatialCoordinates = actionObj.optJSONArray("spatial_coordinates")
            ?.takeIf { it.length() == 2 }
            ?.let { arr ->
                val x = arr.optDouble(0, -1.0).toFloat()
                val y = arr.optDouble(1, -1.0).toFloat()
                if (x in 0f..1f && y in 0f..1f) floatArrayOf(x, y) else null
            }

        val actionCheckpoint = actionObj.optJSONObject("checkpoint")
        val mergedCheckpoint = if (actionCheckpoint != null) {
            CloudCheckpoint(
                expectedPackage = actionCheckpoint.optString("expected_package", checkpoint.expectedPackage),
                expectedPageType = actionCheckpoint.optString("expected_page_type", checkpoint.expectedPageType),
                expectedElements = parseStringArray(actionCheckpoint.optJSONArray("expected_elements")).ifEmpty {
                    checkpoint.expectedElements
                },
            )
        } else {
            checkpoint
        }

        return AgentAction(
            intent = intent,
            actionId = actionObj.optString("action_id", ""),
            targetDesc = actionObj.optString("target_desc", "").ifBlank { "wait" },
            targetBbox = selector?.boundsHint ?: legacyBbox,
            targetSomId = targetSomId,
            spatialCoordinates = spatialCoordinates, // P1: Spatial Grounding
            selector = selector,
            inputText = actionObj.optString("input_text", "").ifBlank { null },
            packageName = actionObj.optString("package_name", "").ifBlank { null },
            intentSpec = intentSpec,
            riskLevel = risk,
            expectedPackage = mergedCheckpoint.expectedPackage.takeIf { it.isNotBlank() },
            expectedPageType = mergedCheckpoint.expectedPageType.takeIf { it.isNotBlank() },
            expectedElements = mergedCheckpoint.expectedElements,
            elderlyNarration = actionObj.optString("narration", "正在处理中，请稍候。"),
            reasoning = reviewerReason.ifBlank { "cloud_review" },
            subStepCompleted = false,
        )
    }

    private fun parseSelector(obj: JSONObject): ActionSelector {
        val bounds = obj.optJSONArray("bounds_hint_0_1000")
        return ActionSelector(
            packageName = obj.optString("package_name", ""),
            resourceId = obj.optString("resource_id", ""),
            text = obj.optString("text", ""),
            contentDesc = obj.optString("content_desc", ""),
            className = obj.optString("class_name", ""),
            boundsHint = bounds?.takeIf { it.length() == 4 }?.let { arr ->
                IntArray(4) { idx -> arr.optInt(idx, 0) }
            },
            nodeSignature = obj.optString("node_signature", ""),
        )
    }

    private fun parseIntentSpec(obj: JSONObject): IntentSpec? {
        val action = obj.optString("action", "").trim()
        if (action.isBlank()) return null
        val extras = mutableMapOf<String, Any?>()
        val extrasObj = obj.optJSONObject("extras")
        if (extrasObj != null) {
            val keys = extrasObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = extrasObj.opt(key)
                extras[key] = if (value == JSONObject.NULL) null else value
            }
        }
        return IntentSpec(
            action = action,
            dataUri = obj.optString("data_uri", "").ifBlank { null },
            packageName = obj.optString("package_name", "").ifBlank { null },
            extras = extras,
        )
    }

    private fun toUiNodesJson(nodes: List<UiNode>): JSONArray {
        val arr = JSONArray()
        for (node in nodes) {
            arr.put(
                JSONObject().apply {
                    put("class_name", node.className)
                    put("text", node.text)
                    put("content_desc", node.contentDesc)
                    put("resource_id", node.viewIdResourceName)
                    put("package_name", node.packageName)
                    put(
                        "bounds",
                        JSONArray().apply {
                            put(node.bounds.left)
                            put(node.bounds.top)
                            put(node.bounds.right)
                            put(node.bounds.bottom)
                        },
                    )
                    put("clickable", node.isClickable)
                    put("editable", node.isEditable)
                    put("scrollable", node.isScrollable)
                    put("visible_to_user", node.isVisibleToUser)
                    put("within_screen", node.isWithinScreen)
                },
            )
        }
        return arr
    }

    private fun toFramesJson(frames: List<CapturedFrame>): JSONArray {
        val arr = JSONArray()
        for (frame in frames) {
            arr.put(
                JSONObject().apply {
                    put("frame_id", frame.frameId)
                    put("ts_ms", frame.tsMs)
                    if (!frame.gcsUri.isNullOrBlank()) {
                        put("gcs_uri", frame.gcsUri)
                    } else {
                        val inlineBase64 = frame.inlineImageBase64OrNull()
                        if (!inlineBase64.isNullOrBlank()) {
                            put("image_base64", inlineBase64)
                        } else {
                            throw IllegalStateException("frame ${frame.frameId} is missing both gcsUri and inline image payload")
                        }
                    }
                    put("ui_signature", frame.uiSignature)
                },
            )
        }
        return arr
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i, "").trim()
            if (value.isNotBlank()) result += value
        }
        return result
    }

    private fun toSomMarkersJson(markers: List<SomMarkerPayload>): JSONArray {
        val arr = JSONArray()
        for (marker in markers) {
            arr.put(
                JSONObject().apply {
                    put("id", marker.id)
                    put("text", marker.text)
                    put("content_desc", marker.contentDesc)
                    put("resource_id", marker.resourceId)
                    put("class_name", marker.className)
                    put("package_name", marker.packageName)
                    put(
                        "bounds",
                        JSONArray().apply {
                            marker.bounds.forEach { put(it) }
                        },
                    )
                    put("clickable", marker.clickable)
                    put("editable", marker.editable)
                    put("scrollable", marker.scrollable)
                },
            )
        }
        return arr
    }
}
