package com.immersive.ui.agent.loop.tools

import android.content.Intent
import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Launch an installed app by display name or explicit package name. Display-name
 * launches resolve the package by matching launcher-activity labels; package
 * launches go through the platform launcher intent.
 */
class OpenAppTool : PhoneTool {
    override val name: String = "open_app"
    override val description: String =
        "Open an installed app. Provide app_name (display name) or package (exact package id)."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.NORMAL

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("app_name", ToolSupport.prop("string", "Display name of the app to open, e.g. \"Chrome\"."))
        props.put("package", ToolSupport.prop("string", "Exact package id of the app to open."))
        return ToolSupport.objectSchema(props)
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val pkg = args.optString("package", "").trim()
        val appName = args.optString("app_name", "").trim()

        // Explicit package: launch via the platform launcher intent.
        if (pkg.isNotEmpty()) {
            val launchIntent = ctx.appContext.packageManager.getLaunchIntentForPackage(pkg)
                ?: return ToolResult(ok = false, text = "No launchable activity for package \"$pkg\".")
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                ctx.appContext.startActivity(launchIntent)
                ToolResult(ok = true, text = "Opened package \"$pkg\".")
            } catch (t: Throwable) {
                ToolResult(ok = false, text = "Failed to open package \"$pkg\": ${t.message}")
            }
        }

        // Display name: resolve the package by matching launcher-activity labels,
        // then launch it. (Previously delegated to the flow ExecutionModule; inlined
        // here so the active loop no longer depends on the legacy flow package.)
        if (appName.isNotEmpty()) {
            val pm = ctx.appContext.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val resolvedPkg = pm.queryIntentActivities(launcherIntent, 0)
                .firstOrNull { it.loadLabel(pm).toString().contains(appName, ignoreCase = true) }
                ?.activityInfo?.packageName
            val launchIntent = resolvedPkg?.let { pm.getLaunchIntentForPackage(it) }
                ?: return ToolResult(ok = false, text = "Could not find an installed app named \"$appName\".")
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // startActivity can throw; catch so expected failures never propagate (PhoneTool contract).
            return try {
                ctx.appContext.startActivity(launchIntent)
                ToolResult(ok = true, text = "Opened \"$appName\" ($resolvedPkg).")
            } catch (t: Throwable) {
                ToolResult(ok = false, text = "Failed to open \"$appName\": ${t.message}")
            }
        }

        return ToolResult(ok = false, text = "open_app requires app_name or package.")
    }
}
