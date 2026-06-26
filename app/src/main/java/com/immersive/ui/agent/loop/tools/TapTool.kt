package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.UiNode
import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Tap a point on screen. Targeting priority mirrors the existing motor:
 * absolute x/y pixels, then a SoM id resolved from the pruned UI tree, then an
 * exact-text selector. target_desc is advisory text only (used for permission
 * descriptions and keyword screening upstream).
 */
class TapTool : PhoneTool {
    override val name: String = "tap"
    override val description: String =
        "Tap a UI element. Provide pixel coordinates {x,y}, or a som_id from read_ui_tree's node index, " +
            "or a selector (visible text to match exactly). target_desc describes the element in words."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.NORMAL

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("x", ToolSupport.prop("integer", "Absolute X pixel coordinate of the tap target."))
        props.put("y", ToolSupport.prop("integer", "Absolute Y pixel coordinate of the tap target."))
        props.put("som_id", ToolSupport.prop("integer", "Node index from read_ui_tree to tap at its center."))
        props.put("selector", ToolSupport.prop("string", "Exact visible text of the element to tap."))
        props.put("target_desc", ToolSupport.prop("string", "Human description of the tap target."))
        return ToolSupport.objectSchema(props)
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val service = ctx.service()
            ?: return ToolResult(ok = false, text = "Accessibility service unavailable; tap not performed.")

        // Priority 1: explicit pixel coordinates.
        if (args.has("x") && args.has("y")) {
            val x = args.optDouble("x", Double.NaN)
            val y = args.optDouble("y", Double.NaN)
            if (x.isNaN() || y.isNaN()) {
                return ToolResult(ok = false, text = "Invalid x/y coordinates for tap.")
            }
            val ok = ToolSupport.awaitGesture { cb -> service.performClickAt(x.toFloat(), y.toFloat(), cb) }
            return ToolResult(ok = ok, text = if (ok) "Tapped at ($x, $y)." else "Tap at ($x, $y) failed.")
        }

        // Priority 2: SoM id resolved against the pruned UI tree.
        val somId = if (args.has("som_id")) args.optInt("som_id", -1) else -1
        if (somId >= 0) {
            val node = resolveBySomId(somId, ToolSupport.readPrunedNodes(service))
            if (node != null) {
                val centerX = (node.bounds.left + node.bounds.right) / 2f
                val centerY = (node.bounds.top + node.bounds.bottom) / 2f
                val ok = ToolSupport.awaitGesture { cb -> service.performClickAt(centerX, centerY, cb) }
                return ToolResult(ok = ok, text = if (ok) "Tapped node #$somId." else "Tap on node #$somId failed.")
            }
            return ToolResult(ok = false, text = "No UI node matched som_id=$somId.")
        }

        // Priority 3: exact-text selector. performClickByExactText already rejects
        // hard-blocked candidates, so a stray "pay" button can never be clicked here.
        val selector = args.optString("selector", "").trim()
        if (selector.isNotEmpty()) {
            val ok = service.performClickByExactText(selector)
            return ToolResult(
                ok = ok,
                text = if (ok) "Tapped element matching \"$selector\"." else "No safe element matched \"$selector\".",
            )
        }

        return ToolResult(ok = false, text = "tap requires x/y, som_id, or selector.")
    }

    private fun resolveBySomId(somId: Int, nodes: List<UiNode>): UiNode? {
        return nodes.firstOrNull { it.index == somId }
    }
}
