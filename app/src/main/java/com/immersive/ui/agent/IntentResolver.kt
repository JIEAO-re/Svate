package com.immersive.ui.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Intent 直达解析器 —— Action Pyramid Tier 1。
 *
 * 当 AI 决定打开 App 执行搜索任务时，尝试直接构建 DeepLink/Intent，
 * 绕过 GUI 模拟操作，实现毫秒级完成。
 *
 * 优先级：
 * 1. DeepLink URL（如 youtube://search?q=xxx）
 * 2. ACTION_SEARCH Intent
 * 3. ACTION_VIEW + URL scheme
 * 4. 降级为 getLaunchIntentForPackage()（普通启动）
 */
object IntentResolver {

    private const val TAG = "IntentResolver"

    /**
     * 主流 App 的搜索 DeepLink 字典。
     * key = 包名, value = { query -> DeepLink URI 字符串 }
     */
    private val DEEPLINK_REGISTRY: Map<String, (String) -> String> = mapOf(
        // 寰俊锛堜緷璧栧畼鏂瑰鑸崗璁紝涓嶆敮鎸佹椂浼氳嚜鍔ㄩ€€鍖?
        "com.tencent.mm" to { q ->
            "weixin://dl/search?query=${Uri.encode(q)}"
        },
        // YouTube
        "com.google.android.youtube" to { q ->
            "https://www.youtube.com/results?search_query=${Uri.encode(q)}"
        },
        // 抖音
        "com.ss.android.ugc.aweme" to { q ->
            "snssdk1128://search/trending?keyword=${Uri.encode(q)}"
        },
        // 百度
        "com.baidu.searchbox" to { q ->
            "https://m.baidu.com/s?word=${Uri.encode(q)}"
        },
        // 微博
        "com.sina.weibo" to { q ->
            "sinaweibo://searchall?q=${Uri.encode(q)}"
        },
        // 知乎
        "com.zhihu.android" to { q ->
            "zhihu://search?q=${Uri.encode(q)}"
        },
        // 哔哩哔哩
        "tv.danmaku.bili" to { q ->
            "bilibili://search?keyword=${Uri.encode(q)}"
        },
        // 小红书
        "com.xingin.xhs" to { q ->
            "xhsdiscover://search/result?keyword=${Uri.encode(q)}"
        },
        // 淘宝
        "com.taobao.taobao" to { q ->
            "taobao://s.taobao.com/?q=${Uri.encode(q)}"
        },
        // Google Search
        "com.google.android.googlequicksearchbox" to { q ->
            "https://www.google.com/search?q=${Uri.encode(q)}"
        },
        // Chrome
        "com.android.chrome" to { q ->
            "https://www.google.com/search?q=${Uri.encode(q)}"
        },
    )

    /**
     * 尝试为指定的 App + 搜索关键词构建直达 Intent。
     *
     * @return 可发送的 Intent，或 null（表示无法直达，需走 GUI 模拟）
     */
    fun tryResolveSearchIntent(
        packageName: String?,
        searchQuery: String?,
        context: Context,
    ): Intent? {
        if (packageName.isNullOrBlank() || searchQuery.isNullOrBlank()) return null

        // 1. 尝试 DeepLink 字典
        val deepLinkBuilder = DEEPLINK_REGISTRY[packageName]
        if (deepLinkBuilder != null) {
            val uri = deepLinkBuilder(searchQuery)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 验证 Intent 是否可被解析
            if (intent.resolveActivity(context.packageManager) != null) {
                Log.d(TAG, "DeepLink resolved: $uri")
                return intent
            }
            // DeepLink 可能不支持，尝试不指定包名（让系统选择浏览器）
            if (uri.startsWith("http")) {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (webIntent.resolveActivity(context.packageManager) != null) {
                    Log.d(TAG, "Web fallback resolved: $uri")
                    return webIntent
                }
            }
        }

        // 2. 尝试 ACTION_SEARCH
        val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
            setPackage(packageName)
            putExtra("query", searchQuery)
            putExtra(android.app.SearchManager.QUERY, searchQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (searchIntent.resolveActivity(context.packageManager) != null) {
            Log.d(TAG, "ACTION_SEARCH resolved for $packageName")
            return searchIntent
        }

        // 3. 尝试 ACTION_WEB_SEARCH
        val webSearchIntent = Intent(Intent.ACTION_WEB_SEARCH).apply {
            setPackage(packageName)
            putExtra("query", searchQuery)
            putExtra(android.app.SearchManager.QUERY, searchQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (webSearchIntent.resolveActivity(context.packageManager) != null) {
            Log.d(TAG, "ACTION_WEB_SEARCH resolved for $packageName")
            return webSearchIntent
        }

        Log.d(TAG, "No direct intent available for $packageName, falling back to GUI")
        return null
    }

    /**
     * 检查某个包名是否在 DeepLink 字典中注册。
     */
    fun hasDeepLink(packageName: String?): Boolean {
        return packageName != null && DEEPLINK_REGISTRY.containsKey(packageName)
    }
}
