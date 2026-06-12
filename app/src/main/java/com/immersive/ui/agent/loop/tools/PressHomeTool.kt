package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/** Go to the device home screen. */
class PressHomeTool : PhoneTool {
    override val name: String = "press_home"
    override val description: String = "Go to the device home screen."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.LOW

    override fun parametersJsonSchema(): String = ToolSupport.emptySchema()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val service = ctx.service()
            ?: return ToolResult(ok = false, text = "Accessibility service unavailable; home not performed.")
        val ok = service.performHome()
        return ToolResult(ok = ok, text = if (ok) "Returned to home." else "Home press failed.")
    }
}
