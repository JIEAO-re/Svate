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
 * P1 reactive refactor: edge safety guard chain.
 *
 * Responsibilities:
 * - Sanitize high-risk actions
 * - Block publish and payment actions in HOMEWORK mode
 * - Trigger SYSTEM_ALERT_WINDOW for manual user confirmation (human in the loop)
 * - Apply semantic guards for destructive operations
 * - Add P2 visual prompt-injection defense
 *
 * Design principles:
 * - Do not fall back to a rigid WAIT when user confirmation is better
 * - Every block should carry a clear reason and recovery path
 */
class EdgeSecurityGuard(
    private val context: Context,
    private val config: SecurityGuardConfig = SecurityGuardConfig(),
) {
    companion object {
        private const val TAG = "EdgeSecurityGuard"

        // Hard-block keywords
        private val HARD_BLOCKED_KEYWORDS = listOf(
            "delete", "remove", "uninstall", "format", "reset",
            "删除", "移除", "卸载", "格式化", "重置", "清空",
            "transfer", "payment", "pay", "purchase", "buy",
            "转账", "支付", "付款", "购买", "充值",
        )

        // HOMEWORK-mode specific blocks
        private val HOMEWORK_BLOCKED_KEYWORDS = listOf(
            "submit", "publish", "post", "send", "share",
            "提交", "发布", "发送", "分享", "上传",
        )
    }

    // ========== Output stream ==========
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

    // ========== P2 visual injection guard ==========
    private val injectionGuard = VisualInjectionGuard()

    // ========== User confirmation callback ==========
    var onRequestConfirm: ((AgentAction, (Boolean) -> Unit) -> Unit)? = null

    /**
     * Validate and sanitize an action.
     *
     * @return SanitizeResult with pass/fail state, sanitized action, and block reason.
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

        // ========== P2 addition: visual injection scan ==========
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

        // 1. High-risk actions trigger user confirmation immediately.
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

        // 2. Hard-block keyword detection
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

        // 3. HOMEWORK-mode specific blocking
        if (taskSpec.mode == TaskMode.HOMEWORK) {
            if (containsBlockedKeyword(mergedText, HOMEWORK_BLOCKED_KEYWORDS)) {
                Log.w(TAG, "HOMEWORK mode: submit/publish blocked")
                _securityEvents.tryEmit(SecurityEvent(
                    type = SecurityEventType.HOMEWORK_SUBMIT_BLOCKED,
                    action = action,
                    reason = "HOMEWORK mode blocks submit/publish actions",
                ))

                // Ask for user confirmation instead of blocking immediately.
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

        // 4. Intent-specific validation
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

        // 5. WARNING level: record it but allow execution
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
     * Request user confirmation (human in the loop).
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
                    // Default to rejection when no callback is registered.
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

// ========== Data classes ==========

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
