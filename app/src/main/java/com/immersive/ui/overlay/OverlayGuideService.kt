package com.immersive.ui.overlay

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.roundToInt

class OverlayGuideService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlayRoot: FrameLayout? = null
    private var lockView: RainbowLockView? = null
    private var lockLabel: TextView? = null
    private var hintBanner: TextView? = null
    private var rainbowAnimator: ValueAnimator? = null
    private var lastSignature: String = ""

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_TARGET -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val ymin = intent.getIntExtra(EXTRA_YMIN, 0).coerceIn(0, 1000)
                val xmin = intent.getIntExtra(EXTRA_XMIN, 0).coerceIn(0, 1000)
                val ymax = intent.getIntExtra(EXTRA_YMAX, 0).coerceIn(0, 1000)
                val xmax = intent.getIntExtra(EXTRA_XMAX, 0).coerceIn(0, 1000)
                val label = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { "请点这里" }
                showTargetLock(ymin, xmin, ymax, xmax, label)
            }

            ACTION_SHOW_HINT -> {
                if (!Settings.canDrawOverlays(this)) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val hint = intent.getStringExtra(EXTRA_HINT).orEmpty().ifBlank { "请向左或向右滑动继续查找" }
                showSwipeHint(hint)
            }

            ACTION_HIDE -> {
                removeOverlay()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showTargetLock(
        ymin: Int,
        xmin: Int,
        ymax: Int,
        xmax: Int,
        label: String,
    ) {
        ensureOverlayCreated()
        val root = overlayRoot ?: return
        val lock = lockView ?: return
        val text = lockLabel ?: return
        val hint = hintBanner ?: return

        hint.visibility = android.view.View.GONE
        lock.visibility = android.view.View.VISIBLE
        text.visibility = android.view.View.VISIBLE

        val (screenWidth, screenHeight) = getScreenSize()
        val left = ((xmin / 1000f) * screenWidth).roundToInt().coerceIn(0, screenWidth - 1)
        val top = ((ymin / 1000f) * screenHeight).roundToInt().coerceIn(0, screenHeight - 1)
        val right = ((xmax / 1000f) * screenWidth).roundToInt().coerceIn(left + 1, screenWidth)
        val bottom = ((ymax / 1000f) * screenHeight).roundToInt().coerceIn(top + 1, screenHeight)

        val boxWidth = (right - left).coerceAtLeast(dp(72))
        val boxHeight = (bottom - top).coerceAtLeast(dp(72))

        lock.layoutParams = FrameLayout.LayoutParams(boxWidth, boxHeight).apply {
            leftMargin = left
            topMargin = top
        }

        text.text = "👇 $label"
        text.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = left.coerceIn(0, screenWidth - dp(180))
            topMargin = if (bottom + dp(12) + dp(44) < screenHeight) {
                bottom + dp(12)
            } else {
                (top - dp(58)).coerceAtLeast(dp(8))
            }
        }

        val signature = "$left,$top,$right,$bottom,$label"
        if (signature != lastSignature) {
            lastSignature = signature
            lock.scaleX = 0.2f
            lock.scaleY = 0.2f
            lock.translationY = -dp(220).toFloat()
            lock.alpha = 0f
            lock.animate()
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .alpha(1f)
                .setDuration(700L)
                .start()
        }

        if (root.parent == null) {
            windowManager.addView(root, buildWindowLayoutParams())
        }
        ensureRainbowAnimator()
    }

    private fun showSwipeHint(message: String) {
        ensureOverlayCreated()
        val root = overlayRoot ?: return
        val lock = lockView ?: return
        val text = lockLabel ?: return
        val hint = hintBanner ?: return

        lock.visibility = android.view.View.GONE
        text.visibility = android.view.View.GONE
        hint.visibility = android.view.View.VISIBLE
        hint.text = message
        hint.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply {
            topMargin = dp(48)
        }

        if (root.parent == null) {
            windowManager.addView(root, buildWindowLayoutParams())
        }
    }

    private fun ensureOverlayCreated() {
        if (overlayRoot != null) return

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = false
            isFocusable = false
        }

        val lock = RainbowLockView(this).apply {
            visibility = android.view.View.GONE
        }

        val label = TextView(this).apply {
            visibility = android.view.View.GONE
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#CC101318"))
                cornerRadius = dp(18).toFloat()
            }
        }

        val hint = TextView(this).apply {
            visibility = android.view.View.GONE
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.parseColor("#D9333A4D"))
                cornerRadius = dp(20).toFloat()
            }
        }

        root.addView(lock)
        root.addView(label)
        root.addView(hint)

        overlayRoot = root
        lockView = lock
        lockLabel = label
        hintBanner = hint
    }

    private fun ensureRainbowAnimator() {
        if (rainbowAnimator != null) return
        val lock = lockView ?: return
        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 1800L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                lock.setRotationDegrees(animator.animatedValue as Float)
            }
            start()
        }
    }

    private fun removeOverlay() {
        rainbowAnimator?.cancel()
        rainbowAnimator = null
        overlayRoot?.let { root ->
            if (root.parent != null) {
                windowManager.removeView(root)
            }
        }
        overlayRoot = null
        lockView = null
        lockLabel = null
        hintBanner = null
        lastSignature = ""
    }

    private fun buildWindowLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun getScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = resources.displayMetrics
            metrics.widthPixels to metrics.heightPixels
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }

    companion object {
        private const val ACTION_SHOW_TARGET = "com.immersive.ui.overlay.SHOW_TARGET"
        private const val ACTION_SHOW_HINT = "com.immersive.ui.overlay.SHOW_HINT"
        private const val ACTION_HIDE = "com.immersive.ui.overlay.HIDE"

        private const val EXTRA_YMIN = "extra_ymin"
        private const val EXTRA_XMIN = "extra_xmin"
        private const val EXTRA_YMAX = "extra_ymax"
        private const val EXTRA_XMAX = "extra_xmax"
        private const val EXTRA_LABEL = "extra_label"
        private const val EXTRA_HINT = "extra_hint"

        fun showTargetLock(
            context: Context,
            ymin: Int,
            xmin: Int,
            ymax: Int,
            xmax: Int,
            label: String,
        ) {
            val intent = Intent(context, OverlayGuideService::class.java).apply {
                action = ACTION_SHOW_TARGET
                putExtra(EXTRA_YMIN, ymin)
                putExtra(EXTRA_XMIN, xmin)
                putExtra(EXTRA_YMAX, ymax)
                putExtra(EXTRA_XMAX, xmax)
                putExtra(EXTRA_LABEL, label)
            }
            context.startService(intent)
        }

        fun showSwipeHint(context: Context, hint: String) {
            val intent = Intent(context, OverlayGuideService::class.java).apply {
                action = ACTION_SHOW_HINT
                putExtra(EXTRA_HINT, hint)
            }
            context.startService(intent)
        }

        fun hideOverlay(context: Context) {
            val intent = Intent(context, OverlayGuideService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }
    }
}
