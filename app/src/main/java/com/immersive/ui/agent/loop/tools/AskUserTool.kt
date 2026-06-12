package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Ask the user a question and pause for their input. The loop recognizes this
 * tool by name and surfaces the question to the UI; execute() just echoes the
 * question so the loop can present it.
 */
class AskUserTool : PhoneTool {
    override val name: String = "ask_user"
    override val description: String =
        "Ask the user a clarifying question and pause the loop until they answer. " +
            "Use when the goal is ambiguous or needs a user decision."
    override val isReadOnly: Boolean = true
    override val riskClass: RiskClass = RiskClass.SAFE

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("question", ToolSupport.prop("string", "The question to ask the user."))
        return ToolSupport.objectSchema(props, required = listOf("question"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val question = args.optString("question", "").ifBlank { "Please provide more details." }
        return ToolResult(ok = true, text = question)
    }
}
