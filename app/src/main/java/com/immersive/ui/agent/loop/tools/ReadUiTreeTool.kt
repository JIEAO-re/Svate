package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Read the current accessibility UI tree, pruned to interactive/meaningful nodes,
 * and return a compact text rendering the model can target with x/y or selectors.
 */
class ReadUiTreeTool : PhoneTool {
    override val name: String = "read_ui_tree"
    override val description: String =
        "Read the current screen's UI elements (text, bounds, clickable/editable flags). " +
            "Use the listed bounds to choose tap coordinates."
    override val isReadOnly: Boolean = true
    override val riskClass: RiskClass = RiskClass.SAFE

    override fun parametersJsonSchema(): String = ToolSupport.emptySchema()

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val service = ctx.service()
            ?: return ToolResult(ok = false, text = "Accessibility service unavailable; cannot read UI tree.")
        val nodes = ToolSupport.readPrunedNodes(service)
        val foreground = service.getForegroundPackageName().orEmpty()
        val header = if (foreground.isBlank()) "" else "foreground=$foreground\n"
        return ToolResult(ok = true, text = header + ToolSupport.renderNodes(nodes))
    }
}
