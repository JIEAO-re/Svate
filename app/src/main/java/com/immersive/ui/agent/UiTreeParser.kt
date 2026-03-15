package com.immersive.ui.agent

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Simplified UI node data extracted from AccessibilityNodeInfo.
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
 * Parse the AccessibilityNodeInfo tree into a structured UiNode list
 * and generate a text description suitable for Gemini prompts.
 */
object UiTreeParser {

    /**
     * Traverse the full UI tree from the root node and extract meaningful nodes.
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
     * Format a UiNode list as text that Gemini can understand.
     * Keep only nodes with interaction value (clickable, scrollable, editable, or textual).
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
        // Prevent StackOverflowError when the UI tree becomes too deep.
        if (depth > 25) return

        try {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Filter out invisible or tiny nodes.
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
            // AccessibilityNodeInfo can become stale; ignore it safely.
        }
    }
}
