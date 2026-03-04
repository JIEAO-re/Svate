package com.immersive.ui.guide

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo

/**
 * 已安装应用的精简信息
 */
data class AppInfo(
    val appName: String,
    val packageName: String,
)

/**
 * 扫描设备上所有用户可见（有 Launcher 图标）的已安装应用。
 * 需要 AndroidManifest 声明 QUERY_ALL_PACKAGES 权限。
 */
object InstalledAppScanner {

    /**
     * 获取所有拥有 Launcher 入口的已安装应用列表（去除自身）。
     */
    fun getInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)

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
     * 将应用列表格式化为适合注入 Gemini Prompt 的简洁文本。
     * 格式示例：微信(com.tencent.mm), 支付宝(com.eg.android.AlipayGphone), ...
     */
    fun formatForPrompt(apps: List<AppInfo>): String {
        return apps.joinToString(", ") { "${it.appName}(${it.packageName})" }
    }

    /**
     * 本地模糊匹配：根据用户输入文本，从已安装应用中筛选名称包含关键词的候选应用。
     */
    fun fuzzyMatch(query: String, apps: List<AppInfo>, maxResults: Int = 5): List<AppInfo> {
        if (query.isBlank()) return emptyList()

        val queryLower = query.lowercase()

        // 优先级 1：应用名完全包含查询词
        val exactContains = apps.filter { it.appName.lowercase().contains(queryLower) }
        if (exactContains.isNotEmpty()) return exactContains.take(maxResults)

        // 优先级 2：查询词中包含应用名（如用户说"打开微信"，匹配到"微信"）
        val reverseContains = apps.filter { queryLower.contains(it.appName.lowercase()) }
        if (reverseContains.isNotEmpty()) return reverseContains.take(maxResults)

        // 优先级 3：逐字匹配（查询词的任意单字出现在应用名中）
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
