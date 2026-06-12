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

        // The search-submit exemption was removed: submit is always blocked,
        // even in a search context.
        assertTrue(AgentActionSafety.containsHardBlockedKeyword("submit search query"))
        assertTrue(AgentActionSafety.containsHardBlockedKeyword("\u63d0\u4ea4\u641c\u7d22\u5173\u952e\u8bcd"))
    }

    @Test
    fun containsHardBlockedKeyword_usesWordBoundariesForEnglish() {
        // Short English keywords must match whole words only.
        assertTrue(AgentActionSafety.containsHardBlockedKeyword("tap to pay now"))
        assertTrue(AgentActionSafety.containsHardBlockedKeyword("place order"))

        // No false positives inside longer words: "prepay" contains "pay",
        // "recorder" contains "order", "formation" contains "format".
        assertFalse(AgentActionSafety.containsHardBlockedKeyword("prepay balance details"))
        assertFalse(AgentActionSafety.containsHardBlockedKeyword("voice recorder app"))
        assertFalse(AgentActionSafety.containsHardBlockedKeyword("view formation diagram"))
    }

    @Test
    fun containsHardBlockedKeyword_keepsSubstringMatchForChinese() {
        // Chinese keywords keep substring semantics.
        assertTrue(AgentActionSafety.containsHardBlockedKeyword("\u7acb\u5373\u652f\u4ed8\u8ba2\u5355"))
        assertTrue(AgentActionSafety.containsHardBlockedKeyword("\u70b9\u51fb\u5220\u9664\u6309\u94ae"))
        assertFalse(AgentActionSafety.containsHardBlockedKeyword("\u6d4f\u89c8\u5546\u54c1\u8be6\u60c5"))
    }
}
