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
 * Agent 无障碍服务，提供 UI 树读取与操作执行能力。
 *
 * 该服务由系统管理生命周期，通过静态 instance 给 OpenClawOrchestrator（Svate 编排器）调用。
 * 用户需要在系统设置中手动开启此服务。
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
         * 检查无障碍服务是否已启用。
         *
         * 安全加固：不仅靠静态 instance，还通过系统 Settings 查询确认服务确实在运行。
         * 防止 instance 因进程异常残留为非 null 但服务实际已断开的情况。
         */
        fun isServiceEnabled(context: Context): Boolean {
            // 快速路径：instance 为 null 则一定未启用
            if (instance == null) return false

            // 双重校验：通过系统 Settings 确认服务确实在已启用列表中
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ).orEmpty()
                val expectedComponent = ComponentName(context, AgentAccessibilityService::class.java)
                    .flattenToString()
                enabledServices.split(':').any { it.equals(expectedComponent, ignoreCase = true) }
            } catch (_: Throwable) {
                // Settings 查询失败时回退到 instance 检查
                instance != null
            }
        }

        /**
         * 引导用户前往无障碍设置页面。
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
        // 服务被中断。
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ================================================================
    // 感知能力
    // ================================================================

    /**
     * 获取当前屏幕的 UI 树根节点。
     */
    fun getRootNode(): AccessibilityNodeInfo? {
        return rootInActiveWindow
    }

    fun getForegroundPackageName(): String? {
        return rootInActiveWindow?.packageName?.toString()
    }

    /**
     * 获取解析后的 UI 节点列表。
     */
    fun getUiNodes(): List<UiNode> {
        return UiTreeParser.parse(rootInActiveWindow)
    }

    /**
     * 获取屏幕尺寸（像素）。
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
    // 操作能力
    // ================================================================

    // ========== P1 新增：Spatial Grounding 原生坐标执行 ==========
    /**
     * 通过 Gemini Spatial Grounding 归一化坐标执行点击（坐标范围 0.0-1.0）。
     *
     * 这是 P1 视觉基座革命的核心执行路径，彻底解决游戏、小程序、Flutter 等
     * 跨端 UI 的"致盲"问题。当 Gemini 返回纯视觉定位坐标时，此方法为首选。
     *
     * @param normalizedX 归一化 X 坐标，范围 [0.0, 1.0]，0.0 = 左边缘，1.0 = 右边缘
     * @param normalizedY 归一化 Y 坐标，范围 [0.0, 1.0]，0.0 = 上边缘，1.0 = 下边缘
     * @param callback 执行结果回调
     */
    fun performSpatialClick(
        normalizedX: Float,
        normalizedY: Float,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        // 校验归一化坐标范围
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
     * 通过 Spatial Grounding 坐标数组执行点击。
     *
     * @param spatialCoordinates [x, y] 归一化坐标数组，范围 [0.0, 1.0]
     * @param callback 执行结果回调
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
     * 通过 Spatial Grounding 坐标执行长按手势。
     *
     * @param normalizedX 归一化 X 坐标，范围 [0.0, 1.0]
     * @param normalizedY 归一化 Y 坐标，范围 [0.0, 1.0]
     * @param durationMs 长按持续时间（毫秒），默认 800ms
     * @param callback 执行结果回调
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
     * 通过 Spatial Grounding 坐标执行拖拽手势。
     *
     * @param fromX 起点归一化 X 坐标
     * @param fromY 起点归一化 Y 坐标
     * @param toX 终点归一化 X 坐标
     * @param toY 终点归一化 Y 坐标
     * @param durationMs 拖拽持续时间（毫秒）
     * @param callback 执行结果回调
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
     * 通过比例坐标在屏幕点击（坐标范围 0-1000）。
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
     * 在精确像素坐标点击。
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
     * 通过 UI 树查找并点击包含指定文本的节点。
     */
    fun performClickByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (result) return true
            }
            // 若节点自身不可点，尝试点击其父节点。
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable) {
                    val result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (result) return true
                }
                parent = parent.parent
                depth++
            }
        }
        return false
    }

    /**
     * 执行滑动手势。
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

        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                callback?.invoke(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                callback?.invoke(false)
            }
        }, null)
    }

    /**
     * 在当前焦点输入框中输入文本。
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
     * 提交当前输入（先尝试 IME 回车，再尝试点击“搜索/前往/Go”等按钮）。
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
            if (performClickByText(text)) return true
        }
        return false
    }

    /**
     * 执行返回。
     */
    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * 回到桌面。
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

