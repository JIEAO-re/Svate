package com.immersive.ui.agent.flow

import android.util.Log
import com.immersive.ui.BuildConfig
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.AgentContext
import com.immersive.ui.agent.CloudCheckpoint
import com.immersive.ui.agent.CloudDecisionClient
import com.immersive.ui.agent.ObservationReason
import com.immersive.ui.agent.UiNodeStatsPayload
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P1 响应式重构：通信层流水线
 *
 * 职责：
 * - 维持与云端的网络连接
 * - 接收 PerceptionSnapshot，发送推流请求
 * - 接收云端返回的 AgentAction
 * - 处理网络重试与降级
 *
 * 未来演进：
 * - 升级为 WebSocket/gRPC 双向流
 * - 对接 Gemini Multimodal Live API
 */
class LiveDecisionChannel(
    private val scope: CoroutineScope,
    private val cloudClient: CloudDecisionClient = CloudDecisionClient(),
    private val config: DecisionChannelConfig = DecisionChannelConfig(),
) {
    companion object {
        private const val TAG = "LiveDecisionChannel"
    }

    // ========== 输出流 ==========
    private val _decisions = MutableSharedFlow<DecisionResult>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val decisions: SharedFlow<DecisionResult> = _decisions.asSharedFlow()

    private val _errors = MutableSharedFlow<DecisionError>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val errors: SharedFlow<DecisionError> = _errors.asSharedFlow()

    private val _state = MutableStateFlow(ChannelState.DISCONNECTED)
    val state: StateFlow<ChannelState> = _state.asStateFlow()

    // ========== 内部状态 ==========
    private val requestMutex = Mutex()
    private var channelJob: Job? = null
    private var consecutiveFailures = 0

    fun start() {
        _state.value = ChannelState.CONNECTING
        channelJob = scope.launch(Dispatchers.IO) {
            // 模拟连接建立
            delay(100)
            _state.value = ChannelState.CONNECTED
            Log.d(TAG, "Decision channel connected")
        }
    }

    fun stop() {
        channelJob?.cancel()
        channelJob = null
        _state.value = ChannelState.DISCONNECTED
        consecutiveFailures = 0
    }

    /**
     * 发送感知快照，获取决策结果
     *
     * @param gcsUri P2 媒体异步化：GCS 预签名 URL 上传后的 gs:// URI
     */
    suspend fun requestDecision(
        ctx: AgentContext,
        snapshot: PerceptionSnapshot,
        observationReason: ObservationReason,
        gcsUri: String? = null,
    ): DecisionResult? {
        if (_state.value != ChannelState.CONNECTED) {
            _errors.emit(DecisionError("channel_not_connected", "Decision channel is not connected"))
            return null
        }

        return requestMutex.withLock {
            try {
                _state.value = ChannelState.REQUESTING
                val finalFrame = if (gcsUri != null) {
                    snapshot.frame.copy(gcsUri = gcsUri, imageBase64 = "")
                } else {
                    snapshot.frame
                }

                val result = cloudClient.nextStep(
                    ctx = ctx,
                    uiNodes = snapshot.prunedNodes,
                    frames = listOf(finalFrame),
                    foregroundPackage = snapshot.foregroundPackage,
                    observationReason = observationReason,
                    somAnnotatedImageBase64 = snapshot.somAnnotatedImageBase64,
                    somMarkers = snapshot.somMarkers,
                    uiNodeStats = UiNodeStatsPayload(
                        rawCount = snapshot.rawNodeCount,
                        prunedCount = snapshot.prunedNodeCount,
                    ),
                    frameFingerprint = snapshot.fingerprint,
                    // 服务端 schema 对齐：传递 UI 树 XML 文本
                    uiTreeXml = snapshot.uiTreeText,
                )

                consecutiveFailures = 0
                _state.value = ChannelState.CONNECTED

                val decisionResult = DecisionResult(
                    traceId = result.traceId,
                    action = result.action,
                    checkpoint = result.checkpoint,
                    reviewerVerdict = result.reviewerVerdict,
                    plannerLatencyMs = result.plannerLatencyMs,
                    reviewerLatencyMs = result.reviewerLatencyMs,
                    blockReason = result.blockReason,
                    snapshotFingerprint = snapshot.fingerprint,
                )

                _decisions.emit(decisionResult)
                decisionResult
            } catch (e: Exception) {
                Log.w(TAG, "Decision request failed", e)
                consecutiveFailures++
                _state.value = if (consecutiveFailures >= config.maxConsecutiveFailures) {
                    ChannelState.DEGRADED
                } else {
                    ChannelState.CONNECTED
                }

                _errors.emit(DecisionError(
                    code = "request_failed",
                    message = e.message ?: "Unknown error",
                    retryable = consecutiveFailures < config.maxConsecutiveFailures,
                ))
                null
            }
        }
    }

    /**
     * 重置连接状态（用于从降级状态恢复）
     */
    fun resetConnection() {
        consecutiveFailures = 0
        _state.value = ChannelState.CONNECTED
    }
}

// ========== 数据类 ==========

data class DecisionChannelConfig(
    val maxConsecutiveFailures: Int = 3,
    val requestTimeoutMs: Long = 15_000L,
)

enum class ChannelState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    REQUESTING,
    DEGRADED,
}

data class DecisionResult(
    val traceId: String,
    val action: AgentAction,
    val checkpoint: CloudCheckpoint,
    val reviewerVerdict: String,
    val plannerLatencyMs: Int,
    val reviewerLatencyMs: Int,
    val blockReason: String?,
    val snapshotFingerprint: String,
)

data class DecisionError(
    val code: String,
    val message: String,
    val retryable: Boolean = true,
)
