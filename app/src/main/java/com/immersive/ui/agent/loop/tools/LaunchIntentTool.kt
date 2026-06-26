package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.IntentGuard
import com.immersive.ui.agent.IntentSpec
import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Launch a system intent (e.g. ACTION_VIEW a URL inside a whitelisted app).
 *
 * Every request is validated by IntentGuard's whitelist; a rejected intent
 * returns ok=false with the guard's reason so the model can choose another path.
 * This tool is HIGH risk and always prompts before running.
 */
class LaunchIntentTool : PhoneTool {
    override val name: String = "launch_intent"
    override val description: String =
        "Launch a system intent such as opening a URL in a whitelisted app. " +
            "Provide action (e.g. android.intent.action.VIEW), and optionally uri and package. " +
            "Only whitelisted actions/packages are allowed."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.HIGH

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("action", ToolSupport.prop("string", "Intent action, e.g. android.intent.action.VIEW."))
        props.put("uri", ToolSupport.prop("string", "Optional data URI for the intent."))
        props.put("package", ToolSupport.prop("string", "Optional target package id."))
        return ToolSupport.objectSchema(props, required = listOf("action"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val action = args.optString("action", "").trim()
        if (action.isBlank()) {
            return ToolResult(ok = false, text = "launch_intent requires an action.")
        }
        val uri = args.optString("uri", "").trim().ifBlank { null }
        val pkg = args.optString("package", "").trim().ifBlank { null }

        val spec = IntentSpec(action = action, dataUri = uri, packageName = pkg)
        val check = IntentGuard.validate(spec, fallbackPackage = pkg)
        if (!check.allowed) {
            return ToolResult(ok = false, text = "Intent rejected by guard: ${check.reason ?: "blocked"}.")
        }

        return try {
            val intent = IntentGuard.buildIntent(spec, fallbackPackage = pkg)
            ctx.appContext.startActivity(intent)
            ToolResult(ok = true, text = "Launched intent $action.")
        } catch (t: Throwable) {
            ToolResult(ok = false, text = "Intent launch failed: ${t.message}")
        }
    }
}
