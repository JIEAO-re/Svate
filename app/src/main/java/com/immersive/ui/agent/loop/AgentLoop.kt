package com.immersive.ui.agent.loop

import android.content.Context
import com.immersive.ui.agent.IntentGuard
import com.immersive.ui.agent.IntentSpec
import com.immersive.ui.agent.flow.VisualInjectionGuard
import com.immersive.ui.agent.loop.tools.ToolSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Permission mode, toggled live from the UI. */
enum class PermissionMode { ASK, AUTO }

/** The user's resolution of a permission prompt. */
enum class PermissionDecision { GRANT_ONCE, GRANT_ALWAYS, DENY }

/** Events emitted by the loop for the UI to render. */
sealed interface AgentLoopEvent {
    data class Narration(val text: String) : AgentLoopEvent
    data class PhaseChanged(val phase: String) : AgentLoopEvent
    data class ToolStarted(val toolName: String, val summary: String) : AgentLoopEvent
    data class ToolFinished(val toolName: String, val ok: Boolean, val summary: String) : AgentLoopEvent
    data class AwaitingPermission(
        val toolCallId: String,
        val toolName: String,
        val description: String,
        val riskClass: String, // "safe" | "low" | "normal" | "high"
    ) : AgentLoopEvent
    data class Finished(val success: Boolean, val summary: String) : AgentLoopEvent
    data class Failed(val reason: String) : AgentLoopEvent
}

/**
 * Claude-Code-style on-device tool loop. The device owns the loop and calls tools
 * locally; the cloud is a thin function-calling proxy queried once per turn.
 *
 * The loop runs in [scope]; [start] launches it, [stop] cancels it, and
 * [resolvePermission] wakes a suspended permission prompt. [mode] is settable live.
 */
class AgentLoop(
    appContext: Context,
    private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<AgentLoopEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AgentLoopEvent> = _events.asSharedFlow()

    @Volatile
    var mode: PermissionMode = PermissionMode.ASK
        set(value) {
            field = value
            state.mode = value
        }

    private val state = AgentLoopState(initialMode = mode)
    private val registry = ToolRegistry.createDefault()
    private val gate = PermissionGate()
    private val turnClient = AgentTurnClient()
    private val injectionGuard = VisualInjectionGuard()
    private val toolContext = ToolContext(appContext.applicationContext, scope)

    /** Pending permission prompts keyed by toolCallId. */
    private val pendingPermissions = ConcurrentHashMap<String, CompletableDeferred<PermissionDecision>>()

    private val sessionId: String = "loop_${System.currentTimeMillis()}"

    @Volatile
    private var loopJob: Job? = null

    /** Start the loop for the given [goal]. A second call is ignored while running. */
    fun start(goal: String) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            try {
                runLoop(goal)
            } catch (t: Throwable) {
                emit(AgentLoopEvent.Failed("loop error: ${t.message}"))
            }
        }
    }

    /** Cancel the loop and reject any pending permission prompts. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        // Release waiters so suspended prompts do not leak.
        for ((_, deferred) in pendingPermissions) {
            deferred.complete(PermissionDecision.DENY)
        }
        pendingPermissions.clear()
    }

    /** Resolve a previously emitted AwaitingPermission. No-op for unknown ids. */
    fun resolvePermission(toolCallId: String, decision: PermissionDecision) {
        pendingPermissions.remove(toolCallId)?.complete(decision)
    }

    // ===== Core loop =====

    private suspend fun runLoop(goal: String) {
        emit(AgentLoopEvent.PhaseChanged("starting"))
        state.appendUserText(buildString {
            append("Goal: ").append(goal).append('\n')
            append(buildObservationText())
        })
        attachObservationImage()

        while (scope.isActive) {
            if (!state.beginTurn()) {
                emit(AgentLoopEvent.Failed("reached max turns (${state.maxTurns})"))
                return
            }
            emit(AgentLoopEvent.PhaseChanged("thinking"))

            val response = try {
                turnClient.runTurn(
                    sessionId = sessionId,
                    traceId = "turn_${state.turnsUsed()}_${System.currentTimeMillis()}",
                    systemInstruction = buildSystemInstruction(),
                    contents = state.toContents(),
                    tools = registry.toDeclarations(),
                )
            } catch (t: Throwable) {
                emit(AgentLoopEvent.Failed("turn request failed: ${t.message}"))
                return
            }

            response.text?.let { narration ->
                state.appendModelText(narration)
                emit(AgentLoopEvent.Narration(narration))
            }

            // Turn complete with no tool calls: the model is done.
            if (response.toolCalls.isEmpty()) {
                emit(AgentLoopEvent.Finished(success = true, summary = response.text ?: "Done."))
                return
            }

            emit(AgentLoopEvent.PhaseChanged("acting"))
            for (call in response.toolCalls) {
                val terminal = handleToolCall(call)
                if (terminal) return
            }
        }
    }

    /**
     * Handle one tool call end to end. Returns true when the loop should terminate
     * (finish tool, declined ask_user, or budget exhaustion).
     */
    private suspend fun handleToolCall(call: TurnToolCall): Boolean {
        val tool = registry.byName(call.name)
        if (tool == null) {
            // Unknown tool: feed an error back so the model can correct course.
            state.appendFunctionCall(call.name, call.args)
            state.appendFunctionResponse(call.name, errorResponse("unknown tool: ${call.name}"))
            emit(AgentLoopEvent.ToolFinished(call.name, ok = false, summary = "unknown tool"))
            return checkBudgetAfterFailure("unknown tool: ${call.name}")
        }

        // Record the model's function_call before resolving/executing it.
        state.appendFunctionCall(call.name, call.args)
        emit(AgentLoopEvent.ToolStarted(call.name, summarizeArgs(call)))

        // ask_user pauses for the user; reuse the permission channel as the resume
        // signal (GRANT = acknowledged/continue, DENY = user declined).
        if (call.name == ToolRegistry.ASK_USER_TOOL) {
            return handleAskUser(call, tool)
        }

        val query = buildQuery(tool, call)
        val outcome = gate.evaluate(query, mode)

        when (outcome) {
            GateOutcome.DENY -> {
                // Feed the denial back so the model can pick another route.
                state.appendFunctionResponse(
                    call.name,
                    errorResponse("denied by safety policy"),
                )
                emit(AgentLoopEvent.ToolFinished(call.name, ok = false, summary = "denied by policy"))
                return checkBudgetAfterFailure("denied by policy")
            }

            GateOutcome.ASK -> {
                val decision = awaitPermission(call, tool, query)
                if (decision == PermissionDecision.DENY) {
                    state.appendFunctionResponse(
                        call.name,
                        errorResponse("user declined the action"),
                    )
                    emit(AgentLoopEvent.ToolFinished(call.name, ok = false, summary = "user declined"))
                    return checkBudgetAfterFailure("user declined")
                }
                gate.applyDecision(query, decision)
                // Granted: fall through to execution.
            }

            GateOutcome.ALLOW -> Unit
        }

        return executeAndRecord(call, tool)
    }

    /** Execute a granted/allowed tool, append its response, and handle finish/budget. */
    private suspend fun executeAndRecord(call: TurnToolCall, tool: PhoneTool): Boolean {
        val result = try {
            tool.execute(call.args, toolContext)
        } catch (t: Throwable) {
            ToolResult(ok = false, text = "tool threw: ${t.message}")
        }

        emit(AgentLoopEvent.ToolFinished(call.name, ok = result.ok, summary = result.text))

        // finish terminates the loop with the model's summary.
        if (call.name == ToolRegistry.FINISH_TOOL) {
            state.appendFunctionResponse(call.name, successResponse(result.text))
            emit(AgentLoopEvent.Finished(success = result.ok, summary = result.text))
            return true
        }

        // After a successful write tool, attach a fresh observation so the model
        // sees the effect of its action.
        var attached = false
        if (!tool.isReadOnly && result.ok) {
            appendObservationAfterWrite()
            attached = true
        }

        val response = if (result.ok) successResponse(result.text) else errorResponse(result.text)
        response.put("observation_attached", attached)
        state.appendFunctionResponse(call.name, response)

        return if (result.ok) {
            state.recordToolSuccess()
            false
        } else {
            checkBudgetAfterFailure(result.text)
        }
    }

    /** ask_user: surface the question and pause until the user resumes the loop. */
    private suspend fun handleAskUser(call: TurnToolCall, tool: PhoneTool): Boolean {
        val result = try {
            tool.execute(call.args, toolContext)
        } catch (t: Throwable) {
            ToolResult(ok = false, text = "ask_user failed: ${t.message}")
        }
        val question = result.text
        emit(AgentLoopEvent.Narration(question))
        emit(AgentLoopEvent.PhaseChanged("awaiting_user"))

        val deferred = CompletableDeferred<PermissionDecision>()
        pendingPermissions[call.id] = deferred
        emit(
            AgentLoopEvent.AwaitingPermission(
                toolCallId = call.id,
                toolName = call.name,
                description = question,
                riskClass = riskLabel(tool.riskClass),
            ),
        )
        val decision = deferred.await()
        pendingPermissions.remove(call.id)

        if (decision == PermissionDecision.DENY) {
            state.appendFunctionResponse(call.name, errorResponse("user declined to answer"))
            emit(AgentLoopEvent.Finished(success = false, summary = "Stopped: user declined to answer."))
            return true
        }
        // The frozen API carries no free-text answer; record acknowledgement so the
        // model continues with the user's implicit go-ahead.
        state.appendFunctionResponse(call.name, successResponse("user acknowledged the question"))
        return false
    }

    /** Emit AwaitingPermission and suspend until [resolvePermission] wakes it. */
    private suspend fun awaitPermission(
        call: TurnToolCall,
        tool: PhoneTool,
        query: PermissionQuery,
    ): PermissionDecision {
        val deferred = CompletableDeferred<PermissionDecision>()
        pendingPermissions[call.id] = deferred
        emit(
            AgentLoopEvent.AwaitingPermission(
                toolCallId = call.id,
                toolName = call.name,
                description = summarizeArgs(call),
                riskClass = riskLabel(gate.effectiveRisk(query)),
            ),
        )
        val decision = deferred.await()
        pendingPermissions.remove(call.id)
        return decision
    }

    // ===== Deny floor + query building =====

    /**
     * Build the permission query for a tool call, computing the on-device deny
     * floor: a visual-injection HIGH screen, or a launch_intent the IntentGuard
     * rejects. Keyword upgrades for tap/type are handled inside the gate.
     */
    private fun buildQuery(tool: PhoneTool, call: TurnToolCall): PermissionQuery {
        val targetText = extractTargetText(call.args)
        val deny = computeDenyFloor(tool, call)
        return PermissionQuery(
            toolName = tool.name,
            riskClass = tool.riskClass,
            isReadOnly = tool.isReadOnly,
            targetText = targetText,
            denyFloor = deny,
            packageScope = call.args.optString("package", "").trim(),
        )
    }

    private fun computeDenyFloor(tool: PhoneTool, call: TurnToolCall): Boolean {
        // launch_intent rejected by the whitelist is a hard deny.
        if (tool.name == "launch_intent") {
            val spec = IntentSpec(
                action = call.args.optString("action", "").trim(),
                dataUri = call.args.optString("uri", "").trim().ifBlank { null },
                packageName = call.args.optString("package", "").trim().ifBlank { null },
            )
            if (!IntentGuard.validate(spec, fallbackPackage = spec.packageName).allowed) {
                return true
            }
        }

        // A visual-injection HIGH/CRITICAL screen hard-blocks any write tool.
        if (!tool.isReadOnly) {
            val service = toolContext.service()
            if (service != null) {
                val nodes = ToolSupport.readPrunedNodes(service)
                val foreground = service.getForegroundPackageName()
                val (w, h) = service.getScreenSize()
                val scan = injectionGuard.scan(
                    uiNodes = nodes,
                    screenshotBase64 = null,
                    foregroundPackage = foreground,
                    screenWidth = w,
                    screenHeight = h,
                )
                if (scan.shouldBlock) return true
            }
        }
        return false
    }

    /** Pull the screenable target text (selector/desc/typed text) from args. */
    private fun extractTargetText(args: JSONObject): String {
        val parts = mutableListOf<String>()
        for (key in listOf("selector", "target_desc", "text", "app_name")) {
            val value = args.optString(key, "").trim()
            if (value.isNotEmpty()) parts.add(value)
        }
        return parts.joinToString(" ")
    }

    // ===== Observations =====

    /** Build the text portion of an observation (foreground + pruned nodes). */
    private fun buildObservationText(): String {
        val service = toolContext.service() ?: return "Observation: accessibility service unavailable."
        val nodes = ToolSupport.readPrunedNodes(service)
        val foreground = service.getForegroundPackageName().orEmpty()
        val header = if (foreground.isBlank()) "" else "foreground=$foreground\n"
        return "Observation:\n" + header + ToolSupport.renderNodes(nodes)
    }

    /** Attach an image-bearing observation for the current screen, if capture works. */
    private suspend fun attachObservationImage() {
        val capture = toolContext.capture() ?: return
        val bytes = capture.captureBase64()
        if (!bytes.isNullOrBlank()) {
            state.appendObservation(text = buildObservationText(), imageBase64 = bytes)
        }
    }

    /** Append a fresh observation after a write tool so the model sees the effect. */
    private suspend fun appendObservationAfterWrite() {
        val capture = toolContext.capture()
        val image = capture?.captureBase64()
        state.appendObservation(text = buildObservationText(), imageBase64 = image)
    }

    // ===== Budgets and helpers =====

    /** Record a failure; if the budget is exhausted, emit Failed and signal terminate. */
    private suspend fun checkBudgetAfterFailure(reason: String): Boolean {
        val withinBudget = state.recordToolFailure()
        return if (!withinBudget) {
            emit(AgentLoopEvent.Failed("failure budget exhausted: $reason"))
            true
        } else {
            false
        }
    }

    private suspend fun emit(event: AgentLoopEvent) {
        _events.emit(event)
    }

    private fun successResponse(text: String): JSONObject =
        JSONObject().apply {
            put("ok", true)
            put("result", text)
        }

    private fun errorResponse(text: String): JSONObject =
        JSONObject().apply {
            put("ok", false)
            put("error", text)
        }

    private fun summarizeArgs(call: TurnToolCall): String {
        val desc = call.args.optString("target_desc", "").trim()
        if (desc.isNotEmpty()) return "${call.name}: $desc"
        val keys = call.args.keys().asSequence().toList()
        if (keys.isEmpty()) return call.name
        val rendered = keys.joinToString(", ") { key -> "$key=${call.args.opt(key)}" }
        return "${call.name}($rendered)"
    }

    private fun riskLabel(risk: RiskClass): String = when (risk) {
        RiskClass.SAFE -> "safe"
        RiskClass.LOW -> "low"
        RiskClass.NORMAL -> "normal"
        RiskClass.HIGH -> "high"
    }

    private fun buildSystemInstruction(): String {
        return buildString {
            appendLine("You are an on-device Android UI automation agent.")
            appendLine("You drive a real phone by calling the provided tools one step at a time.")
            appendLine()
            appendLine("Tools: take_screenshot and read_ui_tree observe the screen; tap/type_text/swipe/")
            appendLine("scroll/press_back/press_home/open_app/launch_intent act on it; wait pauses; finish")
            appendLine("ends the task; ask_user asks the user a clarifying question.")
            appendLine()
            appendLine("Targeting: prefer tapping by the bounds shown in read_ui_tree (use the center as x/y),")
            appendLine("or pass som_id (the node index), or a selector matching exact visible text. Read the")
            appendLine("latest screenshot and UI nodes before each action.")
            appendLine()
            appendLine("Safety red lines: never attempt payment, transfer, password entry, deletion, or")
            appendLine("system authorization. high-risk steps require explicit user approval and may be denied;")
            appendLine("if a tool is denied, choose a different, safer path. Call finish with a summary when the")
            appendLine("goal is achieved or cannot be safely completed.")
        }
    }
}
