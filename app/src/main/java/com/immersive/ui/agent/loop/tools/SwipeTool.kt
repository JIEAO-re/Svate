package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Swipe the screen, either by a named direction (up/down/left/right) or by an
 * explicit pixel path {x1,y1,x2,y2}. The pixel path is normalized against the
 * real screen size and dispatched through the service's spatial drag.
 */
class SwipeTool : PhoneTool {
    override val name: String = "swipe"
    override val description: String =
        "Swipe the screen. Provide a direction (up/down/left/right), or an explicit path {x1,y1,x2,y2} in pixels."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.LOW

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put(
            "direction",
            JSONObject().apply {
                put("type", "string")
                put("description", "Swipe direction.")
                put("enum", org.json.JSONArray(listOf("up", "down", "left", "right")))
            },
        )
        props.put("x1", ToolSupport.prop("integer", "Start X pixel for an explicit swipe path."))
        props.put("y1", ToolSupport.prop("integer", "Start Y pixel for an explicit swipe path."))
        props.put("x2", ToolSupport.prop("integer", "End X pixel for an explicit swipe path."))
        props.put("y2", ToolSupport.prop("integer", "End Y pixel for an explicit swipe path."))
        return ToolSupport.objectSchema(props)
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val service = ctx.service()
            ?: return ToolResult(ok = false, text = "Accessibility service unavailable; swipe not performed.")

        // Explicit path takes priority when all four coordinates are present.
        if (args.has("x1") && args.has("y1") && args.has("x2") && args.has("y2")) {
            val (screenWidth, screenHeight) = service.getScreenSize()
            if (screenWidth <= 0 || screenHeight <= 0) {
                return ToolResult(ok = false, text = "Screen size unavailable for swipe path.")
            }
            val fromX = (args.optDouble("x1", 0.0) / screenWidth).toFloat().coerceIn(0f, 1f)
            val fromY = (args.optDouble("y1", 0.0) / screenHeight).toFloat().coerceIn(0f, 1f)
            val toX = (args.optDouble("x2", 0.0) / screenWidth).toFloat().coerceIn(0f, 1f)
            val toY = (args.optDouble("y2", 0.0) / screenHeight).toFloat().coerceIn(0f, 1f)
            val ok = ToolSupport.awaitGesture { cb ->
                service.performSpatialDrag(fromX, fromY, toX, toY, callback = cb)
            }
            return ToolResult(ok = ok, text = if (ok) "Swiped along path." else "Swipe path failed.")
        }

        val direction = args.optString("direction", "").trim().ifBlank { "up" }
        val ok = ToolSupport.awaitGesture { cb -> service.performSwipe(direction, cb) }
        return ToolResult(ok = ok, text = if (ok) "Swiped $direction." else "Swipe $direction failed.")
    }
}
