package com.immersive.ui.agent.flow

import android.content.Context
import android.content.Intent
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.UiNode

/**
 * Execution module: action execution.
 *
 * Extracted from OpenClawOrchestrator, responsible for:
 * - Handing planned AgentAction objects to AccessibilityMotor
 * - Launching apps
 * - Managing the set of launchable package names
 */
class ExecutionModule(
    private val context: Context,
    private val motor: AccessibilityMotor,
) {
    private var launchablePackages: Set<String> = emptySet()

    /**
     * Query all launchable app package names from the system.
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
     * Try to open the target app directly.
     *
     * @return The launched package name on success, or null on failure.
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
     * Execute an action.
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
