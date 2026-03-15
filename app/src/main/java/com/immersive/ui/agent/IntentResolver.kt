package com.immersive.ui.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Direct Intent resolver: Action Pyramid Tier 1.
 *
 * When the AI decides to open an app for a search task, try to build a direct
 * deep link or Intent and skip GUI simulation for millisecond-level completion.
 *
 * Priority:
 * 1. Deep-link URL (for example, youtube://search?q=xxx)
 * 2. ACTION_SEARCH Intent
 * 3. ACTION_VIEW + URL scheme
 * 4. Fall back to getLaunchIntentForPackage() for a regular launch
 */
object IntentResolver {

    private const val TAG = "IntentResolver"

    /**
     * Search deep-link dictionary for mainstream apps.
     * key = package name, value = { query -> deep-link URI string }
     */
    private val DEEPLINK_REGISTRY: Map<String, (String) -> String> = mapOf(
        // WeChat (depends on the official navigation protocol and degrades automatically when unsupported)
        "com.tencent.mm" to { q ->
            "weixin://dl/search?query=${Uri.encode(q)}"
        },
        // YouTube
        "com.google.android.youtube" to { q ->
            "https://www.youtube.com/results?search_query=${Uri.encode(q)}"
        },
        // Douyin
        "com.ss.android.ugc.aweme" to { q ->
            "snssdk1128://search/trending?keyword=${Uri.encode(q)}"
        },
        // Baidu
        "com.baidu.searchbox" to { q ->
            "https://m.baidu.com/s?word=${Uri.encode(q)}"
        },
        // Weibo
        "com.sina.weibo" to { q ->
            "sinaweibo://searchall?q=${Uri.encode(q)}"
        },
        // Zhihu
        "com.zhihu.android" to { q ->
            "zhihu://search?q=${Uri.encode(q)}"
        },
        // Bilibili
        "tv.danmaku.bili" to { q ->
            "bilibili://search?keyword=${Uri.encode(q)}"
        },
        // Xiaohongshu
        "com.xingin.xhs" to { q ->
            "xhsdiscover://search/result?keyword=${Uri.encode(q)}"
        },
        // Taobao
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
     * Try to build a direct Intent for the given app and search query.
     *
     * @return A sendable Intent, or null when direct launch is not possible and GUI simulation is required.
     */
    fun tryResolveSearchIntent(
        packageName: String?,
        searchQuery: String?,
        context: Context,
    ): Intent? {
        if (packageName.isNullOrBlank() || searchQuery.isNullOrBlank()) return null

        // 1. Try the deep-link dictionary.
        val deepLinkBuilder = DEEPLINK_REGISTRY[packageName]
        if (deepLinkBuilder != null) {
            val uri = deepLinkBuilder(searchQuery)
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Verify that the Intent can be resolved.
            if (intent.resolveActivity(context.packageManager) != null) {
                Log.d(TAG, "DeepLink resolved: $uri")
                return intent
            }
            // The deep link may be unsupported; retry without a package so the system can pick a browser.
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

        // 2. Try ACTION_SEARCH.
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

        // 3. Try ACTION_WEB_SEARCH.
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
     * Check whether a package name is registered in the deep-link dictionary.
     */
    fun hasDeepLink(packageName: String?): Boolean {
        return packageName != null && DEEPLINK_REGISTRY.containsKey(packageName)
    }
}
