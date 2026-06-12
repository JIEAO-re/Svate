package com.immersive.ui.agent

import java.security.MessageDigest

object FrameFingerprint {

    fun build(
        foregroundPackage: String?,
        uiNodes: List<UiNode>,
        imageBytes: ByteArray? = null,
        imageBase64: String = "",
    ): String {
        val uiSig = uiNodes
            .take(40)
            .joinToString("|") { node ->
                "${node.packageName}#${node.className}#${node.viewIdResourceName}#${node.text}#${node.contentDesc}#${node.bounds}"
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
                sb.append("%02x".format(bytes[index].toInt() and 0xff))
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
            sb.append("%02x".format(b))
        }
        return sb.toString()
    }
}
