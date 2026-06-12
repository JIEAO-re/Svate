package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import org.json.JSONObject

/**
 * Type text into the currently focused input field, optionally submitting (IME
 * enter / search button) afterward. The caller is expected to have focused the
 * field (e.g. via a prior tap).
 */
class TypeTextTool : PhoneTool {
    override val name: String = "type_text"
    override val description: String =
        "Type text into the focused input field. Set submit=true to press enter/search after typing. " +
            "Focus the field with a tap first if needed."
    override val isReadOnly: Boolean = false
    override val riskClass: RiskClass = RiskClass.NORMAL

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("text", ToolSupport.prop("string", "The text to type into the focused field."))
        props.put("submit", ToolSupport.prop("boolean", "Whether to submit (enter/search) after typing."))
        return ToolSupport.objectSchema(props, required = listOf("text"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val service = ctx.service()
            ?: return ToolResult(ok = false, text = "Accessibility service unavailable; typing not performed.")
        val text = args.optString("text", "")
        if (text.isBlank()) {
            return ToolResult(ok = false, text = "type_text requires non-empty text.")
        }
        val typed = service.performInput(text)
        if (!typed) {
            return ToolResult(ok = false, text = "Could not type; no editable field is focused.")
        }
        val submit = args.optBoolean("submit", false)
        if (!submit) {
            return ToolResult(ok = true, text = "Typed \"$text\".")
        }
        val submitted = service.performSubmitInput()
        return ToolResult(
            ok = true,
            text = if (submitted) "Typed \"$text\" and submitted." else "Typed \"$text\"; submit had no effect.",
        )
    }
}
