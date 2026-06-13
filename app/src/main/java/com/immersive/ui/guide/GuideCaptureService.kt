package com.immersive.ui.guide

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import com.immersive.ui.R
import com.immersive.ui.overlay.OverlayGuideService
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class GuideCaptureService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    // Touched from both the main thread (capture guard) and the worker thread
    // (reset + direction/miss bookkeeping); @Volatile gives the needed visibility.
    @Volatile private var isAnalyzing = false
    @Volatile private var preferDirection = "LEFT"
    @Volatile private var consecutiveMisses = 0

    /**
     * Required on API 34+ before createVirtualDisplay; also fires when the user
     * revokes the projection. Tearing down here keeps capture from running blind.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked the projection: tear down and stop the service so the
            // mediaProjection-type foreground service does not linger projection-less.
            // stopProjection unregisters this callback before projection.stop().
            stopProjection()
            stopSelf()
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var width: Int = 0
    private var height: Int = 0
    private var density: Int = 0

    private var targetAppName: String = "目标应用"
    private var inferredGoal: String = ""

    private val captureRunnable = object : Runnable {
        override fun run() {
            captureAndAnalyzeFrame()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = getResultDataFromIntent(intent)
                targetAppName = intent.getStringExtra(EXTRA_TARGET_APP_NAME).orEmpty().ifBlank { "目标应用" }
                inferredGoal = intent.getStringExtra(EXTRA_INFERRED_GOAL).orEmpty()

                if (resultCode == 0 || resultData == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }

                // API 29+ must explicitly specify foregroundServiceType.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                if (!startProjection(resultCode, resultData)) {
                    // Projection failed to bind (e.g. createVirtualDisplay threw);
                    // do not run a capture loop that can never produce frames.
                    stopSelf()
                    return START_NOT_STICKY
                }
                mainHandler.removeCallbacks(captureRunnable)
                mainHandler.post(captureRunnable)
            }

            // A sticky restart redelivers a null intent without the projection grant,
            // which can never call startForeground in time; never auto-restart.
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(captureRunnable)
        worker.shutdownNow()
        stopProjection()
        OverlayGuideService.hideOverlay(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Returns true when the projection bound and a virtual display was created. */
    private fun startProjection(resultCode: Int, resultData: Intent): Boolean {
        return try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)

            // Required on API 34+ before createVirtualDisplay (see projectionCallback).
            projection?.registerCallback(projectionCallback, mainHandler)

            val metrics = resources.displayMetrics
            width = max(720, metrics.widthPixels)
            height = max(1280, metrics.heightPixels)
            density = metrics.densityDpi

            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "ui-guide-capture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null,
            )
            virtualDisplay != null
        } catch (t: Throwable) {
            android.util.Log.e("GuideCaptureService", "startProjection failed", t)
            stopProjection()
            false
        }
    }

    private fun stopProjection() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {
        }
        projection?.stop()
        projection = null
    }

    private fun captureAndAnalyzeFrame() {
        if (isAnalyzing) return
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage() ?: return

        val frameBase64 = try {
            imageToCompressedBase64(image)
        } catch (_: Exception) {
            image.close()
            return
        }
        image.close()

        isAnalyzing = true
        worker.execute {
            try {
                val result = GuideAiEngines.analyzeScreen(
                    imageBase64 = frameBase64,
                    targetAppName = targetAppName,
                    inferredGoal = inferredGoal,
                    preferredDirection = preferDirection,
                )

                if (!result.targetFound) {
                    // Flip the suggested swipe direction only after several consecutive
                    // misses instead of oscillating on every captured frame.
                    consecutiveMisses++
                    if (consecutiveMisses >= DIRECTION_FLIP_THRESHOLD) {
                        preferDirection = if (preferDirection == "LEFT") "RIGHT" else "LEFT"
                        consecutiveMisses = 0
                    }
                } else {
                    consecutiveMisses = 0
                }

                mainHandler.post {
                    applyScreenResult(result)
                }
            } finally {
                isAnalyzing = false
            }
        }
    }

    private fun applyScreenResult(result: ScreenAnalysisResult) {
        if (result.targetFound && result.bbox != null) {
            val lockLabel = if (result.enteredTargetApp) {
                "已进入${targetAppName}，继续引导"
            } else {
                "找到${targetAppName}"
            }
            OverlayGuideService.showTargetLock(
                context = this,
                ymin = result.bbox[0],
                xmin = result.bbox[1],
                ymax = result.bbox[2],
                xmax = result.bbox[3],
                label = lockLabel,
            )
        } else {
            val hintText = if (result.instruction.isBlank()) {
                "还没看到“$targetAppName”，请向${if (preferDirection == "LEFT") "左" else "右"}滑动一页。"
            } else {
                result.instruction
            }
            OverlayGuideService.showSwipeHint(this, hintText)
        }
    }

    private fun imageToCompressedBase64(image: android.media.Image): String {
        val imageWidth = image.width
        val imageHeight = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * imageWidth

        var bitmap: Bitmap? = null
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        try {
            bitmap = Bitmap.createBitmap(
                imageWidth + rowPadding / pixelStride,
                imageHeight,
                Bitmap.Config.ARGB_8888,
            )
            bitmap.copyPixelsFromBuffer(buffer)
            cropped = Bitmap.createBitmap(bitmap, 0, 0, imageWidth, imageHeight)

            val maxDimension = 800
            val scale = if (cropped.width >= cropped.height) {
                maxDimension.toFloat() / cropped.width.toFloat()
            } else {
                maxDimension.toFloat() / cropped.height.toFloat()
            }.coerceAtMost(1f)

            scaled = Bitmap.createScaledBitmap(
                cropped,
                (cropped.width * scale).roundToInt().coerceAtLeast(1),
                (cropped.height * scale).roundToInt().coerceAtLeast(1),
                true,
            )

            val output = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, output)
            val bytes = output.toByteArray()
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            // Recycle every intermediate bitmap; this path runs ~1/second, so leaking
            // three full-screen bitmaps per frame otherwise churns native memory.
            if (scaled != null && scaled !== cropped) scaled.recycle()
            if (cropped != null && cropped !== bitmap) cropped.recycle()
            bitmap?.recycle()
        }
    }

    private fun buildNotification(): Notification {
        val channelId = "guide_capture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "导航截图服务",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("导航引导运行中")
            .setContentText("每秒截图分析并更新系统悬浮高亮框")
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun getResultDataFromIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    companion object {
        private const val ACTION_START = "com.immersive.ui.guide.START"
        private const val ACTION_STOP = "com.immersive.ui.guide.STOP"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val EXTRA_TARGET_APP_NAME = "extra_target_app_name"
        private const val EXTRA_INFERRED_GOAL = "extra_inferred_goal"
        private const val NOTIFICATION_ID = 12001

        /** Consecutive not-found frames required before the swipe hint changes direction. */
        private const val DIRECTION_FLIP_THRESHOLD = 3

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            targetAppName: String,
            inferredGoal: String,
        ) {
            val intent = Intent(context, GuideCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_TARGET_APP_NAME, targetAppName)
                putExtra(EXTRA_INFERRED_GOAL, inferredGoal)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, GuideCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
