package com.immersive.ui.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSafetyTest {

    @Test
    fun validateClickBbox_acceptsValidBbox() {
        val result = AgentActionSafety.validateClickBbox(intArrayOf(100, 100, 260, 280))
        assertTrue(result.allowed)
    }

    @Test
    fun validateClickBbox_rejectsNegativeValue() {
        val result = AgentActionSafety.validateClickBbox(intArrayOf(-1, 100, 260, 280))
        assertFalse(result.allowed)
    }

    @Test
    fun validateClickBbox_rejectsOutOfRangeValue() {
        val result = AgentActionSafety.validateClickBbox(intArrayOf(100, 100, 260, 1001))
        assertFalse(result.allowed)
    }

    @Test
    fun validateClickBbox_rejectsInvalidOrder() {
        val result = AgentActionSafety.validateClickBbox(intArrayOf(300, 100, 200, 280))
        assertFalse(result.allowed)
    }

    @Test
    fun validateClickBbox_rejectsTooSmallBbox() {
        val result = AgentActionSafety.validateClickBbox(intArrayOf(100, 100, 110, 120))
        assertFalse(result.allowed)
    }

    @Test
    fun isBlockedSystemPackage_detectsSystemUiPackage() {
        assertTrue(AgentActionSafety.isBlockedSystemPackage(AgentActionSafety.SYSTEM_UI_PACKAGE))
        assertFalse(AgentActionSafety.isBlockedSystemPackage("com.google.android.youtube"))
    }

    @Test
    fun isKnownLauncherPackage_rejectsInvalidPackage() {
        val launchablePackages = setOf("com.google.android.youtube", "com.android.settings")
        assertFalse(AgentActionSafety.isKnownLauncherPackage("com.fake.invalid", launchablePackages))
    }

    @Test
    fun containsHardBlockedKeyword_detectsSensitiveActions() {
        assertTrue(AgentActionSafety.containsHardBlockedKeyword("please submit and publish"))
        assertTrue(AgentActionSafety.containsHardBlockedKeyword("confirm payment"))
        assertTrue(AgentActionSafety.containsHardBlockedKeyword("\u786e\u8ba4\u652f\u4ed8"))

        assertFalse(AgentActionSafety.containsHardBlockedKeyword("submit search query"))
        assertFalse(AgentActionSafety.containsHardBlockedKeyword("\u63d0\u4ea4\u641c\u7d22\u5173\u952e\u8bcd"))
    }
}
