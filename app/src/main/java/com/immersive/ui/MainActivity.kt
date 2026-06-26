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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.immersive.ui.agent.AgentCaptureService
import com.immersive.ui.agent.DecisionOption
import com.immersive.ui.agent.DecisionRequest
import com.immersive.ui.agent.loop.LoopTurn
import com.immersive.ui.agent.shizuku.ShizukuManager
import com.immersive.ui.agent.loop.PermissionDecision
import com.immersive.ui.agent.loop.PermissionMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import com.immersive.ui.guide.AppCandidate
import com.immersive.ui.guide.GoalChatResult
import com.immersive.ui.guide.GuideAiEngines
import com.immersive.ui.guide.GuideCaptureService
import com.immersive.ui.guide.InstalledAppScanner
import com.immersive.ui.overlay.AgentStopOverlayService
import com.immersive.ui.ui.theme.UINavTheme
import com.immersive.ui.ui.theme.SvateColors
import com.immersive.ui.ui.theme.SvateShape
import com.immersive.ui.ui.theme.SvateSerif
import com.immersive.ui.ui.theme.glassTopBrush
import com.immersive.ui.ui.theme.glassBottomBrush
import com.immersive.ui.ui.theme.LiquidGlassSurface
import com.immersive.ui.ui.theme.recordBackdrop
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.platform.LocalDensity
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private data class UiMessage(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    // Tool steps this assistant turn ran (one "✅/❌ tool: summary" line each). Shown as a
    // collapsible "已运行 N 条命令" card under the message. In-memory only (not persisted).
    val commands: List<String> = emptyList(),
)

/** A processed file the user attached to the next message (image / PDF / text doc). */
private data class Attachment(
    val id: String,
    val name: String,
    val kind: String, // "image" | "pdf" | "text" | "other"
    val thumbnail: androidx.compose.ui.graphics.ImageBitmap?,
    val imagesBase64: List<String>,
    val text: String,
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
    private var readyPlan by mutableStateOf<GoalChatResult?>(null)
    private var candidateApps = mutableStateListOf<AppCandidate>()

    // Files the user attached to the next message (images / PDFs / text docs).
    private val attachments = mutableStateListOf<Attachment>()
    private var isProcessingAttachment by mutableStateOf(false)
    private lateinit var attachmentPickerLauncher: ActivityResultLauncher<Array<String>>
    private var isTtsEnabled by mutableStateOf(false) // 默认静音
    private var isTtsReady by mutableStateOf(false)

    // Privacy: user profile extraction uploads conversation content, so it is opt-in (default off).
    private var isProfileExtractionEnabled by mutableStateOf(false)

    // Agent autonomous mode: state moved to MainViewModel, local toggle kept here.
    private var isAgentMode by mutableStateOf(true) // 默认代理模式
    private var pendingPlan: GoalChatResult? = null
    private var pendingSpeechText: String? = null

    // New experimental "autonomous agent loop" mode (claude-code-style on-device loop).
    // It coexists with the fixed pipeline above and is selected independently from the UI.
    private var isAgentLoopMode by mutableStateOf(false)
    // Goal held while the loop-mode MediaProjection consent dialog is up.
    private var pendingLoopGoal: String? = null
    // Guards against launching a second screen-capture consent dialog while one is
    // already in flight (e.g. when the durable pending state is re-read after recreation).
    private var screenConsentInFlight = false
    // Shown once the first time the user switches the permission mode to AUTO (放行).
    private var showAutoModeNotice by mutableStateOf(false)
    // Shown once the first time the user switches to EXPERIMENTAL (实验, no confirmation at all).
    private var showExperimentalNotice by mutableStateOf(false)

    // Conversation session management
    private var chatSessions = mutableStateListOf<ChatSession>()
    private var currentSessionId by mutableStateOf("")
    private var showEditTitleDialog by mutableStateOf(false)
    private var editingSessionId by mutableStateOf("")
    private var editTitleText by mutableStateOf("")
    private var showSettingsDialog by mutableStateOf(false)
    private var showClearConfirm by mutableStateOf(false)
    private var isTyping by mutableStateOf(false)
    private var isStoppingGuide = false

    // User-configured OpenAI-compatible model endpoint (base URL / model / key),
    // stored locally. When set, the device drives the model directly — no backend,
    // no Gemini default.
    private var endpointBaseUrl by mutableStateOf("")
    private var endpointModel by mutableStateOf("")
    private var endpointApiKey by mutableStateOf("")

    // Web-search config (web_search tool): provider key + api key + optional endpoint.
    private var searchProvider by mutableStateOf("TAVILY")
    private var searchApiKey by mutableStateOf("")
    private var searchEndpoint by mutableStateOf("")

    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var loopProjectionLauncher: ActivityResultLauncher<Intent>
    private lateinit var speechLauncher: ActivityResultLauncher<Intent>
    private lateinit var audioPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private var textToSpeech: TextToSpeech? = null

    /** ViewModel (Room and errorFlow hub). */
    private val mainViewModel: MainViewModel by lazy {
        androidx.lifecycle.ViewModelProvider(this)[MainViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        textToSpeech = TextToSpeech(this, this)
        mediaProjectionManager = getSystemService(MediaProjectionManager::class.java)
        isProfileExtractionEnabled = getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_PROFILE_EXTRACTION_ENABLED, false)

        // Load the saved model endpoint and apply it so goal understanding routes
        // to it immediately (the agent loop reads it fresh at each task start).
        com.immersive.ui.agent.loop.ModelEndpointStore.load(this).let { cfg ->
            endpointBaseUrl = cfg.baseUrl
            endpointModel = cfg.model
            endpointApiKey = cfg.apiKey
            GuideAiEngines.setModelEndpoint(cfg)
        }
        com.immersive.ui.agent.loop.SearchEndpointStore.load(this).let { cfg ->
            searchProvider = cfg.provider.name
            searchApiKey = cfg.apiKey
            searchEndpoint = cfg.endpoint
        }

        // Scan installed apps and inject them into the AI engine.
        val apps = InstalledAppScanner.getInstalledApps(this)
        GuideAiEngines.setInstalledApps(apps)

        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val plan = pendingPlan
            if (result.resultCode == Activity.RESULT_OK && result.data != null && plan != null) {
                // Assist mode only: run the overlay guide while the user is inside the
                // target app. Autonomous mode now goes through the on-device agent loop
                // (startAgentLoopFromGoal) and never reaches this launcher.
                GuideCaptureService.start(
                    context = this,
                    resultCode = result.resultCode,
                    resultData = result.data!!,
                    targetAppName = plan.targetAppName,
                    inferredGoal = plan.inferredGoal,
                )
                // Assist mode runs while the user is in another app, so the floating
                // stop button is the visible stop entry point in that state.
                try { AgentStopOverlayService.start(this) } catch (_: Exception) {}
                mainViewModel.setGuideRunning(true)
                mainViewModel.setStatusText("Assist mode guide is running")
                Toast.makeText(this, "Guide started. Switch to the target app to continue.", Toast.LENGTH_SHORT).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "Screen capture permission is required to start.", Toast.LENGTH_SHORT).show()
            }
            pendingPlan = null
        }

        // Loop mode owns its own projection consent so take_screenshot and the
        // post-action observations carry real frames (agent-loop.md §7). Declining
        // is allowed: the loop degrades to UI-tree-only, which is the pre-wiring
        // behavior, and the system prompt stops promising screenshots.
        loopProjectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            screenConsentInFlight = false
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                AgentCaptureService.start(this, result.resultCode, result.data!!)
                // The capture service binds the projection asynchronously; the loop
                // waits (bounded) for it inside ensureScreenAccess before grabbing a frame.
                mainViewModel.resolveScreenAccess(true)
            } else {
                Toast.makeText(this, "未授权录屏，Agent 将仅依靠界面树运行（无截图）", Toast.LENGTH_LONG).show()
                mainViewModel.resolveScreenAccess(false)
            }
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

        // Image/document attachment picker (multi-select). The picker grants per-URI read
        // access; processing (downscale image / render PDF pages / read text) runs off-thread.
        attachmentPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            onAttachmentsPicked(uris)
        }

        // Load stored conversation history from Room; legacy SharedPreferences data
        // is migrated inside loadSessionsFromDb on first run.
        lifecycleScope.launch {
            chatSessions.addAll(mainViewModel.loadSessionsFromDb())
            if (chatSessions.isEmpty()) {
                startNewSession()
            } else {
                // Restore the most recent session.
                switchSession(chatSessions.first().id)
            }
        }

        observeAgentViewModelEvents()
        observeAgentStopRequests()

        setContent {
            UINavTheme {
                val drawerState = rememberDrawerState(DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                val viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                val isGuideRunning by viewModel.isGuideRunning.collectAsState()
                val agentLoopRunning by viewModel.agentLoopRunning.collectAsState()
                val permissionMode by viewModel.permissionMode.collectAsState()
                val agentLoopNarration by viewModel.agentLoopNarration.collectAsState()
                val pendingPermission by viewModel.pendingPermission.collectAsState()
                // Either entry point being active enables the shared stop UI.
                val anyGuideRunning = isGuideRunning || agentLoopRunning
                val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

                // Listen for error events and surface them with a Snackbar.
                LaunchedEffect(viewModel) {
                    viewModel.errorFlow.collect { msg ->
                        snackbarHostState.showSnackbar(msg)
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.width(296.dp),
                            drawerContainerColor = SvateColors.SurfaceMuted,
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
                        Box(modifier = Modifier.fillMaxSize().background(SvateColors.Canvas)) {
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
                                isGuideRunning = anyGuideRunning,
                                statusText = viewModel.statusText.collectAsState().value,
                                readyPlan = readyPlan,
                                candidateApps = candidateApps.toList(),
                                isTtsEnabled = isTtsEnabled,
                                isTtsReady = isTtsReady,
                                isAgentMode = isAgentMode,
                                isAgentLoopMode = isAgentLoopMode,
                                agentPhaseText = viewModel.agentPhaseText.collectAsState().value,
                                pendingDecisionRequest = viewModel.pendingDecisionRequest.collectAsState().value,
                                permissionMode = permissionMode,
                                agentLoopPhase = viewModel.agentLoopPhase.collectAsState().value,
                                agentLoopNarration = agentLoopNarration,
                                pendingPermission = pendingPermission,
                                onInputChange = { inputText = it },
                                onSend = { sendCurrentMessage() },
                                onVoice = { requestVoiceInput() },
                                onToggleTts = { toggleTts() },
                                onToggleAgentMode = { isAgentMode = !isAgentMode },
                                onToggleAgentLoopMode = { enabled ->
                                    isAgentLoopMode = enabled
                                    // The fixed-pipeline agent/assist toggle is mutually exclusive
                                    // with the experimental loop to avoid ambiguous start routing.
                                    if (enabled) isAgentMode = true
                                },
                                onTogglePermissionMode = {
                                    when (viewModel.togglePermissionMode()) {
                                        PermissionMode.AUTO ->
                                            if (!hasSeenAutoNotice()) {
                                                showAutoModeNotice = true
                                                markAutoNoticeSeen()
                                            }
                                        PermissionMode.EXPERIMENTAL ->
                                            if (!hasSeenExperimentalNotice()) {
                                                showExperimentalNotice = true
                                                markExperimentalNoticeSeen()
                                            }
                                        PermissionMode.SAFE -> Unit
                                        PermissionMode.ASK -> Unit
                                    }
                                },
                                onPermissionResolved = { id, decision ->
                                    viewModel.onPermissionResolved(id, decision)
                                },
                                onStartGuide = { startGuide(it) },
                                onStartAgentLoop = { goal -> startAgentLoopFromGoal(goal) },
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
                                attachments = attachments.toList(),
                                isProcessingAttachment = isProcessingAttachment,
                                onPickAttachments = { pickAttachments() },
                                onRemoveAttachment = { id -> removeAttachment(id) },
                            )
                        }
                    }
                }
            }

                if (showEditTitleDialog) {
                    AlertDialog(
                        onDismissRequest = { showEditTitleDialog = false },
                        containerColor = SvateColors.Surface,
                        title = { Text("编辑标题", fontFamily = SvateSerif, color = SvateColors.TextPrimary) },
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
                        containerColor = SvateColors.Surface,
                        title = { Text("设置", fontFamily = SvateSerif, color = SvateColors.TextPrimary) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                // Svate now routes every message through the one general
                                // agent (it decides per message whether to answer or act).
                                // The legacy 代理/辅助 pipeline toggles were retired; whether
                                // it asks before each step lives in the top-bar shield/
                                // lightning icon (ASK/AUTO).
                                Text(
                                    text = "Svate 现在是通用手机助手：能直接回答，也能在你需要时操作手机，每条消息自动判断。是否每步征求许可，用顶栏的盾牌/闪电图标切换。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SvateColors.TextTertiary,
                                )

                                // Model endpoint (OpenAI-compatible): base URL / model / key,
                                // stored locally. When set, the device drives the model directly.
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "模型端点 (OpenAI 兼容)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "填写后，设备直接连接此端点驱动 Agent，不经后端、不用 Gemini。留空则用默认后端。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SvateColors.TextTertiary,
                                    )
                                    Text(
                                        text = "⚠️ 连接自定义端点后，屏幕截图与界面文字会直接发送到该端点（密码字段已打码，但其他敏感内容不会）。请仅填写你信任的服务地址。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SvateColors.Danger,
                                    )
                                    OutlinedTextField(
                                        value = endpointBaseUrl,
                                        onValueChange = { endpointBaseUrl = it.trim(); persistModelEndpoint() },
                                        label = { Text("Base URL") },
                                        placeholder = { Text("https://your-host/v1") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = endpointModel,
                                        onValueChange = { endpointModel = it.trim(); persistModelEndpoint() },
                                        label = { Text("模型名") },
                                        placeholder = { Text("claude-opus-4-8") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = endpointApiKey,
                                        onValueChange = { endpointApiKey = it.trim(); persistModelEndpoint() },
                                        label = { Text("API Key") },
                                        placeholder = { Text("sk-...") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                // Web search (web_search tool): provider + key/endpoint, stored locally.
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "联网搜索 (web_search)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Text(
                                        text = "配置后，Agent 可直接联网搜索信息，不必打开浏览器。Tavily/Brave 填 API Key;SearXNG 填自托管实例地址。查询会发送到所选搜索服务。",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SvateColors.TextTertiary,
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("TAVILY" to "Tavily", "BRAVE" to "Brave", "SEARXNG" to "SearXNG").forEach { (key, label) ->
                                            val selected = searchProvider == key
                                            TextButton(onClick = { searchProvider = key; persistSearchConfig() }) {
                                                Text(
                                                    (if (selected) "● " else "○ ") + label,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                                )
                                            }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = searchApiKey,
                                        onValueChange = { searchApiKey = it.trim(); persistSearchConfig() },
                                        label = { Text("Search API Key") },
                                        placeholder = { Text(if (searchProvider == "SEARXNG") "(SearXNG 可留空)" else "tvly-... / brave key") },
                                        singleLine = true,
                                        visualTransformation = PasswordVisualTransformation(),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    OutlinedTextField(
                                        value = searchEndpoint,
                                        onValueChange = { searchEndpoint = it.trim(); persistSearchConfig() },
                                        label = { Text(if (searchProvider == "SEARXNG") "SearXNG 实例地址" else "Endpoint（可选覆盖）") },
                                        placeholder = { Text(if (searchProvider == "SEARXNG") "https://your-searxng" else "留空用默认官方端点") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                // Direct file access (All files access / MANAGE_EXTERNAL_STORAGE):
                                // lets the agent read/write/manage files without opening a file manager.
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("文件访问", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = "让 Agent 直接读写、管理手机文件（需「所有文件访问」权限）",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SvateColors.TextTertiary,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val hasFileAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                                        android.os.Environment.isExternalStorageManager()
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (hasFileAccess) SvateColors.Accent else SvateColors.Border)
                                            .bouncyClickable { openAllFilesAccessSettings() }
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = if (hasFileAccess) "已开启" else "去开启",
                                            color = if (hasFileAccess) Color.White else SvateColors.TextSecondary,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                    }
                                }

                                // Shizuku privileged control (true device-admin tools: shell/pm/am/settings).
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("特权控制 (Shizuku)", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            text = "ADB 级权限：强停/卸载/授权/改系统设置。状态：${ShizukuManager.status()}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = SvateColors.TextTertiary,
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    val shizukuReady = ShizukuManager.isAvailable() && ShizukuManager.hasPermission()
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (shizukuReady) SvateColors.Accent else SvateColors.Border)
                                            .bouncyClickable { onShizukuAction() }
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = if (shizukuReady) "已就绪" else "去授权",
                                            color = if (shizukuReady) Color.White else SvateColors.TextSecondary,
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
                                            .background(if (isTtsEnabled) SvateColors.Accent else SvateColors.Border)
                                            .bouncyClickable { toggleTts() }
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = if (isTtsEnabled) "On" else "Off",
                                            color = if (isTtsEnabled) Color.White else SvateColors.TextSecondary,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }

                                // Profile extraction opt-in (uploads conversation content; default off)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(R.string.settings_profile_extraction),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isProfileExtractionEnabled) SvateColors.Accent else SvateColors.Border)
                                            .bouncyClickable { toggleProfileExtraction() }
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = if (isProfileExtractionEnabled) "On" else "Off",
                                            color = if (isProfileExtractionEnabled) Color.White else SvateColors.TextSecondary,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }

                                // Voice input
                                Row(
                                        modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SvateColors.SurfaceMuted)
                                        .bouncyClickable {
                                            showSettingsDialog = false
                                            requestVoiceInput()
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text("语音输入", style = MaterialTheme.typography.bodyMedium, color = SvateColors.TextSecondary)
                                }

                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SvateColors.Divider))

                                Row(
                                        modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(SvateColors.DangerSoft)
                                        .bouncyClickable { showSettingsDialog = false; showClearConfirm = true }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                ) {
                                    Text("清除所有对话和偏好数据", style = MaterialTheme.typography.bodyMedium, color = SvateColors.Danger)
                                }

                                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SvateColors.Divider))

                                // About Svate
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("关于 Svate", style = MaterialTheme.typography.bodySmall, color = SvateColors.TextTertiary)
                                    Text("v1.0.0", style = MaterialTheme.typography.bodySmall, color = SvateColors.TextTertiary)
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

                // Secondary confirmation dialog before clearing data.
                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        containerColor = SvateColors.Surface,
                        title = { Text("确认清除", fontFamily = SvateSerif, color = SvateColors.TextPrimary) },
                        text = { Text("这将清除所有对话与偏好数据，且无法撤销。", color = SvateColors.TextSecondary) },
                        confirmButton = {
                            TextButton(onClick = {
                                chatSessions.clear()
                                // Clears Room, SharedPreferences sessions, and the user profile.
                                mainViewModel.clearAllData()
                                startNewSession()
                                showClearConfirm = false
                            }) { Text(stringResource(R.string.action_confirm_clear), color = SvateColors.Danger) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
                        },
                    )
                }

                // One-time explanation shown the first time the user enables AUTO (放行) mode.
                if (showAutoModeNotice) {
                    AlertDialog(
                        onDismissRequest = { showAutoModeNotice = false },
                        containerColor = SvateColors.Surface,
                        title = { Text(stringResource(R.string.permission_auto_notice_title), fontFamily = SvateSerif, color = SvateColors.TextPrimary) },
                        text = { Text(stringResource(R.string.permission_auto_notice_body)) },
                        confirmButton = {
                            TextButton(onClick = { showAutoModeNotice = false }) {
                                Text(stringResource(R.string.action_done))
                            }
                        },
                    )
                }

                // One-time strong warning the first time EXPERIMENTAL (实验) mode is enabled.
                if (showExperimentalNotice) {
                    AlertDialog(
                        onDismissRequest = { showExperimentalNotice = false },
                        containerColor = SvateColors.Surface,
                        title = {
                            Text(
                                stringResource(R.string.permission_experimental_notice_title),
                                fontFamily = SvateSerif,
                                color = SvateColors.Danger,
                                fontWeight = FontWeight.Medium,
                            )
                        },
                        text = { Text(stringResource(R.string.permission_experimental_notice_body)) },
                        confirmButton = {
                            TextButton(onClick = { showExperimentalNotice = false }) {
                                Text(stringResource(R.string.action_done))
                            }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        saveCurrentSession()
        // The ViewModel owns the agent lifecycle, so no stop call is needed here.
        // saveCurrentSession enqueues the final SharedPreferences mirror write on
        // ioExecutor; drain it before killing the executor (shutdownNow would drop
        // the just-enqueued task). Bounded so a stuck task cannot hang teardown.
        ioExecutor.shutdown()
        try {
            if (!ioExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow()
            }
        } catch (_: InterruptedException) {
            ioExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
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
        val hasAttachments = attachments.isNotEmpty()
        // The single agent run is the only "in flight" gate now; block re-entry while
        // one is active so a second message cannot start an overlapping run. A message
        // may be attachments-only (e.g. "look at this photo" with no text).
        if ((text.isBlank() && !hasAttachments) || isSending || mainViewModel.agentLoopRunning.value) return

        // Snapshot and clear the staged attachments for this message.
        val pending = attachments.toList()
        attachments.clear()
        val attachImages = pending.flatMap { it.imagesBase64 }
        val attachText = pending
            .filter { it.text.isNotBlank() }
            .joinToString("\n\n") { "【${it.name}】\n${it.text}" }

        isSending = true
        isTyping = true
        inputText = ""

        // The chat bubble shows the typed text plus a compact note of what was attached.
        val bubble = buildString {
            append(text)
            if (pending.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append("📎 ").append(pending.joinToString("、") { it.name })
            }
        }
        messages += UiMessage(createId(), "user", bubble)

        // Persist the turn and auto-title from the first user message, same as before.
        saveCurrentSession()
        autoGenerateTitleIfNeeded()

        // Seed the prior conversation (everything before this new message) so the
        // agent keeps context across turns; the new message is the run's goal. Cap to
        // the last 16 turns so a long chat does not blow the model's context window.
        val history = messages
            .dropLast(1)
            .takeLast(16)
            .map { m ->
                // Fold the prior turn's executed commands into its history text so a
                // follow-up message knows what the agent already did on the device.
                val text = if (m.role == "assistant" && m.commands.isNotEmpty()) {
                    m.content + "\n（本轮已执行的操作：" + m.commands.joinToString("；") + "）"
                } else {
                    m.content
                }
                LoopTurn(role = if (m.role == "assistant") "model" else "user", text = text)
            }

        // Every message now drives the general agent loop: it decides on its own
        // whether to just answer, ask a clarifying question, or operate the phone.
        // isSending stays true for the whole run (input is locked to one run at a
        // time); the agentLoopRunning collector clears it on any termination path.
        mainViewModel.startAgentLoop(text, history, attachImages, attachText)
    }

    /** Open the system picker for images / PDFs / text documents (multi-select). */
    private fun pickAttachments() {
        if (isSending || mainViewModel.agentLoopRunning.value) return
        try {
            attachmentPickerLauncher.launch(
                arrayOf("image/*", "application/pdf", "text/*", "application/json", "application/xml"),
            )
        } catch (e: Exception) {
            mainViewModel.emitError("无法打开文件选择器：${e.localizedMessage}")
        }
    }

    /** Process picked URIs off the main thread (downscale / render / read), then stage chips. */
    private fun onAttachmentsPicked(uris: List<Uri>) {
        isProcessingAttachment = true
        val capped = uris.take(6)
        ioExecutor.execute {
            val processed = capped.mapNotNull { uri ->
                runCatching { AttachmentProcessor.process(this, uri) }.getOrNull()?.let { p ->
                    Attachment(
                        id = createId(),
                        name = p.name,
                        kind = p.kind,
                        thumbnail = p.thumbnail?.asImageBitmap(),
                        imagesBase64 = p.imagesBase64,
                        text = p.text,
                    )
                }
            }
            runOnUiThread {
                attachments.addAll(processed)
                isProcessingAttachment = false
                if (processed.size < capped.size) {
                    mainViewModel.emitError("部分附件未能读取")
                }
            }
        }
    }

    private fun removeAttachment(id: String) {
        attachments.removeAll { it.id == id }
    }

    /**
     * Automatically send a confirmation message after the user selects a candidate app.
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
        // Autonomous mode now runs through the on-device agent loop; the legacy
        // fixed-pipeline (OpenClawOrchestrator) has been retired, so both the loop
        // toggle and "agent mode" route to the loop, which owns its own
        // capture/accessibility usage. "Assist mode" keeps the overlay-guide
        // projection flow that runs while the user is inside the target app.
        if (isAgentLoopMode || isAgentMode) {
            startAgentLoopFromGoal(plan.inferredGoal)
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
            Toast.makeText(this, "Please enable overlay permission first, then start again.", Toast.LENGTH_LONG).show()
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
        mainViewModel.setStatusText("Ready to start. Please grant screen capture permission.")
        projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
    }

    /**
     * Launch the experimental autonomous agent loop from a goal string.
     * Like agent mode, it requires the accessibility service to be enabled, and it
     * asks for MediaProjection consent so the model sees real screenshots; declining
     * the projection falls back to UI-tree-only operation.
     */
    private fun startAgentLoopFromGoal(goal: String) {
        val trimmed = goal.trim()
        if (trimmed.isBlank()) {
            Toast.makeText(this, "请先告诉我你想完成什么", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AgentAccessibilityService.isServiceEnabled(this)) {
            Toast.makeText(this, "自主 Agent 需要无障碍权限，正在打开设置…", Toast.LENGTH_LONG).show()
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

        // A still-active projection from an earlier run in this process can be reused
        // directly. Checking isProjectionActive (not just instance != null) avoids
        // reusing a service whose projection failed to bind or was revoked, which
        // would leave the loop screenshot-blind.
        if (AgentCaptureService.instance?.isProjectionActive() == true) {
            launchAgentLoopNow(trimmed, awaitCaptureMs = 0L)
            return
        }
        pendingLoopGoal = trimmed
        mainViewModel.setStatusText("请授权录屏，让 Agent 能看到屏幕")
        try {
            loopProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            // No consent UI available (e.g. restricted profile): degrade to tree-only.
            pendingLoopGoal = null
            launchAgentLoopNow(trimmed, awaitCaptureMs = 0L)
        }
    }

    /**
     * Legacy direct loop-start entry (retained, no longer on the default path: the
     * chat now routes every message through [sendCurrentMessage] → startAgentLoop).
     * Screen recording is acquired lazily by the loop, so no projection pre-grant.
     */
    private fun launchAgentLoopNow(goal: String, @Suppress("UNUSED_PARAMETER") awaitCaptureMs: Long) {
        readyPlan = null
        candidateApps.clear()
        mainViewModel.setStatusText("自主 Agent 正在运行")
        mainViewModel.startAgentLoop(goal)
    }

    private fun observeAgentViewModelEvents() {
        val viewModel = mainViewModel
        lifecycleScope.launch {
            // CREATED, not STARTED: an app-operation run finishes while Svate is in the
            // BACKGROUND (the agent navigated to another app). agentMessages is a replay=0
            // SharedFlow, so a STARTED-scoped collector — cancelled on onStop — misses the
            // terminal Finished/Failed result, and the user returns to a chat with no result
            // and no command card (the task seemed to "vanish"). CREATED stays subscribed
            // through onStop so the result + commands are recorded even when backgrounded.
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.CREATED) {
                launch {
                    viewModel.agentMessages.collect { message ->
                        // A run produced a chat-visible message (answer, question, or
                        // task result): stop the typing indicator, post it, persist it.
                        isTyping = false
                        isSending = false
                        messages += UiMessage(createId(), "assistant", message, commands = viewModel.lastRunCommands)
                        saveCurrentSession()
                    }
                }
                launch {
                    viewModel.narrationEvents.collect { text ->
                        // First sign of life from the run clears the typing dots; the
                        // live tool/phase strip takes over from here.
                        isTyping = false
                        speakAssistant(text)
                    }
                }
                launch {
                    // Authoritative input lock: keep the composer disabled for the
                    // whole run and re-enable on every termination path (finish,
                    // fail, stop, or a start that errored out).
                    viewModel.agentLoopRunning.collect { running ->
                        if (!running) {
                            isSending = false
                            isTyping = false
                        }
                    }
                }
            }
        }
        // The loop owns its run on the ViewModel scope, so a screen-access request can
        // arrive while the Activity is only CREATED (e.g. the agent is in another app);
        // collect at CREATED so the consent dialog can still be launched. screenAccessPending
        // is durable StateFlow state, so a recreated Activity re-reads it and still drives
        // the dialog rather than dropping the request.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.CREATED) {
                viewModel.screenAccessPending.collect { pending ->
                    if (pending) handleScreenAccessRequest()
                }
            }
        }
    }

    /**
     * The running loop asked to see the screen. Reuse an active projection if one
     * exists, otherwise launch the system screen-capture consent. The result is
     * relayed back to the loop from [loopProjectionLauncher]. Guarded so a re-emitted
     * pending state (e.g. after recreation) does not stack a second consent dialog.
     */
    private fun handleScreenAccessRequest() {
        if (screenConsentInFlight) return
        if (AgentCaptureService.instance?.isProjectionActive() == true) {
            mainViewModel.resolveScreenAccess(true)
            return
        }
        // Consent must be requested from a foreground Activity; bring Svate forward
        // if the agent had navigated away, then launch the dialog.
        screenConsentInFlight = true
        try {
            val bringToFront = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(bringToFront)
            loopProjectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            // No consent surface available: degrade to UI-tree-only.
            screenConsentInFlight = false
            mainViewModel.resolveScreenAccess(false)
        }
    }

    private fun stopGuide() {
        if (isStoppingGuide) return
        isStoppingGuide = true
        try {
            // The legacy guide pipeline is retired. Stopping the always-on agent must NOT
            // run the old teardown (which wrote a misleading "Guide stopped" banner) nor
            // inject a "Conversation summary" bubble (which polluted the next turn's history
            // and made the model confabulate about why it "got stuck"). Just halt the live
            // loop + its side services, and leave one honest in-chat record so the user —
            // and the model's next-turn history — both know the task was stopped, not done.
            val wasRunning = mainViewModel.agentLoopRunning.value
            mainViewModel.stopAgentLoop()
            try { AgentStopOverlayService.stop(this) } catch (_: Exception) {}
            mainViewModel.setStatusText("")
            isSending = false
            isTyping = false
            if (wasRunning) {
                messages += UiMessage(
                    createId(),
                    "assistant",
                    "⏹️ 已停止，当前任务未完成。",
                    commands = mainViewModel.lastRunCommands,
                )
                saveCurrentSession()
            }
        } finally {
            isStoppingGuide = false
        }
    }

    private fun observeAgentStopRequests() {
        lifecycleScope.launch {
            // CREATED (not STARTED): the floating stop button is tapped while the user is in
            // another app, i.e. while this Activity is stopped, so the collector must stay active.
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.CREATED) {
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

    private fun toggleProfileExtraction() {
        isProfileExtractionEnabled = !isProfileExtractionEnabled
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PROFILE_EXTRACTION_ENABLED, isProfileExtractionEnabled)
            .apply()
    }

    /**
     * Open the system "All files access" screen for Svate so the user can grant
     * MANAGE_EXTERNAL_STORAGE, which powers the agent's direct file tools.
     */
    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        } catch (e: Exception) {
            // Some OEM ROMs reject the per-app action; fall back to the global list.
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "请在 系统设置 → 应用 → Svate 里开启「所有文件访问」", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Settings "特权控制" button: if Shizuku isn't reachable, open the Shizuku app (or
     * guide installation); if reachable but unauthorized, request permission; else it's ready.
     */
    private fun onShizukuAction() {
        when {
            !ShizukuManager.isAvailable() -> {
                val launch = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                if (launch != null) {
                    startActivity(launch)
                } else {
                    Toast.makeText(this, "请先安装 Shizuku，并用无线调试激活后再授权", Toast.LENGTH_LONG).show()
                }
            }
            !ShizukuManager.hasPermission() -> ShizukuManager.requestPermission()
            else -> Toast.makeText(this, "Shizuku 已就绪", Toast.LENGTH_SHORT).show()
        }
    }

    /** Persist the model-endpoint fields and apply them to goal understanding. */
    private fun persistModelEndpoint() {
        val cfg = com.immersive.ui.agent.loop.EndpointConfig(
            baseUrl = endpointBaseUrl,
            model = endpointModel,
            apiKey = endpointApiKey,
        )
        com.immersive.ui.agent.loop.ModelEndpointStore.save(this, cfg)
        GuideAiEngines.setModelEndpoint(cfg)
    }

    /** Persist the web-search config read by the web_search tool at call time. */
    private fun persistSearchConfig() {
        com.immersive.ui.agent.loop.SearchEndpointStore.save(
            this,
            com.immersive.ui.agent.loop.SearchConfig(
                provider = com.immersive.ui.agent.loop.SearchProvider.fromKey(searchProvider),
                apiKey = searchApiKey,
                endpoint = searchEndpoint,
            ),
        )
    }

    private fun hasSeenAutoNotice(): Boolean =
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_NOTICE_SEEN, false)

    private fun markAutoNoticeSeen() {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_NOTICE_SEEN, true)
            .apply()
    }

    private fun hasSeenExperimentalNotice(): Boolean =
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getBoolean(KEY_EXPERIMENTAL_NOTICE_SEEN, false)

    private fun markExperimentalNoticeSeen() {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EXPERIMENTAL_NOTICE_SEEN, true)
            .apply()
    }

    /** Random UUID so LazyColumn keys never collide, even for ids generated in a tight loop. */
    private fun createId(): String = UUID.randomUUID().toString()

    // ================================================================
    // Conversation session management
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
            content = "你好，我是 Svate。可以陪你聊天，也能帮你操作手机、读写文件。说说看你想做什么？",
        )
        readyPlan = null
        candidateApps.clear()
        mainViewModel.clearPendingInteractions()
        mainViewModel.setStatusText("Please tell me your goal")
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
        mainViewModel.setStatusText(if (session.summary.isNotBlank()) session.summary else "Conversation restored")
    }

    private fun saveCurrentSession() {
        val session = chatSessions.find { it.id == currentSessionId } ?: return
        session.messages.clear()
        session.messages.addAll(messages.map { ChatMsg(it.role, it.content) })
        // Serialize the legacy SharedPreferences mirror off the main thread (Room is
        // the primary store via saveSessionToDb); the single-thread ioExecutor keeps
        // these writes ordered, matching autoGenerateTitleIfNeeded.
        val snapshot = chatSessions.toList()
        ioExecutor.execute { ChatStorage.saveSessions(this, snapshot) }
        mainViewModel.saveSessionToDb(session)
    }

    /**
     * Automatically generate a title after the first user turn.
     */
    private fun autoGenerateTitleIfNeeded() {
        val session = chatSessions.find { it.id == currentSessionId } ?: return
        // Only generate a title in auto-title mode after at least one user turn.
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
        mainViewModel.saveSessionToDb(session)
        // Trigger recompose
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
        mainViewModel.deleteSessionFromDb(sessionId)
        if (sessionId == currentSessionId) {
            if (chatSessions.isNotEmpty()) {
                switchSession(chatSessions.first().id)
            } else {
                startNewSession()
            }
        }
    }

    companion object {
        private const val SETTINGS_PREFS = "svate_settings"
        private const val KEY_PROFILE_EXTRACTION_ENABLED = "profile_extraction_enabled"
        private const val KEY_AUTO_NOTICE_SEEN = "agent_auto_notice_seen"
        private const val KEY_EXPERIMENTAL_NOTICE_SEEN = "agent_experimental_notice_seen"
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
    isAgentLoopMode: Boolean,
    agentPhaseText: String,
    pendingDecisionRequest: DecisionRequest?,
    permissionMode: PermissionMode,
    agentLoopPhase: String,
    agentLoopNarration: List<String>,
    pendingPermission: MainViewModel.PermissionPrompt?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoice: () -> Unit,
    onToggleTts: () -> Unit,
    onToggleAgentMode: () -> Unit,
    onToggleAgentLoopMode: (Boolean) -> Unit,
    onTogglePermissionMode: () -> Unit,
    onPermissionResolved: (String, PermissionDecision) -> Unit,
    onStartGuide: (GoalChatResult) -> Unit,
    onStartAgentLoop: (String) -> Unit,
    onStopGuide: () -> Unit,
    onCandidateSelect: (AppCandidate) -> Unit,
    onConfirmAction: (Boolean) -> Unit,
    onDecisionSelect: (DecisionOption?) -> Unit,
    onCopyText: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit = {},
    isTyping: Boolean = false,
    onSuggestionClick: (String) -> Unit = {},
    attachments: List<Attachment> = emptyList(),
    isProcessingAttachment: Boolean = false,
    onPickAttachments: () -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
) {
    val listState = rememberLazyListState()

    // The live status (running/thinking panel or typing dots) is appended as a trailing
    // item in conversation order, so keep the scroll pinned to the last visible item —
    // messages plus that trailing item while a run is active.
    val liveActive = isTyping ||
        (isGuideRunning && (agentLoopPhase.isNotBlank() || agentLoopNarration.isNotEmpty()))
    LaunchedEffect(messages.size, liveActive, agentLoopNarration.size, agentLoopPhase) {
        val count = messages.size + if (liveActive) 1 else 0
        if (count > 0) {
            listState.animateScrollToItem(count - 1)
        }
    }
    val density = LocalDensity.current
    var topBarHeight by remember { mutableStateOf(0) }
    var bottomBarHeight by remember { mutableStateOf(0) }
    val topInset = with(density) { topBarHeight.toDp() }
    val bottomInset = with(density) { bottomBarHeight.toDp() }
    // Backdrop layer: the conversation records into it so the glass bars can blur + refract
    // what scrolls behind them — real liquid glass, not a translucent fade.
    val backdrop = rememberGraphicsLayer()
    var backdropOrigin by remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SvateColors.Canvas),
    ) {
        // ===== Conversation (full-bleed; recorded into `backdrop`, scrolls behind the glass) =====
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { backdropOrigin = it.localToRoot(Offset.Zero) }
                .recordBackdrop(backdrop),
        ) {
        if (messages.size <= 1) {
            EmptyState(
                onSuggestionClick = onSuggestionClick,
                contentPadding = PaddingValues(top = topInset, bottom = bottomInset),
            )
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = topInset + 6.dp, bottom = bottomInset + 6.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    if (msg.role == "assistant") {
                        AssistantMessage(msg = msg, onCopyText = onCopyText)
                    } else {
                        UserMessage(msg = msg)
                    }
                }

                // Live status appended in conversation order (bottom of the thread).
                val showPanel = isGuideRunning &&
                    (agentLoopPhase.isNotBlank() || agentLoopNarration.isNotEmpty())
                if (showPanel) {
                    item(key = "live_status") {
                        ThinkingPanel(phaseRaw = agentLoopPhase, lines = agentLoopNarration)
                    }
                } else if (isTyping) {
                    item(key = "typing_indicator") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SvateAvatar(size = 30.dp, textStyle = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.width(12.dp))
                            TypingDots()
                        }
                    }
                }
            }
        }
        }

        // ===== Frosted top bar overlay (liquid glass over the recorded backdrop) =====
        GlassTopBar(
            backdrop = backdrop,
            backdropOrigin = backdropOrigin,
            permissionMode = permissionMode,
            isGuideRunning = isGuideRunning,
            onOpenDrawer = onOpenDrawer,
            onTogglePermissionMode = onTogglePermissionMode,
            onStopGuide = onStopGuide,
            onOpenSettings = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topBarHeight = it.height },
        )

        // ===== Frosted composer overlay (permission card + attachment chips + pill) =====
        GlassComposer(
            backdrop = backdrop,
            backdropOrigin = backdropOrigin,
            inputText = inputText,
            isSending = isSending,
            attachments = attachments,
            isProcessingAttachment = isProcessingAttachment,
            pendingPermission = pendingPermission,
            onInputChange = onInputChange,
            onSend = onSend,
            onPickAttachments = onPickAttachments,
            onRemoveAttachment = onRemoveAttachment,
            onPermissionResolved = onPermissionResolved,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { bottomBarHeight = it.height },
        )
    }
}


/**
 * Sidebar with search and pagination.
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
            .padding(top = 18.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        // Brand line (serif wordmark)
        Text(
            text = "Svate",
            style = MaterialTheme.typography.titleLarge,
            fontFamily = SvateSerif,
            color = SvateColors.TextPrimary,
            modifier = Modifier.padding(start = 6.dp, bottom = 16.dp),
        )

        // New conversation
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SvateShape.Field)
                .background(SvateColors.Surface)
                .border(1.dp, SvateColors.Border, SvateShape.Field)
                .bouncyClickable { onNewSession() }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = SvateColors.Accent,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "新建对话",
                style = MaterialTheme.typography.bodyMedium,
                color = SvateColors.TextPrimary,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it; displayCount = 20 },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索对话", style = MaterialTheme.typography.bodySmall, color = SvateColors.TextTertiary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = SvateColors.TextTertiary, modifier = Modifier.size(18.dp)) },
            shape = SvateShape.Field,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SvateColors.Accent,
                unfocusedBorderColor = SvateColors.Border,
                focusedContainerColor = SvateColors.Surface,
                unfocusedContainerColor = SvateColors.Surface,
                focusedTextColor = SvateColors.TextPrimary,
                unfocusedTextColor = SvateColors.TextPrimary,
                cursorColor = SvateColors.Accent,
            ),
            textStyle = MaterialTheme.typography.bodySmall,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Conversation list
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
                            .clip(SvateShape.Field)
                            .combinedClickable(
                                onClick = { onSessionClick(session) },
                                onLongClick = { showMenu = true },
                            )
                            .background(if (isCurrent) SvateColors.AccentSoft else Color.Transparent)
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isCurrent) SvateColors.Accent else SvateColors.TextTertiary),
                        )
                        Spacer(modifier = Modifier.width(11.dp))
                        Text(
                            text = session.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isCurrent) SvateColors.AccentDeep else SvateColors.TextSecondary,
                            fontWeight = if (isCurrent) FontWeight.Medium else FontWeight.Normal,
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
                            text = { Text("删除对话", color = SvateColors.Danger) },
                            onClick = { showMenu = false; onDeleteSession(session) },
                        )
                    }
                }
            }

            // Load more items for pagination
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
                                .clip(SvateShape.Small)
                                .bouncyClickable { displayCount += 20 }
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SvateColors.TextTertiary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Collapsible "已运行 N 条命令" card shown under an assistant turn that ran tools. Collapsed
 * by default; tap the header to expand the executed steps. This is the persisted, post-run
 * form of the live ThinkingPanel.
 */
@Composable
private fun CommandsCard(commands: List<String>) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(SvateShape.Field)
            .background(SvateColors.Surface)
            .border(1.dp, SvateColors.Border, SvateShape.Field)
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelMedium,
                color = SvateColors.TextTertiary,
            )
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = "已运行 ${commands.size} 条命令",
                style = MaterialTheme.typography.labelLarge,
                color = SvateColors.TextSecondary,
                fontWeight = FontWeight.Medium,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 13.dp, end = 13.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(SvateColors.Divider))
                Spacer(modifier = Modifier.height(2.dp))
                commands.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelMedium,
                        color = SvateColors.TextSecondary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

/**
 * Frosted top bar: serif wordmark + a small green "online" pulse, an icon-and-text permission
 * badge (询问/放行/实验), and a stop (running) or settings (idle) action. The translucent
 * gradient background lets the conversation scroll visibly behind it (glassmorphism).
 */
@Composable
private fun GlassTopBar(
    backdrop: GraphicsLayer,
    backdropOrigin: Offset,
    permissionMode: PermissionMode,
    isGuideRunning: Boolean,
    onOpenDrawer: () -> Unit,
    onTogglePermissionMode: () -> Unit,
    onStopGuide: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiquidGlassSurface(
        backdrop = backdrop,
        backdropOrigin = backdropOrigin,
        shape = RectangleShape,
        cornerRadius = 0.dp,
        blurRadius = 34.dp,
        refraction = 12.dp,
        rim = 16.dp,
        modifier = modifier.fillMaxWidth(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(42.dp)) {
                Icon(
                    imageVector = Icons.Default.Menu,
                    contentDescription = stringResource(R.string.menu_label),
                    tint = SvateColors.TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Svate",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = SvateSerif,
                    color = SvateColors.TextPrimary,
                )
                Spacer(modifier = Modifier.width(7.dp))
                val infinite = rememberInfiniteTransition(label = "online")
                val onlineAlpha by infinite.animateFloat(
                    initialValue = 0.3f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "online_alpha",
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .graphicsLayer { alpha = onlineAlpha }
                        .clip(CircleShape)
                        .background(SvateColors.Accent),
                )
            }

            // Permission mode badge — tap cycles 安全(shield) → 询问(lightning) → 实验(flask).
            val permIcon = when (permissionMode) {
                PermissionMode.SAFE -> R.drawable.ic_permission_ask           // shield 盾牌
                PermissionMode.AUTO -> R.drawable.ic_permission_auto          // lightning 闪电
                PermissionMode.EXPERIMENTAL -> R.drawable.ic_permission_experimental // flask 锥形瓶
                PermissionMode.ASK -> R.drawable.ic_permission_auto
            }
            val permFg = when (permissionMode) {
                PermissionMode.SAFE -> SvateColors.AccentDeep
                PermissionMode.AUTO -> SvateColors.Warning
                PermissionMode.EXPERIMENTAL -> SvateColors.Danger
                PermissionMode.ASK -> SvateColors.Warning
            }
            val permBg = when (permissionMode) {
                PermissionMode.SAFE -> SvateColors.AccentSoft
                PermissionMode.AUTO -> SvateColors.WarningSoft
                PermissionMode.EXPERIMENTAL -> SvateColors.DangerSoft
                PermissionMode.ASK -> SvateColors.WarningSoft
            }
            val permLabel = when (permissionMode) {
                PermissionMode.SAFE -> "安全"
                PermissionMode.AUTO -> "询问"
                PermissionMode.EXPERIMENTAL -> "实验"
                PermissionMode.ASK -> "询问"
            }
            Row(
                modifier = Modifier
                    .clip(SvateShape.Small)
                    .background(permBg)
                    .bouncyClickable { onTogglePermissionMode() }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(permIcon),
                    contentDescription = permLabel,
                    tint = permFg,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(permLabel, style = MaterialTheme.typography.labelMedium, color = permFg, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isGuideRunning) {
                Box(
                    modifier = Modifier
                        .clip(SvateShape.Small)
                        .background(SvateColors.DangerSoft)
                        .bouncyClickable { onStopGuide() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.action_stop),
                        style = MaterialTheme.typography.labelLarge,
                        color = SvateColors.Danger,
                        fontWeight = FontWeight.Medium,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(SvateShape.Small)
                        .bouncyClickable { onOpenSettings() }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text("设置", style = MaterialTheme.typography.labelLarge, color = SvateColors.TextSecondary)
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(0.6.dp).background(SvateColors.Border))
      }
    }
}

/** Pending-permission card (ask mode): tool name as a mono chip + risk label + three actions.
 *  High-risk requests use a soft-red surface; everything else a neutral white card. */
@Composable
private fun PermissionRequestCard(
    prompt: MainViewModel.PermissionPrompt,
    onPermissionResolved: (String, PermissionDecision) -> Unit,
) {
    val isHigh = prompt.riskClass == "high"
    val riskLabel = when (prompt.riskClass) {
        "safe" -> stringResource(R.string.permission_risk_safe)
        "low" -> stringResource(R.string.permission_risk_low)
        "high" -> stringResource(R.string.permission_risk_high)
        else -> stringResource(R.string.permission_risk_normal)
    }
    val riskColor = when (prompt.riskClass) {
        "high" -> SvateColors.Danger
        "normal" -> SvateColors.Warning
        else -> SvateColors.AccentDeep
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(SvateShape.Card)
            .background(if (isHigh) SvateColors.DangerSoft else SvateColors.Surface)
            .border(1.dp, if (isHigh) SvateColors.DangerBorder else SvateColors.Border, SvateShape.Card)
            .padding(15.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text(
            text = stringResource(R.string.permission_request_title),
            style = MaterialTheme.typography.bodyMedium,
            color = SvateColors.TextPrimary,
            fontWeight = FontWeight.Medium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = prompt.toolName,
                style = MaterialTheme.typography.labelMedium,
                color = SvateColors.TextPrimary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clip(SvateShape.Small)
                    .background(if (isHigh) SvateColors.Surface else SvateColors.SurfaceMuted)
                    .border(1.dp, SvateColors.Border, SvateShape.Small)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(riskLabel, style = MaterialTheme.typography.labelMedium, color = riskColor, fontWeight = FontWeight.Medium)
        }
        Text(prompt.description, style = MaterialTheme.typography.bodySmall, color = SvateColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onPermissionResolved(prompt.toolCallId, PermissionDecision.GRANT_ONCE) },
                modifier = Modifier.weight(1f),
                shape = SvateShape.Small,
                colors = ButtonDefaults.buttonColors(containerColor = SvateColors.Accent),
                contentPadding = PaddingValues(vertical = 9.dp),
            ) {
                Text(
                    stringResource(R.string.permission_grant_once),
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.labelMedium,
                    color = SvateColors.TextOnAccent,
                )
            }
            OutlinedButton(
                onClick = { onPermissionResolved(prompt.toolCallId, PermissionDecision.GRANT_ALWAYS) },
                modifier = Modifier.weight(1f),
                shape = SvateShape.Small,
                border = BorderStroke(1.dp, SvateColors.Accent),
                contentPadding = PaddingValues(vertical = 9.dp),
            ) {
                Text(
                    stringResource(R.string.permission_grant_always),
                    style = MaterialTheme.typography.labelMedium,
                    color = SvateColors.AccentDeep,
                )
            }
            OutlinedButton(
                onClick = { onPermissionResolved(prompt.toolCallId, PermissionDecision.DENY) },
                modifier = Modifier.weight(1f),
                shape = SvateShape.Small,
                border = BorderStroke(1.dp, SvateColors.DangerBorder),
                contentPadding = PaddingValues(vertical = 9.dp),
            ) {
                Text(
                    stringResource(R.string.permission_deny),
                    style = MaterialTheme.typography.labelMedium,
                    color = SvateColors.Danger,
                )
            }
        }
    }
}

/** Empty state: big serif greeting, capability slogan, and icon suggestion cards. */
@Composable
private fun EmptyState(
    onSuggestionClick: (String) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        SvateAvatar(size = 66.dp, textStyle = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "有什么可以帮你？",
            style = MaterialTheme.typography.headlineSmall,
            fontFamily = SvateSerif,
            color = SvateColors.TextPrimary,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "聊天 · 操作手机 · 读写文件",
            style = MaterialTheme.typography.bodySmall,
            color = SvateColors.TextTertiary,
        )
        Spacer(modifier = Modifier.height(26.dp))
        val suggestions = listOf("讲个冷笑话", "打开设置看看电池电量", "帮我查下今天的天气")
        suggestions.forEach { text ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp)
                    .clip(SvateShape.Chip)
                    .background(SvateColors.Surface)
                    .border(1.dp, SvateColors.Border, SvateShape.Chip)
                    .bouncyClickable { onSuggestionClick(text) }
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(SvateShape.Small)
                        .background(SvateColors.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(SvateColors.Accent))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SvateColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text("↗", style = MaterialTheme.typography.bodyMedium, color = SvateColors.TextTertiary)
            }
        }
    }
}

/** Assistant turn: avatar + full-width markdown text (no bubble), optional commands card and
 *  copy action. Gentle fade + slide-up entrance. */
@Composable
private fun AssistantMessage(msg: UiMessage, onCopyText: (String) -> Unit) {
    val enter = remember(msg.id) { Animatable(0f) }
    LaunchedEffect(msg.id) {
        enter.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .graphicsLayer {
                alpha = enter.value.coerceIn(0f, 1f)
                translationY = (1f - enter.value) * 14f
            },
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        SvateAvatar(size = 30.dp, textStyle = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f).padding(top = 3.dp)) {
            Text(
                text = remember(msg.content) { buildMarkdownAnnotatedString(msg.content) },
                style = MaterialTheme.typography.bodyLarge,
                color = SvateColors.TextPrimary,
                lineHeight = 25.sp,
            )
            if (msg.commands.isNotEmpty()) {
                CommandsCard(commands = msg.commands)
            }
            val canCopy = msg.content.startsWith("Research Summary") ||
                msg.content.startsWith("Homework Draft") ||
                msg.content.contains("Reference Draft")
            if (canCopy) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "复制",
                    style = MaterialTheme.typography.labelMedium,
                    color = SvateColors.AccentDeep,
                    modifier = Modifier
                        .clip(SvateShape.Small)
                        .bouncyClickable { onCopyText(msg.content) }
                        .background(SvateColors.AccentSoft)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}

/** User turn: right-aligned bold dark bubble. Gentle fade + slide-up entrance. */
@Composable
private fun UserMessage(msg: UiMessage) {
    val enter = remember(msg.id) { Animatable(0f) }
    LaunchedEffect(msg.id) {
        enter.animateTo(targetValue = 1f, animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .graphicsLayer {
                alpha = enter.value.coerceIn(0f, 1f)
                translationY = (1f - enter.value) * 14f
            },
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(SvateShape.Bubble)
                .background(SvateColors.UserBubble)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            Text(
                text = msg.content,
                style = MaterialTheme.typography.bodyLarge,
                color = SvateColors.TextOnDark,
                lineHeight = 23.sp,
            )
        }
    }
}

/**
 * Frosted bottom composer: a translucent gradient footer (content scrolls behind it) holding
 * — in order — the pending-permission card, staged attachment chips, and the rounded pill with
 * attach / multiline field / green send. Tracks the keyboard via imePadding.
 */
@Composable
private fun GlassComposer(
    backdrop: GraphicsLayer,
    backdropOrigin: Offset,
    inputText: String,
    isSending: Boolean,
    attachments: List<Attachment>,
    isProcessingAttachment: Boolean,
    pendingPermission: MainViewModel.PermissionPrompt?,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickAttachments: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onPermissionResolved: (String, PermissionDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    // No imePadding here: the window already resizes for the keyboard (the Box shrinks above
    // it), so adding imePadding would double-count the inset and float the composer up into
    // the middle of the screen. Bottom-aligned in the (resized) Box keeps it on the keyboard.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
    ) {
        // Pending permission floats just above the pill, near the thumb.
        if (pendingPermission != null) {
            PermissionRequestCard(prompt = pendingPermission, onPermissionResolved = onPermissionResolved)
            Spacer(modifier = Modifier.height(6.dp))
        }

        // Staged attachment chips + a parsing spinner.
        if (attachments.isNotEmpty() || isProcessingAttachment) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 4.dp, end = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachments.forEach { att ->
                    AttachmentChip(att = att, onRemove = { onRemoveAttachment(att.id) })
                }
                if (isProcessingAttachment) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SvateColors.Accent)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("解析附件…", style = MaterialTheme.typography.labelSmall, color = SvateColors.TextTertiary)
                    }
                }
            }
        }

        val canSend = !isSending && (inputText.isNotBlank() || attachments.isNotEmpty())
        LiquidGlassSurface(
            backdrop = backdrop,
            backdropOrigin = backdropOrigin,
            shape = SvateShape.Pill,
            cornerRadius = 26.dp,
            blurRadius = 30.dp,
            refraction = 26.dp,
            rim = 26.dp,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.45f), SvateShape.Pill),
        ) {
          Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .bouncyClickable(enabled = !isSending) { onPickAttachments() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "添加图片或文档",
                    tint = SvateColors.TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }

            TextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).heightIn(min = 38.dp, max = 140.dp),
                placeholder = {
                    Text("给 Svate 发送消息", color = SvateColors.TextTertiary, style = MaterialTheme.typography.bodyLarge)
                },
                enabled = !isSending,
                colors = TextFieldDefaults.colors(
                    focusedTextColor = SvateColors.TextPrimary,
                    unfocusedTextColor = SvateColors.TextPrimary,
                    disabledTextColor = SvateColors.TextTertiary,
                    cursorColor = SvateColors.Accent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                maxLines = 5,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = SvateColors.TextPrimary),
            )

            val sendScale by animateFloatAsState(
                targetValue = if (canSend) 1f else 0.9f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "send_scale",
            )
            IconButton(
                onClick = onSend,
                enabled = canSend,
                modifier = Modifier
                    .size(38.dp)
                    .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                    .clip(CircleShape)
                    .background(if (canSend) SvateColors.Accent else SvateColors.BorderStrong),
            ) {
                Text(
                    text = "↑",
                    color = if (canSend) SvateColors.TextOnAccent else SvateColors.TextTertiary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
          }
        }
    }
}

/** The Svate mark: a green disc with a serif "S". The one green identity anchor, reused for
 *  assistant turns, the thinking row, and the empty state. */
@Composable
private fun SvateAvatar(
    size: androidx.compose.ui.unit.Dp,
    textStyle: androidx.compose.ui.text.TextStyle,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(SvateColors.Accent),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "S",
            color = SvateColors.TextOnAccent,
            style = textStyle,
            fontFamily = SvateSerif,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Live "running" panel rendered as a trailing list item: the Svate avatar, a black pulsing
 * dot + phase label, then an execution timeline. The timeline turns the model's recent tool
 * steps into nodes — solid = done, pulsing ring = current, hollow = upcoming, red = failed —
 * in a black-and-white treatment so green stays reserved for identity and actions.
 */
@Composable
private fun ThinkingPanel(phaseRaw: String, lines: List<String>) {
    val phase = when (phaseRaw) {
        "starting" -> "启动中"
        "thinking" -> "思考中"
        "acting" -> "执行中"
        "awaiting_user" -> "等待你回应"
        "" -> "处理中"
        else -> phaseRaw
    }
    val infinite = rememberInfiniteTransition(label = "think")
    val pulse by infinite.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SvateAvatar(size = 30.dp, textStyle = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer { alpha = pulse }
                    .clip(CircleShape)
                    .background(SvateColors.LoadingInk),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = phase,
                style = MaterialTheme.typography.bodyMedium,
                color = SvateColors.TextSecondary,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.width(8.dp))
            TypingDots()
        }
        val recent = lines.takeLast(5)
        if (recent.isNotEmpty()) {
            Column(modifier = Modifier.padding(start = 42.dp).fillMaxWidth()) {
                recent.forEachIndexed { index, raw ->
                    val status = when {
                        raw.startsWith("✅") -> "done"
                        raw.startsWith("❌") -> "fail"
                        raw.startsWith("▶") -> "active"
                        else -> "note"
                    }
                    val isTool = status != "note"
                    val text = raw.removePrefix("▶").removePrefix("✅").removePrefix("❌").trim()
                    val isLast = index == recent.lastIndex
                    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                        Column(
                            modifier = Modifier.width(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            TimelineNode(status = status, pulse = pulse)
                            if (!isLast) {
                                Box(
                                    modifier = Modifier
                                        .width(1.5.dp)
                                        .weight(1f)
                                        .background(SvateColors.TimelineLine),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = text,
                            style = if (isTool) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                            color = when (status) {
                                "active" -> SvateColors.TextPrimary
                                "fail" -> SvateColors.Danger
                                else -> SvateColors.TextSecondary
                            },
                            fontFamily = if (isTool) FontFamily.Monospace else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = if (isLast) 0.dp else 11.dp),
                        )
                    }
                }
            }
        }
    }
}

/** A single execution-timeline node, sized/styled by step status (black-and-white). */
@Composable
private fun TimelineNode(status: String, pulse: Float) {
    when (status) {
        "active" -> Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(11.dp)
                .graphicsLayer { alpha = 0.5f + 0.5f * pulse }
                .clip(CircleShape)
                .background(SvateColors.Surface)
                .border(2.dp, SvateColors.TimelineActive, CircleShape),
        )
        "fail" -> Box(
            modifier = Modifier.padding(top = 3.dp).size(9.dp).clip(CircleShape).background(SvateColors.Danger),
        )
        "done" -> Box(
            modifier = Modifier.padding(top = 3.dp).size(9.dp).clip(CircleShape).background(SvateColors.TimelineDone),
        )
        else -> Box(
            modifier = Modifier.padding(top = 4.dp).size(7.dp).clip(CircleShape).background(SvateColors.TimelinePending),
        )
    }
}

/** A staged-attachment chip: image/PDF thumbnail or a doc badge, with a remove button. */
@Composable
private fun AttachmentChip(att: Attachment, onRemove: () -> Unit) {
    Box {
        Row(
            modifier = Modifier
                .clip(SvateShape.Small)
                .background(SvateColors.Surface)
                .border(1.dp, SvateColors.Border, SvateShape.Small)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            val thumb = att.thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb,
                    contentDescription = att.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(7.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(SvateColors.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (att.kind == "pdf") "PDF" else if (att.kind == "text") "TXT" else "FILE",
                        style = MaterialTheme.typography.labelSmall,
                        color = SvateColors.AccentDeep,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(
                text = att.name,
                style = MaterialTheme.typography.labelMedium,
                color = SvateColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 120.dp),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(18.dp)
                .clip(CircleShape)
                .background(SvateColors.UserBubble)
                .bouncyClickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "移除",
                tint = SvateColors.TextOnDark,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        repeat(3) { index ->
            // FastOutSlowInEasing gives each dot a non-linear rise/fall; the staggered
            // delay makes the three dots ripple instead of blinking in unison.
            val t by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 620, delayMillis = index * 140, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$index",
            )
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        translationY = -7f * t
                        val s = 0.7f + 0.5f * t
                        scaleX = s
                        scaleY = s
                    }
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(SvateColors.LoadingInk.copy(alpha = 0.28f + 0.6f * t)),
            )
        }
    }
}

/** Inline **bold** / `code` pattern, compiled once instead of per line per recomposition. */
private val MARKDOWN_INLINE_PATTERN = Regex("""(\*\*(.+?)\*\*)|(`(.+?)`)""")

/**
 * Simple Markdown-to-AnnotatedString parser.
 * Supports **bold**, `inline code`, and `-`/`•` lists.
 *
 * Not @Composable (it calls no composables); callers wrap it in remember(text) so the
 * AnnotatedString is rebuilt only when the message text changes, not on every recomposition.
 */
private fun buildMarkdownAnnotatedString(text: String) = buildAnnotatedString {
    val lines = text.split("\n")
    lines.forEachIndexed { lineIdx, line ->
        // List prefix
        val (prefix, rest) = if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("•")) {
            "  • " to line.trimStart().removePrefix("- ").removePrefix("•")
        } else {
            "" to line
        }
        if (prefix.isNotEmpty()) append(prefix)

        // Parse inline **bold** and `code`
        var lastEnd = 0
        MARKDOWN_INLINE_PATTERN.findAll(rest).forEach { match ->
            if (match.range.first > lastEnd) append(rest.substring(lastEnd, match.range.first))
            when {
                match.groupValues[1].isNotEmpty() -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(match.groupValues[2])
                    pop()
                }
                match.groupValues[3].isNotEmpty() -> {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = SvateColors.Divider))
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

