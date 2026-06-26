package com.immersive.ui.agent.loop.tools

import com.immersive.ui.agent.UiNode
import com.immersive.ui.agent.testRect
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [ToolSupport]: secret redaction, password masking, gesture bridge. */
class ToolSupportTest {

    @Test
    fun redacts_skStyleApiKey() {
        val input = "key: sk-Ef9k2M9a7MUlmkxhiuyt7ShcO0n83PCiMqnazUjWrlaGOCLt done"
        val out = ToolSupport.redactSecrets(input)
        assertFalse(out.contains("Ef9k2"))
        assertTrue(out.contains("sk-‹redacted›"))
    }

    @Test
    fun redacts_keyEmbeddedInSearchText() {
        // The exact mangled WeChat-search content from the leak incident.
        val input = "sk-Ef9k2M9a7MUlmkxhiuyt7ShcO0n83PCiMqnazUjWrlaGOCLt"
        assertFalse(ToolSupport.redactSecrets(input).contains("Ef9k2"))
    }

    @Test
    fun leaves_ordinaryTextUnchanged() {
        val input = "搜索 文件传输助手 Login bounds=[0,0,100,50]"
        assertEquals(input, ToolSupport.redactSecrets(input))
    }

    @Test
    fun shortSkLikeToken_isNotOverRedacted() {
        // "sk-" with too few chars after it is not a key; leave it alone.
        val input = "ski-jump sk-abc"
        assertEquals(input, ToolSupport.redactSecrets(input))
    }

    @Test
    fun redacts_googleApiKey() {
        val input = "key AIzaSyA1234567890abcdefGHIJKLmnopQRSTUv done"
        val out = ToolSupport.redactSecrets(input)
        assertFalse(out.contains("AIzaSyA"))
        assertTrue(out.contains("‹redacted›"))
    }

    @Test
    fun redacts_githubAndBearerTokens() {
        assertFalse(ToolSupport.redactSecrets("ghp_0123456789abcdefABCD").contains("ghp_0123"))
        val bearer = ToolSupport.redactSecrets("Authorization: Bearer abcdef0123456789XYZ")
        assertFalse(bearer.contains("abcdef0123456789XYZ"))
    }

    @Test
    fun renderNodes_masksPasswordFieldText() {
        val node = UiNode(
            index = 0,
            className = "android.widget.EditText",
            text = "hunter2",
            contentDesc = "",
            packageName = "com.app",
            bounds = testRect(0, 0, 200, 80),
            isClickable = false,
            isScrollable = false,
            isEditable = true,
            isFocused = false,
            isChecked = false,
            viewIdResourceName = "pw",
            isPassword = true,
        )
        val out = ToolSupport.renderNodes(listOf(node))
        assertFalse("password value must never reach the model", out.contains("hunter2"))
        assertTrue(out.contains("‹password›"))
        assertTrue(out.contains("password"))   // attr flag so the model can still target it
    }

    @Test
    fun awaitGesture_returnsTrue_whenCallbackFiresTrue() = runTest {
        val result = ToolSupport.awaitGesture { cb -> cb(true) }
        assertTrue(result)
    }

    @Test
    fun awaitGesture_returnsFalse_onTimeout() = runTest {
        // Callback never fires -> withTimeoutOrNull elapses (virtual time) -> false.
        val result = ToolSupport.awaitGesture(timeoutMs = 50L) { /* never resumes */ }
        assertFalse(result)
    }
}
