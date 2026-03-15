package com.immersive.ui.agent.flow

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.ActionIntent
import com.immersive.ui.agent.ActionResolver
import com.immersive.ui.agent.IntentGuard
import com.immersive.ui.agent.UiNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * P1 reactive refactor: executor pipeline.
 *
 * Responsibilities:
 * - Receive actions that already passed the safety guard
 * - Execute low-level AccessibilityService operations
 * - Support coordinate-driven execution through Spatial Grounding
 * - Emit execution results for verification
 *
 * Execution priority:
 * 1. Spatial Grounding coordinates (pure visual targeting)
 * 2. SoM ID resolution
 * 3. Selector matching
 * 4. Legacy Bbox
 */
class AccessibilityMotor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val config: MotorConfig = MotorConfig(),
) {
    companion object {
        private const val TAG = "AccessibilityMotor"

        private val SEARCH_SUBMIT_KEYWORDS = listOf(
            "search", "find", "go", "query", "submit",
            "搜索", "查找", "前往", "提交",
        )
    }

    // ========== Output stream ==========
    private val _executionResults = MutableSharedFlow<ExecutionResult>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val executionResults: SharedFlow<ExecutionResult> = _executionResults.asSharedFlow()

    private val _state = MutableStateFlow(MotorState.IDLE)
    val state: StateFlow<MotorState> = _state.asStateFlow()

    // ========== Internal state ==========
    private var launchablePackages: Set<String> = emptySet()

    fun setLaunchablePackages(packages: Set<String>) {
        launchablePackages = packages
    }

    /**
     * Execute an action.
     */
    suspend fun execute(
        action: AgentAction,
        uiNodes: List<UiNode>,
        somMarkerMap: Map<Int, UiNode> = emptyMap(),
    ): ExecutionResult {
        val service = AgentAccessibilityService.instance
        if (service == null) {
            val result = ExecutionResult(
                success = false,
                action = action,
                failCode = "service_unavailable",
            )
            _executionResults.emit(result)
            return result
        }

        _state.value = MotorState.EXECUTING

        val result = when (action.intent) {
            ActionIntent.CLICK -> executeClick(action, uiNodes, somMarkerMap, service)
            ActionIntent.TYPE -> executeType(action, uiNodes, somMarkerMap, service)
            ActionIntent.SUBMIT_INPUT -> executeSubmitInput(service)
            ActionIntent.SCROLL_UP -> executeSwipe(service, "UP")
            ActionIntent.SCROLL_DOWN -> executeSwipe(service, "DOWN")
            ActionIntent.SCROLL_LEFT -> executeSwipe(service, "LEFT")
            ActionIntent.SCROLL_RIGHT -> executeSwipe(service, "RIGHT")
            ActionIntent.BACK -> executeBack(service)
            ActionIntent.HOME -> executeHome(service)
            ActionIntent.OPEN_APP -> executeOpenApp(action, service)
            ActionIntent.OPEN_INTENT -> executeOpenIntent(action)
            ActionIntent.WAIT -> executeWait(action)
            ActionIntent.FINISH -> ExecutionResult(success = true, action = action)
        }

        _state.value = MotorState.IDLE
        _executionResults.emit(result)
        return result
    }

    // ========== CLICK execution ==========
    private suspend fun executeClick(
        action: AgentAction,
        uiNodes: List<UiNode>,
        somMarkerMap: Map<Int, UiNode>,
        service: AgentAccessibilityService,
    ): ExecutionResult {
        // Priority 1: Spatial Grounding coordinates
        val spatialCoords = action.spatialCoordinates
        if (spatialCoords != null && spatialCoords.size == 2) {
            Log.d(TAG, "CLICK via Spatial Grounding: [${spatialCoords[0]}, ${spatialCoords[1]}]")
            val success = awaitCallback { callback ->
                service.performSpatialClick(spatialCoords[0], spatialCoords[1], callback)
            }
            return ExecutionResult(
                success = success,
                action = action,
                failCode = if (success) null else "spatial_click_failed",
                targetingMethod = "SPATIAL_COORDINATES",
            )
        }

        // Priority 2: SoM ID
        val somId = action.targetSomId
        if (somId != null && somId > 0) {
            val node = somMarkerMap[somId] ?: uiNodes.firstOrNull { it.index == somId }
            if (node != null) {
                val centerX = (node.bounds.left + node.bounds.right) / 2f
                val centerY = (node.bounds.top + node.bounds.bottom) / 2f
                val success = awaitCallback { callback ->
                    service.performClickAt(centerX, centerY, callback)
                }
                return ExecutionResult(
                    success = success,
                    action = action,
                    failCode = if (success) null else "som_click_failed",
                    targetingMethod = "SOM_ID",
                    resolvedNode = toNodeReceipt(node),
                )
            }
        }

        // Priority 3: Selector
        val selector = action.selector
        if (selector != null) {
            val (screenWidth, screenHeight) = service.getScreenSize()
            val resolved = ActionResolver.resolve(selector, uiNodes, screenWidth, screenHeight)
            if (resolved != null) {
                val centerX = (resolved.node.bounds.left + resolved.node.bounds.right) / 2f
                val centerY = (resolved.node.bounds.top + resolved.node.bounds.bottom) / 2f
                val success = awaitCallback { callback ->
                    service.performClickAt(centerX, centerY, callback)
                }
                return ExecutionResult(
                    success = success,
                    action = action,
                    failCode = if (success) null else "selector_click_failed",
                    targetingMethod = "SELECTOR",
                    resolvedNode = toNodeReceipt(resolved.node),
                    resolveScore = resolved.score,
                )
            }
        }

        // Priority 4: legacy bbox
        val bbox = action.targetBbox
        if (bbox != null) {
            val success = awaitCallback { callback ->
                service.performClickOnBbox(bbox, callback)
            }
            return ExecutionResult(
                success = success,
                action = action,
                failCode = if (success) null else "bbox_click_failed",
                targetingMethod = "LEGACY_BBOX",
            )
        }

        return ExecutionResult(
            success = false,
            action = action,
            failCode = "no_targeting_method",
        )
    }

    // ========== TYPE execution ==========
    private suspend fun executeType(
        action: AgentAction,
        uiNodes: List<UiNode>,
        somMarkerMap: Map<Int, UiNode>,
        service: AgentAccessibilityService,
    ): ExecutionResult {
        val text = action.inputText
        if (text.isNullOrBlank()) {
            return ExecutionResult(
                success = false,
                action = action,
                failCode = "missing_input_text",
            )
        }

        // Focus the input field first.
        val spatialCoords = action.spatialCoordinates
        if (spatialCoords != null && spatialCoords.size == 2) {
            val focusSuccess = awaitCallback { callback ->
                service.performSpatialClick(spatialCoords[0], spatialCoords[1], callback)
            }
            if (!focusSuccess) {
                return ExecutionResult(
                    success = false,
                    action = action,
                    failCode = "spatial_focus_failed",
                    targetingMethod = "SPATIAL_COORDINATES",
                )
            }
            delay(120)
        } else if (action.selector != null) {
            val (screenWidth, screenHeight) = service.getScreenSize()
            val resolved = ActionResolver.resolve(action.selector, uiNodes, screenWidth, screenHeight)
            if (resolved != null) {
                val centerX = (resolved.node.bounds.left + resolved.node.bounds.right) / 2f
                val centerY = (resolved.node.bounds.top + resolved.node.bounds.bottom) / 2f
                val focusSuccess = awaitCallback { callback ->
                    service.performClickAt(centerX, centerY, callback)
                }
                if (!focusSuccess) {
                    return ExecutionResult(
                        success = false,
                        action = action,
                        failCode = "selector_focus_failed",
                        targetingMethod = "SELECTOR",
                        resolvedNode = toNodeReceipt(resolved.node),
                    )
                }
                delay(120)
            }
        }

        // Input the text.
        val inputSuccess = service.performInput(text)
        return ExecutionResult(
            success = inputSuccess,
            action = action,
            failCode = if (inputSuccess) null else "type_failed",
        )
    }

    // ========== Other execution methods ==========
    private suspend fun executeSubmitInput(service: AgentAccessibilityService): ExecutionResult {
        val byIme = service.performSubmitInput()
        val success = if (byIme) true else {
            SEARCH_SUBMIT_KEYWORDS.any { keyword ->
                service.performClickByText(keyword)
            }
        }
        return ExecutionResult(
            success = success,
            action = AgentAction(
                intent = ActionIntent.SUBMIT_INPUT,
                targetDesc = "submit_input",
                targetBbox = null,
                elderlyNarration = "Submitting input",
                reasoning = "submit_input",
            ),
            failCode = if (success) null else "submit_failed",
        )
    }

    private suspend fun executeSwipe(service: AgentAccessibilityService, direction: String): ExecutionResult {
        val success = awaitCallback { callback ->
            service.performSwipe(direction, callback)
        }
        return ExecutionResult(
            success = success,
            action = AgentAction(
                intent = ActionIntent.valueOf("SCROLL_$direction"),
                targetDesc = "scroll_$direction",
                targetBbox = null,
                elderlyNarration = "Scrolling $direction",
                reasoning = "scroll",
            ),
            failCode = if (success) null else "swipe_failed",
        )
    }

    private fun executeBack(service: AgentAccessibilityService): ExecutionResult {
        val success = service.performBack()
        return ExecutionResult(
            success = success,
            action = AgentAction(
                intent = ActionIntent.BACK,
                targetDesc = "back",
                targetBbox = null,
                elderlyNarration = "Going back",
                reasoning = "back",
            ),
            failCode = if (success) null else "back_failed",
        )
    }

    private fun executeHome(service: AgentAccessibilityService): ExecutionResult {
        val success = service.performHome()
        return ExecutionResult(
            success = success,
            action = AgentAction(
                intent = ActionIntent.HOME,
                targetDesc = "home",
                targetBbox = null,
                elderlyNarration = "Going home",
                reasoning = "home",
            ),
            failCode = if (success) null else "home_failed",
        )
    }

    private fun executeOpenApp(action: AgentAction, service: AgentAccessibilityService): ExecutionResult {
        val pkg = action.packageName
        if (pkg.isNullOrBlank()) {
            return ExecutionResult(
                success = false,
                action = action,
                failCode = "missing_package",
            )
        }

        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            return ExecutionResult(
                success = false,
                action = action,
                failCode = "launch_intent_not_found",
            )
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)

        return ExecutionResult(
            success = true,
            action = action,
            launchedPackage = pkg,
        )
    }

    private fun executeOpenIntent(action: AgentAction): ExecutionResult {
        val spec = action.intentSpec
        if (spec == null) {
            return ExecutionResult(
                success = false,
                action = action,
                failCode = "missing_intent_spec",
            )
        }

        return try {
            val intent = IntentGuard.buildIntent(spec, action.packageName)
            context.startActivity(intent)
            ExecutionResult(success = true, action = action)
        } catch (e: Exception) {
            ExecutionResult(
                success = false,
                action = action,
                failCode = "intent_launch_failed",
            )
        }
    }

    private suspend fun executeWait(action: AgentAction): ExecutionResult {
        delay(config.waitDurationMs)
        return ExecutionResult(success = true, action = action)
    }

    // ========== Helper methods ==========
    private suspend fun awaitCallback(
        register: ((Boolean) -> Unit) -> Unit,
    ): Boolean {
        return withTimeoutOrNull(config.actionTimeoutMs) {
            suspendCancellableCoroutine<Boolean> { cont ->
                register { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        } ?: false
    }

    private fun toNodeReceipt(node: UiNode): NodeReceipt {
        return NodeReceipt(
            text = node.text.take(80),
            contentDesc = node.contentDesc.take(80),
            resourceId = node.viewIdResourceName,
            className = node.className,
            packageName = node.packageName,
        )
    }
}

// ========== Data classes ==========

data class MotorConfig(
    val actionTimeoutMs: Long = 3_000L,
    val waitDurationMs: Long = 1_500L,
)

enum class MotorState {
    IDLE,
    EXECUTING,
}

data class ExecutionResult(
    val success: Boolean,
    val action: AgentAction,
    val failCode: String? = null,
    val targetingMethod: String? = null,
    val resolvedNode: NodeReceipt? = null,
    val resolveScore: Int? = null,
    val launchedPackage: String? = null,
)

data class NodeReceipt(
    val text: String,
    val contentDesc: String,
    val resourceId: String,
    val className: String,
    val packageName: String,
)
