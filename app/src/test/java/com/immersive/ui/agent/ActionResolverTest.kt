package com.immersive.ui.agent

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ActionResolverTest {

    @Test
    fun resolve_prefersResourceIdMatch() {
        val selector = ActionSelector(
            packageName = "com.example.app",
            resourceId = "com.example.app:id/search_box",
            text = "搜索",
        )
        val nodes = listOf(
            UiNode(
                index = 0,
                className = "android.widget.TextView",
                text = "搜索",
                contentDesc = "",
                packageName = "com.example.app",
                bounds = Rect(100, 100, 300, 200),
                isClickable = true,
                isScrollable = false,
                isEditable = false,
                isFocused = false,
                isChecked = false,
                viewIdResourceName = "com.example.app:id/search_box",
            ),
        )

        val result = ActionResolver.resolve(selector, nodes, screenWidth = 1080, screenHeight = 2400)
        assertNotNull(result)
        assertEquals(8, result!!.score)
    }

    @Test
    fun resolve_returnsNullWhenBelowThreshold() {
        val selector = ActionSelector(
            packageName = "com.example.app",
            className = "android.widget.EditText",
        )
        val nodes = listOf(
            UiNode(
                index = 0,
                className = "android.widget.TextView",
                text = "hello",
                contentDesc = "",
                packageName = "com.example.app",
                bounds = Rect(100, 100, 300, 200),
                isClickable = true,
                isScrollable = false,
                isEditable = false,
                isFocused = false,
                isChecked = false,
                viewIdResourceName = "",
            ),
        )

        val result = ActionResolver.resolve(selector, nodes, screenWidth = 1080, screenHeight = 2400)
        assertNull(result)
    }
}

