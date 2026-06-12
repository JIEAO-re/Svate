package com.immersive.ui.agent.loop.tools

import android.content.Intent
import com.immersive.ui.agent.flow.AccessibilityMotor
import com.immersive.ui.agent.flow.ExecutionModule
import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Launch an installed app by display name or explicit package name. Reuses the
 * existing ExecutionModule launch-by-name logic; package launches go through the
 * platform launcher intent.
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

        // Display name: reuse ExecutionModule's launch-by-label resolution.
        if (appName.isNotEmpty()) {
            val motor = AccessibilityMotor(ctx.appContext, ctx.scope)
            val executionModule = ExecutionModule(ctx.appContext, motor)
            // tryDirectOpenApp resolves the package and starts the activity; the
            // startActivity can throw. Catch it so expected failures never propagate
            // (PhoneTool contract), and distinguish not-found from a launch error.
            val launched = try {
                executionModule.tryDirectOpenApp(appName)
            } catch (t: Throwable) {
                return ToolResult(ok = false, text = "Failed to open \"$appName\": ${t.message}")
            }
            return if (launched != null) {
                ToolResult(ok = true, text = "Opened \"$appName\" ($launched).")
            } else {
                ToolResult(ok = false, text = "Could not find an installed app named \"$appName\".")
            }
        }

        return ToolResult(ok = false, text = "open_app requires app_name or package.")
    }
}
