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
import androidx.compose.ui.res.painterResource
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
import com.immersive.ui.agent.AgentCaptureService
import com.immersive.ui.agent.DecisionOption
import com.immersive.ui.agent.DecisionRequest
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
import com.immersive.ui.guide.SimpleChatMessage
import com.immersive.ui.overlay.AgentStopOverlayService
import com.immersive.ui.ui.theme.UINavTheme
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    private var readyPlan by mutableStateOf<GoalChatResult?>(null)
    private var candidateApps = mutableStateListOf<AppCandidate>()
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
    // Shown once the first time the user switches the permission mode to AUTO (放行).
    private var showAutoModeNotice by mutableStateOf(false)

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

        // Scan installed apps and inject them into the AI engine.
        val apps = InstalledAppScanner.getInstalledApps(this)
        GuideAiEngines.setInstalledApps(apps)

        projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val plan = pendingPlan
            if (result.resultCode == Activity.RESULT_OK && result.data != null && plan != null) {
                if (isAgentMode) {
                    AgentCaptureService.start(this, result.resultCode, result.data!!)
                    // The ViewModel owns the agent lifecycle (sets isGuideRunning on success/failure).
                    mainViewModel.startAgent(plan)
                } else {
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
                }
                val modeLabel = if (isAgentMode) "Agent mode" else "Assist mode"
                mainViewModel.setStatusText("$modeLabel guide is running")
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
            val goal = pendingLoopGoal
            pendingLoopGoal = null
            if (goal == null) return@registerForActivityResult
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                AgentCaptureService.start(this, result.resultCode, result.data!!)
                // The service binds the projection asynchronously; the ViewModel
                // waits (bounded) for it before the loop's first observation.
                launchAgentLoopNow(goal, awaitCaptureMs = 2000L)
            } else {
                Toast.makeText(this, "未授权录屏，Agent 将仅依靠界面树运行（无截图）", Toast.LENGTH_LONG).show()
                launchAgentLoopNow(goal, awaitCaptureMs = 0L)
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
                                    val next = viewModel.togglePermissionMode()
                                    if (next == PermissionMode.AUTO && !hasSeenAutoNotice()) {
                                        showAutoModeNotice = true
                                        markAutoNoticeSeen()
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
                                // Mode toggle
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

                                // Experimental autonomous agent loop mode (coexists with the fixed pipeline).
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.mode_agent_loop),
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.weight(1f),
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (isAgentLoopMode) Color(0xFF7C3AED) else Color(0xFFE5E5E5))
                                                .bouncyClickable(enabled = !anyGuideRunning) {
                                                    val enabled = !isAgentLoopMode
                                                    isAgentLoopMode = enabled
                                                    if (enabled) isAgentMode = true
                                                }
                                                .padding(horizontal = 14.dp, vertical = 6.dp),
                                        ) {
                                            Text(
                                                text = if (isAgentLoopMode) "On" else "Off",
                                                color = if (isAgentLoopMode) Color.White else Color(0xFF6B6B80),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                        }
                                    }
                                    Text(
                                        text = stringResource(R.string.mode_agent_loop_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color(0xFF8E8EA0),
                                    )
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
                                            .background(if (isProfileExtractionEnabled) Color(0xFF10A37F) else Color(0xFFE5E5E5))
                                            .bouncyClickable { toggleProfileExtraction() }
                                            .padding(horizontal = 14.dp, vertical = 6.dp),
                                    ) {
                                        Text(
                                            text = if (isProfileExtractionEnabled) "On" else "Off",
                                            color = if (isProfileExtractionEnabled) Color.White else Color(0xFF6B6B80),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }

                                // Voice input
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

                                // About Svate
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

                // Secondary confirmation dialog before clearing data.
                if (showClearConfirm) {
                    AlertDialog(
                        onDismissRequest = { showClearConfirm = false },
                        title = { Text("Confirm Clear") },
                        text = { Text("This will clear all conversations and user preferences. This action cannot be undone.") },
                        confirmButton = {
                            TextButton(onClick = {
                                chatSessions.clear()
                                // Clears Room, SharedPreferences sessions, and the user profile.
                                mainViewModel.clearAllData()
                                startNewSession()
                                showClearConfirm = false
                            }) { Text(stringResource(R.string.action_confirm_clear), color = Color(0xFFEF4444)) }
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
                        title = { Text(stringResource(R.string.permission_auto_notice_title)) },
                        text = { Text(stringResource(R.string.permission_auto_notice_body)) },
                        confirmButton = {
                            TextButton(onClick = { showAutoModeNotice = false }) {
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
        if (text.isBlank() || isSending) return

        readyPlan = null
        candidateApps.clear()
        isSending = true
        isTyping = true
        inputText = ""
        messages += UiMessage(createId(), "user", text)

        val requestMessages = messages.map { SimpleChatMessage(role = it.role, content = it.content) }
        // Snapshot the opt-in flag on the UI thread before hopping to the executor.
        // Profile injection shares the privacy toggle with extraction: when it is off,
        // the request is byte-identical to the no-profile behavior.
        val allowProfileContext = isProfileExtractionEnabled
        ioExecutor.execute {
            try {
                // formatForPrompt returns null when the stored profile is empty,
                // so injection only happens when the toggle is on AND data exists.
                val profileContext = if (allowProfileContext) {
                    UserProfileStore.formatForPrompt(this@MainActivity)
                } else {
                    null
                }
                val response = GuideAiEngines.chatForGoal(requestMessages, profileContext)
                runOnUiThread {
                    messages += UiMessage(createId(), "assistant", response.reply)
                    speakAssistant(response.reply)

                    saveCurrentSession()
                    autoGenerateTitleIfNeeded()

                    candidateApps.clear()
                    if (response.candidates.isNotEmpty()) {
                        candidateApps.addAll(response.candidates)
                        mainViewModel.setStatusText("Please choose one app from the candidates")
                    }

                    readyPlan = if (response.readyToStart) response else null
                    if (response.readyToStart) {
                        val modeLabel = when (response.taskMode) {
                            "SEARCH" -> "Search task"
                            "RESEARCH" -> "Research task"
                            "HOMEWORK" -> "Homework assist"
                            else -> "General task"
                        }
                        mainViewModel.setStatusText("Target confirmed: ${response.targetAppName} ($modeLabel), ready to start")
                    } else if (response.candidates.isEmpty()) {
                        mainViewModel.setStatusText("Please provide more task details")
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
        // Experimental autonomous agent loop: a self-contained on-device loop that owns
        // its own capture/accessibility usage. It only needs the goal text and the
        // accessibility service; it does not go through the fixed-pipeline projection flow.
        if (isAgentLoopMode) {
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

        // Agent mode requires the accessibility service to be enabled.
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

    /** Shared tail of both loop-start paths (with or without a projection grant). */
    private fun launchAgentLoopNow(goal: String, awaitCaptureMs: Long) {
        readyPlan = null
        candidateApps.clear()
        mainViewModel.setStatusText("自主 Agent 正在运行")
        mainViewModel.startAgentLoop(goal, awaitCaptureMs)
        // Floating stop button mirrors the fixed pipeline so the loop can be stopped from any app.
        try { AgentStopOverlayService.start(this) } catch (_: Exception) {}
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
            // Stop both entry points; whichever is idle is a harmless no-op.
            mainViewModel.stopGuide()
            mainViewModel.stopAgentLoop()
            try { AgentStopOverlayService.stop(this) } catch (_: Exception) {}
            Toast.makeText(this, "Guide stopped", Toast.LENGTH_SHORT).show()
            finishSessionWithSummary()
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

    private fun hasSeenAutoNotice(): Boolean =
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE).getBoolean(KEY_AUTO_NOTICE_SEEN, false)

    private fun markAutoNoticeSeen() {
        getSharedPreferences(SETTINGS_PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_NOTICE_SEEN, true)
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
            content = "Hello, I am Svate. Tell me what you want to do.",
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

    private fun finishSessionWithSummary() {
        val session = chatSessions.find { it.id == currentSessionId } ?: return
        session.messages.clear()
        session.messages.addAll(messages.map { ChatMsg(it.role, it.content) })

        // Snapshot the opt-in flag on the UI thread before hopping to the executor.
        val allowProfileExtraction = isProfileExtractionEnabled
        ioExecutor.execute {
            // 1. Generate a summary
            val summary = ChatStorage.generateSummary(session.messages)
            if (summary.isNotBlank()) {
                session.summary = summary
            }

            if (session.isAutoTitle && session.messages.size >= 2) {
                val title = ChatStorage.generateTitle(session.messages)
                session.title = title
            }

            // 3. Extract user preferences. Opt-in only: extraction uploads conversation
            // content to the AI backend, so it must never run when the toggle is off.
            if (allowProfileExtraction) {
                UserProfileStore.extractAndMerge(this@MainActivity, session.messages)
            }

            // 4. Save
            ChatStorage.saveSessions(this@MainActivity, chatSessions.toList())

            runOnUiThread {
                // Trigger recompose
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

    companion object {
        private const val SETTINGS_PREFS = "svate_settings"
        private const val KEY_PROFILE_EXTRACTION_ENABLED = "profile_extraction_enabled"
        private const val KEY_AUTO_NOTICE_SEEN = "agent_auto_notice_seen"
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
        // ===== Top bar =====
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

            // ===== Permission mode toggle (ASK = shield, AUTO = lightning) =====
            val isAuto = permissionMode == PermissionMode.AUTO
            val permIconBg = if (isAuto) Color(0xFFFEF3C7) else Color(0xFFE8F1FC)
            val permIconTint = if (isAuto) Color(0xFFB45309) else Color(0xFF2563EB)
            val permDesc = stringResource(
                if (isAuto) R.string.permission_mode_auto_desc else R.string.permission_mode_ask_desc,
            )
            IconButton(
                onClick = onTogglePermissionMode,
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(permIconBg)
                    .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(8.dp)),
            ) {
                Icon(
                    painter = painterResource(
                        if (isAuto) R.drawable.ic_permission_auto else R.drawable.ic_permission_ask,
                    ),
                    contentDescription = permDesc,
                    tint = permIconTint,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(modifier = Modifier.width(6.dp))

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

            // Stop button (shown only while running)
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

        // ===== Status line (hidden while the agent phase banner already shows progress) =====
        if (statusText.isNotBlank() && !(isAgentMode && agentPhaseText.isNotBlank())) {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8E8EA0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F7F8).copy(alpha = 0.8f))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }

        // ===== Agent status / confirmation prompt =====
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

        // ===== Option picker for uncertain cases =====
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

        // ===== Agent loop: current phase + live narration / tool progress =====
        if (isAgentLoopMode && (agentLoopPhase.isNotBlank() || agentLoopNarration.isNotEmpty())) {
            // Show the most recent lines so the strip stays compact while the loop runs.
            val recent = agentLoopNarration.takeLast(4)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF5F3FF).copy(alpha = 0.85f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (agentLoopPhase.isNotBlank()) {
                    Text(
                        text = agentLoopPhase,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFF5B21B6),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                recent.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5B21B6),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // ===== Agent loop: pending permission request =====
        // Reuses the high-risk confirm visual style, but offers the three loop choices.
        if (pendingPermission != null) {
            val prompt = pendingPermission
            val riskLabel = when (prompt.riskClass) {
                "safe" -> stringResource(R.string.permission_risk_safe)
                "low" -> stringResource(R.string.permission_risk_low)
                "high" -> stringResource(R.string.permission_risk_high)
                else -> stringResource(R.string.permission_risk_normal)
            }
            val riskColor = when (prompt.riskClass) {
                "high" -> Color(0xFFB91C1C)
                "normal" -> Color(0xFFB45309)
                else -> Color(0xFF15803D)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFFBEB).copy(alpha = 0.9f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(R.string.permission_request_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF92400E),
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${prompt.toolName} · $riskLabel",
                    style = MaterialTheme.typography.labelMedium,
                    color = riskColor,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = prompt.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF6B6B80),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onPermissionResolved(prompt.toolCallId, PermissionDecision.GRANT_ONCE) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10A37F)),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.permission_grant_once),
                            fontWeight = FontWeight.SemiBold,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    OutlinedButton(
                        onClick = { onPermissionResolved(prompt.toolCallId, PermissionDecision.GRANT_ALWAYS) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFF10A37F)),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.permission_grant_always),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF10A37F),
                        )
                    }
                    OutlinedButton(
                        onClick = { onPermissionResolved(prompt.toolCallId, PermissionDecision.DENY) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Color(0xFFD1D5DB)),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        Text(
                            stringResource(R.string.permission_deny),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFEF4444),
                        )
                    }
                }
            }
        }

        // ===== Chat area =====
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
                            text = remember(msg.content) { buildMarkdownAnnotatedString(msg.content) },
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

                // Typing animation
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

        // ===== Candidate apps (compact list) =====
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

        // ===== Plan-ready hint =====
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
                    onClick = {
                        if (isAgentLoopMode) onStartAgentLoop(readyPlan.inferredGoal)
                        else onStartGuide(readyPlan)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isAgentLoopMode) Color(0xFF7C3AED) else Color(0xFF10A37F),
                    ),
                    contentPadding = PaddingValues(vertical = 12.dp),
                ) {
                    Text(
                        text = if (isAgentLoopMode) stringResource(R.string.agent_loop_start) else "Start Guide",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // ===== Bottom input area =====
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
            .padding(top = 16.dp, start = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        // New conversation button
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
 * Typing animation with three looping gray dots.
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

