package com.immersive.ui.agent.flow

import android.content.Context
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.TaskSpec
import com.immersive.ui.agent.UiNode

/**
 * Safety module: safety checks and risk interception.
 *
 * Extracted from OpenClawOrchestrator, responsible for:
 * - Action sanitization
 * - High-risk keyword blocking
 * - Visual injection defense
 * - Triggering human-in-the-loop confirmation
 */
class SafetyModule(
    private val securityGuard: EdgeSecurityGuard,
) {
    /**
     * Bind the user confirmation callback.
     */
    fun bindConfirmCallback(callback: ((AgentAction, (Boolean) -> Unit) -> Unit)?) {
        securityGuard.onRequestConfirm = callback
    }

    /**
     * Sanitize an action through the safety layer.
     *
     * @return SanitizeResult with pass state, sanitized action, and block reason.
     */
    suspend fun sanitize(
        action: AgentAction,
        taskSpec: TaskSpec,
        uiNodes: List<UiNode>,
        launchablePackages: Set<String>,
        screenshotBase64: String? = null,
        foregroundPackage: String? = null,
    ): SanitizeResult {
        return securityGuard.sanitize(
            action = action,
            taskSpec = taskSpec,
            uiNodes = uiNodes,
            launchablePackages = launchablePackages,
            screenshotBase64 = screenshotBase64,
            foregroundPackage = foregroundPackage,
        )
    }
}
