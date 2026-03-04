package com.immersive.ui.agent.flow

import android.util.Log
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.AgentCaptureService
import com.immersive.ui.agent.CapturedFrame
import com.immersive.ui.agent.UiNode

/**
 * 观察模块：截图/帧采集与 UI tree 解析
 *
 * 从 OpenClawOrchestrator 拆分而来，负责：
 * - 获取当前屏幕 UI 节点
 * - 截图帧采集
 * - GCS 异步上传（旁路，不阻塞主流程）
 */
class ObservationModule(
    private val gcsUploader: GcsAsyncUploader,
) {
    companion object {
        private const val TAG = "ObservationModule"
    }

    /**
     * 获取无障碍服务实例（非空时可用）
     */
    fun getAccessibilityService(): AgentAccessibilityService? {
        return AgentAccessibilityService.instance
    }

    /**
     * 获取截图服务实例
     */
    fun getCaptureService(): AgentCaptureService? {
        return AgentCaptureService.instance
    }

    /**
     * 获取当前前台包名
     */
    fun getForegroundPackage(): String? {
        return AgentAccessibilityService.instance?.getForegroundPackageName()
    }

    /**
     * 获取当前 UI 节点列表
     */
    fun getUiNodes(): List<UiNode> {
        return AgentAccessibilityService.instance?.getUiNodes() ?: emptyList()
    }

    /**
     * 异步上传截图到 GCS，返回 gs:// URI（失败返回 null）
     *
     * 优先使用原始 ByteArray（跳过 Base64 解码），兼容旧路径。
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
