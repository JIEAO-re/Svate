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
 * Agent 代理模式专用前台服务 —— 持有 MediaProjection，提供截图能力。
 *
 * Android 14+ 要求 MediaProjection 必须运行在声明了
 * foregroundServiceType="mediaProjection" 的前台 Service 中。
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

                // API 29+ 必须显式指定 foregroundServiceType
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
    // 截图
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
     * 截取当前屏幕并返回 Base64 编码的 JPEG。
     *
     * 可靠性保证：Bitmap 缩放与 JPEG 编码在 Dispatchers.Default 执行，
     * 绝不阻塞主线程，避免 ANR。
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

        // 将 Bitmap 像素拷贝从 Image 中提取出来（Image 必须在当前线程关闭）
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

        // 重量级操作（裁剪、缩放、JPEG 编码）移到 Default 线程池
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

                // 池化 ByteArrayOutputStream：复用缓冲区，减少 GC 压力
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
     * 截取当前屏幕并返回原始 JPEG ByteArray，避免 Base64 编码开销。
     * 用于 GCS 上传等直传路径。
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
     * 优先使用 captureBytes 路径，默认不生成 Base64；
     * 仅在兼容链路按需从 imageBytes 派生。
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
    // 通知
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
        /** JPEG 编码输出流初始容量（~80KB），减少 ByteArrayOutputStream 扩容拷贝 */
        private const val JPEG_BUFFER_INITIAL_CAPACITY = 80 * 1024

        /** 池化 ByteArrayOutputStream：高频截屏时复用缓冲区，避免 GC 抖动 */
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
