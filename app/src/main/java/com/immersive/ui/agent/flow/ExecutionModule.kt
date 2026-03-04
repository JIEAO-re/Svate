package com.immersive.ui.agent.flow

import android.content.Context
import android.content.Intent
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.UiNode

/**
 * 执行模块：动作执行
 *
 * 从 OpenClawOrchestrator 拆分而来，负责：
 * - 将决策后的 AgentAction 交给 AccessibilityMotor 执行
 * - 应用启动
 * - 可启动包名管理
 */
class ExecutionModule(
    private val context: Context,
    private val motor: AccessibilityMotor,
) {
    private var launchablePackages: Set<String> = emptySet()

    /**
     * 查询系统中所有可启动的应用包名
     */
    fun queryLaunchablePackages(): Set<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        launchablePackages = pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
        motor.setLaunchablePackages(launchablePackages)
        return launchablePackages
    }

    fun getLaunchablePackages(): Set<String> = launchablePackages

    /**
     * 尝试直接打开目标应用
     *
     * @return 成功打开的包名，失败返回 null
     */
    fun tryDirectOpenApp(targetAppName: String): String? {
        if (targetAppName.isBlank()) return null
        val packageName = findPackageName(targetAppName) ?: return null
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        return packageName
    }

    /**
     * 执行动作
     */
    suspend fun execute(
        action: AgentAction,
        uiNodes: List<UiNode>,
        somMarkerMap: Map<Int, UiNode> = emptyMap(),
    ): ExecutionResult {
        return motor.execute(
            action = action,
            uiNodes = uiNodes,
            somMarkerMap = somMarkerMap,
        )
    }

    private fun findPackageName(appName: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return pm.queryIntentActivities(intent, 0)
            .firstOrNull { it.loadLabel(pm).toString().contains(appName, ignoreCase = true) }
            ?.activityInfo?.packageName
    }
}
