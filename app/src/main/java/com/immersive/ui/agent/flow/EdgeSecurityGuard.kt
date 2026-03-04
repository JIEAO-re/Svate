package com.immersive.ui.agent.flow

import android.content.Context
import android.util.Log
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.AgentActionSafety
import com.immersive.ui.agent.ActionIntent
import com.immersive.ui.agent.ActionSelector
import com.immersive.ui.agent.IntentGuard
import com.immersive.ui.agent.RiskLevel
import com.immersive.ui.agent.SafetyCheckResult
import com.immersive.ui.agent.TaskMode
import com.immersive.ui.agent.TaskSpec
import com.immersive.ui.agent.UiNode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * P1 响应式重构：边缘安全守卫链
 *
 * 职责：
 * - 洗稿（sanitize）高风险动作
 * - HOMEWORK 模式下拦截发布/支付操作
 * - 触发 SYSTEM_ALERT_WINDOW 让用户手动确认（Human-in-the-loop）
 * - 语义守卫：检测破坏性操作
 * - P2 新增：视觉 Prompt Injection 防御
 *
 * 设计原则：
 * - 不再死板 WAIT，而是主动触发用户确认
 * - 所有拦截都有明确的 reason 和 recovery path
 */
class EdgeSecurityGuard(
    private val context: Context,
    private val config: SecurityGuardConfig = SecurityGuardConfig(),
) {
    companion object {
        private const val TAG = "EdgeSecurityGuard"

        // 硬拦截关键词
        private val HARD_BLOCKED_KEYWORDS = listOf(
            "delete", "remove", "uninstall", "format", "reset",
            "删除", "移除", "卸载", "格式化", "重置", "清空",
            "transfer", "payment", "pay", "purchase", "buy",
            "转账", "支付", "付款", "购买", "充值",
        )

        // HOMEWORK 模式特殊拦截
        private val HOMEWORK_BLOCKED_KEYWORDS = listOf(
            "submit", "publish", "post", "send", "share",
            "提交", "发布", "发送", "分享", "上传",
        )
    }

    // ========== 输出流 ==========
    private val _confirmationRequests = MutableSharedFlow<ConfirmationRequest>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val confirmationRequests: SharedFlow<ConfirmationRequest> = _confirmationRequests.asSharedFlow()

    private val _securityEvents = MutableSharedFlow<SecurityEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val securityEvents: SharedFlow<SecurityEvent> = _securityEvents.asSharedFlow()

    // ========== P2 视觉注入守卫 ==========
    private val injectionGuard = VisualInjectionGuard()

    // ========== 用户确认回调 ==========
    var onRequestConfirm: ((AgentAction, (Boolean) -> Unit) -> Unit)? = null

    /**
     * 验证并洗稿动作
     *
     * @return SanitizeResult 包含是否通过、洗稿后的动作、拦截原因
     */
    suspend fun sanitize(
        action: AgentAction,
        taskSpec: TaskSpec,
        uiNodes: List<UiNode>,
        launchablePackages: Set<String>,
        screenshotBase64: String? = null,
        foregroundPackage: String? = null,
    ): SanitizeResult {
        val mergedText = "${action.targetDesc} ${action.reasoning} ${action.elderlyNarration}"

        // ========== P2 新增：视觉注入扫描 ==========
        if (config.enableInjectionGuard) {
            val injectionResult = injectionGuard.scan(
                uiNodes = uiNodes,
                screenshotBase64 = screenshotBase64,
                foregroundPackage = foregroundPackage,
            )

            if (injectionResult.shouldBlock) {
                Log.w(TAG, "Visual injection attack detected: ${injectionResult.threatLevel}")
                _securityEvents.tryEmit(SecurityEvent(
                    type = SecurityEventType.INJECTION_DETECTED,
                    action = action,
                    reason = "Visual injection attack detected: ${injectionResult.threats.firstOrNull()?.description}",
                ))
                return SanitizeResult(
                    passed = false,
                    action = toSafeWait(action, "injection_attack_blocked"),
                    blockReason = "Visual injection attack detected",
                    injectionScanResult = injectionResult,
                )
            }

            if (injectionResult.requiresConfirmation) {
                Log.w(TAG, "Potential injection threat, requesting confirmation")
                _securityEvents.tryEmit(SecurityEvent(
                    type = SecurityEventType.INJECTION_WARNING,
                    action = action,
                    reason = "Potential visual injection: ${injectionResult.threats.firstOrNull()?.description}",
                ))

                val confirmed = requestUserConfirmation(
                    action,
                    "Potential security threat detected on screen. Proceed with caution?",
                )
                if (!confirmed) {
                    return SanitizeResult(
                        passed = false,
                        action = toSafeWait(action, "user_rejected_injection_warning"),
                        blockReason = "User rejected due to injection warning",
                        injectionScanResult = injectionResult,
                    )
                }
            }
        }

        // 1. 高风险等级直接触发用户确认
        if (action.riskLevel == RiskLevel.HIGH) {
            Log.w(TAG, "HIGH risk action detected: ${action.intent}")
            _securityEvents.tryEmit(SecurityEvent(
                type = SecurityEventType.HIGH_RISK_DETECTED,
                action = action,
                reason = "Action marked as HIGH risk by cloud reviewer",
            ))

            val confirmed = requestUserConfirmation(action, "This action is marked as high-risk. Proceed?")
            return if (confirmed) {
                SanitizeResult(passed = true, action = action)
            } else {
                SanitizeResult(
                    passed = false,
                    action = toSafeWait(action, "user_rejected_high_risk"),
                    blockReason = "User rejected high-risk action",
                )
            }
        }

        // 2. 硬拦截关键词检测
        if (containsBlockedKeyword(mergedText, HARD_BLOCKED_KEYWORDS)) {
            Log.w(TAG, "Hard blocked keyword detected in: $mergedText")
            _securityEvents.tryEmit(SecurityEvent(
                type = SecurityEventType.KEYWORD_BLOCKED,
                action = action,
                reason = "Contains hard-blocked keyword",
            ))
            return SanitizeResult(
                passed = false,
                action = toSafeWait(action, "blocked_keyword"),
                blockReason = "Action contains blocked keyword",
            )
        }

        // 3. HOMEWORK 模式特殊拦截
        if (taskSpec.mode == TaskMode.HOMEWORK) {
            if (containsBlockedKeyword(mergedText, HOMEWORK_BLOCKED_KEYWORDS)) {
                Log.w(TAG, "HOMEWORK mode: submit/publish blocked")
                _securityEvents.tryEmit(SecurityEvent(
                    type = SecurityEventType.HOMEWORK_SUBMIT_BLOCKED,
                    action = action,
                    reason = "HOMEWORK mode blocks submit/publish actions",
                ))

                // 触发用户确认而非直接拦截
                val confirmed = requestUserConfirmation(
                    action,
                    "HOMEWORK mode detected a submit action. This may submit your homework. Proceed?",
                )
                return if (confirmed) {
                    SanitizeResult(passed = true, action = action)
                } else {
                    SanitizeResult(
                        passed = false,
                        action = toSafeWait(action, "homework_submit_blocked"),
                        blockReason = "User blocked homework submission",
                    )
                }
            }
        }

        // 4. Intent 类型特殊校验
        when (action.intent) {
            ActionIntent.CLICK -> {
                val bboxCheck = when {
                    action.spatialCoordinates != null -> SafetyCheckResult(true)
                    action.targetBbox != null -> AgentActionSafety.validateClickBbox(action.targetBbox)
                    action.selector != null -> SafetyCheckResult(true)
                    else -> SafetyCheckResult(false, "missing_targeting_method")
                }
                if (!bboxCheck.allowed) {
                    return SanitizeResult(
                        passed = false,
                        action = toSafeWait(action, bboxCheck.reason ?: "invalid_click_target"),
                        blockReason = bboxCheck.reason,
                    )
                }
            }

            ActionIntent.OPEN_APP -> {
                if (!AgentActionSafety.isKnownLauncherPackage(action.packageName, launchablePackages)) {
                    return SanitizeResult(
                        passed = false,
                        action = toSafeWait(action, "invalid_package"),
                        blockReason = "Package not in launchable list: ${action.packageName}",
                    )
                }
            }

            ActionIntent.OPEN_INTENT -> {
                val check = IntentGuard.validate(action.intentSpec, action.packageName)
                if (!check.allowed) {
                    return SanitizeResult(
                        passed = false,
                        action = toSafeWait(action, check.reason ?: "intent_guard_blocked"),
                        blockReason = check.reason,
                    )
                }
            }

            else -> { /* 其他 intent 类型暂不特殊处理 */ }
        }

        // 5. WARNING 级别：记录但放行
        if (action.riskLevel == RiskLevel.WARNING) {
            _securityEvents.tryEmit(SecurityEvent(
                type = SecurityEventType.WARNING_LOGGED,
                action = action,
                reason = "Action marked as WARNING risk",
            ))
        }

        return SanitizeResult(passed = true, action = action)
    }

    /**
     * 请求用户确认（Human-in-the-loop）
     */
    private suspend fun requestUserConfirmation(action: AgentAction, message: String): Boolean {
        _confirmationRequests.tryEmit(ConfirmationRequest(action, message))

        return withTimeoutOrNull(config.confirmationTimeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                val callback = onRequestConfirm
                if (callback != null) {
                    callback.invoke(action) { result ->
                        if (cont.isActive) cont.resume(result)
                    }
                } else {
                    // 无回调时默认拒绝
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }

    private fun containsBlockedKeyword(text: String, keywords: List<String>): Boolean {
        val lowerText = text.lowercase()
        return keywords.any { keyword -> lowerText.contains(keyword.lowercase()) }
    }

    private fun toSafeWait(action: AgentAction, reason: String): AgentAction {
        return action.copy(
            intent = ActionIntent.WAIT,
            targetDesc = "wait",
            targetBbox = null,
            targetSomId = null,
            spatialCoordinates = null,
            inputText = null,
            packageName = null,
            intentSpec = null,
            selector = null,
            expectedPackage = null,
            expectedPageType = null,
            expectedElements = emptyList(),
            riskLevel = RiskLevel.SAFE,
            elderlyNarration = "The action was blocked for safety. Please wait.",
            reasoning = "${action.reasoning} [sanitized:$reason]",
            subStepCompleted = false,
            decisionRequest = null,
            knowledgeCapture = null,
        )
    }
}

// ========== 数据类 ==========

data class SecurityGuardConfig(
    val confirmationTimeoutMs: Long = 60_000L,
    val enableHumanInTheLoop: Boolean = true,
    val enableInjectionGuard: Boolean = true, // P2 新增
)

data class SanitizeResult(
    val passed: Boolean,
    val action: AgentAction,
    val blockReason: String? = null,
    val injectionScanResult: InjectionScanResult? = null, // P2 新增
)

data class ConfirmationRequest(
    val action: AgentAction,
    val message: String,
)

enum class SecurityEventType {
    HIGH_RISK_DETECTED,
    KEYWORD_BLOCKED,
    HOMEWORK_SUBMIT_BLOCKED,
    WARNING_LOGGED,
    USER_CONFIRMED,
    USER_REJECTED,
    INJECTION_DETECTED,   // P2 新增
    INJECTION_WARNING,    // P2 新增
}

data class SecurityEvent(
    val type: SecurityEventType,
    val action: AgentAction,
    val reason: String,
)
