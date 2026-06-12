package com.immersive.ui.agent.loop

import com.immersive.ui.agent.loop.tools.AskUserTool
import com.immersive.ui.agent.loop.tools.FinishTool
import com.immersive.ui.agent.loop.tools.LaunchIntentTool
import com.immersive.ui.agent.loop.tools.OpenAppTool
import com.immersive.ui.agent.loop.tools.PressBackTool
import com.immersive.ui.agent.loop.tools.PressHomeTool
import com.immersive.ui.agent.loop.tools.ReadUiTreeTool
import com.immersive.ui.agent.loop.tools.ScrollTool
import com.immersive.ui.agent.loop.tools.SwipeTool
import com.immersive.ui.agent.loop.tools.TakeScreenshotTool
import com.immersive.ui.agent.loop.tools.TapTool
import com.immersive.ui.agent.loop.tools.TypeTextTool
import com.immersive.ui.agent.loop.tools.WaitTool
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registry of the v1 tool set. Holds the canonical ordered list and produces the
 * wire `tools[]` declarations sent to the model proxy.
 *
 * The well-known tool names finish/ask_user are exposed as constants so the loop
 * can branch on them without a brittle string literal.
 */
class ToolRegistry private constructor(
    private val tools: List<PhoneTool>,
) {
    /** Immutable, ordered view of the registered tools. */
    fun tools(): List<PhoneTool> = tools

    /** Look up a tool by its wire name, or null if unknown. */
    fun byName(name: String): PhoneTool? = tools.firstOrNull { it.name == name }

    /**
     * Build the wire `tools[]` array: one object per tool with name, description,
     * and the parsed parameters_json_schema. The schema string is reparsed into a
     * JSONObject so it embeds as a real JSON object, not a quoted string.
     */
    fun toDeclarations(): JSONArray {
        val arr = JSONArray()
        for (tool in tools) {
            arr.put(
                JSONObject().apply {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters_json_schema", JSONObject(tool.parametersJsonSchema()))
                },
            )
        }
        return arr
    }

    companion object {
        const val FINISH_TOOL = "finish"
        const val ASK_USER_TOOL = "ask_user"

        /** Build the default v1 registry in a stable, documented order. */
        fun createDefault(): ToolRegistry {
            return ToolRegistry(
                listOf(
                    TakeScreenshotTool(),
                    ReadUiTreeTool(),
                    TapTool(),
                    TypeTextTool(),
                    SwipeTool(),
                    ScrollTool(),
                    PressBackTool(),
                    PressHomeTool(),
                    OpenAppTool(),
                    LaunchIntentTool(),
                    WaitTool(),
                    FinishTool(),
                    AskUserTool(),
                ),
            )
        }
    }
}
