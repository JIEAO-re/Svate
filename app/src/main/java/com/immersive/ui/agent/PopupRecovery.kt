package com.immersive.ui.agent

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max
import kotlin.coroutines.resume

enum class PopupType(val label: String) {
    AD_POPUP("广告弹窗"),
    PERMISSION_DIALOG("权限请求弹窗"),
    SYSTEM_UPDATE("系统更新提示"),
    CRASH_DIALOG("应用崩溃弹窗"),
    UNKNOWN_POPUP("未知弹窗"),
}

data class PopupDetection(
    val type: PopupType,
    val closeButtonIndex: Int,
    val closeButtonText: String,
)

object PopupRecovery {
    private val AD_CLOSE_KEYWORDS = listOf(
        "跳过",
        "跳过广告",
        "关闭广告",
        "关闭弹窗",
        "不感兴趣",
        "不再显示",
        "skip",
    )

    private val PERMISSION_KEYWORDS = listOf(
        "允许",
        "始终允许",
        "仅在使用中允许",
        "仅此一次",
    )

    private val LATER_KEYWORDS = listOf(
        "稍后",
        "以后再说",
        "暂不更新",
        "下次再说",
        "我知道了",
        "不再提醒",
        "取消",
    )

    private val CRASH_KEYWORDS = listOf(
        "关闭应用",
        "应用无响应",
        "已停止运行",
        "close app",
        "stopped working",
    )

    fun detect(nodes: List<UiNode>): PopupDetection? {
        if (nodes.size < 3) return null

        val screenWidth = max(1, nodes.maxOfOrNull { it.bounds.right } ?: 1)
        val screenHeight = max(1, nodes.maxOfOrNull { it.bounds.bottom } ?: 1)

        // Crash handling can be from system UI, this is the only default exception.
        for (node in nodes) {
            if (!node.isClickable) continue
            val combined = "${node.text} ${node.contentDesc}".lowercase()
            if (containsAny(combined, CRASH_KEYWORDS) &&
                isReasonableCloseButton(node, screenWidth, screenHeight, allowSystemUi = true)
            ) {
                return PopupDetection(
                    type = PopupType.CRASH_DIALOG,
                    closeButtonIndex = node.index,
                    closeButtonText = node.text.ifBlank { node.contentDesc },
                )
            }
        }

        for (node in nodes) {
            if (!node.isClickable) continue
            if (isSystemUiNode(node)) continue

            val combined = "${node.text} ${node.contentDesc}".lowercase()

            if (containsAny(combined, AD_CLOSE_KEYWORDS) &&
                isReasonableCloseButton(node, screenWidth, screenHeight, allowSystemUi = false) &&
                isLikelyDismissButtonPosition(node, screenWidth, screenHeight)
            ) {
                return PopupDetection(
                    type = PopupType.AD_POPUP,
                    closeButtonIndex = node.index,
                    closeButtonText = node.text.ifBlank { node.contentDesc },
                )
            }

            if (containsAny(combined, PERMISSION_KEYWORDS) &&
                isReasonableDialogAction(node, screenWidth, screenHeight)
            ) {
                return PopupDetection(
                    type = PopupType.PERMISSION_DIALOG,
                    closeButtonIndex = node.index,
                    closeButtonText = node.text.ifBlank { node.contentDesc },
                )
            }

            if (containsAny(combined, LATER_KEYWORDS) &&
                isReasonableDialogAction(node, screenWidth, screenHeight)
            ) {
                return PopupDetection(
                    type = PopupType.SYSTEM_UPDATE,
                    closeButtonIndex = node.index,
                    closeButtonText = node.text.ifBlank { node.contentDesc },
                )
            }
        }

        return null
    }

    suspend fun dismiss(
        detection: PopupDetection,
        nodes: List<UiNode>,
        service: AgentAccessibilityService,
    ): Boolean {
        val targetNode = nodes.firstOrNull { it.index == detection.closeButtonIndex } ?: return false
        val bounds = targetNode.bounds
        val centerX = (bounds.left + bounds.right) / 2f
        val centerY = (bounds.top + bounds.bottom) / 2f

        return withTimeoutOrNull(3_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                service.performClickAt(centerX, centerY) { success ->
                    if (cont.isActive) cont.resume(success)
                }
            }
        } ?: false
    }

    private fun containsAny(content: String, keywords: List<String>): Boolean {
        return keywords.any { keyword ->
            keyword.length >= 2 && content.contains(keyword.lowercase())
        }
    }

    private fun isSystemUiNode(node: UiNode): Boolean {
        return node.packageName == AgentActionSafety.SYSTEM_UI_PACKAGE
    }

    private fun isReasonableCloseButton(
        node: UiNode,
        screenWidth: Int,
        screenHeight: Int,
        allowSystemUi: Boolean,
    ): Boolean {
        if (!allowSystemUi && isSystemUiNode(node)) return false
        val width = node.bounds.width()
        val height = node.bounds.height()
        if (width < 24 || height < 24) return false
        if (width > (screenWidth * 0.6f) || height > (screenHeight * 0.25f)) return false
        return true
    }

    private fun isReasonableDialogAction(
        node: UiNode,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        val width = node.bounds.width()
        val height = node.bounds.height()
        if (width < 30 || height < 24) return false
        if (width > (screenWidth * 0.7f) || height > (screenHeight * 0.3f)) return false
        return true
    }

    private fun isLikelyDismissButtonPosition(
        node: UiNode,
        screenWidth: Int,
        screenHeight: Int,
    ): Boolean {
        val centerX = (node.bounds.left + node.bounds.right) / 2f
        val centerY = (node.bounds.top + node.bounds.bottom) / 2f
        val nearTop = centerY < screenHeight * 0.6f
        val towardRight = centerX > screenWidth * 0.5f
        return nearTop || towardRight
    }
}
