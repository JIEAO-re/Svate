package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.loop.PhoneTool
import com.immersive.ui.agent.loop.RiskClass
import com.immersive.ui.agent.loop.ToolContext
import com.immersive.ui.agent.loop.ToolResult
import kotlinx.coroutines.delay
import org.json.JSONObject

/** Wait for a bounded number of milliseconds to let the UI settle. */
class WaitTool : PhoneTool {
    override val name: String = "wait"
    override val description: String =
        "Wait for a short time (milliseconds) to let the screen settle before observing again."
    override val isReadOnly: Boolean = true
    override val riskClass: RiskClass = RiskClass.SAFE

    override fun parametersJsonSchema(): String {
        val props = JSONObject()
        props.put("ms", ToolSupport.prop("integer", "Milliseconds to wait (clamped to a safe range)."))
        return ToolSupport.objectSchema(props, required = listOf("ms"))
    }

    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult {
        // Clamp so the model cannot stall the loop with a huge or negative value.
        val ms = args.optLong("ms", 1_000L).coerceIn(0L, 10_000L)
        delay(ms)
        return ToolResult(ok = true, text = "Waited ${ms}ms.")
    }
}
