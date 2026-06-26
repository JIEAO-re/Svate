package com.immersive.ui.agent.loop

import android.content.Context
import android.util.Log
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.IntentGuard
import com.immersive.ui.agent.IntentSpec
import com.immersive.ui.agent.UiNode
import com.immersive.ui.agent.shared.VisualInjectionGuard
import com.immersive.ui.agent.loop.tools.FileToolSupport
import com.immersive.ui.agent.loop.tools.ToolSupport
import com.immersive.ui.agent.shizuku.ShizukuManager
import com.immersive.ui.agent.shizuku.ShizukuScreencap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Permission mode, toggled live from the UI.
 * - SAFE: dangerous (HIGH-risk) actions are forbidden outright (hard-denied). Normal
 *   writes still ask; SAFE/LOW/read-only auto-run. The strictest mode (盾牌).
 * - ASK: every write asks first (SAFE/LOW/read-only still auto-run).
 * - AUTO: writes auto-run, but HIGH-risk and the on-device deny floor still stop to confirm.
 * - EXPERIMENTAL: nothing is gated — every action, even HIGH-risk and deny-floor ones,
 *   runs automatically with no confirmation. Powerful and dangerous; opt-in from the UI.
 */
enum class PermissionMode { SAFE, ASK, AUTO, EXPERIMENTAL }

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
    data class Finished(
        val success: Boolean,
        val summary: String,
        // True when the run ended by simply replying in chat (an answer or a
        // clarifying question) without performing any device action, so the UI can
        // show it as a plain assistant message rather than a "task done" result.
        val conversational: Boolean = false,
    ) : AgentLoopEvent
    data class Failed(val reason: String) : AgentLoopEvent

    /**
     * The model asked to see the screen as an image but screen recording is not yet
     * authorized. The UI obtains MediaProjection consent and then calls
     * [AgentLoop.resolveScreenAccess]. Declining degrades the run to UI-tree-only.
     */
    object RequestScreenAccess : AgentLoopEvent
}

/** One prior conversation turn used to seed loop context. [role] is "user" or "model". */
data class LoopTurn(val role: String, val text: String)

/**
 * Packages known to mark their windows FLAG_SECURE, so MediaProjection screenshots come
 * back blank/blocked. For these the loop never requests screen-record consent (which would
 * only pull the user out of the app); it reads them through the accessibility tree instead.
 */
private val SECURE_SCREENSHOT_PACKAGES = setOf(
    "com.tencent.mm",               // WeChat 微信
    "com.eg.android.AlipayGphone",  // Alipay 支付宝
    "com.unionpay",                 // 云闪付
)

/** logcat tag for the on-device run trace (tool calls, narration, secret-leak warnings). */
private const val TAG = "SvateLoop"

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

    /** In-flight screen-access (MediaProjection) request, awaited by the loop. */
    @Volatile
    private var screenAccessDeferred: CompletableDeferred<Boolean>? = null

    /** Once the user declines screen sharing in a run, stop re-prompting for it. */
    @Volatile
    private var screenAccessDenied: Boolean = false

    /** Whether the run touched the device with any tool (vs. a pure chat reply). */
    @Volatile
    private var usedAnyTool: Boolean = false

    // Refreshed on every start so per-task state (allow-rules, conversation,
    // budgets, session id) never leaks across tasks.
    @Volatile
    private var sessionId: String = "loop_${System.currentTimeMillis()}"

    @Volatile
    private var loopJob: Job? = null

    /**
     * Start a run for the latest user message [goal], seeding prior chat [history]
     * so follow-ups keep context. A second call is ignored while running.
     */
    fun start(
        goal: String,
        history: List<LoopTurn> = emptyList(),
        attachmentImages: List<String> = emptyList(),
        attachmentText: String = "",
    ) {
        if (loopJob?.isActive == true) return

        // Isolate this run from any previous one: clear conversation/budgets,
        // drop session allow-rules (so "always allow" never crosses runs), reset
        // any pending prompts, and mint a fresh session id.
        state.reset()
        gate.clearRules()
        pendingPermissions.clear()
        screenAccessDeferred = null
        screenAccessDenied = false
        usedAnyTool = false
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
                runLoop(goal, history, attachmentImages, attachmentText)
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
        // A loop awaiting a screen-access grant must not hang after a stop.
        screenAccessDeferred?.complete(false)
        screenAccessDeferred = null
    }

    /** Resolve a previously emitted AwaitingPermission. No-op for unknown ids. */
    fun resolvePermission(toolCallId: String, decision: PermissionDecision) {
        pendingPermissions.remove(toolCallId)?.complete(decision)
    }

    /**
     * Resolve a [AgentLoopEvent.RequestScreenAccess]: true once MediaProjection
     * consent was granted (the capture service is starting), false if declined.
     */
    fun resolveScreenAccess(granted: Boolean) {
        screenAccessDeferred?.complete(granted)
    }

    // ===== Core loop =====

    private suspend fun runLoop(
        goal: String,
        history: List<LoopTurn>,
        attachmentImages: List<String>,
        attachmentText: String,
    ) {
        emit(AgentLoopEvent.PhaseChanged("starting"))

        // Seed prior chat so a follow-up message keeps its context. Skip any leading
        // model turns (e.g. the canned greeting) so the conversation starts on a user
        // turn — Gemini rejects a history that begins with a model role.
        var seenUser = false
        for (turn in history) {
            val text = turn.text.trim()
            if (text.isEmpty()) continue
            if (turn.role == "model") {
                if (!seenUser) continue
                state.appendModelText(text)
            } else {
                seenUser = true
                state.appendUserText(text)
            }
        }

        // Build the latest user message: goal, plus any extracted document text, plus a
        // screen observation only when accessibility is on (a pure chat turn must not be
        // polluted with "accessibility service unavailable"). Uploaded/rendered images
        // ride along on the same user turn so the model sees them this run.
        val hasAccessibility = toolContext.service() != null
        val userText = buildString {
            append(goal)
            if (attachmentText.isNotBlank()) {
                append("\n\n附件内容：\n").append(attachmentText)
            }
            if (hasAccessibility) {
                append('\n').append(buildObservationText())
            }
        }
        if (attachmentImages.isNotEmpty()) {
            state.appendUserAttachments(userText, attachmentImages)
        } else {
            state.appendUserText(userText)
        }
        if (hasAccessibility) {
            attachObservationImage()
        }

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
                Log.i(TAG, "model: ${ToolSupport.redactSecrets(narration.take(300))}")
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
                val finalText = response.text?.trim().orEmpty()
                if (finalText.isEmpty()) {
                    // The model produced neither a tool call nor any visible answer — e.g.
                    // it only emitted <think> reasoning (now stripped). Ending here is what
                    // made app tasks "interrupt for no reason" mid-flow. Instead, nudge it
                    // to either act or answer, bounded by the failure budget so a model that
                    // keeps stalling still terminates.
                    state.appendUserText(
                        "You returned no tool call and no answer. If the task is not finished, " +
                            "call the next tool now (read_ui_tree first if you need the current " +
                            "screen). If it is finished, reply with a short final answer, or call " +
                            "finish.",
                    )
                    Log.w(TAG, "empty model turn (no tool call, no answer); nudging to continue")
                    val terminal = checkBudgetAfterFailure("empty model turn")
                    if (terminal) return
                    continue
                }
                // Genuine completion: the model is done. If it never touched the
                // device, this is a plain chat answer — mark it conversational so
                // the UI renders it as a normal message, not a "task done" result.
                emit(
                    AgentLoopEvent.Finished(
                        success = true,
                        summary = finalText,
                        conversational = !usedAnyTool,
                    ),
                )
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
        // Run trace (secrets redacted): lets `adb logcat -s SvateLoop` show exactly
        // what the model did each step — the tool and its (capped) arguments.
        Log.i(TAG, "tool_call ${ToolSupport.redactSecrets(summarizeArgs(call))}")

        // ask_user hands the turn back to the user as a chat message and ends the
        // run; their typed reply starts the next run with this question in history.
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
        // A *write* tool means this run actually changed the device, so a later
        // text-only completion is a task summary, not pure chat. Read-only tools
        // (read_ui_tree / take_screenshot) used only to answer a question keep the
        // reply conversational.
        if (!tool.isReadOnly && call.name != ToolRegistry.FINISH_TOOL) usedAnyTool = true

        // take_screenshot needs a capture source. Privileged screencap (Shizuku) is the
        // universal one — it bypasses FLAG_SECURE and needs no consent — so when it is
        // available we skip MediaProjection entirely (captureObservationImage uses it).
        // Otherwise acquire MediaProjection lazily the first time the model wants the screen.
        if (call.name == ToolRegistry.SCREENSHOT_TOOL &&
            toolContext.capture()?.isProjectionActive() != true &&
            !ShizukuScreencap.isAvailable()
        ) {
            // No privileged capture: secure apps blank out under MediaProjection, so asking
            // for screen-record consent there only yanks the user out of the app for a frame
            // that would come back blocked anyway. Refuse locally and steer to read_ui_tree.
            if (isSecureForegroundApp()) {
                val msg = "This app marks its window secure, so screenshots come back " +
                    "blocked — this is Android privacy, not an error. Screen capture is " +
                    "unavailable here; use read_ui_tree to read and operate this app."
                state.appendFunctionResponse(call.name, errorResponse(msg))
                emit(AgentLoopEvent.ToolFinished(call.name, ok = false, summary = "secure app: use read_ui_tree"))
                return checkBudgetAfterFailure("secure app screenshot blocked")
            }
            ensureScreenAccess()
        }

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
        // The model asked to see the screen but the user declined sharing it. Make
        // that explicit so the model stops retrying and continues from read_ui_tree.
        if (call.name == ToolRegistry.SCREENSHOT_TOOL && !result.ok && screenAccessDenied) {
            result = ToolResult(
                ok = false,
                text = "The user declined to share the screen, so screenshots are unavailable. Rely on read_ui_tree to read the screen.",
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

    /**
     * ask_user: hand the turn back to the user as a normal chat message and end the
     * run. The user types their reply in the chat box, which starts a fresh run with
     * this question (and their answer) seeded into the conversation history — a more
     * natural flow than blocking the loop on an answerless acknowledge/decline.
     */
    private suspend fun handleAskUser(call: TurnToolCall, tool: PhoneTool): Boolean {
        val result = try {
            tool.execute(call.args, toolContext)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            ToolResult(ok = false, text = "ask_user failed: ${t.message}")
        }
        val question = result.text
        state.appendFunctionResponse(call.name, successResponse("asked the user; awaiting their reply"))
        emit(AgentLoopEvent.ToolFinished(call.name, ok = true, summary = question))
        emit(AgentLoopEvent.Finished(success = true, summary = question, conversational = true))
        return true
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
        // An app on screen that yields an EMPTY tree is blocking accessibility (WeChat
        // and some secure/payment apps do this). Say so explicitly so the model stops
        // retrying taps it cannot possibly land and hands back to the user fast, instead
        // of flailing for many turns (it cannot see or tap anything inside such an app,
        // and screenshots there are blocked too).
        if (nodes.isEmpty() && foreground.isNotBlank()) {
            Log.w(TAG, "empty accessibility tree for foreground=$foreground (app blocks accessibility)")
            val hint = if (ShizukuScreencap.isAvailable()) {
                "This app exposes NO accessibility content (empty UI tree) — WeChat and some secure " +
                    "apps do this. But you CAN still see it: call take_screenshot (privileged capture " +
                    "works here even though MediaProjection is blocked) and operate by tapping the pixel " +
                    "coordinates you read off the image, re-screenshotting after each tap. Do not rely " +
                    "on read_ui_tree for this app."
            } else {
                "This app exposes NO accessibility content (the UI tree is empty) and screenshots are " +
                    "blocked too. You cannot read or tap inside it on this device. Do NOT keep retrying; " +
                    "tell the user and ask them to do the in-app step themselves (call ask_user)."
            }
            return "Observation:\n" + header + hint
        }
        val rendered = ToolSupport.renderNodes(nodes)
        // If a secret was on screen and got redacted, log which app showed it — that
        // pinpoints the leak source (e.g. Svate's own settings, or a search box).
        if (rendered.contains("‹redacted›")) {
            Log.w(TAG, "redacted a secret-like token from on-screen observation (foreground=$foreground)")
        }
        return "Observation:\n" + header + rendered
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
        // Privileged screencap (Shizuku) bypasses FLAG_SECURE and needs no consent dialog,
        // so it's the universal capture path when available — it sees apps that block both
        // MediaProjection and the accessibility tree (e.g. WeChat). Fall back to projection.
        if (ShizukuScreencap.isAvailable()) {
            val shot = ShizukuScreencap.captureBase64()
            if (!shot.isNullOrBlank()) return shot
        }
        val capture = toolContext.capture() ?: return null
        return capture.captureBase64()?.takeIf { it.isNotBlank() }
    }

    /** True when the app currently on screen is a known FLAG_SECURE app (screenshots blocked). */
    private fun isSecureForegroundApp(): Boolean {
        val pkg = toolContext.service()?.getForegroundPackageName().orEmpty().trim()
        return pkg.isNotEmpty() && pkg in SECURE_SCREENSHOT_PACKAGES
    }

    /**
     * Ensure screen recording is active, asking the UI for MediaProjection consent
     * once if needed. Returns true when capture is (now) live. A decline is sticky
     * for the rest of the run so the model is not re-prompted every screenshot.
     */
    private suspend fun ensureScreenAccess(): Boolean {
        if (toolContext.capture()?.isProjectionActive() == true) return true
        if (screenAccessDenied) return false

        val deferred = CompletableDeferred<Boolean>()
        screenAccessDeferred = deferred
        emit(AgentLoopEvent.RequestScreenAccess)
        // Bound the wait: if the consent request is never answered (e.g. the event is
        // dropped while the Activity is being recreated, or the dialog is ignored),
        // give up gracefully and continue UI-tree-only instead of hanging the run.
        // Distinguish an explicit decline (false) — sticky for the rest of the run —
        // from a mere timeout (null): on timeout, leave screenAccessDenied unset so a
        // projection that binds later can still self-heal on the next screenshot.
        val outcome: Boolean? = try {
            // 30s is ample for a consent tap; the old 90s could park the user on Svate's
            // consent surface (away from their task) for a full minute on a dropped event.
            kotlinx.coroutines.withTimeoutOrNull(30_000L) { deferred.await() }
        } finally {
            screenAccessDeferred = null
        }
        if (outcome == false) {
            screenAccessDenied = true
            return false
        }
        if (outcome == null) {
            return false
        }
        // The capture service binds the projection asynchronously after consent;
        // wait (bounded) for it to come up before the next frame grab.
        val deadline = System.currentTimeMillis() + 8000
        while (toolContext.capture()?.isProjectionActive() != true &&
            System.currentTimeMillis() < deadline &&
            currentCoroutineContext().isActive
        ) {
            delay(100)
        }
        return toolContext.capture()?.isProjectionActive() == true
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
        if (desc.isNotEmpty()) return "${call.name}: ${capArg(desc)}"
        val keys = call.args.keys().asSequence().toList()
        if (keys.isEmpty()) return call.name
        val rendered = keys.joinToString(", ") { key -> "$key=${capArg(call.args.opt(key)?.toString().orEmpty())}" }
        return "${call.name}($rendered)"
    }

    /** Bound a single arg value so a long payload (e.g. write_file content) does not
     *  bloat a permission prompt or the progress strip. */
    private fun capArg(value: String, max: Int = 120): String =
        if (value.length <= max) value else value.take(max) + "…"

    private fun riskLabel(risk: RiskClass): String = when (risk) {
        RiskClass.SAFE -> "safe"
        RiskClass.LOW -> "low"
        RiskClass.NORMAL -> "normal"
        RiskClass.HIGH -> "high"
    }

    private fun buildSystemInstruction(): String {
        // Rebuilt every turn so the prompt tracks live capability (agent-loop.md §7).
        // Three independent capabilities: chat (always), files (All-files-access), and
        // screen control (accessibility + optional screenshots). Each is described only
        // when actually usable, so the model never promises what the device cannot do.
        val accessibilityOn = toolContext.service() != null
        val shizukuReady = ShizukuManager.isAvailable() && ShizukuManager.hasPermission()
        // Privileged screencap (Shizuku) is always-available capture that bypasses
        // FLAG_SECURE, so screenshots work even without an active MediaProjection.
        val screenshotsAvailable = toolContext.capture()?.isProjectionActive() == true || shizukuReady
        val fileAccessOn = FileToolSupport.hasAllFilesAccess()
        return buildString {
            appendLine("You are Svate, a helpful, general-purpose phone assistant chatting with the user.")
            appendLine("Reply in the user's language. You can do two kinds of things:")
            appendLine()
            appendLine("1) ANSWER DIRECTLY. For questions, explanations, advice, calculations, writing, or")
            appendLine("   casual conversation — anything that does not require the device — just reply in")
            appendLine("   plain text. Do NOT call any tool. This is the common case.")
            appendLine()
            appendLine("2) USE THE DEVICE. When the user wants you to actually do something — read or manage")
            appendLine("   their files, operate an app, change a setting, look something up in an app, check")
            appendLine("   device status — carry it out with the tools, then call finish with a short summary.")
            appendLine()
            appendLine("Choose the right mode per message. Never act just for the sake of acting. If a request")
            appendLine("is ambiguous, call ask_user with ONE short clarifying question instead of guessing; that")
            appendLine("hands the turn back to the user and they reply in chat.")
            appendLine()
            // --- Files: real filesystem access, independent of the screen. ---
            if (fileAccessOn) {
                appendLine("Files: you can directly manage the phone's shared storage WITHOUT opening any app —")
                appendLine("list_files, read_file, write_file, move_file, delete_file. Paths are relative to the")
                appendLine("storage root (\"/\"), e.g. Download, DCIM/Camera, Pictures, Documents, or absolute.")
                appendLine("Use these directly for any file request; do not open a file-manager app for it.")
            } else {
                appendLine("Files: direct file access is OFF (the \"All files access\" permission is not granted).")
                appendLine("To read or manage files, tell the user to enable it in Svate 设置 → 授予文件访问权限")
                appendLine("and retry; only as a fallback operate a file-manager app through the screen.")
            }
            appendLine()
            // --- Screen: needs the accessibility service. ---
            if (accessibilityOn) {
                appendLine("Screen: operate apps step by step.")
                appendLine("- read_ui_tree returns the current screen's interactive elements (text, bounds, ids).")
                appendLine("  It is free and always available — read it before acting and after the screen changes.")
                if (screenshotsAvailable) {
                    appendLine("- take_screenshot returns the screen as an image; use it to see visual content.")
                } else {
                    appendLine("- You cannot see the screen as an image yet. Only if a task truly needs visual")
                    appendLine("  content you cannot get from read_ui_tree, call take_screenshot — the user is asked")
                    appendLine("  once to share their screen. Otherwise rely on read_ui_tree.")
                }
                appendLine("- Act with: tap, type_text, swipe, scroll, press_back, press_home, open_app,")
                appendLine("  launch_intent. wait pauses briefly.")
                appendLine("- Drive ALL on-screen interaction with these accessibility tools ONLY. Even if the")
                appendLine("  shell/Shizuku tool is available, do NOT use it to operate app UI: no `input tap`,")
                appendLine("  `input text`, `input swipe`, `input keyevent`; no `am start`/deep-links to jump")
                appendLine("  between app screens; no `cmd`/`dumpsys`/`ime` to inspect the UI. To see the screen")
                appendLine("  use read_ui_tree; to tap use the tap tool (by bounds center x/y, som_id, or exact")
                appendLine("  text selector); to type use type_text. The accessibility tools are reliable and")
                appendLine("  focus the right field; shell coordinate-taps are guesswork and silently miss.")
                appendLine("  Reserve shell for system administration only (force-stop, settings, uninstall," )
                appendLine("  package/permission management) — never as a substitute for normal app navigation.")
                if (shizukuReady) {
                    val (sw, sh) = toolContext.service()?.getScreenSize() ?: (0 to 0)
                    appendLine("- Secure / accessibility-blocked apps (WeChat/微信, banking, payment): read_ui_tree")
                    appendLine("  often returns NOTHING for them (their content is not in the accessibility tree),")
                    appendLine("  and MediaProjection is blocked. But you CAN still see them — take_screenshot uses")
                    appendLine("  privileged capture that works even on these apps. So when read_ui_tree is empty,")
                    appendLine("  take_screenshot and operate by TAPPING PIXEL COORDINATES you read off the image:")
                    appendLine("  the screenshot is the full screen at ${sw}×${sh} pixels, so pass tap x/y in that")
                    appendLine("  pixel range, then re-screenshot after each tap to see the result. To enter text,")
                    appendLine("  tap the input field first, then use type_text normally — it automatically falls")
                    appendLine("  back to a privileged keyboard for these apps, so Chinese goes in even though the")
                    appendLine("  field is not in the accessibility tree. After typing, tap the send button.")
                } else {
                    appendLine("- Secure apps: some apps (WeChat/微信, banking, payment) mark their windows secure,")
                    appendLine("  so screenshots there come back blank/blocked — this is Android privacy, not an error.")
                    appendLine("  For these apps do NOT call take_screenshot; rely entirely on read_ui_tree, which still")
                    appendLine("  reads the screen. You can read and operate them normally through the UI tree.")
                }
                appendLine()
                appendLine("Targeting: prefer tapping by the bounds from read_ui_tree (use the center as x/y), or")
                appendLine("pass som_id (the node index), or a selector matching exact visible text. A som_id is")
                appendLine("only valid for the read_ui_tree frame you just got: after any tap/type/scroll/")
                appendLine("navigation the ids are stale, so call read_ui_tree again and use the fresh ids.")
                appendLine()
                appendLine("Typing text: ALWAYS enter text with the type_text tool. It inserts the EXACT string")
                appendLine("you pass straight into the focused field and handles Chinese correctly. Therefore:")
                appendLine("- Pass the plain final text in the user's language — Chinese characters directly")
                appendLine("  (我想你了). NEVER type pinyin/romanization (\"woxiangnile\"), and NEVER URL-encode or")
                appendLine("  percent-encode it (do NOT send \"%E6%88%91...\"); type the real characters.")
                appendLine("- Do NOT type via the shell tool (shell `input text` cannot enter Chinese/spaces and")
                appendLine("  mangles the text). Use type_text for every text field.")
                appendLine("- Put ONLY what that field needs: a search box gets the search term, a message box")
                appendLine("  gets the message text.")
                appendLine()
                appendLine("Sending a message in a chat app (e.g. WeChat 微信): do it ONE step at a time, calling")
                appendLine("read_ui_tree between steps. 1) open the app; 2) reach the target conversation — if you")
                appendLine("search, type the contact name in the search box, then TAP the matching result row to")
                appendLine("open the chat (do not keep typing into the search box); 3) tap the message input box to")
                appendLine("focus it; 4) type ONLY the message text with type_text; 5) you MUST then tap the send")
                appendLine("button — typing alone does NOT send the message, so the task is not done until you")
                appendLine("have tapped send and confirmed it. Never combine the recipient name and the message")
                appendLine("into one field, and never type anything you were not explicitly asked to send.")
            } else {
                val alsoFiles = if (fileAccessOn) " and manage files" else ""
                appendLine("Screen: control is OFF (Svate's accessibility service is not enabled), so you cannot")
                appendLine("read or tap the screen or open apps yet. You can still chat$alsoFiles. If the user")
                appendLine("wants you to operate apps, tell them to enable Svate's accessibility service in settings.")
            }
            appendLine()
            // --- Privileged (Shizuku): true device-admin power. ---
            if (shizukuReady) {
                appendLine("Privileged (Shizuku): you have ADB-level control of the device. The shell tool runs")
                appendLine("any command (pm, am, settings, cmd, dumpsys, svc, input, wm). Typed shortcuts:")
                appendLine("force_stop_app, uninstall_package, grant_permission, revoke_permission, set_setting.")
                appendLine("Use these for real administration (force-stop a frozen app, uninstall an app, grant a")
                appendLine("permission, change a protected setting, read system info via dumpsys). They are very")
                appendLine("powerful and can break the device — prefer the typed shortcuts over raw shell, be precise,")
                appendLine("and only use them when the task genuinely needs privilege.")
                appendLine("IMPORTANT: shell is for system administration, NOT for operating app UI. To tap, type,")
                appendLine("scroll, open an app, or read what is on screen, use the accessibility tools (tap,")
                appendLine("type_text, swipe, scroll, open_app, read_ui_tree) — never `input tap/text/swipe`,")
                appendLine("`am start` deep-links, or `dumpsys`/`cmd` to navigate or inspect an app's screen.")
            } else {
                appendLine("Privileged control (Shizuku) is not active right now. If the user asks for device-admin")
                appendLine("actions (force-stop, silent uninstall, grant/revoke permissions, change protected settings,")
                appendLine("run pm/am/settings), tell them to install Shizuku and activate it via wireless debugging,")
                appendLine("then grant Svate access — after that these tools work.")
            }
            appendLine()
            appendLine("Safety red lines: never attempt payment, money transfer, password entry, or system")
            appendLine("authorization on your own, and never delete the user's files or data without their")
            appendLine("explicit go-ahead. High-risk steps require approval and may be denied; if denied, find a")
            appendLine("safer path or stop and explain.")
        }
    }
}
