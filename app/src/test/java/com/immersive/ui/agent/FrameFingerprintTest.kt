package com.immersive.ui.agent

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FrameFingerprintTest {

    private fun baselineBytes(size: Int = 4096): ByteArray =
        ByteArray(size) { i -> (i % 251).toByte() }

    private fun sampleNodes(): List<UiNode> = listOf(
        testNode(
            index = 0,
            bounds = testRect(0, 0, 1080, 200),
            text = "搜索",
            className = "android.widget.EditText",
        ),
        testNode(
            index = 1,
            bounds = testRect(0, 200, 1080, 400),
            text = "购物车",
            clickable = true,
        ),
    )

    @Test
    fun build_isStableForIdenticalInput() {
        val first = FrameFingerprint.build("com.example.app", sampleNodes(), baselineBytes())
        val second = FrameFingerprint.build("com.example.app", sampleNodes(), baselineBytes())
        assertEquals(first, second)
    }

    @Test
    fun build_changesWhenMidImageBytesChange() {
        // Head (first 64 bytes) and tail (last 64 bytes) stay identical; only
        // a mid-image region changes. Equidistant sampling must still pick up
        // the difference - head/tail-only sampling would miss it entirely.
        val original = baselineBytes()
        val mutated = baselineBytes()
        for (i in 1000 until 3000) {
            mutated[i] = (mutated[i].toInt() xor 0x5A).toByte()
        }
        // Sanity: the regions a head/tail-only sampler would look at are unchanged.
        assertArrayEquals(original.copyOfRange(0, 64), mutated.copyOfRange(0, 64))
        assertArrayEquals(
            original.copyOfRange(original.size - 64, original.size),
            mutated.copyOfRange(mutated.size - 64, mutated.size),
        )

        val first = FrameFingerprint.build("com.example.app", sampleNodes(), original)
        val second = FrameFingerprint.build("com.example.app", sampleNodes(), mutated)
        assertNotEquals(first, second)
    }

    @Test
    fun build_changesWhenForegroundPackageChanges() {
        val bytes = baselineBytes()
        val first = FrameFingerprint.build("com.example.app", sampleNodes(), bytes)
        val second = FrameFingerprint.build("com.other.app", sampleNodes(), bytes)
        assertNotEquals(first, second)
    }

    @Test
    fun build_changesWhenUiNodeTextChanges() {
        val bytes = baselineBytes()
        val first = FrameFingerprint.build("com.example.app", sampleNodes(), bytes)
        val changed = listOf(
            sampleNodes()[0],
            testNode(
                index = 1,
                bounds = testRect(0, 200, 1080, 400),
                text = "我的订单",
                clickable = true,
            ),
        )
        val second = FrameFingerprint.build("com.example.app", changed, bytes)
        assertNotEquals(first, second)
    }

    @Test
    fun build_changesWhenNodeBoundsChange() {
        val bytes = baselineBytes()
        val first = FrameFingerprint.build("com.example.app", sampleNodes(), bytes)
        val moved = listOf(
            sampleNodes()[0],
            testNode(
                index = 1,
                bounds = testRect(0, 600, 1080, 800),
                text = "购物车",
                clickable = true,
            ),
        )
        val second = FrameFingerprint.build("com.example.app", moved, bytes)
        assertNotEquals(first, second)
    }
}
