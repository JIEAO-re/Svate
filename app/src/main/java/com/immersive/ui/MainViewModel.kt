package com.immersive.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.immersive.ui.agent.AgentCaptureService
import com.immersive.ui.agent.AgentPhase
import com.immersive.ui.agent.ChatMsg
import com.immersive.ui.agent.ChatSession
import com.immersive.ui.agent.ChatStorage
import com.immersive.ui.agent.DecisionOption
import com.immersive.ui.agent.DecisionRequest
import com.immersive.ui.agent.TaskSpec
import com.immersive.ui.agent.UserProfileStore
import com.immersive.ui.agent.loop.AgentLoop
import com.immersive.ui.agent.loop.AgentLoopEvent
import com.immersive.ui.agent.loop.LoopTurn
import com.immersive.ui.agent.loop.PermissionDecision
import com.immersive.ui.agent.loop.PermissionMode
import com.immersive.ui.agent.loop.tools.ToolSupport
import com.immersive.ui.data.AppDatabase
import com.immersive.ui.data.MessageEntity
import com.immersive.ui.data.SessionEntity
import com.immersive.ui.guide.GoalChatResult
import com.immersive.ui.overlay.AgentStopOverlayService
import com.immersive.ui.overlay.OverlayGuideService
import com.immersive.ui.guide.GuideCaptureService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holder for UI state and business logic.
 * Separates conversation persistence, error reporting, and agent lifecycle logic from MainActivity.
 *
 * Agent state is exposed through StateFlow and rendered by the Activity via collectAsState().
 * viewModelScope keeps the agent alive across configuration changes such as rotation.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val ctx: Context get() = getApplication()

    // Error Snackbar channel
    private val _errorFlow = MutableSharedFlow<String>()
    val errorFlow = _errorFlow.asSharedFlow()

    fun emitError(msg: String) {
        viewModelScope.launch { _errorFlow.emit(msg) }
    }

    // Agent state StateFlows
    private val _isGuideRunning = MutableStateFlow(false)
    val isGuideRunning: StateFlow<Boolean> = _isGuideRunning.asStateFlow()

    private val _agentPhaseText = MutableStateFlow("")
    val agentPhaseText: StateFlow<String> = _agentPhaseText.asStateFlow()

    private val _statusText = MutableStateFlow("Please confirm your task goal with AI first")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _pendingDecisionRequest = MutableStateFlow<DecisionRequest?>(null)
    val pendingDecisionRequest: StateFlow<DecisionRequest?> = _pendingDecisionRequest.asStateFlow()

    var pendingConfirmCallback: ((Boolean) -> Unit)? = null
        private set
    var pendingDecisionCallback: ((DecisionOption?) -> Unit)? = null
        private set

    // Events delivered back to the Activity layer that are not suitable for StateFlow.
    private val _agentMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val agentMessages = _agentMessages.asSharedFlow()

    private val _narrationEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val narrationEvents = _narrationEvents.asSharedFlow()

    // True while the loop is waiting for screen-recording (MediaProjection) consent.
    // Modeled as durable StateFlow state (not a one-shot event) so a recreated Activity
    // re-reads the pending flag and can still drive the consent dialog, instead of the
    // request being silently dropped during the recreation gap.
    private val _screenAccessPending = MutableStateFlow(false)
    val screenAccessPending: StateFlow<Boolean> = _screenAccessPending.asStateFlow()

    // ================================================================
    // Autonomous agent loop (claude-code-style). The ViewModel only talks
    // to the frozen AgentLoop public API and never its internals.
    // ================================================================

    /**
     * A pending permission request surfaced by the agent loop, mapped from the
     * AwaitingPermission event into a UI-friendly model the Activity renders.
     */
    data class PermissionPrompt(
        val toolCallId: String,
        val toolName: String,
        val description: String,
        val riskClass: String,
    )

    // Single AgentLoop instance owned by the ViewModel so it survives configuration
    // changes; built lazily with the application context and viewModelScope.
    private val agentLoop: AgentLoop by lazy {
        AgentLoop(getApplication<Application>().applicationContext, viewModelScope).also { loop ->
            loop.mode = _permissionMode.value
            // Drain loop events into UI state on the main scope.
            viewModelScope.launch {
                loop.events.collect { event -> onAgentLoopEvent(event) }
            }
        }
    }

    private val _agentLoopRunning = MutableStateFlow(false)
    val agentLoopRunning: StateFlow<Boolean> = _agentLoopRunning.asStateFlow()

    private val _agentLoopPhase = MutableStateFlow("")
    val agentLoopPhase: StateFlow<String> = _agentLoopPhase.asStateFlow()

    // Rolling list of narration / tool-progress lines for the chat surface.
    private val _agentLoopNarration = MutableStateFlow<List<String>>(emptyList())
    val agentLoopNarration: StateFlow<List<String>> = _agentLoopNarration.asStateFlow()

    // Tool steps executed in the current run (one finished-tool line each). Snapshotted into
    // [lastRunCommands] when the run ends, so the chat can attach a collapsible
    // "已运行 N 条命令" card to that assistant turn. Read by the Activity right after an
    // agentMessages emission (set synchronously before the emit, on the same main scope).
    private val runToolSteps = mutableListOf<String>()

    @Volatile
    var lastRunCommands: List<String> = emptyList()
        private set

    private val _pendingPermission = MutableStateFlow<PermissionPrompt?>(null)
    val pendingPermission: StateFlow<PermissionPrompt?> = _pendingPermission.asStateFlow()

    // Permission mode is persisted in SharedPreferences and mirrored into the loop.
    private val _permissionMode = MutableStateFlow(loadPermissionMode())
    val permissionMode: StateFlow<PermissionMode> = _permissionMode.asStateFlow()

    private fun loadPermissionMode(): PermissionMode {
        val raw = ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PERMISSION_MODE, PermissionMode.SAFE.name)
        return runCatching { PermissionMode.valueOf(raw ?: PermissionMode.SAFE.name) }
            .getOrDefault(PermissionMode.SAFE)
    }

    private fun appendNarration(line: String) {
        // Keep a bounded tail so the list never grows without limit.
        val next = (_agentLoopNarration.value + line).takeLast(200)
        _agentLoopNarration.value = next
    }

    private fun onAgentLoopEvent(event: AgentLoopEvent) {
        when (event) {
            is AgentLoopEvent.Narration -> {
                appendNarration(event.text)
                viewModelScope.launch { _narrationEvents.emit(event.text) }
            }
            is AgentLoopEvent.PhaseChanged -> {
                _agentLoopPhase.value = event.phase
                // The loop only reaches "acting" once it actually runs device tools,
                // so start the floating stop button then — not for a pure chat reply,
                // which would make the overlay flash for a sub-second run.
                if (event.phase == "acting") {
                    try { AgentStopOverlayService.start(ctx.applicationContext) } catch (_: Exception) {}
                }
            }
            AgentLoopEvent.RequestScreenAccess -> {
                _screenAccessPending.value = true
            }
            is AgentLoopEvent.ToolStarted -> {
                appendNarration("▶ ${event.toolName}: ${event.summary}")
            }
            is AgentLoopEvent.ToolFinished -> {
                val mark = if (event.ok) "✅" else "❌"
                appendNarration("$mark ${event.toolName}: ${event.summary}")
                // Record one line per finished tool for the post-run "已运行 N 条命令" card.
                // Skip the terminal finish tool so the count reflects real device commands.
                if (event.toolName != "finish") {
                    // Redact secrets so a tool summary (e.g. a typed string) can never
                    // re-inject an API key into the card or the next turn's history.
                    runToolSteps.add(ToolSupport.redactSecrets("$mark ${event.toolName}: ${event.summary}"))
                }
            }
            is AgentLoopEvent.AwaitingPermission -> {
                _pendingPermission.value = PermissionPrompt(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    description = event.description,
                    riskClass = event.riskClass,
                )
            }
            is AgentLoopEvent.Finished -> {
                _agentLoopRunning.value = false
                _pendingPermission.value = null
                _agentLoopPhase.value = ""
                stopLoopSideServices()
                if (event.conversational) {
                    // A plain chat reply (an answer, or a clarifying question handed
                    // back to the user): show it as a normal assistant message with
                    // no task-status decoration and no command card.
                    lastRunCommands = emptyList()
                    appendNarration(event.summary)
                    viewModelScope.launch {
                        _agentMessages.emit(event.summary)
                        _narrationEvents.emit(event.summary)
                    }
                } else {
                    // A task that touched the device: attach the executed-command list.
                    lastRunCommands = runToolSteps.toList()
                    val mark = if (event.success) "✅" else "⚠️"
                    appendNarration("$mark ${event.summary}")
                    viewModelScope.launch {
                        _agentMessages.emit("$mark ${event.summary}")
                        _narrationEvents.emit(event.summary)
                    }
                }
            }
            is AgentLoopEvent.Failed -> {
                _agentLoopRunning.value = false
                _pendingPermission.value = null
                _agentLoopPhase.value = ""
                stopLoopSideServices()
                lastRunCommands = runToolSteps.toList()
                appendNarration("❌ ${event.reason}")
                viewModelScope.launch {
                    _agentMessages.emit("❌ ${event.reason}")
                    _narrationEvents.emit(event.reason)
                }
            }
        }
    }

    /**
     * Start an autonomous agent run for the latest user message [goal], seeding prior
     * chat [history] for context. The loop decides whether to just reply or to operate
     * the phone; it acquires screen recording lazily (only when it needs to see the
     * screen), so a pure chat reply never triggers any system permission prompt.
     */
    fun startAgentLoop(
        goal: String,
        history: List<LoopTurn> = emptyList(),
        attachmentImages: List<String> = emptyList(),
        attachmentText: String = "",
    ) {
        val trimmed = goal.trim()
        // Allow an attachments-only message (e.g. "look at this photo" with no text).
        val hasAttachments = attachmentImages.isNotEmpty() || attachmentText.isNotBlank()
        if ((trimmed.isBlank() && !hasAttachments) || _agentLoopRunning.value) return
        _agentLoopNarration.value = emptyList()
        runToolSteps.clear()
        lastRunCommands = emptyList()
        _pendingPermission.value = null
        _agentLoopPhase.value = ""
        _screenAccessPending.value = false
        _agentLoopRunning.value = true
        agentLoop.mode = _permissionMode.value
        try {
            agentLoop.start(trimmed.ifBlank { "（见附件）" }, history, attachmentImages, attachmentText)
        } catch (e: Exception) {
            _agentLoopRunning.value = false
            stopLoopSideServices()
            emitError("启动失败：${e.localizedMessage ?: "unknown_error"}")
        }
    }

    /** Relay the Activity's screen-recording consent result back to the running loop. */
    fun resolveScreenAccess(granted: Boolean) {
        _screenAccessPending.value = false
        try { agentLoop.resolveScreenAccess(granted) } catch (_: Exception) {}
    }

    fun stopAgentLoop() {
        try { agentLoop.stop() } catch (_: Exception) {}
        // Snapshot whatever ran so a user-stopped task still records its command process
        // (the chat's "⏹️ 已停止" message attaches these).
        lastRunCommands = runToolSteps.toList()
        _agentLoopRunning.value = false
        _pendingPermission.value = null
        _agentLoopPhase.value = ""
        stopLoopSideServices()
    }

    /**
     * Stop the services that accompany a loop run (screen capture, floating stop
     * button). Idempotent; safe to call from any loop-teardown path. Without this,
     * a loop that finishes on its own leaves the projection and overlay running.
     */
    private fun stopLoopSideServices() {
        val appCtx = ctx.applicationContext
        try { AgentCaptureService.stop(appCtx) } catch (_: Exception) {}
        try { AgentStopOverlayService.stop(appCtx) } catch (_: Exception) {}
    }

    /** Forward the user's choice on a pending permission prompt back to the loop. */
    fun onPermissionResolved(toolCallId: String, decision: PermissionDecision) {
        _pendingPermission.value = null
        try { agentLoop.resolvePermission(toolCallId, decision) } catch (_: Exception) {}
    }

    /** Set the permission mode directly, mirror it into the live AgentLoop, and persist it. */
    fun setPermissionMode(mode: PermissionMode) {
        _permissionMode.value = mode
        agentLoop.mode = mode
        ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERMISSION_MODE, mode.name)
            .apply()
    }

    /**
     * Cycle the permission mode 安全 → 询问 → 实验 → 安全 and return the new mode so the UI
     * can react (e.g. show the experimental notice once). Kept for back-compat; the new
     * UI uses [setPermissionMode] directly via the mode picker.
     */
    fun togglePermissionMode(): PermissionMode {
        val next = when (_permissionMode.value) {
            PermissionMode.SAFE -> PermissionMode.AUTO
            PermissionMode.AUTO -> PermissionMode.EXPERIMENTAL
            PermissionMode.EXPERIMENTAL -> PermissionMode.SAFE
            PermissionMode.ASK -> PermissionMode.AUTO
        }
        setPermissionMode(next)
        return next
    }

    fun setGuideRunning(running: Boolean) {
        _isGuideRunning.value = running
    }

    fun setStatusText(text: String) {
        _statusText.value = text
    }

    fun stopGuide() {
        val appCtx = ctx.applicationContext
        try {
            GuideCaptureService.stop(appCtx)
            AgentCaptureService.stop(appCtx)
            OverlayGuideService.hideOverlay(appCtx)
            AgentStopOverlayService.stop(appCtx)
            _isGuideRunning.value = false
            _agentPhaseText.value = ""
            pendingConfirmCallback = null
            _pendingDecisionRequest.value = null
            pendingDecisionCallback = null
            // Leave the status neutral. The old "Guide stopped" string leaked from the
            // retired pipeline into the always-on agent's stop and confused users.
            _statusText.value = ""
        } catch (_: Exception) {}
    }

    fun confirmAction(confirmed: Boolean) {
        pendingConfirmCallback?.invoke(confirmed)
        pendingConfirmCallback = null
    }

    fun selectDecision(option: DecisionOption?) {
        pendingDecisionCallback?.invoke(option)
        pendingDecisionCallback = null
        _pendingDecisionRequest.value = null
    }

    fun clearPendingInteractions() {
        pendingConfirmCallback = null
        _pendingDecisionRequest.value = null
        pendingDecisionCallback = null
    }

    override fun onCleared() {
        super.onCleared()
        try { stopAgentLoop() } catch (_: Exception) {}
    }

    // Room persistence helpers
    /**
     * Load all sessions from Room with their messages, falling back to SharedPreferences.
     */
    suspend fun loadSessionsFromDb(): MutableList<ChatSession> = withContext(Dispatchers.IO) {
        try {
            val sessionEntities = db.sessionDao().allSessions()
            if (sessionEntities.isEmpty()) {
                val legacy = ChatStorage.loadSessions(ctx)
                legacy.forEach { session -> saveSessionToDb(session) }
                return@withContext legacy
            }
            sessionEntities.map { se ->
                val msgs = db.messageDao().forSession(se.id).map {
                    ChatMsg(role = it.role, content = it.content, timestamp = it.timestamp)
                }
                ChatSession(
                    id = se.id,
                    title = se.title,
                    summary = se.summary,
                    messages = msgs.toMutableList(),
                    createdAt = se.createdAt,
                    isAutoTitle = se.isAutoTitle,
                )
            }.toMutableList()
        } catch (e: Exception) {
            emitError("加载对话历史失败：${e.localizedMessage}")
            ChatStorage.loadSessions(ctx)
        }
    }

    /**
     * Save a single session into Room with upsert semantics.
     */
    fun saveSessionToDb(session: ChatSession) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.sessionDao().upsert(SessionEntity(
                    id = session.id,
                    title = session.title,
                    summary = session.summary,
                    createdAt = session.createdAt,
                    isAutoTitle = session.isAutoTitle,
                ))
                db.messageDao().deleteForSession(session.id)
                db.messageDao().insertAll(session.messages.mapIndexed { idx, m ->
                    MessageEntity(
                        sessionId = session.id,
                        role = m.role,
                        content = m.content,
                        timestamp = m.timestamp,
                        position = idx,
                    )
                })
            } catch (e: Exception) {
                emitError("保存对话失败：${e.localizedMessage}")
            }
        }
    }

    /**
     * Delete a session and keep Room plus SharedPreferences in sync.
     */
    fun deleteSessionFromDb(sessionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.sessionDao().delete(sessionId)
                db.messageDao().deleteForSession(sessionId)
            } catch (e: Exception) {
                emitError("删除对话失败：${e.localizedMessage}")
            }
        }
    }

    /**
     * Clear all sessions from both Room and SharedPreferences.
     */
    fun clearAllData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.sessionDao().deleteAll()
                db.messageDao().deleteAll()
                ChatStorage.saveSessions(ctx, emptyList())
                UserProfileStore.clearProfile(ctx)
            } catch (e: Exception) {
                emitError("清除数据失败：${e.localizedMessage}")
            }
        }
    }

    companion object {
        // Shared with MainActivity's settings store ("svate_settings").
        private const val SETTINGS_PREFS = "svate_settings"
        private const val KEY_PERMISSION_MODE = "agent_permission_mode"
    }
}
