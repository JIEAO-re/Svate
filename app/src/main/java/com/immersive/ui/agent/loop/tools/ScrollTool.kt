package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Scroll the screen in a direction. Implemented as a swipe in the opposite
 * gesture sense handled by the service; reuses performSwipe direction handling.
 */
class ScrollTool : PhoneTool {
    override val name: String = "scroll"
    override val description: String =
        "Scroll the current screen content in a direction (up/down/left/right) to reveal more elements."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.LOW

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put(
            "direction",
            JSONObject().apply {
                put("type", "string")
                put("description", "Scroll direction.")
                put("enum", JSONArray(listOf("up", "down", "left", "right")))
            },
        )
        return ToolSupport.objectSchema(props, required = listOf("direction"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val service = ctx.service()
            ?: return ToolResult(ok = false, text = "Accessibility service unavailable; scroll not performed.")
        val direction = args.optString("direction", "").trim()
        if (direction.isBlank()) {
            return ToolResult(ok = false, text = "scroll requires a direction.")
        }
        val ok = ToolSupport.awaitGesture { cb -> service.performSwipe(direction, cb) }
        return ToolResult(ok = ok, text = if (ok) "Scrolled $direction." else "Scroll $direction failed.")
    }
}
