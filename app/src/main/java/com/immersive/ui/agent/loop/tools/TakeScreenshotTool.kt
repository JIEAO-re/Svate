package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Capture the current screen as a JPEG. The loop is responsible for attaching the
 * actual image bytes to the latest observation; this tool only confirms a capture
 * succeeded so the model knows a fresh frame is available.
 */
class TakeScreenshotTool : PhoneTool {
    override val name: String = "take_screenshot"
    override val description: String =
        "Capture the current screen. Use it to see the latest visual state before deciding the next action."
    override val isReadOnly: Boolean = true
    override val riskClass: RiskClass = RiskClass.SAFE

    override fun parametersJsonSchema(): String = ToolSupport.emptySchema()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val capture = ctx.capture()
            ?: return ToolResult(ok = false, text = "Capture service unavailable; screenshot not taken.")
        val bytes = capture.captureBytes()
        return if (bytes != null && bytes.isNotEmpty()) {
            ToolResult(ok = true, text = "Screenshot captured (${bytes.size} bytes).")
        } else {
            ToolResult(ok = false, text = "Screenshot capture returned no frame.")
        }
    }
}
