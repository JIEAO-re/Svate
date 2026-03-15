package com.immersive.ui.agent

import android.content.Intent
import android.net.Uri

object IntentGuard {
    private const val MAX_EXTRA_ENTRIES = 24
    private const val MAX_EXTRA_STRING_LEN = 220
    private const val MAX_EXTRA_TOTAL_CHARS = 2200

    private val allowedActions = setOf(
        Intent.ACTION_VIEW,
        Intent.ACTION_SEARCH,
        Intent.ACTION_WEB_SEARCH,
    )
    private val allowedPackages = setOf(
        // Wave-1
        "com.tencent.mm",
        "com.ss.android.ugc.aweme",
        "com.taobao.taobao",
        "tv.danmaku.bili",
        "com.xingin.xhs",
        "com.google.android.youtube",
        // Search/browser fallbacks
        "com.android.chrome",
        "com.google.android.googlequicksearchbox",
    )

    /**
     * Hard-block keyword list: reject immediately when the data URI or extras contain these terms.
     * Covers high-risk actions such as delete, uninstall, format, payment, and transfer.
     */
    private val HARD_BLOCKED_KEYWORDS = listOf(
        // English
        "delete", "uninstall", "format", "factory_reset", "wipe",
        "payment", "transfer", "pay", "purchase", "checkout",
        "rm -rf", "drop table", "truncate",
        // Chinese
        "删除", "卸载", "格式化", "恢复出厂", "清空",
        "支付", "转账", "付款", "购买", "充值",
    )

    fun validate(
        spec: IntentSpec?,
        fallbackPackage: String? = null,
    ): SafetyCheckResult {
        if (spec == null) return SafetyCheckResult(false, "missing_intent_spec")
        if (spec.action.isBlank()) return SafetyCheckResult(false, "missing_intent_action")
        if (spec.action !in allowedActions) return SafetyCheckResult(false, "intent_action_blocked")

        val pkg = spec.packageName ?: fallbackPackage
        if (pkg.isNullOrBlank() && spec.dataUri.isNullOrBlank()) {
            return SafetyCheckResult(false, "missing_package_or_data_uri")
        }
        if (!pkg.isNullOrBlank() && pkg !in allowedPackages) {
            return SafetyCheckResult(false, "intent_package_blocked")
        }
        val dataUri = spec.dataUri?.trim().orEmpty()
        if (dataUri.isNotBlank()) {
            val scheme = Uri.parse(dataUri).scheme?.lowercase().orEmpty()
            if (scheme.isBlank()) return SafetyCheckResult(false, "intent_data_uri_invalid")
            val allowCustomScheme = !pkg.isNullOrBlank()
            val allowWebScheme = scheme == "http" || scheme == "https"
            if (!allowCustomScheme && !allowWebScheme) {
                return SafetyCheckResult(false, "intent_data_uri_scheme_blocked")
            }
        }

        // Hard-block scan: look for high-risk keywords in the data URI and extras.
        if (containsBlockedKeyword(dataUri)) {
            return SafetyCheckResult(false, "intent_data_uri_contains_blocked_keyword")
        }

        if (spec.extras.size > MAX_EXTRA_ENTRIES) {
            return SafetyCheckResult(false, "intent_extras_too_many")
        }
        var totalChars = 0
        for ((key, value) in spec.extras) {
            if (key.isBlank()) return SafetyCheckResult(false, "intent_extra_key_blank")
            // Scan high-risk keywords in both extras keys and values.
            if (containsBlockedKeyword(key)) {
                return SafetyCheckResult(false, "intent_extra_key_contains_blocked_keyword")
            }
            when (value) {
                null -> Unit
                is String -> {
                    if (value.length > MAX_EXTRA_STRING_LEN) {
                        return SafetyCheckResult(false, "intent_extra_string_too_long")
                    }
                    if (containsBlockedKeyword(value)) {
                        return SafetyCheckResult(false, "intent_extra_value_contains_blocked_keyword")
                    }
                    totalChars += value.length + key.length
                }
                is Number, is Boolean -> totalChars += key.length + value.toString().length
                else -> return SafetyCheckResult(false, "intent_extra_type_blocked")
            }
            if (totalChars > MAX_EXTRA_TOTAL_CHARS) {
                return SafetyCheckResult(false, "intent_extras_total_too_large")
            }
        }
        return SafetyCheckResult(true)
    }

    fun buildIntent(
        spec: IntentSpec,
        fallbackPackage: String? = null,
    ): Intent {
        val intent = Intent(spec.action).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        spec.dataUri?.takeIf { it.isNotBlank() }?.let {
            intent.data = Uri.parse(it)
        }
        (spec.packageName ?: fallbackPackage)?.takeIf { it.isNotBlank() }?.let {
            intent.setPackage(it)
        }

        for ((key, value) in spec.extras) {
            when (value) {
                null -> intent.putExtra(key, null as String?)
                is String -> intent.putExtra(key, value)
                is Int -> intent.putExtra(key, value)
                is Long -> intent.putExtra(key, value)
                is Float -> intent.putExtra(key, value)
                is Double -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                else -> Unit
            }
        }
        return intent
    }

    private fun containsBlockedKeyword(text: String): Boolean {
        if (text.isBlank()) return false
        val lower = text.lowercase()
        return HARD_BLOCKED_KEYWORDS.any { keyword -> lower.contains(keyword.lowercase()) }
    }
}
