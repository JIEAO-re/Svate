package com.immersive.ui.agent

import java.security.MessageDigest

object FrameFingerprint {

    private val HEX = "0123456789abcdef".toCharArray()

    /** Append the two lowercase hex digits of a byte without a Formatter allocation. */
    private fun StringBuilder.appendHex(value: Int) {
        append(HEX[(value ushr 4) and 0xF])
        append(HEX[value and 0xF])
    }

    fun build(
        foregroundPackage: String?,
        uiNodes: List<UiNode>,
        imageBytes: ByteArray? = null,
        imageBase64: String = "",
    ): String {
        // Serialize bounds from Rect fields instead of Rect.toString(): the
        // explicit format is stable across platforms and keeps this code
        // runnable in JVM unit tests where Rect methods are not available.
        val uiSig = uiNodes
            .take(40)
            .joinToString("|") { node ->
                val b = node.bounds
                "${node.packageName}#${node.className}#${node.viewIdResourceName}#${node.text}#${node.contentDesc}#" +
                    "[${b.left},${b.top},${b.right},${b.bottom}]"
            }
        val imageHint = when {
            imageBytes != null && imageBytes.isNotEmpty() -> sampleBytes(imageBytes)
            imageBase64.isNotBlank() -> sampleText(imageBase64)
            else -> "no_image"
        }
        return sha256("${foregroundPackage.orEmpty()}|$uiSig|$imageHint")
    }

    /** Number of evenly spaced sample points taken across the byte array. */
    private const val SAMPLE_POINTS = 64

    /**
     * Sample bytes evenly across the whole array (plus the total length) so
     * changes anywhere in the image affect the fingerprint. JPEG headers and
     * trailers are nearly constant, so head/tail-only sampling would miss most
     * mid-image changes.
     */
    private fun sampleBytes(bytes: ByteArray): String {
        val count = minOf(SAMPLE_POINTS, bytes.size)
        val sb = StringBuilder(count * 2 + 16)
        if (count > 0) {
            // Evenly spaced indices covering [0, size - 1].
            val step = (bytes.size - 1).toDouble() / maxOf(1, count - 1)
            for (i in 0 until count) {
                val index = (i * step).toInt().coerceIn(0, bytes.size - 1)
                sb.appendHex(bytes[index].toInt() and 0xff)
            }
        }
        sb.append("#len=").append(bytes.size)
        return sb.toString()
    }

    private fun sampleText(imageBase64: String): String {
        return if (imageBase64.length <= 128) {
            imageBase64
        } else {
            imageBase64.take(64) + imageBase64.takeLast(64)
        }
    }

    private fun sha256(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.appendHex(b.toInt() and 0xff)
        }
        return sb.toString()
    }
}
