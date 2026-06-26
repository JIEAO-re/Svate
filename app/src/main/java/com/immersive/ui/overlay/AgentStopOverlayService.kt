package com.immersive.ui.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import com.immersive.ui.agent.AgentEventBus
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Floating overlay shown while the agent is operating another app, with a draggable
 * "stop" pill.
 *
 * Safety: a single tap NO LONGER kills the run. Because this button floats over the
 * app the agent is driving (e.g. WeChat), a stray tap used to silently abort the task
 * and leave behind confusing state. Tapping now ARMS the button (it turns red and reads
 * "确认停止") and only a SECOND tap within a short window actually stops the agent;
 * otherwise it disarms itself. ACTION_CANCEL gestures are ignored.
 */
class AgentStopOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var floatView: FrameLayout? = null
    private var label: TextView? = null

    /** True after the first tap, while waiting for the confirming second tap. */
    private var armed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val disarmRunnable = Runnable { setArmed(false) }

    companion object {
        /** How long the armed/confirm state stays active after the first tap. */
        private const val ARM_WINDOW_MS = 2600L

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

        val pill = TextView(this).apply {
            text = idleText()
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = buildPillBackground(IDLE_COLOR)
            setPadding(40, 22, 40, 22)
        }
        label = pill

        val frame = FrameLayout(this).also { floatView = it }
        frame.addView(pill)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            // Sit lower than the status bar / app action bar so it is less likely to
            // overlap the top-right controls of the app the agent is driving.
            y = 320
        }

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
                    val dx = event.rawX - touchX; val dy = event.rawY - touchY
                    // Treat only a near-stationary release as a tap (not the end of a drag).
                    if (dx * dx + dy * dy < 100) onTap()
                    true
                }
                // A system-cancelled gesture must never be read as a confirming tap.
                else -> false
            }
        }

        wm.addView(frame, params)
    }

    /** First tap arms (shows "确认停止"); a second tap within the window actually stops. */
    private fun onTap() {
        if (armed) {
            mainHandler.removeCallbacks(disarmRunnable)
            AgentEventBus.requestStop()
        } else {
            setArmed(true)
            mainHandler.removeCallbacks(disarmRunnable)
            mainHandler.postDelayed(disarmRunnable, ARM_WINDOW_MS)
        }
    }

    private fun setArmed(value: Boolean) {
        armed = value
        label?.apply {
            text = if (value) "确认停止？" else idleText()
            background = buildPillBackground(if (value) ARMED_COLOR else IDLE_COLOR)
        }
    }

    private fun idleText(): String = "■ 停止"

    private fun buildPillBackground(color: Int): android.graphics.drawable.GradientDrawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 100f
            setColor(color)
            setStroke(2, Color.parseColor("#33000000"))
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(disarmRunnable)
        floatView?.let { windowManager?.removeView(it) }
        floatView = null
        label = null
        super.onDestroy()
    }
}

/** Resting pill color: a calm dark slate so it does not read as a hot kill-switch. */
private val IDLE_COLOR = Color.parseColor("#2B2F36")

/** Armed/confirm color: ChatGPT-style red. */
private val ARMED_COLOR = Color.parseColor("#EF4444")
