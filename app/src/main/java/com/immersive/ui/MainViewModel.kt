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
 * UI 状态和业务逻辑持有者
 * 将对话持久化、错误通知、Agent 生命周期等逻辑从 MainActivity 中隔离。
 *
 * Agent 状态通过 StateFlow 暴露，Activity 仅 collectAsState() 渲染。
 * viewModelScope 保证 Agent 在配置变更（如旋转屏幕）时不被销毁。
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.get(application)
    private val ctx: Context get() = getApplication()

    // ── 错误 Snackbar 通道 ──────────────────────────
    private val _errorFlow = MutableSharedFlow<String>()
    val errorFlow = _errorFlow.asSharedFlow()

    fun emitError(msg: String) {
        viewModelScope.launch { _errorFlow.emit(msg) }
    }

    // ── Agent 状态 StateFlows ──────────────────────────
    private val _isGuideRunning = MutableStateFlow(false)
    val isGuideRunning: StateFlow<Boolean> = _isGuideRunning.asStateFlow()

    private val _agentPhaseText = MutableStateFlow("")
    val agentPhaseText: StateFlow<String> = _agentPhaseText.asStateFlow()

    private val _statusText = MutableStateFlow("Please confirm your task goal with AI first")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _pendingDecisionRequest = MutableStateFlow<DecisionRequest?>(null)
    val pendingDecisionRequest: StateFlow<DecisionRequest?> = _pendingDecisionRequest.asStateFlow()

    // ── Agent 编排器（ViewModel 持有，跨配置变更存活）──
    private var agentOrchestrator: OpenClawOrchestrator? = null
    var pendingConfirmCallback: ((Boolean) -> Unit)? = null
        private set
    var pendingDecisionCallback: ((DecisionOption?) -> Unit)? = null
        private set

    // 回调给 Activity 层的事件（不适合 StateFlow 的一次性事件）
    private val _agentMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val agentMessages = _agentMessages.asSharedFlow()

    private val _narrationEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val narrationEvents = _narrationEvents.asSharedFlow()

    private var lastPhaseMessageLogged: String? = null
    private var lastPhaseMessageTs: Long = 0L

    fun setGuideRunning(running: Boolean) {
        _isGuideRunning.value = running
    }

    fun setStatusText(text: String) {
        _statusText.value = text
    }

    /**
     * 启动 Agent 自主模式（在 viewModelScope 中运行，跨配置变更存活）
     * 使用 applicationContext 避免 Activity 泄漏。
     */
    fun startAgent(plan: GoalChatResult) {
        val appCtx = ctx.applicationContext

        // 清理旧实例
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
    }

    // ── Room 持久化封装 ────────────────────────────
    /**
     * 从 Room 加载所有会话（带消息列表），兜底读 SharedPreferences
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
     * 保存（upsert）单个会话到 Room
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
     * 删除会话（Room + SharedPreferences 同步）
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
     * 清除所有会话（Room + SharedPreferences）
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
}
