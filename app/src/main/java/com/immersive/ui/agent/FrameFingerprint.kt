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

    private fun sampleBytes(bytes: ByteArray): String {
        val headCount = minOf(32, bytes.size)
        val tailStart = maxOf(headCount, bytes.size - 32)
        val sb = StringBuilder((headCount + (bytes.size - tailStart)) * 2)
        for (index in 0 until headCount) {
            sb.append("%02x".format(bytes[index].toInt() and 0xff))
        }
        for (index in tailStart until bytes.size) {
            sb.append("%02x".format(bytes[index].toInt() and 0xff))
        }
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
