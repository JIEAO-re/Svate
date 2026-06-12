package com.immersive.ui.agent.loop

import com.immersive.ui.agent.AgentActionSafety

/** Outcome of a permission evaluation. */
enum class GateOutcome {
    ALLOW,
    ASK,
    DENY,
}

/**
 * Inputs for one permission evaluation. All fields are plain values so the gate's
 * decision is pure and JVM-unit-testable (no android.* calls inside the gate).
 *
 * @param toolName wire tool name (used as the allow-rule key dimension).
 * @param riskClass the tool's declared risk class.
 * @param isReadOnly whether the tool only reads state.
 * @param targetText combined target/selector/text the tool will act on; screened
 *   for hard-block keywords to upgrade tap/type to HIGH.
 * @param denyFloor true when an existing safety layer already hard-blocks this
 *   call (visual-injection HIGH, IntentGuard reject, destructive system action).
 *   The caller computes this on-device; tests can set it directly.
 * @param packageScope optional package the action targets, used to scope
 *   allow-rules (e.g. "always allow tap in com.foo").
 */
data class PermissionQuery(
    val toolName: String,
    val riskClass: RiskClass,
    val isReadOnly: Boolean,
    val targetText: String = "",
    val denyFloor: Boolean = false,
    val packageScope: String = "",
)

/**
 * Permission gate implementing the decision order from agent-loop.md section 4:
 *
 *   1. deny floor                         -> DENY
 *   2. effective HIGH risk                -> ASK (even in AUTO)
 *   3. allow-rule match                   -> ALLOW
 *   4. mode == AUTO                       -> ALLOW
 *   5. mode == ASK and SAFE/LOW/readOnly  -> ALLOW
 *   6. otherwise                          -> ASK
 *
 * "Effective" risk upgrades tap/type to HIGH when the target text hits the hard-
 * block keywords, so a tap on a "Pay" button always prompts even in AUTO mode.
 *
 * Allow-rules are a session-scoped in-memory set. GRANT_ALWAYS adds a rule keyed
 * by tool name (and package scope when present); GRANT_ONCE never adds a rule.
 */
class PermissionGate {

    /** Tools whose target text is screened for hard-block keyword upgrades. */
    private val keywordSensitiveTools = setOf("tap", "type_text")

    /** Session allow-rules: "toolName" or "toolName@package". */
    private val allowRules = mutableSetOf<String>()

    /** Compute the effective risk class, upgrading keyword-sensitive tools to HIGH. */
    fun effectiveRisk(query: PermissionQuery): RiskClass {
        if (query.toolName in keywordSensitiveTools &&
            AgentActionSafety.containsHardBlockedKeyword(query.targetText)
        ) {
            return RiskClass.HIGH
        }
        return query.riskClass
    }

    /** Evaluate the gate for the given query and current [mode]. */
    fun evaluate(query: PermissionQuery, mode: PermissionMode): GateOutcome {
        // 1. Deny floor always wins.
        if (query.denyFloor) return GateOutcome.DENY

        val risk = effectiveRisk(query)

        // 2. HIGH always asks, in both modes.
        if (risk == RiskClass.HIGH) return GateOutcome.ASK

        // 3. Allow-rule match.
        if (matchesAllowRule(query)) return GateOutcome.ALLOW

        // 4. AUTO mode allows everything below the HIGH floor.
        if (mode == PermissionMode.AUTO) return GateOutcome.ALLOW

        // 5. ASK mode: safe/low/read-only run without a prompt.
        if (risk == RiskClass.SAFE || risk == RiskClass.LOW || query.isReadOnly) {
            return GateOutcome.ALLOW
        }

        // 6. Otherwise ask.
        return GateOutcome.ASK
    }

    /** Whether a session allow-rule covers this query. */
    fun matchesAllowRule(query: PermissionQuery): Boolean {
        if (allowRules.contains(query.toolName)) return true
        if (query.packageScope.isNotBlank() &&
            allowRules.contains(ruleKey(query.toolName, query.packageScope))
        ) {
            return true
        }
        return false
    }

    /**
     * Apply the user's decision for a query. GRANT_ALWAYS adds a session allow-rule
     * (scoped by package when one is present); GRANT_ONCE and DENY add nothing.
     */
    fun applyDecision(query: PermissionQuery, decision: PermissionDecision) {
        if (decision == PermissionDecision.GRANT_ALWAYS) {
            val key = if (query.packageScope.isNotBlank()) {
                ruleKey(query.toolName, query.packageScope)
            } else {
                query.toolName
            }
            allowRules.add(key)
        }
    }

    /** Test/diagnostic view of the current rule set. */
    fun activeRules(): Set<String> = allowRules.toSet()

    private fun ruleKey(toolName: String, packageScope: String): String = "$toolName@$packageScope"
}
