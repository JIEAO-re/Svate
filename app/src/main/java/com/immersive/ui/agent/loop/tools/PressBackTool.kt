package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/** Press the system Back button. */
class PressBackTool : PhoneTool {
    override val name: String = "press_back"
    override val description: String = "Press the system Back button to go to the previous screen."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.LOW

    override fun parametersJsonSchema(): String = ToolSupport.emptySchema()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val service = ctx.service()
            ?: return ToolResult(ok = false, text = "Accessibility service unavailable; back not performed.")
        val ok = service.performBack()
        return ToolResult(ok = ok, text = if (ok) "Pressed back." else "Back press failed.")
    }
}
