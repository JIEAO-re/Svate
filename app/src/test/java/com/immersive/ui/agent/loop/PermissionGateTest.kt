package com.immersive.ui.agent.loop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-matrix tests for [PermissionGate]. Pure Kotlin + org.json only; no
 * android.* types are touched so these run on the JVM.
 */
class PermissionGateTest {

    private fun query(
        toolName: String,
        riskClass: RiskClass,
        isReadOnly: Boolean,
        targetText: String = "",
        denyFloor: Boolean = false,
        packageScope: String = "",
    ) = PermissionQuery(
        toolName = toolName,
        riskClass = riskClass,
        isReadOnly = isReadOnly,
        targetText = targetText,
        denyFloor = denyFloor,
        packageScope = packageScope,
    )

    // ===== Rule 1: deny floor wins in both modes =====

    @Test
    fun denyFloor_isDenied_inAskMode() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, denyFloor = true)
        assertEquals(GateOutcome.DENY, gate.evaluate(q, PermissionMode.ASK))
    }

    @Test
    fun denyFloor_isDenied_inAutoMode() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, denyFloor = true)
        assertEquals(GateOutcome.DENY, gate.evaluate(q, PermissionMode.AUTO))
    }

    @Test
    fun denyFloor_beatsAllowRule() {
        // A scoped allow-rule exists, but the deny floor still wins.
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, denyFloor = true, packageScope = "com.foo")
        gate.applyDecision(q, PermissionDecision.GRANT_ALWAYS)
        assertEquals(GateOutcome.DENY, gate.evaluate(q, PermissionMode.AUTO))
    }

    // ===== Rule 2: HIGH always asks, even in AUTO =====

    @Test
    fun high_asks_inAutoMode() {
        val gate = PermissionGate()
        val q = query("launch_intent", RiskClass.HIGH, isReadOnly = false)
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.AUTO))
    }

    @Test
    fun high_asks_inAskMode() {
        val gate = PermissionGate()
        val q = query("launch_intent", RiskClass.HIGH, isReadOnly = false)
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.ASK))
    }

    @Test
    fun high_asks_evenWithAllowRule() {
        // The HIGH floor outranks an allow-rule (order: deny -> high -> allow-rule).
        // The scoped allow-rule genuinely exists so the HIGH floor is what wins.
        val gate = PermissionGate()
        val q = query("launch_intent", RiskClass.HIGH, isReadOnly = false, packageScope = "com.foo")
        gate.applyDecision(q, PermissionDecision.GRANT_ALWAYS)
        assertTrue(gate.matchesAllowRule(q))
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.AUTO))
    }

    // ===== Keyword upgrade: tap/type with hard-block target -> HIGH =====

    @Test
    fun tap_onPayTarget_isUpgradedToHigh_andAsks_inAuto() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Pay now")
        assertEquals(RiskClass.HIGH, gate.effectiveRisk(q))
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.AUTO))
    }

    @Test
    fun type_withTransferText_isUpgradedToHigh() {
        val gate = PermissionGate()
        val q = query("type_text", RiskClass.NORMAL, isReadOnly = false, targetText = "transfer 100")
        assertEquals(RiskClass.HIGH, gate.effectiveRisk(q))
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.AUTO))
    }

    @Test
    fun tap_onChinesePayTarget_isUpgradedToHigh() {
        val gate = PermissionGate()
        // "支付" == 支付 (pay)
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "支付")
        assertEquals(RiskClass.HIGH, gate.effectiveRisk(q))
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.AUTO))
    }

    @Test
    fun tap_onBenignTarget_staysNormal() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Search box")
        assertEquals(RiskClass.NORMAL, gate.effectiveRisk(q))
    }

    @Test
    fun keywordUpgrade_onlyAppliesToTapAndType() {
        // open_app is NORMAL and not keyword-screened, so a "pay" app name stays NORMAL.
        val gate = PermissionGate()
        val q = query("open_app", RiskClass.NORMAL, isReadOnly = false, targetText = "pay app")
        assertEquals(RiskClass.NORMAL, gate.effectiveRisk(q))
    }

    // ===== Rule 4: AUTO allows normal writes =====

    @Test
    fun normalWrite_allowed_inAutoMode() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Login")
        assertEquals(GateOutcome.ALLOW, gate.evaluate(q, PermissionMode.AUTO))
    }

    // ===== Rule 5: ASK allows safe/low/readOnly without a prompt =====

    @Test
    fun safeReadOnly_allowed_inAskMode() {
        val gate = PermissionGate()
        val q = query("take_screenshot", RiskClass.SAFE, isReadOnly = true)
        assertEquals(GateOutcome.ALLOW, gate.evaluate(q, PermissionMode.ASK))
    }

    @Test
    fun lowWrite_allowed_inAskMode() {
        val gate = PermissionGate()
        val q = query("scroll", RiskClass.LOW, isReadOnly = false)
        assertEquals(GateOutcome.ALLOW, gate.evaluate(q, PermissionMode.ASK))
    }

    // ===== Rule 6: ASK + normal write -> ask =====

    @Test
    fun normalWrite_asks_inAskMode() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Login")
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.ASK))
    }

    // ===== Allow-rule behavior =====

    @Test
    fun grantOnce_doesNotAddRule() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Login")
        gate.applyDecision(q, PermissionDecision.GRANT_ONCE)
        assertFalse(gate.matchesAllowRule(q))
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.ASK))
    }

    @Test
    fun grantAlways_withPackageScope_addsRule_andAllowsNextTime_inAskMode() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Login", packageScope = "com.foo")
        gate.applyDecision(q, PermissionDecision.GRANT_ALWAYS)
        assertTrue(gate.matchesAllowRule(q))
        assertEquals(GateOutcome.ALLOW, gate.evaluate(q, PermissionMode.ASK))
    }

    @Test
    fun grantAlways_withoutPackageScope_addsNoRule_andStillAsks() {
        // A scopeless "always allow" must not write a bare toolName rule: without a
        // package it cannot be safely reused, so it degrades to once-only.
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Login")
        gate.applyDecision(q, PermissionDecision.GRANT_ALWAYS)
        assertTrue(gate.activeRules().isEmpty())
        assertFalse(gate.matchesAllowRule(q))
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.ASK))
    }

    @Test
    fun grantAlways_withoutPackageScope_doesNotLeakAcrossApps() {
        // The scopeless grant must never become a cross-app allow: a later call in
        // any concrete package still asks.
        val gate = PermissionGate()
        val scopeless = query("tap", RiskClass.NORMAL, isReadOnly = false)
        gate.applyDecision(scopeless, PermissionDecision.GRANT_ALWAYS)
        val inFoo = query("tap", RiskClass.NORMAL, isReadOnly = false, packageScope = "com.foo")
        assertEquals(GateOutcome.ASK, gate.evaluate(inFoo, PermissionMode.ASK))
    }

    @Test
    fun grantAlways_withPackageScope_isScopedToThatPackage() {
        val gate = PermissionGate()
        val scoped = query("tap", RiskClass.NORMAL, isReadOnly = false, packageScope = "com.foo")
        gate.applyDecision(scoped, PermissionDecision.GRANT_ALWAYS)
        // Same tool, same package -> allowed.
        assertEquals(GateOutcome.ALLOW, gate.evaluate(scoped, PermissionMode.ASK))
        // Same tool, different package -> still asks.
        val otherPkg = query("tap", RiskClass.NORMAL, isReadOnly = false, packageScope = "com.bar")
        assertEquals(GateOutcome.ASK, gate.evaluate(otherPkg, PermissionMode.ASK))
    }

    @Test
    fun clearRules_dropsAllAllowRules() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, packageScope = "com.foo")
        gate.applyDecision(q, PermissionDecision.GRANT_ALWAYS)
        assertTrue(gate.matchesAllowRule(q))
        gate.clearRules()
        assertTrue(gate.activeRules().isEmpty())
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.ASK))
    }

    @Test
    fun deny_decision_addsNoRule() {
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false)
        gate.applyDecision(q, PermissionDecision.DENY)
        assertTrue(gate.activeRules().isEmpty())
    }

    // ===== Rule 0: EXPERIMENTAL allows EVERYTHING (even HIGH / deny floor) =====

    @Test
    fun experimental_allows_denyFloor() {
        val gate = PermissionGate()
        val q = query("launch_intent", RiskClass.HIGH, isReadOnly = false, denyFloor = true)
        assertEquals(GateOutcome.ALLOW, gate.evaluate(q, PermissionMode.EXPERIMENTAL))
    }

    @Test
    fun experimental_allows_highRisk() {
        val gate = PermissionGate()
        val q = query("uninstall_package", RiskClass.HIGH, isReadOnly = false)
        assertEquals(GateOutcome.ALLOW, gate.evaluate(q, PermissionMode.EXPERIMENTAL))
    }

    @Test
    fun experimental_allows_payKeywordTarget() {
        // Even a tap on a "支付" button auto-runs in EXPERIMENTAL (no keyword upgrade prompt).
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "支付")
        assertEquals(GateOutcome.ALLOW, gate.evaluate(q, PermissionMode.EXPERIMENTAL))
    }

    @Test
    fun experimental_allows_normalWrite() {
        val gate = PermissionGate()
        val q = query("type_text", RiskClass.NORMAL, isReadOnly = false, targetText = "hello")
        assertEquals(GateOutcome.ALLOW, gate.evaluate(q, PermissionMode.EXPERIMENTAL))
    }

    @Test
    fun allowRule_doesNotOverrideHighFloor() {
        // A GRANT_ALWAYS recorded for a tap that later targets a "pay" button must
        // still ASK, because the keyword upgrade to HIGH is checked before the rule.
        // Both queries share a package scope so the allow-rule genuinely exists and
        // the HIGH floor is what forces the prompt.
        val gate = PermissionGate()
        val benign = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Open menu", packageScope = "com.foo")
        gate.applyDecision(benign, PermissionDecision.GRANT_ALWAYS)
        assertTrue(gate.matchesAllowRule(benign))
        val risky = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Pay now", packageScope = "com.foo")
        assertEquals(GateOutcome.ASK, gate.evaluate(risky, PermissionMode.AUTO))
    }

    // ===== Rule 1b: SAFE forbids dangerous (HIGH) actions outright =====

    @Test
    fun safe_denies_highRisk() {
        val gate = PermissionGate()
        val q = query("uninstall_package", RiskClass.HIGH, isReadOnly = false)
        assertEquals(GateOutcome.DENY, gate.evaluate(q, PermissionMode.SAFE))
    }

    @Test
    fun safe_denies_payKeywordTap() {
        // A tap on a "支付" button is upgraded to HIGH, so SAFE hard-denies it.
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "支付")
        assertEquals(GateOutcome.DENY, gate.evaluate(q, PermissionMode.SAFE))
    }

    @Test
    fun safe_asks_normalWrite() {
        // Non-dangerous writes are not blocked in SAFE — they still ask.
        val gate = PermissionGate()
        val q = query("tap", RiskClass.NORMAL, isReadOnly = false, targetText = "Login")
        assertEquals(GateOutcome.ASK, gate.evaluate(q, PermissionMode.SAFE))
    }

    @Test
    fun safe_allows_readOnly() {
        val gate = PermissionGate()
        val q = query("take_screenshot", RiskClass.SAFE, isReadOnly = true)
        assertEquals(GateOutcome.ALLOW, gate.evaluate(q, PermissionMode.SAFE))
    }
}
