package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import com.immersive.ui.agent.shizuku.ShizukuTextInput
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

    /**
     * Defensive decode: the model sometimes URL/percent-encodes Chinese (e.g.
     * "%E6%88%91%E6%83%B3%E4%BD%A0%E4%BA%86" for "我想你了"). type_text writes the string
     * literally via ACTION_SET_TEXT, so a fully percent-encoded value would land as that
     * raw "%..." text. When the WHOLE string is percent escapes, decode it back to real
     * characters. A normal message containing a stray "%" does not match, so it is untouched.
     */
    private fun decodeIfPercentEncoded(text: String): String {
        if (text.length >= 3 && text.matches(Regex("(?:%[0-9A-Fa-f]{2})+"))) {
            return runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrDefault(text)
        }
        return text
    }

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("text", ToolSupport.prop("string", "The text to type into the focused field."))
        props.put("submit", ToolSupport.prop("boolean", "Whether to submit (enter/search) after typing."))
        return ToolSupport.objectSchema(props, required = listOf("text"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        val service = ctx.service()
            ?: return ToolResult(ok = false, text = "Accessibility service unavailable; typing not performed.")
        val text = decodeIfPercentEncoded(args.optString("text", ""))
        if (text.isBlank()) {
            return ToolResult(ok = false, text = "type_text requires non-empty text.")
        }
        // Strict mode: only write when a field actually holds input focus. Never fall
        // back to the first editable node on the page, so we cannot type into the wrong field.
        val typed = service.performInputStrict(text)
        if (!typed) {
            // The focused field is not an accessible node (e.g. WeChat, whose UI tree is
            // empty). Fall back to ADBKeyboard via Shizuku, which commits text through the
            // InputConnection like a real keyboard, so the field need not be in the tree.
            if (ShizukuTextInput.type(text)) {
                return ToolResult(ok = true, text = "Typed \"$text\" via privileged keyboard.")
            }
            return ToolResult(
                ok = false,
                text = "No focused input field — tap the field first. (Privileged keyboard input is " +
                    "unavailable: needs Shizuku ready and ADBKeyboard installed.)",
            )
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
