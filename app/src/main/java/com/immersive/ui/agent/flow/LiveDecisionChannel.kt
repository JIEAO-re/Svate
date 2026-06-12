package com.immersive.ui.agent.flow

import android.util.Log
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.AgentContext
import com.immersive.ui.agent.CloudCheckpoint
import com.immersive.ui.agent.CloudDecisionClient
import com.immersive.ui.agent.ObservationReason
import com.immersive.ui.agent.UiNodeStatsPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * P1 reactive refactor: communication pipeline.
 *
 * Responsibilities:
 * - Maintain the network connection to the cloud
 * - Receive PerceptionSnapshot objects and send streaming requests
 * - Receive AgentAction results from the cloud
 * - Handle network retries and degradation
 *
 * Future evolution:
 * - Upgrade to bidirectional WebSocket or gRPC streaming
 * - Integrate with the Gemini Multimodal Live API
 */
class LiveDecisionChannel(
    @Suppress("unused") private val scope: CoroutineScope,
    private val cloudClient: CloudDecisionClient = CloudDecisionClient(),
    private val config: DecisionChannelConfig = DecisionChannelConfig(),
) {
    companion object {
        private const val TAG = "LiveDecisionChannel"
    }

    // ========== Output stream ==========
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

    // ========== Internal state ==========
    private val requestMutex = Mutex()
    private var consecutiveFailures = 0
    private var degradedSinceMs = 0L
    private var lastDegradedProbeAtMs = 0L

    fun start() {
        // The transport is plain request/response HTTP: there is no persistent
        // connection to establish, so the channel is usable immediately.
        consecutiveFailures = 0
        degradedSinceMs = 0L
        lastDegradedProbeAtMs = 0L
        _state.value = ChannelState.CONNECTED
        Log.d(TAG, "Decision channel ready")
    }

    fun stop() {
        _state.value = ChannelState.DISCONNECTED
        consecutiveFailures = 0
        degradedSinceMs = 0L
        lastDegradedProbeAtMs = 0L
    }

    /**
     * Send a perception snapshot and receive the decision result.
     *
     * @param gcsUri P2 media async path: the gs:// URI returned after signed-URL upload to GCS.
     */
    suspend fun requestDecision(
        ctx: AgentContext,
        snapshot: PerceptionSnapshot,
        observationReason: ObservationReason,
        gcsUri: String? = null,
    ): DecisionResult? {
        return requestMutex.withLock {
            when (_state.value) {
                ChannelState.DISCONNECTED -> {
                    _errors.emit(DecisionError("channel_not_connected", "Decision channel is not connected"))
                    return@withLock null
                }
                ChannelState.DEGRADED -> {
                    // Allow a periodic probe so a degraded channel can recover
                    // once the network is back, instead of staying dead forever.
                    if (!shouldProbeWhileDegraded()) {
                        _errors.emit(DecisionError(
                            code = "channel_degraded",
                            message = "Decision channel is degraded; waiting for backoff before probing",
                        ))
                        return@withLock null
                    }
                    lastDegradedProbeAtMs = System.currentTimeMillis()
                    Log.d(TAG, "Probing degraded decision channel")
                }
                else -> Unit
            }

            try {
                _state.value = ChannelState.REQUESTING
                val finalFrame = if (gcsUri != null) {
                    snapshot.frame.copy(gcsUri = gcsUri, imageBase64 = "")
                } else {
                    snapshot.frame
                }

                // The cloud client uses blocking I/O, so run it on an
                // interruptible IO thread and bound it with the configured
                // request timeout.
                val result = withTimeout(config.requestTimeoutMs) {
                    runInterruptible(Dispatchers.IO) {
                        cloudClient.nextStep(
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
                            // Server schema alignment: pass the UI tree as XML text.
                            uiTreeXml = snapshot.uiTreeText,
                        )
                    }
                }

                consecutiveFailures = 0
                degradedSinceMs = 0L
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
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Decision request timed out after ${config.requestTimeoutMs} ms")
                recordFailure("request_timeout", "Decision request timed out after ${config.requestTimeoutMs} ms")
                null
            } catch (e: CancellationException) {
                // Never swallow cooperative cancellation of the caller.
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Decision request failed", e)
                recordFailure("request_failed", e.message ?: "Unknown error")
                null
            }
        }
    }

    /**
     * Reset the connection state, typically when recovering from degradation
     * or when a new session starts.
     */
    fun resetConnection() {
        consecutiveFailures = 0
        degradedSinceMs = 0L
        lastDegradedProbeAtMs = 0L
        _state.value = ChannelState.CONNECTED
    }

    private suspend fun recordFailure(code: String, message: String) {
        consecutiveFailures++
        if (consecutiveFailures >= config.maxConsecutiveFailures) {
            if (_state.value != ChannelState.DEGRADED) {
                degradedSinceMs = System.currentTimeMillis()
            }
            _state.value = ChannelState.DEGRADED
        } else {
            _state.value = ChannelState.CONNECTED
        }
        _errors.emit(DecisionError(
            code = code,
            message = message,
            retryable = consecutiveFailures < config.maxConsecutiveFailures,
        ))
    }

    private fun shouldProbeWhileDegraded(): Boolean {
        val now = System.currentTimeMillis()
        val reference = maxOf(degradedSinceMs, lastDegradedProbeAtMs)
        return now - reference >= config.degradedBackoffMs
    }
}

// ========== Data classes ==========

data class DecisionChannelConfig(
    val maxConsecutiveFailures: Int = 3,
    val requestTimeoutMs: Long = 15_000L,
    /** Backoff before a degraded channel is allowed one probe request. */
    val degradedBackoffMs: Long = 30_000L,
)

enum class ChannelState {
    DISCONNECTED,
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
