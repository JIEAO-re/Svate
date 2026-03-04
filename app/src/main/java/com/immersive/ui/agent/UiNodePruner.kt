package com.immersive.ui.agent

/**
 * Prune UI nodes before sending to cloud:
 * - keep visible and in-screen nodes only
 * - remove purely decorative nodes
 * - cap payload size for token control
 */
object UiNodePruner {

    data class Result(
        val nodes: List<UiNode>,
        val rawCount: Int,
        val prunedCount: Int,
    )

    fun prune(
        nodes: List<UiNode>,
        maxNodes: Int = 80,
    ): Result {
        if (nodes.isEmpty()) {
            return Result(emptyList(), rawCount = 0, prunedCount = 0)
        }

        val filtered = nodes.filter { node ->
            val hasBounds = node.bounds.width() > 0 && node.bounds.height() > 0
            val meaningful = node.isClickable ||
                node.isEditable ||
                node.isScrollable ||
                node.text.isNotBlank() ||
                node.contentDesc.isNotBlank()
            hasBounds &&
                node.isVisibleToUser &&
                node.isWithinScreen &&
                meaningful
        }

        val ranked = filtered.sortedWith(
            compareByDescending<UiNode> { it.isClickable || it.isEditable }
                .thenByDescending { it.text.isNotBlank() || it.contentDesc.isNotBlank() }
                .thenByDescending { it.bounds.width() * it.bounds.height() },
        )
        val selected = ranked.take(maxNodes)
        return Result(
            nodes = selected,
            rawCount = nodes.size,
            prunedCount = selected.size,
        )
    }
}
