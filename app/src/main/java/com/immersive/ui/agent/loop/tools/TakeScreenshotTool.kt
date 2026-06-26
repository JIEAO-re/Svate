package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Capture the current screen as a JPEG. The loop captures the canonical frame and
 * attaches it to the observation that follows this tool's function_response;
 * capturing here as well would burn a second frame (and a throttle slot) per call,
 * so this tool only verifies the capture service is reachable.
 */
class TakeScreenshotTool : PhoneTool {
    override val name: String = "take_screenshot"
    override val description: String =
        "Capture the current screen. Use it to see the latest visual state before deciding the next action."
    override val isReadOnly: Boolean = true
    override val riskClass: RiskClass = RiskClass.SAFE

    override fun parametersJsonSchema(): String = ToolSupport.emptySchema()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        ctx.capture()
            ?: return ToolResult(ok = false, text = "Capture service unavailable; screenshot not taken.")
        // The loop flips this to a failure if the capture yields no frame.
        return ToolResult(ok = true, text = "Screenshot captured; image attached as the next observation.")
    }
}
