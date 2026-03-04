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
 * OpenClawOrchestrator — Svate 产品的核心编排器（Facade）
 *
 * 名称说明：类名 "OpenClaw" 源自 Open Claw 项目（历史代号），当前产品名为 Svate。
 * 保留类名以兼容外部引用，但产品层面统一使用 Svate 品牌。
 *
 * 架构演进：
 * - 从单体类拆解为六个职责单一的子模块，本类作为门面（Facade）组合调用
 * - 用 Kotlin Coroutines Flow + Actor 模式替代死循环与死板 wait
 *
 * 子模块：
 * 1. ObservationModule  — 截图/帧采集与 UI tree 解析
 * 2. PlanningModule     — 决策规划逻辑
 * 3. SafetyModule       — 安全检查与风险拦截
 * 4. ExecutionModule    — 动作执行
 * 5. VerificationModule — 执行后验证
 * 6. TelemetryModule    — 遥测上报
 *
 * 四条流水线（底层组件）：
 * 1. VisualPerceptionFlow（感知器）：高频抽帧、指纹计算、弹窗快反射
 * 2. LiveDecisionChannel（通信层）：维持长连接、推流、接收动作
 * 3. EdgeSecurityGuard（守卫链）：洗稿高风险动作、Human-in-the-loop
 * 4. AccessibilityMotor（执行器）：坐标驱动底层执行
 *
 * 数据流：
 * Observation -> Planning -> Safety -> Execution -> Verification -> Loop
 */
class OpenClawOrchestrator(
    private val context: Context,
) {
    companion object {
        private const val TAG = "OpenClawOrchestrator"
        private const val STARTUP_GUARD_MS = 8_000L
    }

    // ========== 四条底层流水线 ==========
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var perceptionFlow: VisualPerceptionFlow
    private lateinit var decisionChannel: LiveDecisionChannel
    private lateinit var securityGuard: EdgeSecurityGuard
    private lateinit var motor: AccessibilityMotor

    // ========== 六个子模块 ==========
    private lateinit var observationModule: ObservationModule
    private lateinit var planningModule: PlanningModule
    private lateinit var safetyModule: SafetyModule
    private lateinit var executionModule: ExecutionModule
    private lateinit var verificationModule: VerificationModule
    private lateinit var telemetryModule: TelemetryModule

    // ========== P2 媒体异步化 ==========
    private val gcsUploader = GcsAsyncUploader(scope)

    // ========== 状态管理 ==========
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private val agentContext = AtomicReference(AgentContext())
    private var orchestratorJob: Job? = null

    // ========== 外部回调 ==========
    var onPhaseChanged: ((AgentPhase, String) -> Unit)? = null
    var onNarration: ((String) -> Unit)? = null
    var onRequestConfirm: ((AgentAction, (Boolean) -> Unit) -> Unit)? = null
    var onRequestDecision: ((DecisionRequest, (DecisionOption?) -> Unit) -> Unit)? = null
    var onReportReady: ((String) -> Unit)? = null
    var onCompleted: ((String) -> Unit)? = null
    var onFailed: ((String) -> Unit)? = null

    // ========== 内部状态 ==========
    private var pendingTargetPackage: String? = null
    private var startupGuardUntilMs: Long = 0L
    private var lastObservationReason: ObservationReason = ObservationReason.APP_START

    /**
     * 启动 Agent
     */
    fun start(
        goal: String,
        targetAppName: String,
        taskSpec: TaskSpec = TaskSpec(),
    ) {
        if (isRunning.getAndSet(true)) return

        // 初始化四条底层流水线
        perceptionFlow = VisualPerceptionFlow(scope)
        decisionChannel = LiveDecisionChannel(scope)
        securityGuard = EdgeSecurityGuard(context)
        motor = AccessibilityMotor(context, scope)

        // 初始化六个子模块
        observationModule = ObservationModule(gcsUploader)
        planningModule = PlanningModule(context, decisionChannel)
        safetyModule = SafetyModule(securityGuard)
        executionModule = ExecutionModule(context, motor)
        verificationModule = VerificationModule(context)
        telemetryModule = TelemetryModule(context)

        // 绑定安全守卫的用户确认回调
        safetyModule.bindConfirmCallback(onRequestConfirm)

        // 初始化上下文
        agentContext.set(
            AgentContext(
                globalGoal = goal,
                targetAppName = targetAppName,
                taskSpec = taskSpec,
                phase = AgentPhase.IDLE,
                traceId = "sess_${System.currentTimeMillis()}",
            ),
        )

        // 启动编排循环
        orchestratorJob = scope.launch { orchestratorLoop() }
    }

    /**
     * 停止 Agent
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
     * 主编排循环 — 组合调用各子模块
     */
    private suspend fun orchestratorLoop() {
        try {
            // 1. 等待服务就绪
            if (!waitServicesReady()) {
                postFailed("Services failed to start. Please retry.")
                return
            }

            // 2. 查询可启动应用（ExecutionModule）
            val launchablePackages = executionModule.queryLaunchablePackages()

            // 3. 发送 session_start 遥测（TelemetryModule）
            val sessionCtx = agentContext.get()
            telemetryModule.reportSessionStart(sessionCtx)

            // 4. 任务分解（PlanningModule）
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

            // 5. 尝试直接打开目标应用（ExecutionModule）
            val targetApp = agentContext.get().targetAppName
            val openedPackage = executionModule.tryDirectOpenApp(targetApp)
            if (openedPackage != null) {
                pendingTargetPackage = openedPackage
                postNarration("Opening $targetApp...")
                startupGuardUntilMs = System.currentTimeMillis() + STARTUP_GUARD_MS
            }

            // 6. 启动感知流和通信通道
            perceptionFlow.start()
            decisionChannel.start()

            // 7. 订阅弹窗事件
            scope.launch {
                perceptionFlow.popupDetected.collect { event ->
                    Log.d(TAG, "Popup detected: ${event.popup.type}")
                    updatePhase(AgentPhase.RECOVERING, "Detected ${event.popup.type.label}. Closing popup...")
                }
            }

            // 8. 主循环：消费感知快照
            perceptionFlow.snapshots
                .takeWhile { isRunning.get() && !agentContext.get().isTerminal() }
                .collect { snapshot ->
                if (!isRunning.get()) return@collect

                val ctx = agentContext.get()
                if (ctx.isTerminal()) {
                    isRunning.set(false)
                    return@collect
                }

                // 检查步数限制
                if (ctx.isOverStepLimit()) {
                    updatePhase(AgentPhase.FAILED, "Step limit exceeded.")
                    postFailed("Too many steps. Manual takeover is recommended.")
                    isRunning.set(false)
                    return@collect
                }

                // 启动守卫检查
                if (!passStartupGuard(snapshot)) return@collect

                // ObservationModule: 异步上传截图到 GCS
                val gcsUri = observationModule.uploadScreenshotToGcs(
                    imageBase64 = snapshot.frame.imageBase64,
                    traceId = ctx.traceId ?: "",
                    stepIndex = ctx.stepIndex,
                )

                // PlanningModule: 请求决策
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

                // 更新上下文
                agentContext.set(ctx.copy(
                    serverLatencyMs = (decisionResult.plannerLatencyMs + decisionResult.reviewerLatencyMs).toLong(),
                    lastReviewerVerdict = decisionResult.reviewerVerdict,
                ))

                var action = decisionResult.action

                // SafetyModule: 安全守卫洗稿
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

                // 检查是否完成
                if (action.intent == ActionIntent.FINISH) {
                    updatePhase(AgentPhase.COMPLETED, "Task completed.")
                    verificationModule.saveMemory(agentContext.get())
                    postCompleted(action.elderlyNarration)
                    isRunning.set(false)
                    return@collect
                }

                // ExecutionModule: 执行动作
                updatePhase(AgentPhase.EXECUTING, action.elderlyNarration)
                postNarration(action.elderlyNarration)

                val executionResult = executionModule.execute(
                    action = action,
                    uiNodes = snapshot.prunedNodes,
                    somMarkerMap = perceptionFlow.getLastSomMarkerMap(),
                )

                // 等待 UI 稳定
                delay(900)

                // VerificationModule: 验证执行结果
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

                // 更新观察原因
                lastObservationReason = ObservationReason.AFTER_ACTION

                // 处理失败
                if (!stepSuccess) {
                    handleExecutionFailure()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Orchestrator loop failed", t)
            updatePhase(AgentPhase.FAILED, "Unexpected error: ${t.message}")
            postFailed("Agent encountered an issue. Please retry.")
        } finally {
            // TelemetryModule: 上报 session 结束
            telemetryModule.reportSessionEnd(agentContext.get())
            isRunning.set(false)
            perceptionFlow.stop()
            decisionChannel.stop()
        }
    }

    // ========== 辅助方法 ==========

    private suspend fun waitServicesReady(): Boolean {
        var waitCount = 0
        while (AgentCaptureService.instance == null && waitCount < 20 && isRunning.get()) {
            delay(200)
            waitCount++
        }
        return AgentCaptureService.instance != null && AgentAccessibilityService.instance != null
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

    // ========== UI 回调 ==========

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
