package com.immersive.ui.agent.loop

import android.content.Context
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.AgentCaptureService
import kotlinx.coroutines.CoroutineScope
import org.json.JSONObject

/**
 * Risk class for a tool, driving the permission floor (see agent-loop.md section 4).
 *
 * - SAFE   : read-only or trivially reversible; never blocked, never prompted.
 * - LOW    : minor write (scroll/back/home); never blocked, runs without prompt in ASK mode.
 * - NORMAL : meaningful write (tap/type/open_app); in ASK mode it prompts unless an
 *            allow-rule matches; in AUTO mode it runs.
 * - HIGH   : sensitive write (launch_intent, or tap/type whose target text hits the
 *            hard-block keywords); always prompts, in both modes.
 */
enum class RiskClass {
    SAFE,
    LOW,
    NORMAL,
    HIGH,
}

/**
 * Result of executing a [PhoneTool].
 *
 * @param ok whether the tool succeeded.
 * @param text a short, model-facing summary of what happened. This becomes the
 *   primary content of the function_response sent back to the model.
 * @param observationAttached set by the loop (not the tool) to record whether a
 *   fresh screen observation was appended after a write tool. Tools always return
 *   it false; the loop flips it when it attaches an observation.
 */
data class ToolResult(
    val ok: Boolean,
    val text: String,
    val observationAttached: Boolean = false,
)

/**
 * Execution handles a tool needs to do its work on the device.
 *
 * The accessibility service and capture service are looked up lazily through
 * their static singletons because the system owns their lifecycle and they may
 * be unavailable (returning null) when the user has not granted permissions.
 */
class ToolContext(
    val appContext: Context,
    val scope: CoroutineScope,
) {
    /** The running accessibility service instance, or null when disabled. */
    fun service(): AgentAccessibilityService? = AgentAccessibilityService.instance

    /** The running capture service instance, or null when disabled. */
    fun capture(): AgentCaptureService? = AgentCaptureService.instance
}

/**
 * A single capability the on-device loop can expose to the remote model as a
 * function declaration. Each tool wraps an existing device capability.
 */
interface PhoneTool {
    /** Stable wire name (snake_case), e.g. "take_screenshot". */
    val name: String

    /** Human/model-facing description of what the tool does and when to use it. */
    val description: String

    /** Whether the tool only reads device state (no side effects). */
    val isReadOnly: Boolean

    /** Risk class driving the permission floor. */
    val riskClass: RiskClass

    /**
     * JSON Schema (draft-07 style "object" schema) for this tool's parameters,
     * serialized as a string. Built with org.json so no extra dependency is needed.
     */
    fun parametersJsonSchema(): String

    /**
     * Execute the tool with the model-supplied [args] in the given [ctx].
     *
     * Implementations must never throw for expected failures (missing service,
     * rejected target, etc.); they return ToolResult(ok = false, ...) instead so
     * the loop can feed the failure back to the model as a function_response.
     */
    suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult
}
