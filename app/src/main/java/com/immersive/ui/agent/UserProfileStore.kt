package com.immersive.ui.agent

import android.content.Context
import android.content.SharedPreferences
import com.immersive.ui.guide.GuideAiEngines
import org.json.JSONArray
import org.json.JSONObject

/**
 * 用户画像
 */
data class UserProfile(
    val preferences: MutableList<String> = mutableListOf(),  // "喜欢用微信视频通话"
    val frequentApps: MutableList<String> = mutableListOf(), // "微信", "抖音"
    val keyInfo: MutableList<String> = mutableListOf(),      // "联系人小明是儿子"
    val lastUpdated: Long = 0,
)

/**
 * 用户偏好提取与持久化 — 每次对话结束后提取偏好，下次对话自动注入。
 */
object UserProfileStore {

    private const val PREFS_NAME = "svate_user_profile"
    private const val KEY_PROFILE = "profile"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): UserProfile {
        val json = prefs(ctx).getString(KEY_PROFILE, null) ?: return UserProfile()
        return try {
            val obj = JSONObject(json)
            UserProfile(
                preferences = jsonArrayToList(obj.optJSONArray("preferences")),
                frequentApps = jsonArrayToList(obj.optJSONArray("frequent_apps")),
                keyInfo = jsonArrayToList(obj.optJSONArray("key_info")),
                lastUpdated = obj.optLong("last_updated", 0),
            )
        } catch (_: Exception) {
            UserProfile()
        }
    }

    fun clearProfile(ctx: Context) {
        prefs(ctx).edit().remove(KEY_PROFILE).apply()
    }

    fun save(ctx: Context, profile: UserProfile) {
        val obj = JSONObject().apply {
            put("preferences", JSONArray(profile.preferences.distinct().takeLast(30)))
            put("frequent_apps", JSONArray(profile.frequentApps.distinct().takeLast(20)))
            put("key_info", JSONArray(profile.keyInfo.distinct().takeLast(30)))
            put("last_updated", System.currentTimeMillis())
        }
        prefs(ctx).edit().putString(KEY_PROFILE, obj.toString()).apply()
    }

    /**
     * 从对话中提取用户偏好并合并到现有画像
     */
    fun extractAndMerge(ctx: Context, messages: List<ChatMsg>) {
        if (messages.size < 3) return

        val recent = messages.takeLast(20).joinToString("\n") { "${it.role}: ${it.content}" }
        val prompt = """
请从以下对话中提取用户的偏好和关键信息。

对话内容：
$recent

请严格输出 JSON：
{
  "preferences": ["用户偏好1", "用户偏好2"],
  "frequent_apps": ["应用名1"],
  "key_info": ["关键信息1"]
}

规则：
1. preferences: 用户的操作习惯和偏好（如"喜欢用微信视频通话"、"习惯用支付宝付款"）
2. frequent_apps: 用户经常使用的应用名
3. key_info: 重要的个人信息（如"小明是他的儿子"、"家住北京"）
4. 如果没有发现相关信息，返回空数组
5. 不要编造信息，只提取对话中明确提到的内容
""".trimIndent()

        try {
            val json = GuideAiEngines.requestGeminiJsonPublic(prompt, null)
            val existing = load(ctx)

            val newPrefs = jsonArrayToList(json.optJSONArray("preferences"))
            val newApps = jsonArrayToList(json.optJSONArray("frequent_apps"))
            val newInfo = jsonArrayToList(json.optJSONArray("key_info"))

            existing.preferences.addAll(newPrefs)
            existing.frequentApps.addAll(newApps)
            existing.keyInfo.addAll(newInfo)

            save(ctx, existing)
        } catch (_: Exception) {
            // 提取失败不影响主流程
        }
    }

    /**
     * 格式化用户画像，用于注入 Prompt
     */
    fun formatForPrompt(ctx: Context): String? {
        val profile = load(ctx)
        if (profile.preferences.isEmpty() && profile.frequentApps.isEmpty() && profile.keyInfo.isEmpty()) {
            return null
        }

        return buildString {
            if (profile.preferences.isNotEmpty()) {
                appendLine("用户偏好：${profile.preferences.distinct().takeLast(10).joinToString("；")}")
            }
            if (profile.frequentApps.isNotEmpty()) {
                appendLine("常用应用：${profile.frequentApps.distinct().takeLast(5).joinToString("、")}")
            }
            if (profile.keyInfo.isNotEmpty()) {
                appendLine("关键信息：${profile.keyInfo.distinct().takeLast(10).joinToString("；")}")
            }
        }.trim()
    }

    private fun jsonArrayToList(arr: JSONArray?): MutableList<String> {
        if (arr == null) return mutableListOf()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "").trim()
            if (s.isNotBlank()) list += s
        }
        return list
    }
}
