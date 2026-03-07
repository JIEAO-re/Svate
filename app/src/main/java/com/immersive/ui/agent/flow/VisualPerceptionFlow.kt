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
 * P1 响应式重构：视觉感知器流水线
 *
 * 职责：
 * - 高频抽帧（响应式 debounce 替代死板 wait）
 * - 计算帧指纹（去重）
 * - 本地弹窗快反射拦截（PopupRecovery.fastDetectLocal）
 * - SoM 标注渲染
 * - UI 树剪枝
 *
 * 输出：PerceptionSnapshot 流，供 LiveDecisionClient 消费
 */
@OptIn(FlowPreview::class)
class VisualPerceptionFlow(
    private val scope: CoroutineScope,
    private val config: PerceptionConfig = PerceptionConfig(),
) {
    companion object {
        private const val TAG = "VisualPerceptionFlow"
    }

    // ========== 输出流 ==========
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

    // ========== 内部状态 ==========
    private var perceptionJob: Job? = null
    private var lastFingerprint: String? = null
    private var lastSomMarkerMap: Map<Int, UiNode> = emptyMap()

    fun start() {
        if (perceptionJob?.isActive == true) return
        _state.value = PerceptionState.RUNNING
        lastFingerprint = null
        lastSomMarkerMap = emptyMap()

        perceptionJob = scope.launch(Dispatchers.IO) {
            // 订阅 UI 变化事件流，用 debounce 替代死板等待
            val uiEventFlow = AgentAccessibilityService.uiChangeEvents
                .debounce(config.debounceMs)
                .map { it.packageName to it.eventTime }

            // 主感知循环
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

            // 兜底轮询（防止 UI 事件丢失）
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

        // 1. 获取 UI 树
        val rawNodes = service.getUiNodes()
        if (rawNodes.isEmpty()) return

        // 2. 本地弹窗快反射拦截
        val popup = PopupRecovery.detect(rawNodes)
        if (popup != null) {
            _popupDetected.tryEmit(PopupEvent(popup, rawNodes))
            // 尝试自动关闭弹窗
            if (PopupRecovery.dismiss(popup, rawNodes, service)) {
                Log.d(TAG, "Popup auto-dismissed: ${popup.type}")
                delay(300)
                return
            }
        }

        // 3. UI 树剪枝
        val pruneResult = if (config.enablePruning) {
            UiNodePruner.prune(rawNodes)
        } else {
            UiNodePruner.Result(rawNodes, rawNodes.size, rawNodes.size)
        }
        val prunedNodes = pruneResult.nodes

        // 4. 计算 UI 签名
        val uiSignature = computeUiSignature(prunedNodes)

        // 5. 截图
        val frame = captureService.captureFrame(uiSignature) ?: return

        // 6. 计算帧指纹（去重）
        val fingerprint = FrameFingerprint.build(
            foregroundPackage = foregroundPackage,
            uiNodes = prunedNodes,
            imageBytes = frame.imageBytes,
        )
        if (fingerprint == lastFingerprint && config.enableDedup) {
            return // 帧未变化，跳过
        }
        lastFingerprint = fingerprint

        // 7. SoM 标注渲染
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

        // 8. 构建 SoM Markers Payload
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

        // 9. 发射感知快照
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

// ========== 数据类 ==========

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
