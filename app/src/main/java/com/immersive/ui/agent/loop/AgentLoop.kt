package com.immersive.ui.agent.loop

import android.content.Context
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.IntentGuard
import com.immersive.ui.agent.IntentSpec
import com.immersive.ui.agent.UiNode
import com.immersive.ui.agent.flow.VisualInjectionGuard
import com.immersive.ui.agent.loop.tools.ToolSupport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
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
    private val injectionGuard = VisualInjectionGuard()
    private val toolContext = ToolContext(appContext.applicationContext, scope)

    // Resolved per task in start(): a user-configured OpenAI-compatible endpoint
    // (device talks to it directly) takes precedence over the project backend.
    @Volatile
    private var turnClient: TurnClient = AgentTurnClient()

    /** Pending permission prompts keyed by toolCallId. */
    private val pendingPermissions = ConcurrentHashMap<String, CompletableDeferred<PermissionDecision>>()

    // Refreshed on every start so per-task state (allow-rules, conversation,
    // budgets, session id) never leaks across tasks.
    @Volatile
    private var sessionId: String = "loop_${System.currentTimeMillis()}"

    @Volatile
    private var loopJob: Job? = null

    /** Start the loop for the given [goal]. A second call is ignored while running. */
    fun start(goal: String) {
        if (loopJob?.isActive == true) return

        // Isolate this task from any previous run: clear conversation/budgets,
        // drop session allow-rules (so "always allow" never crosses tasks), reset
        // any pending permission prompts, and mint a fresh session id.
        state.reset()
        gate.clearRules()
        pendingPermissions.clear()
        sessionId = "loop_${System.currentTimeMillis()}"

        // Pick the model transport for this task: a locally-configured
        // OpenAI-compatible endpoint drives the model directly from the device
        // (no backend, no Gemini default); otherwise fall back to the backend.
        val endpoint = ModelEndpointStore.load(toolContext.appContext)
        turnClient = if (endpoint.isConfigured()) DirectOpenAiTurnClient(endpoint) else AgentTurnClient()

        // Run the loop off the main thread; SharedFlow.emit is thread-safe and UI
        // collectors hop back to Main themselves.
        loopJob = scope.launch(Dispatchers.Default) {
            try {
                runLoop(goal)
            } catch (t: Throwable) {
                // Let cancellation propagate so stop() does not surface a false
                // failure.
                if (t is kotlinx.coroutines.CancellationException) throw t
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

        // Check the *current* coroutine's lifecycle (the loop job), not the parent
        // scope, so cancelling this loop alone stops it.
        while (currentCoroutineContext().isActive) {
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
                if (t is kotlinx.coroutines.CancellationException) throw t
                emit(AgentLoopEvent.Failed("turn request failed: ${t.message}"))
                return
            }

            // A truncated (MAX_TOKENS) turn may carry partial narration AND incomplete
            // tool calls. Handle it before anything else: do not append the partial
            // model text (it would leave the history ending on a model role, which
            // Gemini rejects next turn) and never execute possibly-incomplete calls.
            // Append a user nudge so the conversation stays valid, then let the
            // failure budget decide whether to stop.
            if (response.meta.truncated) {
                state.appendUserText(
                    "(Your previous reply was cut off because it reached the length limit. " +
                        "Continue with a single concise next step.)",
                )
                val terminal = checkBudgetAfterFailure("model output truncated")
                if (terminal) return
                continue
            }

            response.text?.let { narration ->
                state.appendModelText(narration)
                emit(AgentLoopEvent.Narration(narration))
            }

            // No tool calls this turn: distinguish a real completion from a
            // degraded turn before reporting success (meta contract).
            if (response.toolCalls.isEmpty()) {
                if (response.meta.toolCallsUnsupported) {
                    // The backend could not execute tools; the model only returned
                    // text, so the loop cannot make progress.
                    emit(AgentLoopEvent.Failed("后端不支持工具调用,无法自主执行"))
                    return
                }
                // Genuine completion: the model is done.
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
        var result = try {
            tool.execute(call.args, toolContext)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            ToolResult(ok = false, text = "tool threw: ${t.message}")
        }

        // finish terminates the loop with the model's summary.
        if (call.name == ToolRegistry.FINISH_TOOL) {
            emit(AgentLoopEvent.ToolFinished(call.name, ok = result.ok, summary = result.text))
            state.appendFunctionResponse(call.name, successResponse(result.text))
            emit(AgentLoopEvent.Finished(success = result.ok, summary = result.text))
            return true
        }

        // After a successful write tool — or an explicit take_screenshot, whose whole
        // purpose is the image — capture a fresh observation. The Gemini contract
        // requires every model[function_call] to be directly followed by its
        // function[function_response] (agent-loop.md §2/§6), so the observation text
        // is merged into the response itself and the screenshot image is appended as
        // a *separate* user observation AFTER the response.
        val wantsObservation = result.ok &&
            (!tool.isReadOnly || call.name == ToolRegistry.SCREENSHOT_TOOL)
        val observationText = if (wantsObservation) buildObservationText() else null
        val observationImage = if (wantsObservation) captureObservationImage() else null

        // take_screenshot with no frame is a failure the model must hear about,
        // not a success with a silently missing image.
        if (call.name == ToolRegistry.SCREENSHOT_TOOL && result.ok && observationImage.isNullOrBlank()) {
            result = ToolResult(
                ok = false,
                text = "Screenshot capture returned no frame (screen recording may not be authorized); use read_ui_tree instead.",
            )
        }

        emit(AgentLoopEvent.ToolFinished(call.name, ok = result.ok, summary = result.text))

        val attached = observationText != null && result.ok
        val response = if (result.ok) successResponse(result.text) else errorResponse(result.text)
        response.put("observation_attached", attached)
        // observationText is non-null whenever attached is true.
        if (attached) {
            response.put("observation", observationText)
        }
        // function_response immediately follows its function_call (no user entry
        // inserted in between).
        state.appendFunctionResponse(call.name, response)

        // The post-action screenshot, if any, follows as its own user observation
        // so the model still sees the resulting screen image.
        if (attached) {
            state.appendObservation(
                text = if (call.name == ToolRegistry.SCREENSHOT_TOOL) "(current screen)" else "(post-action screen)",
                imageBase64 = observationImage,
            )
        }

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
            if (t is kotlinx.coroutines.CancellationException) throw t
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
        // Read one pruned-tree snapshot for this evaluation and reuse it for both
        // tap-target resolution and the injection deny floor, instead of parsing
        // the full tree multiple times per tool call (and risking inconsistent
        // snapshots between the two reads).
        val service = toolContext.service()
        val nodes = if (service != null) ToolSupport.readPrunedNodes(service) else emptyList()
        val foreground = service?.getForegroundPackageName().orEmpty().trim()

        // For taps, resolve the underlying node text behind a som_id or x/y so the
        // gate's hard-keyword screening also covers coordinate/index taps, not just
        // text selectors. Otherwise a "Pay"/"支付" button tapped by id or pixel would
        // be classified NORMAL and auto-run in AUTO mode.
        val targetText = listOf(extractTargetText(call.args), resolveTapTargetText(tool, call.args, nodes))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val deny = computeDenyFloor(tool, call, service, nodes)

        // Scope GRANT_ALWAYS by package. Tools that carry no "package" arg
        // (tap/type_text/...) fall back to the current foreground package so an
        // "always allow" stays scoped to the app on screen. When even that is
        // unknown the scope stays blank, which the gate degrades to once-only.
        val explicitPackage = call.args.optString("package", "").trim()
        val packageScope = explicitPackage.ifBlank { foreground }

        return PermissionQuery(
            toolName = tool.name,
            riskClass = tool.riskClass,
            isReadOnly = tool.isReadOnly,
            targetText = targetText,
            denyFloor = deny,
            packageScope = packageScope,
        )
    }

    private fun computeDenyFloor(
        tool: PhoneTool,
        call: TurnToolCall,
        service: AgentAccessibilityService?,
        nodes: List<UiNode>,
    ): Boolean {
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
        if (!tool.isReadOnly && service != null) {
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
        return false
    }

    /**
     * For a tap by som_id or x/y, look up the targeted node in the supplied pruned
     * tree and return its visible text + content description, so hard-keyword
     * screening can see what a coordinate/index tap actually lands on. Returns ""
     * for non-tap tools, selector-only taps (already screened by
     * performClickByExactText), or when no node is found.
     */
    private fun resolveTapTargetText(
        tool: PhoneTool,
        args: JSONObject,
        nodes: List<UiNode>,
    ): String {
        if (tool.name != "tap") return ""
        if (nodes.isEmpty()) return ""

        val node = when {
            args.has("x") && args.has("y") -> {
                val x = args.optDouble("x", Double.NaN)
                val y = args.optDouble("y", Double.NaN)
                if (x.isNaN() || y.isNaN()) {
                    null
                } else {
                    // Smallest node whose bounds contain the tap point.
                    nodes
                        .filter {
                            x >= it.bounds.left && x <= it.bounds.right &&
                                y >= it.bounds.top && y <= it.bounds.bottom
                        }
                        .minByOrNull {
                            (it.bounds.right - it.bounds.left).toLong() *
                                (it.bounds.bottom - it.bounds.top)
                        }
                }
            }
            args.has("som_id") -> {
                val somId = args.optInt("som_id", -1)
                nodes.firstOrNull { it.index == somId }
            }
            else -> null
        } ?: return ""

        return listOf(node.text, node.contentDesc).filter { it.isNotBlank() }.joinToString(" ")
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

    /** Capture a fresh screen image as base64, or null when capture is unavailable. */
    private suspend fun captureObservationImage(): String? {
        val capture = toolContext.capture() ?: return null
        return capture.captureBase64()?.takeIf { it.isNotBlank() }
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
        // Rebuilt every turn so the prompt tracks live screenshot availability
        // (agent-loop.md §7: never promise screenshots the device cannot deliver).
        val screenshotsAvailable = toolContext.capture() != null
        return buildString {
            appendLine("You are an on-device Android UI automation agent.")
            appendLine("You drive a real phone by calling the provided tools one step at a time.")
            appendLine()
            if (screenshotsAvailable) {
                appendLine("Tools: take_screenshot and read_ui_tree observe the screen; tap/type_text/swipe/")
                appendLine("scroll/press_back/press_home/open_app/launch_intent act on it; wait pauses; finish")
                appendLine("ends the task; ask_user asks the user a clarifying question.")
            } else {
                appendLine("Tools: read_ui_tree observes the screen. Screen recording is NOT authorized in this")
                appendLine("session, so take_screenshot is unavailable and no screenshots will be attached —")
                appendLine("rely entirely on read_ui_tree. tap/type_text/swipe/scroll/press_back/press_home/")
                appendLine("open_app/launch_intent act on the screen; wait pauses; finish ends the task;")
                appendLine("ask_user asks the user a clarifying question.")
            }
            appendLine()
            appendLine("Targeting: prefer tapping by the bounds shown in read_ui_tree (use the center as x/y),")
            if (screenshotsAvailable) {
                appendLine("or pass som_id (the node index), or a selector matching exact visible text. Read the")
                appendLine("latest screenshot and UI nodes before each action.")
            } else {
                appendLine("or pass som_id (the node index), or a selector matching exact visible text. Read the")
                appendLine("latest UI nodes before each action.")
            }
            appendLine()
            appendLine("som_id stability: a som_id is only valid for the exact frame you just got from")
            appendLine("read_ui_tree. As soon as the screen changes (after any tap/type/scroll/navigation, or")
            appendLine("when new content loads) those ids are stale: the same som_id may now point at a")
            appendLine("different element or nothing. Always call read_ui_tree again after the screen changes")
            appendLine("and use the fresh ids; never reuse a som_id across screens.")
            appendLine()
            appendLine("Safety red lines: never attempt payment, transfer, password entry, deletion, or")
            appendLine("system authorization. high-risk steps require explicit user approval and may be denied;")
            appendLine("if a tool is denied, choose a different, safer path. Call finish with a summary when the")
            appendLine("goal is achieved or cannot be safely completed.")
        }
    }
}
