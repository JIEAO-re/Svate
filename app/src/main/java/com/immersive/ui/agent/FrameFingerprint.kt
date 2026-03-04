package com.immersive.ui.agent

import java.security.MessageDigest

object FrameFingerprint {

    fun build(
        foregroundPackage: String?,
        uiNodes: List<UiNode>,
        imageBase64: String,
    ): String {
        val uiSig = uiNodes
            .take(40)
            .joinToString("|") { node ->
                "${node.packageName}#${node.className}#${node.viewIdResourceName}#${node.text}#${node.contentDesc}#${node.bounds}"
            }
        val imageHint = if (imageBase64.length <= 128) {
            imageBase64
        } else {
            imageBase64.take(64) + imageBase64.takeLast(64)
        }
        return sha256("${foregroundPackage.orEmpty()}|$uiSig|$imageHint")
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
