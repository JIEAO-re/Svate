package com.immersive.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupRecoveryTest {

    /** Full-screen non-clickable root that anchors the inferred screen size (1080x2400). */
    private fun screenRoot(): UiNode = testNode(
        index = 0,
        bounds = testRect(0, 0, 1080, 2400),
        className = "android.widget.FrameLayout",
    )

    @Test
    fun detect_flagsChinesePermissionDialogAsHumanDecision() {
        val nodes = listOf(
            screenRoot(),
            testNode(
                index = 1,
                bounds = testRect(100, 1000, 980, 1100),
                text = "要允许该应用访问您的位置信息吗？",
                className = "android.widget.TextView",
            ),
            testNode(
                index = 2,
                bounds = testRect(100, 1200, 500, 1320),
                text = "始终允许",
                clickable = true,
            ),
            testNode(
                index = 3,
                bounds = testRect(580, 1200, 980, 1320),
                text = "拒绝",
                clickable = true,
            ),
        )

        val detection = PopupRecovery.detect(nodes)

        assertNotNull(detection)
        assertEquals(PopupType.PERMISSION_DIALOG, detection!!.type)
        assertTrue(detection.requiresHumanDecision)
        assertEquals(PopupRecovery.NO_CLOSE_BUTTON, detection.closeButtonIndex)
        assertFalse(PopupRecovery.canAutoDismiss(detection))
    }

    @Test
    fun detect_flagsEnglishAllowButtonAsPermissionDialog() {
        val nodes = listOf(
            screenRoot(),
            testNode(
                index = 1,
                bounds = testRect(100, 1000, 980, 1100),
                text = "Let this app access your camera?",
                className = "android.widget.TextView",
            ),
            testNode(
                index = 2,
                bounds = testRect(100, 1200, 500, 1320),
                text = "Allow",
                clickable = true,
            ),
            testNode(
                index = 3,
                bounds = testRect(580, 1200, 980, 1320),
                text = "Deny",
                clickable = true,
            ),
        )

        val detection = PopupRecovery.detect(nodes)

        assertNotNull(detection)
        assertEquals(PopupType.PERMISSION_DIALOG, detection!!.type)
        assertTrue(detection.requiresHumanDecision)
        assertEquals(PopupRecovery.NO_CLOSE_BUTTON, detection.closeButtonIndex)
        assertFalse(PopupRecovery.canAutoDismiss(detection))
    }

    @Test
    fun detect_allowsAutoDismissForAdSkipButton() {
        val nodes = listOf(
            screenRoot(),
            testNode(
                index = 1,
                bounds = testRect(100, 600, 980, 1800),
                text = "限时优惠，立即购买年卡",
                className = "android.widget.TextView",
            ),
            testNode(
                index = 2,
                bounds = testRect(900, 80, 1020, 170),
                text = "跳过",
                clickable = true,
            ),
        )

        val detection = PopupRecovery.detect(nodes)

        assertNotNull(detection)
        assertEquals(PopupType.AD_POPUP, detection!!.type)
        assertEquals(2, detection.closeButtonIndex)
        assertFalse(detection.requiresHumanDecision)
        assertTrue(PopupRecovery.canAutoDismiss(detection))
    }

    @Test
    fun detect_neverPicksConsentButtonAsCloseButton() {
        // An ad popup pairing a consent-style button (which even contains the
        // ad-close keyword "关闭广告") with a real skip button: only the skip
        // button may become the close button.
        val nodes = listOf(
            screenRoot(),
            testNode(
                index = 1,
                bounds = testRect(100, 1400, 600, 1520),
                text = "同意并关闭广告",
                clickable = true,
            ),
            testNode(
                index = 2,
                bounds = testRect(880, 90, 1020, 180),
                text = "跳过广告",
                clickable = true,
            ),
        )

        val detection = PopupRecovery.detect(nodes)

        assertNotNull(detection)
        assertEquals(PopupType.AD_POPUP, detection!!.type)
        assertEquals(2, detection.closeButtonIndex)
    }

    @Test
    fun detect_returnsNullWhenOnlyConsentButtonsPresent() {
        // Consent-semantics buttons (同意 / OK / Agree) must never be
        // auto-clicked, so a dialog offering only consent yields no detection.
        val nodes = listOf(
            screenRoot(),
            testNode(
                index = 1,
                bounds = testRect(100, 1000, 980, 1100),
                text = "我们更新了隐私政策",
                className = "android.widget.TextView",
            ),
            testNode(
                index = 2,
                bounds = testRect(100, 1200, 500, 1320),
                text = "同意",
                clickable = true,
            ),
            testNode(
                index = 3,
                bounds = testRect(580, 1200, 980, 1320),
                text = "OK",
                clickable = true,
            ),
        )

        assertNull(PopupRecovery.detect(nodes))
    }

    @Test
    fun detect_usesWordBoundaryForEnglishKeywords() {
        // "disclose" contains "close" as a substring but must not match.
        val noMatch = listOf(
            screenRoot(),
            testNode(
                index = 1,
                bounds = testRect(100, 600, 980, 1800),
                text = "Sponsored content details",
                className = "android.widget.TextView",
            ),
            testNode(
                index = 2,
                bounds = testRect(880, 90, 1020, 180),
                text = "Disclose sponsor info",
                clickable = true,
            ),
        )
        assertNull(PopupRecovery.detect(noMatch))

        // A standalone "Close" button still matches on the word boundary.
        val match = listOf(
            screenRoot(),
            testNode(
                index = 1,
                bounds = testRect(100, 600, 980, 1800),
                text = "Sponsored content details",
                className = "android.widget.TextView",
            ),
            testNode(
                index = 2,
                bounds = testRect(880, 90, 1020, 180),
                text = "Close",
                clickable = true,
            ),
        )
        val detection = PopupRecovery.detect(match)
        assertNotNull(detection)
        assertEquals(PopupType.AD_POPUP, detection!!.type)
        assertEquals(2, detection.closeButtonIndex)
        assertTrue(PopupRecovery.canAutoDismiss(detection))
    }
}
