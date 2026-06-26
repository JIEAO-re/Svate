package com.immersive.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiNodePrunerTest {

    @Test
    fun prune_returnsEmptyResultForEmptyInput() {
        val result = UiNodePruner.prune(emptyList())
        assertEquals(0, result.rawCount)
        assertEquals(0, result.prunedCount)
        assertTrue(result.nodes.isEmpty())
    }

    @Test
    fun prune_dropsInvisibleOffscreenZeroAreaAndDecorativeNodes() {
        val keep = testNode(
            index = 0,
            bounds = testRect(0, 0, 200, 100),
            text = "确认",
            clickable = true,
        )
        val invisible = testNode(
            index = 1,
            bounds = testRect(0, 0, 200, 100),
            text = "hidden",
            clickable = true,
            visibleToUser = false,
        )
        val offscreen = testNode(
            index = 2,
            bounds = testRect(0, 0, 200, 100),
            text = "offscreen",
            clickable = true,
            withinScreen = false,
        )
        val zeroArea = testNode(
            index = 3,
            bounds = testRect(100, 100, 100, 200),
            text = "zero width",
            clickable = true,
        )
        val decorative = testNode(
            index = 4,
            bounds = testRect(0, 0, 50, 50),
            className = "android.widget.ImageView",
        )

        val result = UiNodePruner.prune(listOf(keep, invisible, offscreen, zeroArea, decorative))

        assertEquals(5, result.rawCount)
        assertEquals(1, result.prunedCount)
        assertEquals(listOf(0), result.nodes.map { it.index })
    }

    @Test
    fun prune_ranksInteractiveNodesFirstAndTruncatesToMaxNodes() {
        // Large text-only node: meaningful but not interactive.
        val textOnly = testNode(
            index = 0,
            bounds = testRect(0, 0, 1080, 300),
            text = "页面标题",
            className = "android.widget.TextView",
        )
        // Small clickable button with text: interactive + textual.
        val smallButton = testNode(
            index = 1,
            bounds = testRect(0, 0, 100, 50),
            text = "确定",
            clickable = true,
        )
        // Larger editable field without text: interactive, no text.
        val largeEditor = testNode(
            index = 2,
            bounds = testRect(0, 0, 400, 200),
            className = "android.widget.EditText",
            editable = true,
        )

        val result = UiNodePruner.prune(listOf(textOnly, smallButton, largeEditor), maxNodes = 2)

        assertEquals(3, result.rawCount)
        assertEquals(2, result.prunedCount)
        // Interactive nodes outrank the larger text-only node; within the
        // interactive group, the node that carries text comes first.
        assertEquals(listOf(1, 2), result.nodes.map { it.index })
    }

    @Test
    fun prune_breaksTiesByLargerArea() {
        val smallClickable = testNode(
            index = 0,
            bounds = testRect(0, 0, 50, 50),
            text = "小",
            clickable = true,
        )
        val largeClickable = testNode(
            index = 1,
            bounds = testRect(0, 0, 400, 400),
            text = "大",
            clickable = true,
        )

        val result = UiNodePruner.prune(listOf(smallClickable, largeClickable))

        assertEquals(listOf(1, 0), result.nodes.map { it.index })
    }
}
