package com.immersive.ui.agent.flow

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.AgentCaptureService
import com.immersive.ui.agent.AgentContext
import com.immersive.ui.agent.AgentPhase
import com.immersive.ui.agent.ActionIntent
import com.immersive.ui.agent.DecisionOption
import com.immersive.ui.agent.DecisionRequest
import com.immersive.ui.agent.ObservationReason
import com.immersive.ui.agent.TaskSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenClawOrchestrator: the core facade orchestrator behind the Svate product.
 *
 * Naming note: "OpenClaw" comes from the historical Open Claw codename, while the
 * current product name is Svate. The class name stays unchanged for compatibility,
 *
 * Architecture evolution:
 * - Split the original monolith into six single-purpose modules and keep this class as the facade
 * - Replace rigid loops and waits with Kotlin Coroutines Flow plus an actor-style pipeline
 *
 * Child modules:
 * 1. ObservationModule  ? screenshot/frame capture and UI tree parsing
 * 2. PlanningModule     ? decision planning logic
 * 3. SafetyModule       ? safety checks and risk interception
 * 4. ExecutionModule    ? action execution
 * 5. VerificationModule ? post-execution verification
 * 6. TelemetryModule    ? telemetry reporting
 *
 * Four lower-level pipelines:
 * 1. VisualPerceptionFlow: high-frequency frame sampling, fingerprinting, and popup quick reactions
 * 2. LiveDecisionChannel: persistent communication, frame streaming, and action delivery
 * 3. EdgeSecurityGuard: sanitizing risky actions and handling human-in-the-loop confirmation
 * 4. AccessibilityMotor: coordinate-driven low-level execution
 *
 * Data flow:
 * Observation -> Planning -> Safety -> Execution -> Verification -> Loop
 */
class OpenClawOrchestrator(
    private val context: Context,
) {
    companion object {
        private const val TAG = "OpenClawOrchestrator"
        private const val STARTUP_GUARD_MS = 8_000L
    }

    // ========== Four lower-level pipelines ==========
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var perceptionFlow: VisualPerceptionFlow
    private lateinit var decisionChannel: LiveDecisionChannel
    private lateinit var securityGuard: EdgeSecurityGuard
    private lateinit var motor: AccessibilityMotor

    // ========== Six child modules ==========
    private lateinit var observationModule: ObservationModule
    private lateinit var planningModule: PlanningModule
    private lateinit var safetyModule: SafetyModule
    private lateinit var executionModule: ExecutionModule
    private lateinit var verificationModule: VerificationModule
    private lateinit var telemetryModule: TelemetryModule

    // ========== P2 media async path ==========
    private val gcsUploader = GcsAsyncUploader(scope)

    // ========== State management ==========
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private val agentContext = AtomicReference(AgentContext())
    private var orchestratorJob: Job? = null

    // ========== External callbacks ==========
    var onPhaseChanged: ((AgentPhase, String) -> Unit)? = null
    var onNarration: ((String) -> Unit)? = null
    var onRequestConfirm: ((AgentAction, (Boolean) -> Unit) -> Unit)? = null
    var onRequestDecision: ((DecisionRequest, (DecisionOption?) -> Unit) -> Unit)? = null
    var onReportReady: ((String) -> Unit)? = null
    var onCompleted: ((String) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    // ========== Internal state ==========
    private var pendingTargetPackage: String? = null
    private var startupGuardUntilMs: Long = 0L
    private var lastObservationReason: ObservationReason = ObservationReason.APP_START

    /**
     * Start the agent.
     */
    fun start(
        goal: String,
        targetAppName: String,
        taskSpec: TaskSpec = TaskSpec(),
    ) {
        if (isRunning.getAndSet(true)) return

        // Initialize the four lower-level pipelines.
        perceptionFlow = VisualPerceptionFlow(scope)
        decisionChannel = LiveDecisionChannel(scope)
        securityGuard = EdgeSecurityGuard(context)
        motor = AccessibilityMotor(context, scope)

        // Initialize the six child modules.
        observationModule = ObservationModule(gcsUploader)
        planningModule = PlanningModule(context, decisionChannel)
        safetyModule = SafetyModule(securityGuard)
        executionModule = ExecutionModule(context, motor)
        verificationModule = VerificationModule(context)
        telemetryModule = TelemetryModule(context)

        // Bind the user-confirmation callback for the safety guard.
        safetyModule.bindConfirmCallback(onRequestConfirm)

        // Initialize the context.
        agentContext.set(
            AgentContext(
                globalGoal = goal,
                targetAppName = targetAppName,
                taskSpec = taskSpec,
                phase = AgentPhase.IDLE,
                traceId = "sess_${System.currentTimeMillis()}",
            ),
        )

        // Start the orchestration loop.
        orchestratorJob = scope.launch { orchestratorLoop() }
    }

    /**
     * Stop the agent.
     */
    fun stop() {
        isRunning.set(false)
        orchestratorJob?.cancel()
        orchestratorJob = null

        perceptionFlow.stop()
        decisionChannel.stop()

        startupGuardUntilMs = 0L
        pendingTargetPackage = null
    }

    /**
     * Main orchestration loop that composes the child modules.
     */
    private suspend fun orchestratorLoop() {
        try {
            // 1. Wait for services to become ready.
            val startupFailure = waitServicesReady()
            if (startupFailure != null) {
                updatePhase(AgentPhase.FAILED, startupFailure)
                postFailed(startupFailure)
                return
            }

            // 2. Query launchable apps (ExecutionModule).
            val launchablePackages = executionModule.queryLaunchablePackages()

            // 3. Emit session_start telemetry (TelemetryModule).
            val sessionCtx = agentContext.get()
            telemetryModule.reportSessionStart(sessionCtx)

            // 4. Decompose the task (PlanningModule).
            updatePhase(AgentPhase.DECOMPOSING, "Decomposing the task into steps...")
            val baseCtx = agentContext.get()
            val plan = planningModule.decompose(
                goal = baseCtx.globalGoal,
                targetApp = baseCtx.targetAppName,
                taskSpec = baseCtx.taskSpec,
            )
            if (plan != null) {
                agentContext.set(baseCtx.copy(taskPlan = plan))
                postNarration("Plan generated: ${plan.steps.joinToString(" -> ") { it.description }}")
            }

            // 5. Try to open the target app directly (ExecutionModule).
            val targetApp = agentContext.get().targetAppName
            val openedPackage = executionModule.tryDirectOpenApp(targetApp)
            if (openedPackage != null) {
                pendingTargetPackage = openedPackage
                postNarration("Opening $targetApp...")
                startupGuardUntilMs = System.currentTimeMillis() + STARTUP_GUARD_MS
            }

            // 6. Start the perception flow and communication channel.
            perceptionFlow.start()
            decisionChannel.start()

            // 7. Subscribe to popup events.
            scope.launch {
                perceptionFlow.popupDetected.collect { event ->
                    Log.d(TAG, "Popup detected: ${event.popup.type}")
                    updatePhase(AgentPhase.RECOVERING, "Detected ${event.popup.type.label}. Closing popup...")
                }
            }

            // 8. Main loop: consume perception snapshots.
            perceptionFlow.snapshots
                .takeWhile { isRunning.get() && !agentContext.get().isTerminal() }
                .collect { snapshot ->
                if (!isRunning.get()) return@collect

                val ctx = agentContext.get()
                if (ctx.isTerminal()) {
                    isRunning.set(false)
                    return@collect
                }

                // Check the step limit.
                if (ctx.isOverStepLimit()) {
                    updatePhase(AgentPhase.FAILED, "Step limit exceeded.")
                    postFailed("Too many steps. Manual takeover is recommended.")
                    isRunning.set(false)
                    return@collect
                }

                // Run guard checks.
                if (!passStartupGuard(snapshot)) return@collect

                // ObservationModule: upload screenshots to GCS asynchronously.
                val gcsUri = observationModule.uploadScreenshotToGcs(
                    imageBytes = snapshot.frame.imageBytes,
                    traceId = ctx.traceId ?: "",
                    stepIndex = ctx.stepIndex,
                )

                // PlanningModule: request a decision.
                updatePhase(AgentPhase.PLANNING, "Planning the next action...")
                val decisionResult = planningModule.requestDecision(
                    ctx = ctx,
                    snapshot = snapshot,
                    observationReason = lastObservationReason,
                    gcsUri = gcsUri,
                )

                if (decisionResult == null) {
                    handleDecisionFailure()
                    return@collect
                }

                // Update the context.
                agentContext.set(ctx.copy(
                    serverLatencyMs = (decisionResult.plannerLatencyMs + decisionResult.reviewerLatencyMs).toLong(),
                    lastReviewerVerdict = decisionResult.reviewerVerdict,
                ))

                var action = decisionResult.action

                // SafetyModule: sanitize the action with safety guards.
                val sanitizeResult = safetyModule.sanitize(
                    action = action,
                    taskSpec = ctx.taskSpec,
                    uiNodes = snapshot.prunedNodes,
                    launchablePackages = launchablePackages,
                )

                if (!sanitizeResult.passed) {
                    Log.w(TAG, "Action blocked by security guard: ${sanitizeResult.blockReason}")
                    action = sanitizeResult.action
                }

                // Check whether the task is complete.
                if (action.intent == ActionIntent.FINISH) {
                    updatePhase(AgentPhase.COMPLETED, "Task completed.")
                    verificationModule.saveMemory(agentContext.get())
                    postCompleted(action.elderlyNarration)
                    isRunning.set(false)
                    return@collect
                }

                // ExecutionModule: execute the action.
                updatePhase(AgentPhase.EXECUTING, action.elderlyNarration)
                postNarration(action.elderlyNarration)

                val executionResult = executionModule.execute(
                    action = action,
                    uiNodes = snapshot.prunedNodes,
                    somMarkerMap = perceptionFlow.getLastSomMarkerMap(),
                )

                // Wait for the UI to settle.
                delay(900)

                // VerificationModule: validate the execution result.
                updatePhase(AgentPhase.VERIFYING, "Verifying action result...")
                val (postNodes, postPackage) = verificationModule.getPostExecutionState()
                val checkpointMatched = verificationModule.verifyCheckpoint(action, postPackage, postNodes)

                val record = verificationModule.buildStepRecord(
                    stepIndex = ctx.stepIndex,
                    action = action,
                    executionSuccess = executionResult.success,
                    checkpointMatched = checkpointMatched,
                    reviewerVerdict = decisionResult.reviewerVerdict,
                    resolveScore = executionResult.resolveScore,
                    failCode = executionResult.failCode,
                )

                val stepSuccess = record.success

                val afterCtx = agentContext.get()
                agentContext.set(afterCtx.copy(
                    stepIndex = afterCtx.stepIndex + 1,
                    history = afterCtx.history + record,
                    retryCount = if (stepSuccess) 0 else afterCtx.retryCount + 1,
                    consecutiveFailCount = if (stepSuccess) 0 else afterCtx.consecutiveFailCount + 1,
                ))

                // Update the observation reason.
                lastObservationReason = ObservationReason.AFTER_ACTION

                // Handle failures.
                if (!stepSuccess) {
                    handleExecutionFailure()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Orchestrator loop failed", t)
            updatePhase(AgentPhase.FAILED, "Unexpected error: ${t.message}")
            postFailed("Agent encountered an issue. Please retry.")
        } finally {
            // TelemetryModule: report session end.
            telemetryModule.reportSessionEnd(agentContext.get())
            isRunning.set(false)
            perceptionFlow.stop()
            decisionChannel.stop()
        }
    }

    // ========== Helper methods ==========

    private suspend fun waitServicesReady(): String? {
        var waitCount = 0
        while (
            waitCount < 20 &&
            isRunning.get() &&
            (AgentCaptureService.instance == null || AgentAccessibilityService.instance == null)
        ) {
            delay(200)
            waitCount++
        }
        if (!isRunning.get()) {
            return "Agent startup was cancelled."
        }

        val missingServices = buildList {
            if (AgentCaptureService.instance == null) add("screen capture service")
            if (AgentAccessibilityService.instance == null) add("accessibility service")
        }
        return if (missingServices.isEmpty()) {
            null
        } else {
            "Failed to start ${missingServices.joinToString(" and ")}. Please retry."
        }
    }

    private fun passStartupGuard(snapshot: PerceptionSnapshot): Boolean {
        if (startupGuardUntilMs <= 0L) return true

        val now = System.currentTimeMillis()
        val ready = snapshot.foregroundPackage == pendingTargetPackage && snapshot.prunedNodes.isNotEmpty()

        if (ready || now > startupGuardUntilMs) {
            startupGuardUntilMs = 0L
            return true
        }

        updatePhase(AgentPhase.WAITING_STABLE, "App launching, waiting for stable screen...")
        return false
    }

    private suspend fun handleDecisionFailure() {
        val ctx = agentContext.get()
        if (ctx.consecutiveFailCount >= 3) {
            updatePhase(AgentPhase.FAILED, "Too many consecutive failures.")
            postFailed("Network issues. Please check connection.")
            isRunning.set(false)
        } else {
            agentContext.set(ctx.copy(consecutiveFailCount = ctx.consecutiveFailCount + 1))
            delay(1000)
        }
    }

    private suspend fun handleExecutionFailure() {
        val ctx = agentContext.get()
        if (ctx.isOverRetryLimit()) {
            updatePhase(AgentPhase.FAILED, "Retry limit reached.")
            postFailed("Repeated attempts failed. Manual handling recommended.")
            isRunning.set(false)
        }
    }

    // ========== UI callbacks ==========

    private fun updatePhase(phase: AgentPhase, message: String) {
        val ctx = agentContext.get()
        agentContext.set(ctx.copy(phase = phase))
        mainHandler.post { onPhaseChanged?.invoke(phase, message) }
    }

    private fun postNarration(text: String) {
        mainHandler.post { onNarration?.invoke(text) }
    }

    private fun postCompleted(message: String) {
        mainHandler.post { onCompleted?.invoke(message) }
    }

    private fun postFailed(message: String) {
        mainHandler.post { onFailed?.invoke(message) }
    }
}
