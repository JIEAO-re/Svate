package com.immersive.ui.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Accessibility service for the agent, providing UI tree access and action execution.
 *
 * The system owns this service lifecycle, and OpenClawOrchestrator uses the static instance.
 * The user must enable the service manually in system settings.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibility"

        data class UiChangeEvent(
            val eventType: Int,
            val packageName: String?,
            val eventTime: Long,
        )

        private val _uiChangeEvents = MutableSharedFlow<UiChangeEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val uiChangeEvents: SharedFlow<UiChangeEvent> = _uiChangeEvents.asSharedFlow()

        @Volatile
        var instance: AgentAccessibilityService? = null
            private set

        /**
         * Check whether the accessibility service is enabled.
         *
         * Hardened validation: do not rely on the static instance alone, also confirm through
         * system Settings that the service is actually running. This avoids false positives
         * when instance remains non-null after an abnormal process state.
         */
        fun isServiceEnabled(context: Context): Boolean {
            // Fast path: a null instance always means the service is disabled.
            if (instance == null) return false

            // Double-check through system Settings that the service is listed as enabled.
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                val expectedComponent = ComponentName(context, AgentAccessibilityService::class.java)
                    .flattenToString()
                enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
            } catch (_: Throwable) {
                // Fall back to the instance check if the Settings lookup fails.
                instance != null
            }
        }

        /**
         * Send the user to the accessibility settings screen.
         */
        fun openAccessibilitySettings(context: Context) {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!isSignalEvent(event.eventType)) return
        _uiChangeEvents.tryEmit(
            UiChangeEvent(
                eventType = event.eventType,
                packageName = event.packageName?.toString(),
                eventTime = event.eventTime,
            ),
        )
    }

    override fun onInterrupt() {
        // The service was interrupted.
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ================================================================
    // Perception capabilities
    // ================================================================

    /**
     * Get the root node of the current screen UI tree.
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    fun getForegroundPackageName(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    /**
     * Get the parsed list of UI nodes.
     */
    fun getUiNodes(): List<UiNode> {
        return UiTreeParser.parse(rootInActiveWindow)
    }

    /**
     * Get the screen size in pixels.
     */
    fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    // ================================================================
    // Action capabilities
    // ================================================================

    // ========== P1 addition: native Spatial Grounding coordinate execution ==========
    /**
     * Execute a tap from Gemini Spatial Grounding normalized coordinates in the [0.0, 1.0] range.
     *
     * This is the core execution path of the P1 visual foundation work. It removes the
     * "blind spot" issue for cross-platform UIs such as games, mini-programs, and Flutter.
     * When Gemini returns pure visual coordinates, this method is the preferred path.
     *
     * @param normalizedX Normalized X coordinate in [0.0, 1.0], where 0.0 is the left edge and 1.0 is the right edge.
     * @param normalizedY Normalized Y coordinate in [0.0, 1.0], where 0.0 is the top edge and 1.0 is the bottom edge.
     * @param callback Result callback.
     */
    fun performSpatialClick(
        normalizedX: Float,
        normalizedY: Float,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        // Validate the normalized coordinate range.
        if (normalizedX !in 0f..1f || normalizedY !in 0f..1f) {
            Log.w(TAG, "performSpatialClick rejected: coordinates out of range ($normalizedX, $normalizedY)")
            callback?.invoke(false)
            return
        }

        val (screenWidth, screenHeight) = getScreenSize()
        val absoluteX = normalizedX * screenWidth
        val absoluteY = normalizedY * screenHeight

        Log.d(TAG, "Spatial click: normalized($normalizedX, $normalizedY) -> absolute($absoluteX, $absoluteY)")
        performClickAt(absoluteX, absoluteY, callback)
    }

    /**
     * Execute a tap from a Spatial Grounding coordinate array.
     *
     * @param spatialCoordinates Normalized [x, y] coordinates in the [0.0, 1.0] range.
     * @param callback Result callback.
     */
    fun performSpatialClickFromArray(
        spatialCoordinates: FloatArray,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        if (spatialCoordinates.size != 2) {
            Log.w(TAG, "performSpatialClickFromArray rejected: invalid array size ${spatialCoordinates.size}")
            callback?.invoke(false)
            return
        }
        performSpatialClick(spatialCoordinates[0], spatialCoordinates[1], callback)
    }

    /**
     * Execute a long press from Spatial Grounding coordinates.
     *
     * @param normalizedX Normalized X coordinate in [0.0, 1.0].
     * @param normalizedY Normalized Y coordinate in [0.0, 1.0].
     * @param durationMs Long-press duration in milliseconds, default 800 ms.
     * @param callback Result callback.
     */
    fun performSpatialLongPress(
        normalizedX: Float,
        normalizedY: Float,
        durationMs: Long = 800L,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        if (normalizedX !in 0f..1f || normalizedY !in 0f..1f) {
            Log.w(TAG, "performSpatialLongPress rejected: coordinates out of range")
            callback?.invoke(false)
            return
        }

        val (screenWidth, screenHeight) = getScreenSize()
        val absoluteX = normalizedX * screenWidth
        val absoluteY = normalizedY * screenHeight

        if (!isSafeTouchPoint(absoluteX, absoluteY)) {
            Log.w(TAG, "performSpatialLongPress rejected: unsafe edge point")
            callback?.invoke(false)
            return
        }

        val path = Path().apply { moveTo(absoluteX, absoluteY) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        try {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback?.invoke(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false)
                }
            }, null)
            if (!dispatched) callback?.invoke(false)
        } catch (t: Throwable) {
            Log.e(TAG, "performSpatialLongPress failed", t)
            callback?.invoke(false)
        }
    }

    /**
     * Execute a drag gesture from Spatial Grounding coordinates.
     *
     * @param fromX Start normalized X coordinate.
     * @param fromY Start normalized Y coordinate.
     * @param toX End normalized X coordinate.
     * @param toY End normalized Y coordinate.
     * @param durationMs Drag duration in milliseconds.
     * @param callback Result callback.
     */
    fun performSpatialDrag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long = 500L,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        if (fromX !in 0f..1f || fromY !in 0f..1f || toX !in 0f..1f || toY !in 0f..1f) {
            Log.w(TAG, "performSpatialDrag rejected: coordinates out of range")
            callback?.invoke(false)
            return
        }

        val (screenWidth, screenHeight) = getScreenSize()
        val startX = fromX * screenWidth
        val startY = fromY * screenHeight
        val endX = toX * screenWidth
        val endY = toY * screenHeight

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        try {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback?.invoke(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false)
                }
            }, null)
            if (!dispatched) callback?.invoke(false)
        } catch (t: Throwable) {
            Log.e(TAG, "performSpatialDrag failed", t)
            callback?.invoke(false)
        }
    }

    /**
     * Tap the screen using proportional coordinates in the 0-1000 range.
     * @param bbox [ymin, xmin, ymax, xmax]
     */
    fun performClickOnBbox(bbox: IntArray, callback: ((Boolean) -> Unit)? = null) {
        val check = AgentActionSafety.validateClickBbox(bbox)
        if (!check.allowed) {
            Log.w(TAG, "performClickOnBbox rejected: ${check.reason}")
            callback?.invoke(false)
            return
        }

        val (screenWidth, screenHeight) = getScreenSize()
        val centerX = ((bbox[1] + bbox[3]) / 2f / 1000f * screenWidth)
        val centerY = ((bbox[0] + bbox[2]) / 2f / 1000f * screenHeight)
        performClickAt(centerX, centerY, callback)
    }

    /**
     * Tap at an exact pixel coordinate.
     */
    fun performClickAt(x: Float, y: Float, callback: ((Boolean) -> Unit)? = null) {
        if (!x.isFinite() || !y.isFinite()) {
            Log.w(TAG, "performClickAt rejected: invalid coordinates ($x, $y)")
            callback?.invoke(false)
            return
        }
        if (!isSafeTouchPoint(x, y)) {
            Log.w(TAG, "performClickAt rejected: unsafe edge point ($x, $y)")
            callback?.invoke(false)
            return
        }

        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()

        try {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false)
                }
            }, null)
            if (!dispatched) {
                Log.w(TAG, "dispatchGesture rejected by framework")
                callback?.invoke(false)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "dispatchGesture failed", t)
            callback?.invoke(false)
        }
    }

    /**
     * Execute a swipe gesture.
     */
    fun performSwipe(
        direction: String,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        val (screenWidth, screenHeight) = getScreenSize()
        val centerX = screenWidth / 2f
        val centerY = screenHeight / 2f
        val offsetX = screenWidth * 0.35f
        val offsetY = screenHeight * 0.35f

        val (startX, startY, endX, endY) = when (direction.uppercase()) {
            "UP", "SCROLL_UP" -> listOf(centerX, centerY + offsetY, centerX, centerY - offsetY)
            "DOWN", "SCROLL_DOWN" -> listOf(centerX, centerY - offsetY, centerX, centerY + offsetY)
            "LEFT", "SCROLL_LEFT" -> listOf(centerX + offsetX, centerY, centerX - offsetX, centerY)
            "RIGHT", "SCROLL_RIGHT" -> listOf(centerX - offsetX, centerY, centerX + offsetX, centerY)
            else -> listOf(centerX, centerY + offsetY, centerX, centerY - offsetY)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 400))
            .build()

        try {
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback?.invoke(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback?.invoke(false)
                }
            }, null)
            if (!dispatched) {
                // Fail fast instead of letting the caller wait for a timeout.
                Log.w(TAG, "performSwipe: dispatchGesture rejected by framework")
                callback?.invoke(false)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "performSwipe failed", t)
            callback?.invoke(false)
        }
    }

    /**
     * Input text into the currently focused text field.
     */
    fun performInput(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: findFirstEditableNode(root)
            ?: return false

        if (!focusedNode.isFocused) {
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Input text into the field that currently holds input focus, with no fallback.
     *
     * Unlike [performInput], this never falls back to the first editable node on the
     * page: it only writes when root.findFocus(FOCUS_INPUT) resolves to an editable
     * node. This avoids silently typing into the wrong field when nothing is focused.
     * Returns false when there is no focused editable input or the SET_TEXT action
     * is rejected, so the caller can instruct the model to tap the field first.
     */
    fun performInputStrict(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        if (!focusedNode.isEditable) return false

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Submit the current input by trying IME enter first, then search/go style buttons.
     *
     * Button fallback is intentionally strict: only directly clickable nodes whose
     * trimmed text/contentDesc exactly equals a known submit label (ignoring case)
     * are considered. No parent-climbing and no substring matching, so a stray
     * "Go" inside "Google Pay" or a clickable ancestor wrapping a risky button
     * can never be triggered.
     */
    fun performSubmitInput(): Boolean {
        val root = rootInActiveWindow ?: return false
        val focusedNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            val imeEnterAction = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            val imeOk = focusedNode.performAction(imeEnterAction)
            if (imeOk) return true
        }
        val submitTexts = listOf(
            "搜索",
            "查找",
            "前往",
            "确定",
            "完成",
            "Search",
            "Go",
            "Done",
            "Enter",
        )
        for (text in submitTexts) {
            if (performClickByExactText(text)) return true
        }
        return false
    }

    /**
     * Click a clickable node whose trimmed text or contentDesc exactly equals
     * [expected] (ignoring case). Candidates that carry any hard-blocked keyword
     * are rejected. Returns false when no safe exact match is found.
     */
    fun performClickByExactText(expected: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val target = expected.trim()
        if (target.isEmpty()) return false

        val nodes = root.findAccessibilityNodeInfosByText(target) ?: return false
        for (node in nodes) {
            if (!node.isClickable) continue

            val nodeText = node.text?.toString()?.trim().orEmpty()
            val nodeDesc = node.contentDescription?.toString()?.trim().orEmpty()
            val exactMatch = nodeText.equals(target, ignoreCase = true) ||
                nodeDesc.equals(target, ignoreCase = true)
            if (!exactMatch) continue

            // Hard safety gate: never click candidates carrying blocked keywords.
            if (AgentActionSafety.containsHardBlockedKeyword("$nodeText $nodeDesc")) {
                Log.w(TAG, "performClickByExactText rejected blocked candidate: $nodeText")
                continue
            }

            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        }
        return false
    }

    /**
     * Perform a back action.
     */
    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Return to the home screen.
     */
    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun isSafeTouchPoint(x: Float, y: Float): Boolean {
        val region = getSafeTouchRegion()
        return x in region.left..region.right &&
            y in region.top..region.bottom
    }

    private fun getSafeTouchRegion(): RectFRange {
        val (screenWidth, screenHeight) = getScreenSize()
        val guard = dp(12f)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars() or
                    WindowInsets.Type.navigationBars() or
                    WindowInsets.Type.displayCutout(),
            )
            val bounds = Rect(metrics.bounds)
            RectFRange(
                left = (insets.left + guard).coerceAtMost(bounds.width().toFloat() - guard),
                top = (insets.top + guard).coerceAtMost(bounds.height().toFloat() - guard),
                right = (bounds.width() - insets.right - guard).coerceAtLeast(guard),
                bottom = (bounds.height() - insets.bottom - guard).coerceAtLeast(guard),
            )
        } else {
            val statusApprox = dp(24f)
            val navApprox = dp(32f)
            RectFRange(
                left = guard,
                top = statusApprox + guard,
                right = (screenWidth.toFloat() - guard).coerceAtLeast(guard),
                bottom = (screenHeight.toFloat() - navApprox - guard).coerceAtLeast(guard),
            )
        }
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }

    private fun findFirstEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable && node.isVisibleToUser) {
                return node
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun isSignalEvent(type: Int): Boolean {
        return type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            type == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            type == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            type == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
    }

    private data class RectFRange(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    )
}
