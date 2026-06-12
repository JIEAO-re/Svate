package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Terminate the loop. The loop recognizes this tool by name and emits a Finished
 * event; execute() simply echoes the model's summary and success flag so the
 * loop can carry them into the terminal event.
 */
class FinishTool : PhoneTool {
    override val name: String = "finish"
    override val description: String =
        "Finish the task. Provide a short summary of the outcome and whether it succeeded."
    override val isReadOnly: Boolean = true
    override val riskClass: RiskClass = RiskClass.SAFE

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("summary", ToolSupport.prop("string", "Short summary of what was accomplished."))
        props.put("success", ToolSupport.prop("boolean", "Whether the task was completed successfully."))
        return ToolSupport.objectSchema(props, required = listOf("summary", "success"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val success = args.optBoolean("success", true)
        val summary = args.optString("summary", "").ifBlank { "Task finished." }
        return ToolResult(ok = success, text = summary)
    }
}
