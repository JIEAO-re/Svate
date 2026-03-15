package com.immersive.ui.agent.flow

import android.util.Log
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.AgentCaptureService
import com.immersive.ui.agent.CapturedFrame
import com.immersive.ui.agent.UiNode

/**
 * Observation module: screenshot/frame capture and UI tree parsing.
 *
 * Extracted from OpenClawOrchestrator, responsible for:
 * - Fetching UI nodes from the current screen
 * - Capturing screenshot frames
 * - Uploading screenshots to GCS asynchronously without blocking the main flow
 */
class ObservationModule(
    private val gcsUploader: GcsAsyncUploader,
) {
    companion object {
        private const val TAG = "ObservationModule"
    }

    /**
     * Get the accessibility service instance when available.
     */
    fun getAccessibilityService(): AgentAccessibilityService? {
        return AgentAccessibilityService.instance
    }

    /**
     * Get the capture service instance.
     */
    fun getCaptureService(): AgentCaptureService? {
        return AgentCaptureService.instance
    }

    /**
     * Get the current foreground package name.
     */
    fun getForegroundPackage(): String? {
        return AgentAccessibilityService.instance?.getForegroundPackageName()
    }

    /**
     * Get the current UI node list.
     */
    fun getUiNodes(): List<UiNode> {
        return AgentAccessibilityService.instance?.getUiNodes() ?: emptyList()
    }

    /**
     * Upload the screenshot to GCS asynchronously and return a gs:// URI, or null on failure.
     *
     * Prefer raw ByteArray input to avoid Base64 decoding, while staying compatible with the legacy path.
     */
    suspend fun uploadScreenshotToGcs(
        imageBytes: ByteArray? = null,
        imageBase64: String? = null,
        traceId: String,
        stepIndex: Int,
    ): String? {
        val bytes = imageBytes
            ?: imageBase64?.takeIf { it.isNotBlank() }?.let {
                android.util.Base64.decode(it, android.util.Base64.DEFAULT)
            }
            ?: return null

        val uploadResult = gcsUploader.uploadSync(
            imageBytes = bytes,
            metadata = UploadMetadata(
                sessionId = traceId,
                traceId = traceId,
                frameIndex = stepIndex,
            ),
        )
        if (uploadResult.success) {
            Log.d(TAG, "Screenshot uploaded to GCS: ${uploadResult.gcsUri}")
            return uploadResult.gcsUri
        }
        return null
    }
}
