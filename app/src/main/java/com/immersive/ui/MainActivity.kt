package com.immersive.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.immersive.ui.agent.ChatMsg
import com.immersive.ui.agent.ChatSession
import com.immersive.ui.agent.ChatStorage
import com.immersive.ui.agent.UserProfileStore
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.immersive.ui.agent.AgentEventBus
import androidx.core.content.ContextCompat
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.AgentCaptureService
import com.immersive.ui.agent.DecisionOption
import com.immersive.ui.agent.DecisionRequest
import com.immersive.ui.agent.flow.OpenClawOrchestrator
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.immersive.ui.agent.AgentPhase
import com.immersive.ui.agent.TaskSpec
import com.immersive.ui.guide.AppCandidate
import com.immersive.ui.guide.GoalChatResult
import com.immersive.ui.guide.GuideAiEngines
import com.immersive.ui.guide.GuideCaptureService
import com.immersive.ui.guide.InstalledAppScanner
import com.immersive.ui.guide.SimpleChatMessage
import com.immersive.ui.overlay.AgentStopOverlayService
import com.immersive.ui.overlay.OverlayGuideService
import com.immersive.ui.ui.theme.UINavTheme
import java.util.Locale
import java.util.concurrent.Executors

private data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

fun Modifier.bouncyClickable(
    enabled: Boolean = true,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "bouncy_scale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val messages = mutableStateListOf<UiMessage>()

    private var inputText by mutableStateOf("")
    private var isSending by mutableStateOf(false)
    private var isGuideRunning by mutableStateOf(false)
    private var statusText by mutableStateOf("Please confirm your task goal with AI first")
    private var readyPlan by mutableStateOf<GoalChatResult?>(null)
    private var candidateApps = mutableStateListOf<AppCandidate>()
    private var isTtsEnabled by mutableStateOf(false) // 默认静音
    private var isTtsReady by mutableStateOf(false)

    // Agent 代理模式 — 状态已迁移至 MainViewModel，此处保留模式开关
    private var isAgentMode by mutableStateOf(true) // 默认代理模式
    private var pendingPlan: GoalChatResult? = null
    private var pendingSpeechText: String? = null

    // 瀵硅瘽浼氳瘽绠＄悊
    private var chatSessions = mutableStateListOf<ChatSession>()
    private var currentSessionId by mutableStateOf("")
    private var showEditTitleDialog by mutableStateOf(false)
    private var editingSessionId by mutableStateOf("")
    private var editTitleText by mutableStateOf("")
    private var showSettingsDialog by mutableStateOf(false)
    private var showClearConfirm by mutableStateOf(false)
    private var isTyping by mutableStateOf(false)
    private var isStoppingGuide = false

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var speechLauncher: ActivityResultLauncher<Intent>
    private lateinit var audioPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var textToSpeech: TextToSpeech? = null

    /** ViewModel（Room、errorFlow 中心） */
    private val mainViewModel: MainViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        textToSpeech = TextToSpeech(this, this)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)

        // 扫描已安装应用列表并注入 AI 引擎
        val apps = InstalledAppScanner.getInstalledApps(this)
        GuideAiEngines.setInstalledApps(apps)

        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val plan = pendingPlan
            if (result.resultCode == Activity.RESULT_OK && result.data != null && plan != null) {
                if (isAgentMode) {
                    AgentCaptureService.start(this, result.resultCode, result.data!!)
                    startAgent(plan)
                } else {
                    GuideCaptureService.start(
                        context = this,
                        resultCode = result.resultCode,
                        resultData = result.data!!,
                        targetAppName = plan.targetAppName,
                        inferredGoal = plan.inferredGoal,
                    )
                }
                isGuideRunning = true
                val modeLabel = if (isAgentMode) "Agent mode" else "Assist mode"
                statusText = "$modeLabel guide is running"
                Toast.makeText(this, "Guide started. Switch to the target app to continue.", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Screen capture permission is required to start.", Toast.LENGTH_SHORT).show()
            }
            pendingPlan = null
        }

        speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val text = result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (text.isNotBlank()) {
                    inputText = text
                }
            }
        }

        audioPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                launchVoiceInput()
            } else {
                Toast.makeText(this, "Microphone permission is required for voice input.", Toast.LENGTH_SHORT).show()
            }
        }

        notificationPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

        // 加载历史对话
        chatSessions.addAll(ChatStorage.loadSessions(this))
        if (chatSessions.isEmpty()) {
            startNewSession()
        } else {
            // 恢复最近会话
            switchSession(chatSessions.first().id)
        }

        observeAgentViewModelEvents()
        observeAgentStopRequests()

        setContent {
            UINavTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

                // 监听错误事件并显示 Snackbar
                LaunchedEffect(viewModel) {
                    viewModel.errorFlow.collect { msg ->
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.width(280.dp),
                            drawerContainerColor = Color.White.copy(alpha = 0.85f),
                        ) {
                            DrawerContent(
                                sessions = chatSessions.toList(),
                                currentSessionId = currentSessionId,
                                onSessionClick = { session ->
                                    switchSession(session.id)
                                    scope.launch { drawerState.close() }
                                },
                                onNewSession = {
                                    startNewSession()
                                    scope.launch { drawerState.close() }
                                },
                                onEditTitle = { session ->
                                    editingSessionId = session.id
                                    editTitleText = session.title
                                    showEditTitleDialog = true
                                },
                                onDeleteSession = { session ->
                                    deleteSession(session.id)
                                },
                                onClose = {
                                    scope.launch { drawerState.close() }
                                },
                            )
                        }
                    },
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                        Box(modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFE8F1FC), Color(0xFFF1F5E8))
                            )
                        )) {
                            Scaffold(
                                modifier = Modifier.fillMaxSize(),
                                containerColor = Color.Transparent,
                                snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
                            ) { innerPadding ->
                            GuideScreen(
                                modifier = Modifier.padding(innerPadding),
                                messages = messages.toList(),
                                inputText = inputText,
                                isSending = isSending,
                                isGuideRunning = viewModel.isGuideRunning.collectAsState().value,
                                statusText = viewModel.statusText.collectAsState().value,
                                readyPlan = readyPlan,
                                candidateApps = candidateApps.toList(),
                                isTtsEnabled = isTtsEnabled,
                                isTtsReady = isTtsReady,
                                isAgentMode = isAgentMode,
                                agentPhaseText = viewModel.agentPhaseText.collectAsState().value,
                                pendingDecisionRequest = viewModel.pendingDecisionRequest.collectAsState().value,
                                onInputChange = { inputText = it },
                                onSend = { sendCurrentMessage() },
                                onVoice = { requestVoiceInput() },
                                onToggleTts = { toggleTts() },
                                onToggleAgentMode = { isAgentMode = !isAgentMode },
                                onStartGuide = { startGuide(it) },
                                onStopGuide = { stopGuide() },
                                onCandidateSelect = { selectCandidate(it) },
                                onConfirmAction = { confirmed ->
                                    viewModel.confirmAction(confirmed)
                                },
                                onDecisionSelect = { selected ->
                                    viewModel.selectDecision(selected)
                                },
                                onCopyText = { text ->
                                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("agent_report", text))
                                    Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                onOpenDrawer = {
                                    scope.launch { drawerState.open() }
                                },
                                onOpenSettings = {
                                    showSettingsDialog = true
                                },
                                isTyping = isTyping,
                                onSuggestionClick = { text ->
                                    inputText = text
                                    sendCurrentMessage()
                                },
                            )
                        }
                    }
                }
            }

                if (showEditTitleDialog) {
                    AlertDialog(
                        onDismissRequest = { showEditTitleDialog = false },
                        title = { Text("编辑标题") },
                        text = {
                            OutlinedTextField(
                                value = editTitleText,
                                onValueChange = { editTitleText = it },
                                label = { Text("标题") },
                                singleLine = true,
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                updateSessionTitle(editingSessionId, editTitleText)
                                showEditTitleDialog = false
                            }) {
                                Text("保存")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showEditTitleDialog = false }) {
                                Text("取消")
                            }
                        },
                    )
                }

                if (showSettingsDialog) {
                    AlertDialog(
                        onDismissRequest = { showSettingsDialog = false },
                        title = { Text("设置") },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                // 模式切换
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("运行模式", style = MaterialTheme.typography.bodyMedium)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isAgentMode) Color(0xFF10A37F) else Color(0xFFE5E5E5))
                                            .bouncyClickable(enabled = !isGuideRunning) { isAgentMode = !isAgentMode }
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = if (isAgentMode) "代理模式" else "辅助模式",
                                            color = if (isAgentMode) Color.White else Color(0xFF6B6B80),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }

                                // TTS toggle
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("语音播报", style = MaterialTheme.typography.bodyMedium)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isTtsEnabled) Color(0xFF10A37F) else Color(0xFFE5E5E5))
                                            .bouncyClickable { toggleTts() }
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = if (isTtsEnabled) "On" else "Off",
                                            color = if (isTtsEnabled) Color.White else Color(0xFF6B6B80),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }

                                // 语音输入
                                Row(
                                        modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFF7F7F8))
                                        .bouncyClickable {
                                            showSettingsDialog = false
                                            requestVoiceInput()
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text("语音输入", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF6B6B80))
                                }

                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEAECF0)))

                                Row(
                                        modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFFFF0F0))
                                        .bouncyClickable { showSettingsDialog = false; showClearConfirm = true }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text("清除所有对话和偏好数据", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFEF4444))
                                }

                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEAECF0)))

                                // 关于 Svate
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("关于 Svate", style = MaterialTheme.typography.bodySmall, color = Color(0xFF8E8EA0))
                                    Text("v1.0.0", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB4B4C0))
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showSettingsDialog = false }) {
                                Text(stringResource(R.string.action_done))
                            }
                        },
                    )
                }

                // 二次确认清除对话框
                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        title = { Text("Confirm Clear") },
                        text = { Text("This will clear all conversations and user preferences. This action cannot be undone.") },
                        confirmButton = {
                            TextButton(onClick = {
                                chatSessions.clear()
                                ChatStorage.saveSessions(this@MainActivity, emptyList())
                                UserProfileStore.clearProfile(this@MainActivity)
                                startNewSession()
                                showClearConfirm = false
                            }) { Text(stringResource(R.string.action_confirm_clear), color = Color(0xFFEF4444)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        saveCurrentSession()
        // Agent 生命周期已由 ViewModel 管理，无需在此 stop
        ioExecutor.shutdownNow()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            isTtsReady = false
            return
        }

        val tts = textToSpeech ?: return
        val languageResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
        isTtsReady = languageResult != TextToSpeech.LANG_MISSING_DATA &&
            languageResult != TextToSpeech.LANG_NOT_SUPPORTED

        if (!isTtsReady) {
            val fallback = tts.setLanguage(Locale.CHINESE)
            isTtsReady = fallback != TextToSpeech.LANG_MISSING_DATA &&
                fallback != TextToSpeech.LANG_NOT_SUPPORTED
        }

        if (isTtsReady) {
            pendingSpeechText?.let {
                speakAssistant(it)
            }
            pendingSpeechText = null
        }
    }

    private fun sendCurrentMessage() {
        val text = inputText.trim()
        if (text.isBlank() || isSending) return

        readyPlan = null
        candidateApps.clear()
        isSending = true
        isTyping = true
        inputText = ""
        messages += UiMessage(createId(), "user", text)

        val requestMessages = messages.map { SimpleChatMessage(role = it.role, content = it.content) }
        ioExecutor.execute {
            try {
                val response = GuideAiEngines.chatForGoal(requestMessages)
                runOnUiThread {
                    messages += UiMessage(createId(), "assistant", response.reply)
                    speakAssistant(response.reply)

                    saveCurrentSession()
                    autoGenerateTitleIfNeeded()

                    candidateApps.clear()
                    if (response.candidates.isNotEmpty()) {
                        candidateApps.addAll(response.candidates)
                        statusText = "Please choose one app from the candidates"
                    }

                    readyPlan = if (response.readyToStart) response else null
                    if (response.readyToStart) {
                        val modeLabel = when (response.taskMode) {
                            "SEARCH" -> "Search task"
                            "RESEARCH" -> "Research task"
                            "HOMEWORK" -> "Homework assist"
                            else -> "General task"
                        }
                        statusText = "Target confirmed: ${response.targetAppName} ($modeLabel), ready to start"
                    } else if (response.candidates.isEmpty()) {
                        statusText = "Please provide more task details"
                    }
                    isSending = false
                    isTyping = false
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isSending = false
                    isTyping = false
                    mainViewModel.emitError("AI request failed: ${e.localizedMessage ?: "unknown_error"}")
                }
            }
        }
    }

    /**
     * 用户从候选列表中选择一个应用后，自动发送确认消息。
     */
    private fun selectCandidate(candidate: AppCandidate) {
        candidateApps.clear()
        inputText = "我要用 ${candidate.appName}"
        sendCurrentMessage()
    }

    private fun requestVoiceInput() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        launchVoiceInput()
    }

    private fun launchVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINA.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Please say your goal")
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "This device does not support voice input", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startGuide(plan: GoalChatResult) {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
            Toast.makeText(this, "Please enable overlay permission first, then start again.", Toast.LENGTH_LONG).show()
            return
        }

        // Agent 模式需要检查无障碍服务
        if (isAgentMode && !AgentAccessibilityService.isServiceEnabled(this)) {
            Toast.makeText(this, "Autonomous mode requires accessibility service. Opening settings...", Toast.LENGTH_LONG).show()
            AgentAccessibilityService.openAccessibilitySettings(this)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        pendingPlan = plan
        statusText = "Ready to start. Please grant screen capture permission."
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    /**
     * 启动 Agent 自主模式 — 委托给 ViewModel（跨配置变更存活）
     */
    private fun startAgent(plan: GoalChatResult) {
        mainViewModel.startAgent(plan)
        try { AgentStopOverlayService.start(this) } catch (_: Exception) {}
    }

    private fun toTaskSpec(plan: GoalChatResult): TaskSpec {
        return TaskSpec.fromRaw(
            taskMode = plan.taskMode,
            searchQuery = plan.searchQuery,
            researchDepth = plan.researchDepth,
            homeworkPolicy = plan.homeworkPolicy,
            askOnUncertain = plan.askOnUncertain,
        )
    }

    private fun observeAgentViewModelEvents() {
        val viewModel = mainViewModel
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    viewModel.agentMessages.collect { message ->
                        messages += UiMessage(createId(), "assistant", message)
                    }
                }
                launch {
                    viewModel.narrationEvents.collect { text ->
                        speakAssistant(text)
                    }
                }
            }
        }
    }

    private fun stopGuide() {
        if (isStoppingGuide) return
        isStoppingGuide = true
        try {
            mainViewModel.stopGuide()
            isGuideRunning = false
            Toast.makeText(this, "Guide stopped", Toast.LENGTH_SHORT).show()
            finishSessionWithSummary()
        } finally {
            isStoppingGuide = false
        }
    }

    private fun observeAgentStopRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                AgentEventBus.stopRequests.collect {
                    stopGuide()
                }
            }
        }
    }

    private fun speakAssistant(text: String) {
        if (!isTtsEnabled) return
        val content = text.trim()
        if (content.isBlank()) return
        val tts = textToSpeech ?: return
        if (!isTtsReady) {
            pendingSpeechText = content
            return
        }
        pendingSpeechText = null
        tts.stop()
        tts.speak(content, TextToSpeech.QUEUE_FLUSH, null, "ai_reply_${System.currentTimeMillis()}")
    }

    private fun toggleTts() {
        isTtsEnabled = !isTtsEnabled
        if (!isTtsEnabled) {
            textToSpeech?.stop()
        }
    }

    private fun createId(): String = "${System.currentTimeMillis()}_${(1000..9999).random()}"

    // ================================================================
    // 瀵硅瘽浼氳瘽绠＄悊
    // ================================================================

    private fun startNewSession() {
        saveCurrentSession()
        val currentSession = chatSessions.find { it.id == currentSessionId }
        if (currentSession != null && currentSession.messages.size <= 1) {
            return
        }
        val session = ChatSession(
            id = createId(),
            title = "New Chat",
        )
        chatSessions.add(0, session)
        currentSessionId = session.id
        messages.clear()
        messages += UiMessage(
            id = createId(),
            role = "assistant",
            content = "Hello, I am Svate. Tell me what you want to do.",
        )
        readyPlan = null
        candidateApps.clear()
        mainViewModel.clearPendingInteractions()
        statusText = "Please tell me your goal"
    }

    private fun switchSession(sessionId: String) {
        saveCurrentSession()
        currentSessionId = sessionId
        val session = chatSessions.find { it.id == sessionId } ?: return
        messages.clear()
        messages.addAll(session.messages.map {
            UiMessage(createId(), it.role, it.content)
        })
        readyPlan = null
        candidateApps.clear()
        mainViewModel.clearPendingInteractions()
        statusText = if (session.summary.isNotBlank()) session.summary else "Conversation restored"
    }

    private fun saveCurrentSession() {
        val session = chatSessions.find { it.id == currentSessionId } ?: return
        session.messages.clear()
        session.messages.addAll(messages.map { ChatMsg(it.role, it.content) })
        ChatStorage.saveSessions(this, chatSessions.toList())
        mainViewModel.saveSessionToDb(session)
    }

    /**
     * 首次用户对话后自动生成标题
     */
    private fun autoGenerateTitleIfNeeded() {
        val session = chatSessions.find { it.id == currentSessionId } ?: return
        // 仅在自动标题模式且至少有一轮用户对话时生成
        val userMsgCount = messages.count { it.role == "user" }
        if (!session.isAutoTitle || userMsgCount != 1) return

        ioExecutor.execute {
            val title = ChatStorage.generateTitle(
                messages.map { ChatMsg(it.role, it.content) }
            )
            if (title.isNotBlank()) {
                session.title = title
                ChatStorage.saveSessions(this@MainActivity, chatSessions.toList())
                mainViewModel.saveSessionToDb(session)
                runOnUiThread {
                    val idx = chatSessions.indexOfFirst { it.id == session.id }
                    if (idx >= 0) {
                        chatSessions[idx] = session.copy()
                    }
                }
            }
        }
    }

    private fun updateSessionTitle(sessionId: String, title: String) {
        val session = chatSessions.find { it.id == sessionId } ?: return
        session.title = title.trim().ifBlank { "New Chat" }
        session.isAutoTitle = false
        ChatStorage.saveSessions(this, chatSessions.toList())
        // 瑙﹀彂 recompose
        val idx = chatSessions.indexOf(session)
        if (idx >= 0) {
            chatSessions[idx] = session.copy()
        }
    }

    private fun deleteSession(sessionId: String) {
        val idx = chatSessions.indexOfFirst { it.id == sessionId }
        if (idx < 0) return
        chatSessions.removeAt(idx)
        ChatStorage.saveSessions(this, chatSessions.toList())
        if (sessionId == currentSessionId) {
            if (chatSessions.isNotEmpty()) {
                switchSession(chatSessions.first().id)
            } else {
                startNewSession()
            }
        }
    }

    private fun finishSessionWithSummary() {
        val session = chatSessions.find { it.id == currentSessionId } ?: return
        session.messages.clear()
        session.messages.addAll(messages.map { ChatMsg(it.role, it.content) })

        ioExecutor.execute {
            // 1. 生成总结
            val summary = ChatStorage.generateSummary(session.messages)
            if (summary.isNotBlank()) {
                session.summary = summary
            }

            if (session.isAutoTitle && session.messages.size >= 2) {
                val title = ChatStorage.generateTitle(session.messages)
                session.title = title
            }

            // 3. 提取用户偏好
            UserProfileStore.extractAndMerge(this@MainActivity, session.messages)

            // 4. 保存
            ChatStorage.saveSessions(this@MainActivity, chatSessions.toList())

            runOnUiThread {
                // 触发 recompose
                val idx = chatSessions.indexOf(session)
                if (idx >= 0) {
                    chatSessions[idx] = session.copy()
                }
                if (summary.isNotBlank()) {
                    messages += UiMessage(createId(), "assistant", "Conversation summary: $summary")
                }
            }
        }
    }
}

@Composable
private fun GuideScreen(
    modifier: Modifier = Modifier,
    messages: List<UiMessage>,
    inputText: String,
    isSending: Boolean,
    isGuideRunning: Boolean,
    statusText: String,
    readyPlan: GoalChatResult?,
    candidateApps: List<AppCandidate>,
    isTtsEnabled: Boolean,
    isTtsReady: Boolean,
    isAgentMode: Boolean,
    agentPhaseText: String,
    pendingDecisionRequest: DecisionRequest?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onToggleTts: () -> Unit,
    onToggleAgentMode: () -> Unit,
    onStartGuide: (GoalChatResult) -> Unit,
    onStopGuide: () -> Unit,
    onCandidateSelect: (AppCandidate) -> Unit,
    onConfirmAction: (Boolean) -> Unit,
    onDecisionSelect: (DecisionOption?) -> Unit,
    onCopyText: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit = {},
    isTyping: Boolean = false,
    onSuggestionClick: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent),
    ) {
        // ===== 顶栏 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.65f))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(R.string.menu_label),
                    tint = Color(0xFF8E8EA0),
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = "Svate",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A2E),
                modifier = Modifier.weight(1f),
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFEFEFEF).copy(alpha = 0.6f))
                    .border(1.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .bouncyClickable { onOpenSettings() }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF6B6B80),
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

            // 停止按钮（仅运行中显示）
            if (isGuideRunning) {
                TextButton(
                    onClick = onStopGuide,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.action_stop),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Color(0xFFEAECF0)),
        )

        // ===== Agent 状态 / 确认提示 =====
        if (isAgentMode && agentPhaseText.isNotBlank()) {
            val isWarning = agentPhaseText.startsWith("⚠️")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(if (isWarning) Color(0xFFFFFBEB).copy(alpha=0.8f) else Color(0xFFF7F7F8).copy(alpha=0.8f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = agentPhaseText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isWarning) Color(0xFF92400E) else Color(0xFF6B6B80),
                )
                if (isWarning) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onConfirmAction(true) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10A37F)),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            Text(
                                stringResource(R.string.action_confirm),
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        OutlinedButton(
                            onClick = { onConfirmAction(false) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFD1D5DB)),
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            Text("取消", style = MaterialTheme.typography.labelMedium, color = Color(0xFF6B6B80))
                        }
                    }
                }
            }
        }

        // ===== 不确定时方案选择 =====
        if (isAgentMode && pendingDecisionRequest != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEEF6FF))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "请确认：${pendingDecisionRequest.question}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF1E3A8A),
                    fontWeight = FontWeight.SemiBold,
                )
                if (pendingDecisionRequest.reason.isNotBlank()) {
                    Text(
                        text = pendingDecisionRequest.reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF3B82F6),
                    )
                }
                pendingDecisionRequest.options.forEach { option ->
                    val bg = if (option.recommended) Color(0xFFE0F2FE) else Color.White
                    val border = if (option.recommended) Color(0xFF38BDF8) else Color(0xFFD1D5DB)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .bouncyClickable { onDecisionSelect(option) }
                            .background(bg.copy(alpha = 0.8f))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(border),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (option.recommended) "${option.title}（推荐）" else option.title,
                                color = Color(0xFF1A1A2E),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = option.description,
                                color = Color(0xFF6B7280),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // ===== 聊天区域 =====
        if (messages.size <= 1) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Svate",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD0D0D8),
                )
                Spacer(modifier = Modifier.height(32.dp))
                val suggestions = listOf(
                    "Open Chrome",
                    "Search OpenAI news",
                    "Check today's weather",
                )
                suggestions.forEach { text ->
                    Box(
                        modifier = Modifier
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.65f))
                            .border(1.dp, Color.White.copy(alpha=0.4f), RoundedCornerShape(12.dp))
                            .bouncyClickable { onSuggestionClick(text) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF6B6B80),
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                reverseLayout = false,
            ) {
                items(messages, key = { it.id }) { msg ->
                    val isAssistant = msg.role == "assistant"
                    val timeText = remember(msg.timestamp) {
                        java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(msg.timestamp))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isAssistant) Color.White.copy(alpha = 0.7f) else Color(0xFFE8F1FC).copy(alpha = 0.7f))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                            .padding(16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (isAssistant) "Svate" else "You",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1A1A2E),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFB4B4C0),
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = buildMarkdownAnnotatedString(msg.content),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF374151),
                            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
                        )
                        val canCopy = isAssistant && (
                            msg.content.startsWith("Research Summary") ||
                                msg.content.startsWith("Homework Draft") ||
                                msg.content.contains("Reference Draft")
                            )
                        if (canCopy) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "复制",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF2563EB),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .bouncyClickable { onCopyText(msg.content) }
                                    .background(Color(0xFFEFF6FF).copy(alpha = 0.8f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }

                // 打字动画
                if (isTyping) {
                    item(key = "typing_indicator") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFFF7F7F8))
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Svate",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1A1A2E),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            TypingDots()
                        }
                    }
                }
            }
        }

        // ===== 候选应用（简洁列表） =====
        if (candidateApps.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F7F8))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "请选择应用",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF6B6B80),
                )
                candidates@ for (candidate in candidateApps) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .bouncyClickable { onCandidateSelect(candidate) }
                            .background(Color.White.copy(alpha=0.8f))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = candidate.appName,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1A1A2E),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (candidate.reason.isNotBlank()) {
                                Text(
                                    text = candidate.reason,
                                    color = Color(0xFF8E8EA0),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Text("->", color = Color(0xFF10A37F), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ===== 引导就绪提示 =====
        if (readyPlan != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF0FDF4))
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "${readyPlan.targetAppName}：${readyPlan.inferredGoal}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF065F46),
                )
                if (readyPlan.searchQuery.isNotBlank()) {
                    Text(
                        text = "关键词：${readyPlan.searchQuery}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF065F46),
                    )
                }
                Button(
                    onClick = { onStartGuide(readyPlan) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10A37F)),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    Text(
                        text = "Start Guide",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // ===== 搴曢儴杈撳叆鏍?=====
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color(0xFFEAECF0)),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Send a message",
                            color = Color(0xFFB4B4C0),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    enabled = !isSending,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF10A37F),
                        unfocusedBorderColor = Color(0xFFE5E5E5),
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color(0xFFF7F7F8),
                    ),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )

                IconButton(
                    onClick = onSend,
                    enabled = !isSending && inputText.isNotBlank(),
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (!isSending && inputText.isNotBlank()) Color(0xFF1A1A2E) else Color(0xFFE5E5E5),
                        ),
                ) {
                    Text(
                        text = "↑",
                        color = if (!isSending && inputText.isNotBlank()) Color.White else Color(0xFF8E8EA0),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}


/**
 * 侧边栏，带搜索和分页
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerContent(
    sessions: List<ChatSession>,
    currentSessionId: String,
    onSessionClick: (ChatSession) -> Unit,
    onNewSession: () -> Unit,
    onEditTitle: (ChatSession) -> Unit,
    onDeleteSession: (ChatSession) -> Unit,
    onClose: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var displayCount by remember { mutableStateOf(20) }

    val filtered = remember(sessions, searchQuery) {
        if (searchQuery.isBlank()) sessions
        else sessions.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.summary.contains(searchQuery, ignoreCase = true)
        }
    }
    val visible = filtered.take(displayCount)

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .background(Color.Transparent)
            .padding(top = 16.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        // 鏂板缓瀵硅瘽鎸夐挳
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .bouncyClickable { onNewSession() }
                .background(Color(0xFFF7F7F8).copy(alpha=0.6f))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = Color(0xFF1A1A2E),
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "新建对话",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF1A1A2E),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it; displayCount = 20 },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search conversations", style = MaterialTheme.typography.bodySmall, color = Color(0xFFB4B4C0)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color(0xFFB4B4C0), modifier = Modifier.size(18.dp)) },
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF10A37F),
                unfocusedBorderColor = Color(0xFFEAECF0),
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color(0xFFF7F7F8),
            ),
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 对话列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(visible, key = { it.id }) { session ->
                val isCurrent = session.id == currentSessionId
                var showMenu by remember { mutableStateOf(false) }

                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = { onSessionClick(session) },
                                onLongClick = { showMenu = true },
                            )
                            .background(if (isCurrent) Color(0xFFF0F0F0) else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) Color(0xFF1A1A2E) else Color(0xFF6B6B80),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("编辑标题") },
                            onClick = { showMenu = false; onEditTitle(session) },
                        )
                        DropdownMenuItem(
                            text = { Text("删除对话", color = Color(0xFFEF4444)) },
                            onClick = { showMenu = false; onDeleteSession(session) },
                        )
                    }
                }
            }

            // 分页加载更多
            if (filtered.size > displayCount) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "加载更多（剩余 ${filtered.size - displayCount} 条）",
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .bouncyClickable { displayCount += 20 }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF8E8EA0),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 打字动画，三个灰点循环跳动
 */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF8E8EA0).copy(alpha = alpha)),
            )
        }
    }
}

/**
 * 简易 Markdown 到 AnnotatedString 解析
 * 支持：**加粗**、`行内代码`、以及 `-`/`•` 列表
 */
@Composable
private fun buildMarkdownAnnotatedString(text: String) = buildAnnotatedString {
    val lines = text.split("\n")
    lines.forEachIndexed { lineIdx, line ->
        // 列表前缀
        val (prefix, rest) = if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("•")) {
            "  • " to line.trimStart().removePrefix("- ").removePrefix("•")
        } else {
            "" to line
        }
        if (prefix.isNotEmpty()) append(prefix)

        // 解析行内 **加粗** 和 `代码`
        val pattern = Regex("""(\*\*(.+?)\*\*)|(`(.+?)`)""")
        var lastEnd = 0
        pattern.findAll(rest).forEach { match ->
            if (match.range.first > lastEnd) append(rest.substring(lastEnd, match.range.first))
            when {
                match.groupValues[1].isNotEmpty() -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(match.groupValues[2])
                    pop()
                }
                match.groupValues[3].isNotEmpty() -> {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFFEEEEF2)))
                    append(" ${match.groupValues[4]} ")
                    pop()
                }
            }
            lastEnd = match.range.last + 1
        }
        if (lastEnd < rest.length) append(rest.substring(lastEnd))
        if (lineIdx < lines.lastIndex) append("\n")
    }
}


