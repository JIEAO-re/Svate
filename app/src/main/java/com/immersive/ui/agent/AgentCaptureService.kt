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
import android.view.Display
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.immersive.ui.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong
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
    /** Last capture timestamp; CAS-updated so concurrent captures throttle correctly. */
    private val lastCaptureAtMs = AtomicLong(0L)
    private val imageListenerHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * Guards the imageReader/virtualDisplay/screenWidth/screenHeight set so a capture
     * cannot read pixels out of an ImageReader that a rotation is concurrently closing
     * and replacing. The pixel copy runs off the main thread while onDisplayChanged
     * fires on imageListenerHandler, so the two genuinely race.
     */
    private val captureLock = Any()

    /**
     * Bumped every time the reader is replaced (rotation/resize). A capture samples this
     * before reading buffer bytes; if it changed after the frame was acquired, the frame
     * came from a now-closed reader with stale dimensions and is dropped instead of copied.
     */
    private var captureGeneration: Int = 0

    private var displayManager: DisplayManager? = null

    /**
     * Recreate the capture surface when the display geometry changes. The VirtualDisplay
     * and ImageReader are pinned to the dimensions captured at startProjection() time, so
     * after a rotation the mirrored frame keeps the old size while the live UI tree (and
     * every tap path, which scales against getScreenSize()) reports the rotated bounds.
     * That mismatch misaligns SoM markers and x-y taps until the surface is resized.
     */
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            resizeCaptureIfNeeded()
        }
    }

    /**
     * API 34+ requires a MediaProjection.Callback to be registered before
     * createVirtualDisplay, otherwise the framework throws IllegalStateException
     * ("Must register a callback before starting capture"). onStop also fires when
     * the user revokes the projection from the system UI, so we tear down then.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked the projection (or the system stopped it): tear down and
            // stop the service so a mediaProjection-type foreground service does not
            // linger without an active projection. stopProjection unregisters this
            // callback before projection.stop(), so this never re-enters.
            stopProjection()
            stopSelf()
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

            // A sticky restart redelivers a null intent without the projection
            // grant, leaving a useless service alive; never restart automatically.
            else -> stopSelf()
        }
        return START_NOT_STICKY
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

            // Required on API 34+ before createVirtualDisplay (see projectionCallback).
            projection?.registerCallback(projectionCallback, imageListenerHandler)

            val (width, height) = currentDisplaySize()
            synchronized(captureLock) {
                screenWidth = width
                screenHeight = height
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtualDisplay = projection?.createVirtualDisplay(
                    "agent-capture",
                    width, height, resources.displayMetrics.densityDpi,
                    // No flags: a MediaProjection virtual display already mirrors the
                    // captured screen into our ImageReader surface. VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                    // is a DisplayManager concept meant for non-projection displays; passing it
                    // here is redundant and, on some OEM ROMs (e.g. ColorOS), makes other apps
                    // render masked/blacked-out when switched to while the projection is live.
                    0,
                    imageReader?.surface, null, null,
                )
            }

            // Track rotation/resize so the surface follows the live display geometry.
            displayManager = (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).also {
                it.registerDisplayListener(displayListener, imageListenerHandler)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "startProjection failed", t)
            stopProjection()
        }
    }

    /**
     * Recreate the ImageReader and resize the VirtualDisplay to the current display size
     * when it no longer matches what we are capturing at (the rotation case). Runs on
     * imageListenerHandler. Holds captureLock for the whole swap and bumps captureGeneration
     * so an in-flight capture detects the stale reader before touching its buffer.
     */
    private fun resizeCaptureIfNeeded() {
        try {
            val (width, height) = currentDisplaySize()
            synchronized(captureLock) {
                val display = virtualDisplay ?: return
                if (width == screenWidth && height == screenHeight) return

                val oldReader = imageReader
                val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                // Detach the old surface before resizing, then attach the new one. Closing
                // the old reader last guarantees its surface is no longer the display target.
                display.surface = null
                display.resize(width, height, resources.displayMetrics.densityDpi)
                display.surface = newReader.surface

                imageReader = newReader
                screenWidth = width
                screenHeight = height
                captureGeneration++
                try {
                    oldReader?.close()
                } catch (t: Throwable) {
                    Log.w(TAG, "old imageReader close failed", t)
                }
            }
            Log.d(TAG, "capture surface resized to ${width}x$height")
        } catch (t: Throwable) {
            Log.e(TAG, "resizeCaptureIfNeeded failed", t)
        }
    }

    /**
     * Live full-screen size in pixels, matching AgentAccessibilityService.getScreenSize()
     * (WindowManager.currentWindowMetrics.bounds) so the screenshot resolution and the
     * coordinate space used by every tap path stay in lockstep. minSdk is 33, so the
     * current-window-metrics API is always available.
     */
    private fun currentDisplaySize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val bounds = wm.currentWindowMetrics.bounds
        return bounds.width() to bounds.height()
    }

    private fun stopProjection() {
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (t: Throwable) {
            Log.w(TAG, "displayListener unregister failed", t)
        }
        displayManager = null

        synchronized(captureLock) {
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
            // Invalidate any frame an in-flight capture acquired before this teardown so
            // it is dropped instead of copied out of the just-closed reader.
            captureGeneration++
        }

        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (t: Throwable) {
            Log.w(TAG, "projection unregisterCallback failed", t)
        }
        try {
            projection?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "projection stop failed", t)
        }
        projection = null
    }

    /** True only when a projection is bound and frames can actually be captured. */
    fun isProjectionActive(): Boolean = projection != null && imageReader != null

    /**
     * Capture the current screen and return a Base64-encoded JPEG.
     *
     * Reliability guarantee: bitmap scaling and JPEG encoding run on Dispatchers.Default
     * so the main thread is never blocked and ANRs are avoided.
     */
    suspend fun captureBase64(): String? {
        val bytes = captureJpegBytes() ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Capture the current screen and return raw JPEG bytes to avoid Base64 overhead.
     * Used by direct-upload paths such as GCS.
     */
    suspend fun captureBytes(): ByteArray? = captureJpegBytes()

    /**
     * Shared capture pipeline behind captureBase64/captureBytes: acquire a
     * frame, copy the pixels into a bitmap, then crop, scale, and JPEG-encode
     * on the Default dispatcher. Returns null when no frame is available.
     */
    private suspend fun captureJpegBytes(): ByteArray? {
        // Snapshot the reader and the resize generation together so the frame we acquire
        // can be matched against the reader that was current when we started.
        val reader: ImageReader
        val genAtStart: Int
        synchronized(captureLock) {
            reader = imageReader ?: return null
            genAtStart = captureGeneration
        }
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
        val acquired = image
        if (acquired == null) {
            Log.w(TAG, "acquireLatestImage returned null after $MAX_ACQUIRE_RETRIES attempts")
            return null
        }

        // Copy the pixels under captureLock so a concurrent rotation cannot close this
        // reader mid-copy (use-after-free), and read every stride/dimension from the
        // Image itself so the bitmap is self-consistent. If the generation advanced while
        // we were acquiring, the frame belongs to a replaced reader with stale dimensions:
        // drop it and let the caller's retry loop grab a correctly sized frame.
        var imgWidth = 0
        var imgHeight = 0
        val sourceBitmap: Bitmap = synchronized(captureLock) {
            if (captureGeneration != genAtStart) {
                acquired.close()
                return@synchronized null
            }
            val plane = acquired.planes[0]
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * acquired.width
            imgWidth = acquired.width
            imgHeight = acquired.height
            var bmp: Bitmap? = null
            try {
                bmp = Bitmap.createBitmap(
                    imgWidth + rowPadding / pixelStride,
                    imgHeight,
                    Bitmap.Config.ARGB_8888,
                )
                bmp.copyPixelsFromBuffer(plane.buffer)
                bmp
            } catch (t: Throwable) {
                Log.e(TAG, "captureJpegBytes: pixel copy failed", t)
                bmp?.recycle()
                null
            } finally {
                acquired.close()
            }
        } ?: return null

        // Move heavy work (crop, scale, JPEG encode) to the Default dispatcher pool.
        return withContext(Dispatchers.Default) {
            var cropped: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                cropped = Bitmap.createBitmap(sourceBitmap, 0, 0, imgWidth, imgHeight)

                val maxDim = 800
                val scale = if (cropped.width >= cropped.height) {
                    maxDim.toFloat() / cropped.width
                } else {
                    maxDim.toFloat() / cropped.height
                }.coerceAtMost(1f)

                scaled = Bitmap.createScaledBitmap(
                    cropped,
                    (cropped.width * scale).roundToInt().coerceAtLeast(1),
                    (cropped.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )

                // Pool ByteArrayOutputStream instances to reuse buffers and reduce GC pressure.
                val output = getPooledBuffer()
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, output)
                output.toByteArray()
            } catch (t: Throwable) {
                Log.e(TAG, "captureJpegBytes: encode failed", t)
                null
            } finally {
                if (scaled != null && scaled !== cropped && scaled !== sourceBitmap) {
                    scaled.recycle()
                }
                if (cropped != null && cropped !== sourceBitmap) {
                    cropped.recycle()
                }
                sourceBitmap.recycle()
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
        while (true) {
            val last = lastCaptureAtMs.get()
            val now = SystemClock.uptimeMillis()
            val delta = now - last
            if (delta in 0 until MIN_CAPTURE_INTERVAL_MS) {
                delay(MIN_CAPTURE_INTERVAL_MS - delta)
                continue
            }
            // CAS so two concurrent captures cannot both claim the same slot.
            if (lastCaptureAtMs.compareAndSet(last, now)) return
        }
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
                    if (img == null) return@OnImageAvailableListener
                    if (cont.isActive) {
                        try {
                            source.setOnImageAvailableListener(null, null)
                        } catch (_: Throwable) {
                        }
                        cont.resume(img)
                    } else {
                        // Resumed/cancelled already: close the late frame so it is not
                        // leaked. With maxImages=2, two leaked images brick capture.
                        img.close()
                    }
                }
                try {
                    reader.setOnImageAvailableListener(listener, imageListenerHandler)
                } catch (t: Throwable) {
                    // The reader was closed (e.g. a rotation replaced it) before we could
                    // attach: resume with null so the caller retries on the new reader.
                    if (cont.isActive) cont.resume(null)
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    try {
                        reader.setOnImageAvailableListener(null, null)
                    } catch (_: Throwable) {
                    }
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
