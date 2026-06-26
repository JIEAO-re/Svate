package com.immersive.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Result of turning a picked file into model-ready data. */
data class ProcessedAttachment(
    val name: String,
    val kind: String, // "image" | "pdf" | "text" | "other"
    val imagesBase64: List<String>,
    val text: String,
    val thumbnail: Bitmap?,
)

/**
 * Turns a picked content Uri (image / PDF / text doc) into something the model can use:
 * images become downscaled base64 JPEGs (vision input), PDFs are rendered to page images
 * via the built-in PdfRenderer (so even PDFs become vision input — no extra dependency),
 * and text files are read inline. Runs off the main thread (call from a worker).
 */
object AttachmentProcessor {
    private const val MAX_IMAGE_DIM = 1280
    private const val MAX_PDF_PAGES = 4
    private const val MAX_TEXT_CHARS = 60_000
    private const val THUMB_DIM = 160

    private val TEXT_MIMES = setOf(
        "application/json", "application/xml", "application/javascript",
        "application/x-yaml", "application/csv", "application/x-sh",
    )

    fun process(context: Context, uri: Uri): ProcessedAttachment {
        val name = queryName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty().lowercase()
        return try {
            when {
                mime.startsWith("image/") -> processImage(context, uri, name)
                mime == "application/pdf" || name.endsWith(".pdf", true) -> processPdf(context, uri, name)
                mime.startsWith("text/") || mime in TEXT_MIMES || isTextName(name) -> processText(context, uri, name)
                else -> processText(context, uri, name)
            }
        } catch (t: Throwable) {
            ProcessedAttachment(name, "other", emptyList(), "（$name：读取失败 ${t.message}）", null)
        }
    }

    private fun processImage(context: Context, uri: Uri, name: String): ProcessedAttachment {
        val full = decodeScaled(context, uri, MAX_IMAGE_DIM)
            ?: return ProcessedAttachment(name, "other", emptyList(), "（$name：无法解码图片）", null)
        val b64 = toJpegBase64(full, 82)
        val thumb = scaleToMax(full, THUMB_DIM)
        return ProcessedAttachment(name, "image", listOf(b64), "", thumb)
    }

    private fun processPdf(context: Context, uri: Uri, name: String): ProcessedAttachment {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: return ProcessedAttachment(name, "other", emptyList(), "（$name：无法打开 PDF）", null)
        val images = mutableListOf<String>()
        var thumb: Bitmap? = null
        pfd.use {
            PdfRenderer(it).use { renderer ->
                val pages = min(renderer.pageCount, MAX_PDF_PAGES)
                for (i in 0 until pages) {
                    renderer.openPage(i).use { page ->
                        // Render at ~2x then clamp to MAX_IMAGE_DIM for legibility vs. size.
                        val scale = 2
                        val w = max(1, page.width * scale)
                        val h = max(1, page.height * scale)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val clamped = scaleToMax(bmp, MAX_IMAGE_DIM)
                        images.add(toJpegBase64(clamped, 80))
                        if (i == 0) thumb = scaleToMax(clamped, THUMB_DIM)
                        if (clamped !== bmp) bmp.recycle()
                    }
                }
            }
        }
        if (images.isEmpty()) {
            return ProcessedAttachment(name, "other", emptyList(), "（$name：PDF 无可渲染页面）", null)
        }
        return ProcessedAttachment(name, "pdf", images, "", thumb)
    }

    private fun processText(context: Context, uri: Uri, name: String): ProcessedAttachment {
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(MAX_TEXT_CHARS)
            val n = stream.read(buf)
            if (n <= 0) ByteArray(0) else buf.copyOf(n)
        } ?: ByteArray(0)
        if (looksBinary(bytes)) {
            return ProcessedAttachment(name, "other", emptyList(), "（$name：二进制文件，暂不支持读取内容，仅记录文件名）", null)
        }
        val text = String(bytes, Charsets.UTF_8)
        return ProcessedAttachment(name, "text", emptyList(), text, null)
    }

    // ===== helpers =====

    private fun queryName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val n = c.getString(idx)
                        if (!n.isNullOrBlank()) return n
                    }
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { "附件" } ?: "附件"
    }

    private fun isTextName(name: String): Boolean {
        val n = name.lowercase()
        return listOf(".txt", ".md", ".json", ".csv", ".log", ".xml", ".html", ".htm", ".kt", ".java",
            ".js", ".ts", ".py", ".c", ".cpp", ".h", ".sh", ".yml", ".yaml", ".gradle", ".properties", ".ini")
            .any { n.endsWith(it) }
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        var suspicious = 0
        val sample = min(bytes.size, 2048)
        for (i in 0 until sample) {
            val v = bytes[i].toInt() and 0xFF
            if (v == 0) return true
            if (v < 0x09 || (v in 0x0E..0x1F)) suspicious++
        }
        return suspicious > sample / 16
    }

    private fun decodeScaled(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val (w, h) = bounds.outWidth to bounds.outHeight
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while (max(w, h) / sample > maxDim * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        return scaleToMax(decoded, maxDim)
    }

    private fun scaleToMax(src: Bitmap, maxDim: Int): Bitmap {
        val longest = max(src.width, src.height)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest
        val w = max(1, (src.width * ratio).roundToInt())
        val h = max(1, (src.height * ratio).roundToInt())
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    private fun toJpegBase64(bmp: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
