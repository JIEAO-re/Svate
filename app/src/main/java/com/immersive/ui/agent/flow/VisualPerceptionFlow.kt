package com.immersive.ui.agent.flow

import android.util.Log
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.AgentCaptureService
import com.immersive.ui.agent.CapturedFrame
import com.immersive.ui.agent.FrameFingerprint
import com.immersive.ui.agent.PopupDetection
import com.immersive.ui.agent.PopupRecovery
import com.immersive.ui.agent.SomMarkerPayload
import com.immersive.ui.agent.SomRenderer
import com.immersive.ui.agent.UiNode
import com.immersive.ui.agent.UiNodePruner
import com.immersive.ui.agent.UiTreeParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * P1 reactive refactor: visual perception pipeline.
 *
 * Responsibilities:
 * - Sample frames at high frequency using reactive debounce instead of rigid waits
 * - Compute frame fingerprints for deduplication
 * - React quickly to local popups through PopupRecovery.fastDetectLocal
 * - Render SoM overlays
 * - Prune the UI tree
 *
 * Output: a PerceptionSnapshot stream consumed by LiveDecisionClient.
 */
@OptIn(FlowPreview::class)
class VisualPerceptionFlow(
    private val scope: CoroutineScope,
    private val config: PerceptionConfig = PerceptionConfig(),
) {
    companion object {
        private const val TAG = "VisualPerceptionFlow"
    }

    // ========== Output stream ==========
    private val _snapshots = MutableSharedFlow<PerceptionSnapshot>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val snapshots: SharedFlow<PerceptionSnapshot> = _snapshots.asSharedFlow()

    private val _popupDetected = MutableSharedFlow<PopupEvent>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val popupDetected: SharedFlow<PopupEvent> = _popupDetected.asSharedFlow()

    private val _state = MutableStateFlow(PerceptionState.IDLE)
    val state: StateFlow<PerceptionState> = _state.asStateFlow()

    // ========== Internal state ==========
    private var perceptionJob: Job? = null
    private var lastFingerprint: String? = null
    private var lastSomMarkerMap: Map<Int, UiNode> = emptyMap()

    fun start() {
        if (perceptionJob?.isActive == true) return
        _state.value = PerceptionState.RUNNING
        lastFingerprint = null
        lastSomMarkerMap = emptyMap()

        perceptionJob = scope.launch(Dispatchers.IO) {
            // Subscribe to UI-change events and use debounce instead of rigid waits.
            val uiEventFlow = AgentAccessibilityService.uiChangeEvents
                .debounce(config.debounceMs)
                .map { it.packageName to it.eventTime }

            // Main perception loop.
            launch {
                uiEventFlow.collect { (pkg, _) ->
                    if (!isActive) return@collect
                    try {
                        captureAndEmit(pkg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Perception cycle failed", e)
                    }
                }
            }

            // Fallback polling in case UI events are missed.
            launch {
                while (isActive) {
                    delay(config.fallbackPollMs)
                    try {
                        val pkg = AgentAccessibilityService.instance?.getForegroundPackageName()
                        captureAndEmit(pkg)
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallback poll failed", e)
                    }
                }
            }
        }
    }

    fun stop() {
        perceptionJob?.cancel()
        perceptionJob = null
        _state.value = PerceptionState.IDLE
        lastFingerprint = null
        lastSomMarkerMap = emptyMap()
    }

    fun getLastSomMarkerMap(): Map<Int, UiNode> = lastSomMarkerMap

    private suspend fun captureAndEmit(foregroundPackage: String?) {
        val service = AgentAccessibilityService.instance ?: return
        val captureService = AgentCaptureService.instance ?: return

        // 1. Fetch the UI tree.
        val rawNodes = service.getUiNodes()
        if (rawNodes.isEmpty()) return

        // 2. Quick local popup interception.
        val popup = PopupRecovery.detect(rawNodes)
        if (popup != null) {
            _popupDetected.tryEmit(PopupEvent(popup, rawNodes))
            // Try to close the popup automatically.
            if (PopupRecovery.dismiss(popup, rawNodes, service)) {
                Log.d(TAG, "Popup auto-dismissed: ${popup.type}")
                delay(300)
                return
            }
        }

        // 3. Prune the UI tree.
        val pruneResult = if (config.enablePruning) {
            UiNodePruner.prune(rawNodes)
        } else {
            UiNodePruner.Result(rawNodes, rawNodes.size, rawNodes.size)
        }
        val prunedNodes = pruneResult.nodes

        // 4. Compute the UI signature.
        val uiSignature = computeUiSignature(prunedNodes)

        // 5. Capture the screen.
        val frame = captureService.captureFrame(uiSignature) ?: return

        // 6. Compute the frame fingerprint for deduplication.
        val fingerprint = FrameFingerprint.build(
            foregroundPackage = foregroundPackage,
            uiNodes = prunedNodes,
            imageBytes = frame.imageBytes,
        )
        if (fingerprint == lastFingerprint && config.enableDedup) {
            return // 帧未变化，跳过
        }
        lastFingerprint = fingerprint

        // 7. Render SoM overlays.
        val (screenWidth, screenHeight) = service.getScreenSize()
        val somResult = if (config.enableSom) {
            val interactiveNodes = SomRenderer.filterInteractiveNodes(prunedNodes)
            SomRenderer.render(
                screenshotBytes = frame.imageBytes ?: return,
                interactiveNodes = interactiveNodes,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
            )
        } else {
            null
        }
        lastSomMarkerMap = somResult?.markerMap ?: emptyMap()

        // 8. Build the SoM markers payload.
        val somMarkers = somResult?.markerMap?.entries?.map { (id, node) ->
            SomMarkerPayload(
                id = id,
                text = node.text,
                contentDesc = node.contentDesc,
                resourceId = node.viewIdResourceName,
                className = node.className,
                packageName = node.packageName,
                bounds = intArrayOf(
                    node.bounds.left,
                    node.bounds.top,
                    node.bounds.right,
                    node.bounds.bottom,
                ),
                clickable = node.isClickable,
                editable = node.isEditable,
                scrollable = node.isScrollable,
            )
        }.orEmpty()

        // 9. Emit the perception snapshot.
        val snapshot = PerceptionSnapshot(
            frame = frame,
            foregroundPackage = foregroundPackage,
            prunedNodes = prunedNodes,
            rawNodeCount = pruneResult.rawCount,
            prunedNodeCount = pruneResult.prunedCount,
            uiSignature = uiSignature,
            fingerprint = fingerprint,
            somAnnotatedImageBase64 = somResult?.annotatedImageBase64,
            somMarkers = somMarkers,
            uiTreeText = UiTreeParser.formatForPrompt(prunedNodes),
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            timestampMs = System.currentTimeMillis(),
        )
        _snapshots.emit(snapshot)
    }

    private fun computeUiSignature(nodes: List<UiNode>): String {
        return nodes.take(40)
            .joinToString("|") { node ->
                "${node.packageName}#${node.className}#${node.viewIdResourceName}#${node.text}#${node.contentDesc}"
            }
            .hashCode()
            .toString()
    }
}

// ========== Data classes ==========

data class PerceptionConfig(
    val debounceMs: Long = 500L,
    val fallbackPollMs: Long = 2000L,
    val enablePruning: Boolean = true,
    val enableSom: Boolean = true,
    val enableDedup: Boolean = true,
)

enum class PerceptionState {
    IDLE,
    RUNNING,
}

data class PerceptionSnapshot(
    val frame: CapturedFrame,
    val foregroundPackage: String?,
    val prunedNodes: List<UiNode>,
    val rawNodeCount: Int,
    val prunedNodeCount: Int,
    val uiSignature: String,
    val fingerprint: String,
    val somAnnotatedImageBase64: String?,
    val somMarkers: List<SomMarkerPayload>,
    val uiTreeText: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val timestampMs: Long,
)

data class PopupEvent(
    val popup: PopupDetection,
    val nodes: List<UiNode>,
)
