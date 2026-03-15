package com.immersive.ui.agent

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
import android.util.Log
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.immersive.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.coroutines.resume

/**
 * Foreground service dedicated to agent mode that owns the MediaProjection and provides screenshots.
 *
 * Android 14+ requires MediaProjection to run inside a foreground service that declares
 * foregroundServiceType="mediaProjection".
 */
class AgentCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    private var lastCaptureAtMs: Long = 0L
    private val imageListenerHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData = getResultDataFromIntent(intent)

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
                startProjection(resultCode, resultData)
                instance = this
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopProjection()
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ================================================================
    // Screen capture
    // ================================================================

    private fun startProjection(resultCode: Int, resultData: Intent) {
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, resultData)

            val metrics = resources.displayMetrics
            screenWidth = max(720, metrics.widthPixels)
            screenHeight = max(1280, metrics.heightPixels)

            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            virtualDisplay = projection?.createVirtualDisplay(
                "agent-capture",
                screenWidth, screenHeight, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null,
            )
        } catch (t: Throwable) {
            Log.e(TAG, "startProjection failed", t)
            stopProjection()
        }
    }

    private fun stopProjection() {
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "virtualDisplay release failed", t)
        }
        virtualDisplay = null
        try {
            imageReader?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "imageReader close failed", t)
        }
        imageReader = null
        try {
            projection?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "projection stop failed", t)
        }
        projection = null
    }

    /**
     * Capture the current screen and return a Base64-encoded JPEG.
     *
     * Reliability guarantee: bitmap scaling and JPEG encoding run on Dispatchers.Default
     * so the main thread is never blocked and ANRs are avoided.
     */
    suspend fun captureBase64(): String? {
        val reader = imageReader ?: return null
        throttleCaptureIfNeeded()

        // VirtualDisplay may not have delivered a frame yet.
        // Retry up to MAX_ACQUIRE_RETRIES times with a short delay.
        var image: android.media.Image? = null
        for (attempt in 1..MAX_ACQUIRE_RETRIES) {
            image = awaitNextImage(reader, ACQUIRE_RETRY_DELAY_MS)
            if (image != null) break
            if (attempt < MAX_ACQUIRE_RETRIES) {
                delay(ACQUIRE_RETRY_DELAY_MS)
            }
        }
        if (image == null) {
            Log.w(TAG, "acquireLatestImage returned null after $MAX_ACQUIRE_RETRIES attempts")
            return null
        }

        // Extract a bitmap copy from Image pixels; Image must be closed on this thread.
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val imgWidth = image.width
        val imgHeight = image.height

        var rawBitmap: Bitmap? = null
        try {
            rawBitmap = Bitmap.createBitmap(
                imgWidth + rowPadding / pixelStride,
                imgHeight,
                Bitmap.Config.ARGB_8888,
            )
            rawBitmap!!.copyPixelsFromBuffer(plane.buffer)
        } catch (t: Throwable) {
            Log.e(TAG, "captureBase64: pixel copy failed", t)
            rawBitmap?.recycle()
            image.close()
            return null
        } finally {
            image.close()
        }

        // Move heavy work (crop, scale, JPEG encode) to the Default dispatcher pool.
        return withContext(Dispatchers.Default) {
            var cropped: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                cropped = Bitmap.createBitmap(rawBitmap!!, 0, 0, imgWidth, imgHeight)

                val maxDim = 800
                val scale = if (cropped!!.width >= cropped!!.height) {
                    maxDim.toFloat() / cropped!!.width
                } else {
                    maxDim.toFloat() / cropped!!.height
                }.coerceAtMost(1f)

                scaled = Bitmap.createScaledBitmap(
                    cropped!!,
                    (cropped!!.width * scale).roundToInt().coerceAtLeast(1),
                    (cropped!!.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )

                // Pool ByteArrayOutputStream instances to reuse buffers and reduce GC pressure.
                val output = getPooledBuffer()
                scaled!!.compress(Bitmap.CompressFormat.JPEG, 70, output)
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            } catch (t: Throwable) {
                Log.e(TAG, "captureBase64: encode failed", t)
                null
            } finally {
                if (scaled != null && scaled !== cropped && scaled !== rawBitmap) {
                    scaled?.recycle()
                }
                if (cropped != null && cropped !== rawBitmap) {
                    cropped?.recycle()
                }
                rawBitmap?.recycle()
            }
        }
    }

    /**
     * Capture the current screen and return raw JPEG bytes to avoid Base64 overhead.
     * Used by direct-upload paths such as GCS.
     */
    suspend fun captureBytes(): ByteArray? {
        val reader = imageReader ?: return null
        throttleCaptureIfNeeded()

        var image: android.media.Image? = null
        for (attempt in 1..MAX_ACQUIRE_RETRIES) {
            image = awaitNextImage(reader, ACQUIRE_RETRY_DELAY_MS)
            if (image != null) break
            if (attempt < MAX_ACQUIRE_RETRIES) {
                delay(ACQUIRE_RETRY_DELAY_MS)
            }
        }
        if (image == null) {
            Log.w(TAG, "captureBytes: acquireLatestImage returned null after $MAX_ACQUIRE_RETRIES attempts")
            return null
        }

        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val imgWidth = image.width
        val imgHeight = image.height

        var rawBitmap: Bitmap? = null
        try {
            rawBitmap = Bitmap.createBitmap(
                imgWidth + rowPadding / pixelStride,
                imgHeight,
                Bitmap.Config.ARGB_8888,
            )
            rawBitmap!!.copyPixelsFromBuffer(plane.buffer)
        } catch (t: Throwable) {
            Log.e(TAG, "captureBytes: pixel copy failed", t)
            rawBitmap?.recycle()
            image.close()
            return null
        } finally {
            image.close()
        }

        return withContext(Dispatchers.Default) {
            var cropped: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                cropped = Bitmap.createBitmap(rawBitmap!!, 0, 0, imgWidth, imgHeight)
                val maxDim = 800
                val scale = if (cropped!!.width >= cropped!!.height) {
                    maxDim.toFloat() / cropped!!.width
                } else {
                    maxDim.toFloat() / cropped!!.height
                }.coerceAtMost(1f)
                scaled = Bitmap.createScaledBitmap(
                    cropped!!,
                    (cropped!!.width * scale).roundToInt().coerceAtLeast(1),
                    (cropped!!.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
                val output = getPooledBuffer()
                scaled!!.compress(Bitmap.CompressFormat.JPEG, 70, output)
                output.toByteArray()
            } catch (t: Throwable) {
                Log.e(TAG, "captureBytes: encode failed", t)
                null
            } finally {
                if (scaled != null && scaled !== cropped && scaled !== rawBitmap) {
                    scaled?.recycle()
                }
                if (cropped != null && cropped !== rawBitmap) {
                    cropped?.recycle()
                }
                rawBitmap?.recycle()
            }
        }
    }

    /**
     * Prefer the captureBytes path and do not generate Base64 by default;
     * only derive it from imageBytes when a compatibility path needs it.
     */
    suspend fun captureFrame(uiSignature: String): CapturedFrame? {
        val bytes = captureBytes() ?: return null
        val now = SystemClock.uptimeMillis()
        return CapturedFrame(
            frameId = "f_${now}_${(1000..9999).random()}",
            tsMs = now,
            imageBase64 = "",
            uiSignature = uiSignature.ifBlank { "unknown" },
            imageBytes = bytes,
        )
    }

    private suspend fun throttleCaptureIfNeeded() {
        val now = SystemClock.uptimeMillis()
        val delta = now - lastCaptureAtMs
        if (delta in 0 until MIN_CAPTURE_INTERVAL_MS) {
            delay(MIN_CAPTURE_INTERVAL_MS - delta)
        }
        lastCaptureAtMs = SystemClock.uptimeMillis()
    }

    private suspend fun awaitNextImage(
        reader: ImageReader,
        waitMs: Long,
    ): android.media.Image? {
        val immediate = try {
            reader.acquireLatestImage()
        } catch (t: Throwable) {
            Log.e(TAG, "acquireLatestImage immediate read failed", t)
            null
        }
        if (immediate != null) return immediate

        return withTimeoutOrNull(waitMs) {
            suspendCancellableCoroutine<android.media.Image?> { cont ->
                val listener = ImageReader.OnImageAvailableListener { source ->
                    val img = try {
                        source.acquireLatestImage()
                    } catch (_: Throwable) {
                        null
                    }
                    if (img != null && cont.isActive) {
                        source.setOnImageAvailableListener(null, null)
                        cont.resume(img)
                    }
                }
                reader.setOnImageAvailableListener(listener, imageListenerHandler)
                cont.invokeOnCancellation {
                    reader.setOnImageAvailableListener(null, null)
                }
            }
        }
    }

    // ================================================================
    // Notifications
    // ================================================================

    private fun buildNotification(): Notification {
        val channelId = "agent_capture_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                channelId,
                "Agent 代理截图服务",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Svate 代理模式运行中")
            .setContentText("正在自动为您完成任务")
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
        private const val TAG = "AgentCaptureService"
        private const val MIN_CAPTURE_INTERVAL_MS = 450L
        private const val MAX_ACQUIRE_RETRIES = 5
        private const val ACQUIRE_RETRY_DELAY_MS = 300L
        /** Initial JPEG output-stream capacity (~80 KB) to reduce ByteArrayOutputStream resize copies. */
        private const val JPEG_BUFFER_INITIAL_CAPACITY = 80 * 1024

        /** Pool ByteArrayOutputStream instances to reuse buffers during high-frequency captures and avoid GC jitter. */
        private val jpegBufferPool = ThreadLocal<ByteArrayOutputStream>()
        private fun getPooledBuffer(): ByteArrayOutputStream {
            val existing = jpegBufferPool.get()
            if (existing != null) { existing.reset(); return existing }
            val fresh = ByteArrayOutputStream(JPEG_BUFFER_INITIAL_CAPACITY)
            jpegBufferPool.set(fresh)
            return fresh
        }

        private const val ACTION_START = "com.immersive.ui.agent.CAPTURE_START"
        private const val ACTION_STOP = "com.immersive.ui.agent.CAPTURE_STOP"
        private const val EXTRA_RESULT_CODE = "extra_result_code"
        private const val EXTRA_RESULT_DATA = "extra_result_data"
        private const val NOTIFICATION_ID = 12002

        @Volatile
        var instance: AgentCaptureService? = null
            private set

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, AgentCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, AgentCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
