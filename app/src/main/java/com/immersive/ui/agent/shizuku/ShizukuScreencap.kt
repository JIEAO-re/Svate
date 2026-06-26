package com.immersive.ui.agent.shizuku

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Captures the real screen via the privileged `screencap` shell command (run through
 * Shizuku).
 *
 * Unlike MediaProjection, `screencap` at shell/ADB privilege bypasses FLAG_SECURE on this
 * device, so it sees apps that block BOTH MediaProjection screenshots and the accessibility
 * tree — e.g. WeChat. The PNG is written to shared storage (the privileged uid-2000 process
 * can write /sdcard), read back by the app (which holds All-files access), downscaled, and
 * returned as a base64 JPEG for the model's vision. No MediaProjection consent dialog needed.
 */
object ShizukuScreencap {

    // A hidden file at the storage root: the privileged process can always write here, and
    // the app can read/overwrite it with MANAGE_EXTERNAL_STORAGE.
    private const val CAP_PATH = "/sdcard/.svate_screencap.png"

    /** True when Shizuku is reachable and authorized, so a privileged screenshot is possible. */
    fun isAvailable(): Boolean = ShizukuManager.isAvailable() && ShizukuManager.hasPermission()

    /**
     * Capture the current screen and return it as a base64 JPEG, or null when Shizuku is
     * unavailable or the capture failed.
     *
     * [maxDim] bounds the longest side. It defaults high (4000) so a normal phone screen is
     * NOT downscaled: the image stays at the real screen resolution, so a pixel coordinate
     * the model reads off the image maps 1:1 to the screen coordinate the tap tool expects.
     * (Downscaling would shift every tap.) [quality] is the JPEG quality.
     */
    suspend fun captureBase64(maxDim: Int = 4000, quality: Int = 72): String? {
        if (!isAvailable()) return null
        return withContext(Dispatchers.Default) {
            var raw: Bitmap? = null
            var scaled: Bitmap? = null
            try {
                // screencap writes the PNG to the file; stdout stays empty. exit=0 = success.
                val res = ShizukuManager.exec("screencap -p $CAP_PATH")
                if (!res.ok) return@withContext null
                val file = File(CAP_PATH)
                if (!file.exists() || file.length() == 0L) return@withContext null
                raw = BitmapFactory.decodeFile(CAP_PATH) ?: return@withContext null
                val longest = max(raw.width, raw.height)
                val scale = if (longest > maxDim) maxDim.toFloat() / longest else 1f
                scaled = if (scale < 1f) {
                    Bitmap.createScaledBitmap(
                        raw,
                        (raw.width * scale).roundToInt().coerceAtLeast(1),
                        (raw.height * scale).roundToInt().coerceAtLeast(1),
                        true,
                    )
                } else {
                    raw
                }
                val baos = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
            } catch (t: Throwable) {
                null
            } finally {
                if (scaled != null && scaled !== raw) scaled.recycle()
                raw?.recycle()
                // Best-effort cleanup; harmless if it fails (the path is reused next time).
                runCatching { File(CAP_PATH).delete() }
            }
        }
    }
}
