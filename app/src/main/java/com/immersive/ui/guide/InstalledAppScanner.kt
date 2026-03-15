package com.immersive.ui.guide

import android.content.Context
import android.content.Intent

/**
 * Simplified metadata for an installed app.
 */
data class AppInfo(
    val appName: String,
    val packageName: String,
)

/**
 * Scan all user-visible installed apps that expose a launcher icon.
 * Uses launcher visibility queries only and does not rely on QUERY_ALL_PACKAGES.
 */
object InstalledAppScanner {

    /**
     * Return all installed apps with launcher entry points, excluding this app itself.
     */
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)

        val selfPackage = context.packageName

        return resolveInfos
            .asSequence()
            .map { info ->
                val pkg = info.activityInfo.packageName
                val label = info.loadLabel(pm).toString().trim()
                AppInfo(appName = label, packageName = pkg)
            }
            .filter { it.packageName != selfPackage }
            .distinctBy { it.packageName }
            .sortedBy { it.appName }
            .toList()
    }

    /**
     * Format the app list as concise text suitable for Gemini prompt injection.
     * Example format: WeChat(com.tencent.mm), Alipay(com.eg.android.AlipayGphone), ...
     */
    fun formatForPrompt(apps: List<AppInfo>): String {
        return apps.joinToString(", ") { "${it.appName}(${it.packageName})" }
    }

    /**
     * Fuzzy-match installed apps locally by filtering app names against the user's query text.
     */
    fun fuzzyMatch(query: String, apps: List<AppInfo>, maxResults: Int = 5): List<AppInfo> {
        if (query.isBlank()) return emptyList()

        val queryLower = query.lowercase()

        // Priority 1: app name fully contains the query text.
        val exactContains = apps.filter { it.appName.lowercase().contains(queryLower) }
        if (exactContains.isNotEmpty()) return exactContains.take(maxResults)

        // Priority 2: the query contains the app name, such as "open WeChat" matching "WeChat".
        val reverseContains = apps.filter { queryLower.contains(it.appName.lowercase()) }
        if (reverseContains.isNotEmpty()) return reverseContains.take(maxResults)

        // Priority 3: character-level matching when any query character appears in the app name.
        val charMatch = apps
            .map { app ->
                val matchScore = queryLower.count { ch -> app.appName.lowercase().contains(ch) }
                app to matchScore
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }

        return charMatch.take(maxResults)
    }
}
