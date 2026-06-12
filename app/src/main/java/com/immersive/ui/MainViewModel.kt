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
import com.immersive.ui.agent.flow.OpenClawOrchestrator
import com.immersive.ui.agent.loop.AgentLoop
import com.immersive.ui.agent.loop.AgentLoopEvent
import com.immersive.ui.agent.loop.PermissionDecision
import com.immersive.ui.agent.loop.PermissionMode
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

    // Agent orchestrator held by the ViewModel to survive configuration changes
    private var agentOrchestrator: OpenClawOrchestrator? = null
    var pendingConfirmCallback: ((Boolean) -> Unit)? = null
        private set
    var pendingDecisionCallback: ((DecisionOption?) -> Unit)? = null
        private set

    // Events delivered back to the Activity layer that are not suitable for StateFlow.
    private val _agentMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val agentMessages = _agentMessages.asSharedFlow()

    private val _narrationEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val narrationEvents = _narrationEvents.asSharedFlow()

    private var lastPhaseMessageLogged: String? = null
    private var lastPhaseMessageTs: Long = 0L

    // ================================================================
    // Autonomous agent loop (new claude-code-style mode; coexists with
    // the fixed OpenClawOrchestrator pipeline above). The ViewModel only
    // talks to the frozen AgentLoop public API and never its internals.
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

    private val _pendingPermission = MutableStateFlow<PermissionPrompt?>(null)
    val pendingPermission: StateFlow<PermissionPrompt?> = _pendingPermission.asStateFlow()

    // Permission mode is persisted in SharedPreferences and mirrored into the loop.
    private val _permissionMode = MutableStateFlow(loadPermissionMode())
    val permissionMode: StateFlow<PermissionMode> = _permissionMode.asStateFlow()

    private fun loadPermissionMode(): PermissionMode {
        val raw = ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PERMISSION_MODE, PermissionMode.ASK.name)
        return runCatching { PermissionMode.valueOf(raw ?: PermissionMode.ASK.name) }
            .getOrDefault(PermissionMode.ASK)
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
            }
            is AgentLoopEvent.ToolStarted -> {
                appendNarration("▶ ${event.toolName}: ${event.summary}")
            }
            is AgentLoopEvent.ToolFinished -> {
                val mark = if (event.ok) "✅" else "❌"
                appendNarration("$mark ${event.toolName}: ${event.summary}")
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
                val mark = if (event.success) "✅" else "⚠️"
                appendNarration("$mark ${event.summary}")
                viewModelScope.launch {
                    _agentMessages.emit("$mark ${event.summary}")
                    _narrationEvents.emit(event.summary)
                }
            }
            is AgentLoopEvent.Failed -> {
                _agentLoopRunning.value = false
                _pendingPermission.value = null
                _agentLoopPhase.value = ""
                appendNarration("❌ ${event.reason}")
                viewModelScope.launch {
                    _agentMessages.emit("❌ ${event.reason}")
                    _narrationEvents.emit(event.reason)
                }
            }
        }
    }

    /** Start the autonomous agent loop. Runs alongside the fixed pipeline; never both at once. */
    fun startAgentLoop(goal: String) {
        val trimmed = goal.trim()
        if (trimmed.isBlank() || _agentLoopRunning.value) return
        _agentLoopNarration.value = emptyList()
        _pendingPermission.value = null
        _agentLoopPhase.value = ""
        _agentLoopRunning.value = true
        agentLoop.mode = _permissionMode.value
        try {
            agentLoop.start(trimmed)
        } catch (e: Exception) {
            _agentLoopRunning.value = false
            emitError("启动失败：${e.localizedMessage ?: "unknown_error"}")
        }
    }

    fun stopAgentLoop() {
        try { agentLoop.stop() } catch (_: Exception) {}
        _agentLoopRunning.value = false
        _pendingPermission.value = null
        _agentLoopPhase.value = ""
    }

    /** Forward the user's choice on a pending permission prompt back to the loop. */
    fun onPermissionResolved(toolCallId: String, decision: PermissionDecision) {
        _pendingPermission.value = null
        try { agentLoop.resolvePermission(toolCallId, decision) } catch (_: Exception) {}
    }

    /**
     * Toggle the permission mode, mirror it into the live AgentLoop, and persist it.
     * Returns the new mode so the UI can react (e.g. show the AUTO notice once).
     */
    fun togglePermissionMode(): PermissionMode {
        val next = if (_permissionMode.value == PermissionMode.ASK) PermissionMode.AUTO else PermissionMode.ASK
        _permissionMode.value = next
        agentLoop.mode = next
        ctx.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PERMISSION_MODE, next.name)
            .apply()
        return next
    }

    fun setGuideRunning(running: Boolean) {
        _isGuideRunning.value = running
    }

    fun setStatusText(text: String) {
        _statusText.value = text
    }

    /**
     * Start autonomous agent mode inside viewModelScope so it survives configuration changes.
     * Uses applicationContext to avoid leaking the Activity.
     */
    fun startAgent(plan: GoalChatResult) {
        val appCtx = ctx.applicationContext

        // Clear any previous instance
        try { agentOrchestrator?.stop() } catch (_: Exception) {}
        agentOrchestrator = null
        try { GuideCaptureService.stop(appCtx) } catch (_: Exception) {}
        try { OverlayGuideService.hideOverlay(appCtx) } catch (_: Exception) {}
        try { AgentStopOverlayService.stop(appCtx) } catch (_: Exception) {}

        val orchestrator = OpenClawOrchestrator(appCtx)
        agentOrchestrator = orchestrator

        orchestrator.onPhaseChanged = { phase, message ->
            _agentPhaseText.value = message
            _statusText.value = "Autonomous mode: $message"
            if (phase != AgentPhase.WAITING_USER_DECISION) {
                _pendingDecisionRequest.value = null
                pendingDecisionCallback = null
            }
            val now = System.currentTimeMillis()
            val shouldAppend = lastPhaseMessageLogged != message || now - lastPhaseMessageTs > 1500L
            if (shouldAppend) {
                viewModelScope.launch { _agentMessages.emit("🤖 $message") }
                lastPhaseMessageLogged = message
                lastPhaseMessageTs = now
            }
        }

        orchestrator.onNarration = { text ->
            viewModelScope.launch { _narrationEvents.emit(text) }
        }

        orchestrator.onRequestConfirm = { action, callback ->
            _agentPhaseText.value = "⚠️ High-risk action requires confirmation: ${action.targetDesc}"
            viewModelScope.launch {
                _agentMessages.emit("⚠️ Confirmation required: ${action.elderlyNarration}")
            }
            pendingConfirmCallback = callback
        }

        orchestrator.onRequestDecision = { request, callback ->
            _pendingDecisionRequest.value = request
            pendingDecisionCallback = callback
            _agentPhaseText.value = "请确认：${request.question}"
            viewModelScope.launch { _agentMessages.emit("请确认：${request.question}") }
        }

        orchestrator.onReportReady = { report ->
            viewModelScope.launch {
                _agentMessages.emit(report)
                _narrationEvents.emit("Report is ready and posted in chat.")
            }
        }

        orchestrator.onCompleted = { message ->
            _isGuideRunning.value = false
            _statusText.value = "✅ Task completed"
            viewModelScope.launch { _agentMessages.emit("✅ $message") }
            viewModelScope.launch { _narrationEvents.emit(message) }
            cleanupAgent(appCtx)
        }

        orchestrator.onFailed = { message ->
            _isGuideRunning.value = false
            _statusText.value = "❌ Task failed"
            viewModelScope.launch { _agentMessages.emit("❌ $message") }
            viewModelScope.launch { _narrationEvents.emit(message) }
            cleanupAgent(appCtx)
        }

        // Mark running before start so a fast completion/failure callback is never overwritten.
        _isGuideRunning.value = true
        _statusText.value = "Agent mode guide is running"
        try {
            orchestrator.start(
                goal = plan.inferredGoal,
                targetAppName = plan.targetAppName,
                taskSpec = TaskSpec.fromRaw(
                    taskMode = plan.taskMode,
                    searchQuery = plan.searchQuery,
                    researchDepth = plan.researchDepth,
                    homeworkPolicy = plan.homeworkPolicy,
                    askOnUncertain = plan.askOnUncertain,
                ),
            )
        } catch (e: Exception) {
            _isGuideRunning.value = false
            _statusText.value = "❌ Failed to start agent"
            emitError("启动失败：${e.localizedMessage ?: "unknown_error"}")
            try { orchestrator.stop() } catch (_: Exception) {}
            cleanupAgent(appCtx)
            return
        }
        try { AgentStopOverlayService.start(appCtx) } catch (_: Exception) {}
    }

    fun stopGuide() {
        val appCtx = ctx.applicationContext
        try {
            agentOrchestrator?.stop()
            agentOrchestrator = null
            GuideCaptureService.stop(appCtx)
            AgentCaptureService.stop(appCtx)
            OverlayGuideService.hideOverlay(appCtx)
            AgentStopOverlayService.stop(appCtx)
            _isGuideRunning.value = false
            _agentPhaseText.value = ""
            pendingConfirmCallback = null
            _pendingDecisionRequest.value = null
            pendingDecisionCallback = null
            _statusText.value = "Guide stopped"
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

    private fun cleanupAgent(appCtx: Context) {
        agentOrchestrator = null
        pendingConfirmCallback = null
        _pendingDecisionRequest.value = null
        pendingDecisionCallback = null
        try { AgentCaptureService.stop(appCtx) } catch (_: Exception) {}
    }

    override fun onCleared() {
        super.onCleared()
        try { agentOrchestrator?.stop() } catch (_: Exception) {}
        agentOrchestrator = null
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
