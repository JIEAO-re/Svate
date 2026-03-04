package com.immersive.ui.agent.flow

import android.content.Context
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.TaskSpec
import com.immersive.ui.agent.UiNode

/**
 * 安全模块：安全检查与风险拦截
 *
 * 从 OpenClawOrchestrator 拆分而来，负责：
 * - 动作洗稿（sanitize）
 * - 高风险关键词拦截
 * - 视觉注入防御
 * - Human-in-the-loop 确认触发
 */
class SafetyModule(
    private val securityGuard: EdgeSecurityGuard,
) {
    /**
     * 绑定用户确认回调
     */
    fun bindConfirmCallback(callback: ((AgentAction, (Boolean) -> Unit) -> Unit)?) {
        securityGuard.onRequestConfirm = callback
    }

    /**
     * 对动作进行安全洗稿
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
