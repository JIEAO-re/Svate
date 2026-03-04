package com.immersive.ui.agent

import android.content.Context
import android.content.SharedPreferences
import com.immersive.ui.guide.GuideAiEngines
import org.json.JSONArray
import org.json.JSONObject

/**
 * 对话中的单条消息
 */
data class ChatMsg(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * 一次完整对话
 */
data class ChatSession(
    val id: String,
    var title: String,
    var summary: String = "",
    val messages: MutableList<ChatMsg> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var isAutoTitle: Boolean = true,
)

/**
 * 对话持久化 + Gemini 标题/总结生成
 */
object ChatStorage {

    private const val PREFS_NAME = "svate_chat_storage"
    private const val KEY_SESSIONS = "sessions"
    private const val MAX_SESSIONS = 100

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ================================================================
    // 读写
    // ================================================================

    fun loadSessions(ctx: Context): MutableList<ChatSession> {
        val json = prefs(ctx).getString(KEY_SESSIONS, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(json)
            val list = mutableListOf<ChatSession>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val msgs = mutableListOf<ChatMsg>()
                val msgsArr = obj.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until msgsArr.length()) {
                    val m = msgsArr.optJSONObject(j) ?: continue
                    msgs += ChatMsg(
                        role = m.optString("role"),
                        content = m.optString("content"),
                        timestamp = m.optLong("ts", 0),
                    )
                }
                list += ChatSession(
                    id = obj.optString("id"),
                    title = obj.optString("title"),
                    summary = obj.optString("summary", ""),
                    messages = msgs,
                    createdAt = obj.optLong("created", 0),
                    isAutoTitle = obj.optBoolean("auto_title", true),
                )
            }
            list
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveSessions(ctx: Context, sessions: List<ChatSession>) {
        val arr = JSONArray()
        val trimmed = sessions.take(MAX_SESSIONS)
        for (s in trimmed) {
            val msgsArr = JSONArray()
            // 只存最近 200 条消息以控制大小
            for (m in s.messages.takeLast(200)) {
                msgsArr.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    put("ts", m.timestamp)
                })
            }
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("summary", s.summary)
                put("messages", msgsArr)
                put("created", s.createdAt)
                put("auto_title", s.isAutoTitle)
            })
        }
        prefs(ctx).edit().putString(KEY_SESSIONS, arr.toString()).apply()
    }

    // ================================================================
    // Gemini 标题生成（≤15 token）
    // ================================================================

    fun generateTitle(messages: List<ChatMsg>): String {
        val recent = messages.takeLast(10).joinToString("\n") { "${it.role}: ${it.content}" }
        val prompt = """
请为以下对话生成一个极短的中文标题（不超过8个字）。
只输出标题本身，不要引号，不要解释。

对话内容：
$recent
""".trimIndent()

        return try {
            val json = GuideAiEngines.requestGeminiJsonPublic(
                "$prompt\n\n请输出 JSON: {\"title\": \"...\"}",
                null,
            )
            val title = json.optString("title", "").trim()
            if (title.isNotBlank() && title.length <= 20) title else fallbackTitle(messages)
        } catch (_: Exception) {
            fallbackTitle(messages)
        }
    }

    private fun fallbackTitle(messages: List<ChatMsg>): String {
        val first = messages.firstOrNull { it.role == "user" }?.content ?: "新对话"
        return if (first.length > 8) first.take(8) + "…" else first
    }

    // ================================================================
    // Gemini 对话总结
    // ================================================================

    fun generateSummary(messages: List<ChatMsg>): String {
        val recent = messages.takeLast(20).joinToString("\n") { "${it.role}: ${it.content}" }
        val prompt = """
请对以下对话进行简短总结（不超过50字），概括用户完成了什么操作。
只输出总结本身，不要引号。

对话内容：
$recent

请输出 JSON: {"summary": "..."}
""".trimIndent()

        return try {
            val json = GuideAiEngines.requestGeminiJsonPublic(prompt, null)
            json.optString("summary", "").trim()
        } catch (_: Exception) {
            ""
        }
    }
}
