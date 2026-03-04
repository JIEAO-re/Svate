package com.immersive.ui.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 精简的 UI 节点信息，从 AccessibilityNodeInfo 中提取
 */
data class UiNode(
    val index: Int,
    val className: String,
    val text: String,
    val contentDesc: String,
    val packageName: String,
    val bounds: Rect,
    val isClickable: Boolean,
    val isScrollable: Boolean,
    val isEditable: Boolean,
    val isFocused: Boolean,
    val isChecked: Boolean,
    val viewIdResourceName: String,
    val isVisibleToUser: Boolean = true,
    val isWithinScreen: Boolean = true,
)

/**
 * 将 AccessibilityNodeInfo 树解析为结构化的 UiNode 列表，
 * 并生成适合注入 Gemini Prompt 的文本描述。
 */
object UiTreeParser {

    /**
     * 从根节点遍历整棵 UI 树，提取所有有意义的节点
     */
    fun parse(root: AccessibilityNodeInfo?): List<UiNode> {
        if (root == null) return emptyList()
        val nodes = mutableListOf<UiNode>()
        var index = 0
        val screenBounds = Rect()
        root.getBoundsInScreen(screenBounds)
        traverseNode(root, nodes, indexCounter = { index++ }, screenBounds = screenBounds)
        return nodes
    }

    /**
     * 将 UiNode 列表格式化为 Gemini 可理解的文本。
     * 只保留有交互意义的节点（可点击、可滚动、可编辑、有文本）。
     */
    fun formatForPrompt(nodes: List<UiNode>, maxNodes: Int = 60): String {
        val meaningful = nodes.filter { node ->
            node.isClickable || node.isScrollable || node.isEditable ||
                node.text.isNotBlank() || node.contentDesc.isNotBlank()
        }

        val selected = meaningful.take(maxNodes)

        if (selected.isEmpty()) return "（未能读取到 UI 节点信息）"

        val sb = StringBuilder()
        sb.appendLine("屏幕 UI 节点列表（共 ${selected.size} 个关键节点）：")
        for (node in selected) {
            val typeShort = node.className.substringAfterLast(".")
            val label = buildString {
                if (node.text.isNotBlank()) append("\"${node.text}\"")
                if (node.contentDesc.isNotBlank()) {
                    if (isNotEmpty()) append(" ")
                    append("desc=\"${node.contentDesc}\"")
                }
            }.ifBlank { "(无文字)" }

            val attrs = buildList {
                if (node.isClickable) add("可点击")
                if (node.isScrollable) add("可滚动")
                if (node.isEditable) add("可输入")
                if (node.isFocused) add("已聚焦")
                if (node.isChecked) add("已选中")
            }.joinToString(",")

            val bounds = node.bounds
            val pkg = if (node.packageName.isBlank()) "" else " pkg=${node.packageName}"
            sb.appendLine(
                "[${node.index}] $typeShort $label bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] $attrs$pkg"
            )
        }
        return sb.toString()
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        result: MutableList<UiNode>,
        indexCounter: () -> Int,
        depth: Int = 0,
        screenBounds: Rect,
    ) {
        // 防止 UI 树过深导致 StackOverflowError
        if (depth > 25) return

        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // 过滤不可见或极小的节点
            if (bounds.width() > 0 && bounds.height() > 0) {
                val isVisible = node.isVisibleToUser
                val isWithinScreen = bounds.right > screenBounds.left &&
                    bounds.left < screenBounds.right &&
                    bounds.bottom > screenBounds.top &&
                    bounds.top < screenBounds.bottom
                result += UiNode(
                    index = indexCounter(),
                    className = node.className?.toString().orEmpty(),
                    text = node.text?.toString().orEmpty().take(100),
                    contentDesc = node.contentDescription?.toString().orEmpty().take(100),
                    packageName = node.packageName?.toString().orEmpty(),
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isScrollable = node.isScrollable,
                    isEditable = node.isEditable,
                    isFocused = node.isFocused,
                    isChecked = @Suppress("DEPRECATION") node.isChecked,
                    viewIdResourceName = node.viewIdResourceName.orEmpty(),
                    isVisibleToUser = isVisible,
                    isWithinScreen = isWithinScreen,
                )
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                traverseNode(child, result, indexCounter, depth + 1, screenBounds)
            }
        } catch (_: Exception) {
            // AccessibilityNodeInfo 可能已失效（stale node），安全忽略
        }
    }
}
