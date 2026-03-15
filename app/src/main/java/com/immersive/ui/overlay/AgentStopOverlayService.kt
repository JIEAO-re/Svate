package com.immersive.ui.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import com.immersive.ui.agent.AgentEventBus
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Floating overlay for agent mode with a draggable red "Stop" button.
 * Tapping it notifies MainActivity through the SharedFlow event bus to stop the agent.
 */
class AgentStopOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatView: FrameLayout? = null

    companion object {
        fun start(context: Context) {
            context.startService(Intent(context, AgentStopOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentStopOverlayService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        try {
            if (android.provider.Settings.canDrawOverlays(this)) {
                buildOverlay()
            } else {
                stopSelf()
            }
        } catch (_: Exception) {
            stopSelf()
        }
    }

    private fun buildOverlay() {
        val wm = windowManager ?: return

        // Red circular button
        val button = TextView(this).apply {
            text = "✕ 停止"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = buildRoundBackground()
            setPadding(32, 20, 32, 20)
        }

        val frame = FrameLayout(this).also { floatView = it }
        frame.addView(button)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 200
        }

        // Drag handling
        var initialX = 0; var initialY = 0
        var touchX = 0f; var touchY = 0f
        frame.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x; initialY = params.y
                    touchX = event.rawX; touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (touchX - event.rawX).toInt()
                    params.y = initialY + (event.rawY - touchY).toInt()
                    wm.updateViewLayout(frame, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // Treat very small movement as a click.
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    if (dx * dx + dy * dy < 100) {
                        AgentEventBus.requestStop()
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(frame, params)
    }

    private fun buildRoundBackground(): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 100f
            setColor(Color.parseColor("#EF4444"))
        }
    }

    override fun onDestroy() {
        floatView?.let { windowManager?.removeView(it) }
        floatView = null
        super.onDestroy()
    }
}
