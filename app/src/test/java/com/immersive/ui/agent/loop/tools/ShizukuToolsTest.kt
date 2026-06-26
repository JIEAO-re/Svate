package com.immersive.ui.agent.loop.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Shizuku shell-safety helpers. The typed admin tools embed
 * package ids / permission names / setting keys into `sh -c` command lines, so a
 * regression in [isSafeToken] or [shellQuote] is a privileged shell-injection sink.
 */
class ShizukuToolsTest {

    @Test
    fun isSafeToken_acceptsPackageAndPermissionTokens() {
        assertTrue(isSafeToken("com.tencent.mm"))
        assertTrue(isSafeToken("android.permission.CAMERA"))
        assertTrue(isSafeToken("screen_brightness"))
        assertTrue(isSafeToken("a-b_c.d123"))
    }

    @Test
    fun isSafeToken_rejectsEmptyAndShellMetacharacters() {
        assertFalse(isSafeToken(""))
        assertFalse(isSafeToken("a b"))            // space
        assertFalse(isSafeToken("pkg;rm -rf /"))   // command separator
        assertFalse(isSafeToken("\$(reboot)"))     // command substitution
        assertFalse(isSafeToken("a&&b"))
        assertFalse(isSafeToken("a|b"))
        assertFalse(isSafeToken("a`b`"))
        assertFalse(isSafeToken("a/b"))            // path separator not allowed
        assertFalse(isSafeToken("a'b"))            // quote
    }

    @Test
    fun shellQuote_wrapsInSingleQuotes() {
        assertEquals("'hello'", shellQuote("hello"))
        assertEquals("''", shellQuote(""))
    }

    @Test
    fun shellQuote_escapesEmbeddedSingleQuotes() {
        // The classic '\'' close-escape-reopen sequence keeps the value a single arg.
        assertEquals("'a'\\''b'", shellQuote("a'b"))
        // A break-out attempt stays fully single-quoted: the leading ' is escaped as
        // '\'' so the rest can never be interpreted by the shell as separate tokens.
        assertEquals("''\\''; rm -rf / #'", shellQuote("'; rm -rf / #"))
    }
}
