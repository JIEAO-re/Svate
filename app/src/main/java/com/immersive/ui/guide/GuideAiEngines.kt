package com.immersive.ui.guide

import com.immersive.ui.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class SimpleChatMessage(
    val role: String,
    val content: String,
)

data class AppCandidate(
    val appName: String,
    val packageName: String,
    val reason: String,
)

data class GoalChatResult(
    val reply: String,
    val inferredGoal: String,
    val targetAppName: String,
    val readyToStart: Boolean,
    val candidates: List<AppCandidate> = emptyList(),
    val taskMode: String = "GENERAL",
    val searchQuery: String = "",
    val researchDepth: Int = 3,
    val homeworkPolicy: String = "REFERENCE_ONLY",
    val askOnUncertain: Boolean = true,
)

data class ScreenAnalysisResult(
    val targetFound: Boolean,
    val enteredTargetApp: Boolean,
    val directionHint: String,
    val instruction: String,
    val bbox: IntArray?,
)

object GuideAiEngines {
    private const val UNKNOWN_APP = "Target App"
    private var installedApps: List<AppInfo> = emptyList()

    fun setInstalledApps(apps: List<AppInfo>) {
        installedApps = apps
    }

    fun chatForGoal(messages: List<SimpleChatMessage>): GoalChatResult {
        val fallback = fallbackChat(messages)
        try {
            val viaBff = requestChatGoalViaBff(messages)
            if (viaBff.reply.isNotBlank()) {
                return viaBff
            }
        } catch (_: Exception) {
            // Fall through to generic model prompt fallback.
        }

        val latestUser = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val conversation = messages
            .takeLast(16)
            .joinToString("\n") { msg ->
                val role = if (msg.role == "assistant") "assistant" else "user"
                "$role: ${msg.content}"
            }
        val appListText = if (installedApps.isNotEmpty()) {
            "\nInstalled apps:\n${InstalledAppScanner.formatForPrompt(installedApps)}\n"
        } else {
            ""
        }

        val prompt = """
You are a task clarification assistant for mobile automation.
Return strict JSON only (no markdown):
{
  "reply": "response to user",
  "inferred_goal": "normalized goal",
  "target_app_name": "single app name or empty if unclear",
  "ready_to_start": true/false,
  "task_mode": "GENERAL|SEARCH|RESEARCH|HOMEWORK",
  "search_query": "search query text or empty",
  "research_depth": 1-8,
  "homework_policy": "REFERENCE_ONLY|NAVIGATION_ONLY",
  "ask_on_uncertain": true/false,
  "candidates": [
    { "app_name": "app", "package_name": "pkg", "reason": "why this app" }
  ]
}

Rules:
1) If user explicitly named a single app, fill target_app_name, candidates empty.
2) If user intent is ambiguous, provide 2-3 candidates from installed app list.
3) Never invent package names, must come from installed apps list.
4) task_mode:
   - GENERAL: normal navigation
   - SEARCH: includes search/find/lookup intent
   - RESEARCH: asks for synthesis/multi-source summary/article
   - HOMEWORK: homework/exercise/question solving task
5) Default research_depth=3, homework_policy=REFERENCE_ONLY, ask_on_uncertain=true.
$appListText
Conversation:
$conversation
        """.trimIndent()

        return try {
            val json = requestGeminiJson(prompt, imageBase64 = null)
            val userSpecifiedApp = inferTargetApp(latestUser) != UNKNOWN_APP
            val candidates = normalizeCandidates(
                candidates = parseCandidates(json.optJSONArray("candidates")),
                latestUser = latestUser,
            )
            val fallbackMode = inferTaskMode(latestUser)
            val rawTargetApp = json.optString("target_app_name", fallback.targetAppName).trim()
            val enforceCandidates = !userSpecifiedApp && candidates.isNotEmpty()

            GoalChatResult(
                reply = json.optString("reply", fallback.reply).ifBlank { fallback.reply },
                inferredGoal = json.optString("inferred_goal", fallback.inferredGoal).ifBlank { fallback.inferredGoal },
                targetAppName = if (enforceCandidates) "" else rawTargetApp,
                readyToStart = if (enforceCandidates) false else json.optBoolean("ready_to_start", fallback.readyToStart),
                candidates = if (enforceCandidates) candidates else candidates.take(3),
                taskMode = parseTaskMode(json.optString("task_mode", fallbackMode), fallbackMode),
                searchQuery = json.optString("search_query", fallback.searchQuery).ifBlank { fallback.searchQuery },
                researchDepth = sanitizeResearchDepth(json.optInt("research_depth", fallback.researchDepth)),
                homeworkPolicy = parseHomeworkPolicy(
                    json.optString("homework_policy", fallback.homeworkPolicy),
                    fallback.homeworkPolicy,
                ),
                askOnUncertain = json.optBoolean("ask_on_uncertain", fallback.askOnUncertain),
            )
        } catch (_: Exception) {
            fallback
        }
    }

    fun analyzeScreen(
        imageBase64: String,
        targetAppName: String,
        inferredGoal: String,
        preferredDirection: String,
    ): ScreenAnalysisResult {
        val fallback = ScreenAnalysisResult(
            targetFound = false,
            enteredTargetApp = false,
            directionHint = preferredDirection,
            instruction = "I cannot find $targetAppName yet. Swipe ${if (preferredDirection == "LEFT") "left" else "right"} and continue.",
            bbox = null,
        )

        val prompt = """
You are a mobile screenshot analyzer.
Target app: $targetAppName
Goal: $inferredGoal

Return strict JSON only:
{
  "target_found": true/false,
  "entered_target_app": true/false,
  "direction_hint": "LEFT"|"RIGHT"|"NONE",
  "instruction": "short instruction",
  "bbox": [ymin, xmin, ymax, xmax] or null
}

Rules:
1) If target icon is not visible, target_found=false and keep user swiping.
2) If target icon is visible, target_found=true and return icon bbox.
3) If already in target app, entered_target_app=true.
4) bbox range must be 0-1000.
        """.trimIndent()

        return try {
            val json = requestGeminiJson(prompt, imageBase64)
            val bbox = parseBbox(json.opt("bbox"))
            ScreenAnalysisResult(
                targetFound = json.optBoolean("target_found", fallback.targetFound),
                enteredTargetApp = json.optBoolean("entered_target_app", false),
                directionHint = json.optString("direction_hint", preferredDirection).uppercase(),
                instruction = json.optString("instruction", fallback.instruction).ifBlank { fallback.instruction },
                bbox = bbox,
            )
        } catch (_: Exception) {
            fallback
        }
    }

    private fun fallbackChat(messages: List<SimpleChatMessage>): GoalChatResult {
        val latest = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val appName = inferTargetApp(latest)
        val mode = inferTaskMode(latest)
        val searchQuery = inferSearchQuery(latest)
        val researchDepth = if (mode == "RESEARCH") 3 else 1
        val homeworkPolicy = if (mode == "HOMEWORK") "REFERENCE_ONLY" else "NAVIGATION_ONLY"

        if (appName != UNKNOWN_APP) {
            val inferredGoal = latest.ifBlank { "Open $appName and complete the task." }
            val ready = latest.length >= 6
            val reply = if (ready) {
                "Target confirmed: $inferredGoal. Tap Start Guide to continue."
            } else {
                "I can open $appName. Tell me what operation you want next."
            }
            return GoalChatResult(
                reply = reply,
                inferredGoal = inferredGoal,
                targetAppName = appName,
                readyToStart = ready,
                taskMode = mode,
                searchQuery = searchQuery,
                researchDepth = if (mode == "RESEARCH") researchDepth else 3,
                homeworkPolicy = homeworkPolicy,
                askOnUncertain = true,
            )
        }

        if (latest.isNotBlank() && installedApps.isNotEmpty()) {
            val fuzzyResults = InstalledAppScanner.fuzzyMatch(latest, installedApps, maxResults = 3)
            if (fuzzyResults.isNotEmpty()) {
                val candidates = normalizeCandidates(
                    candidates = fuzzyResults.map { app ->
                        AppCandidate(
                            appName = app.appName,
                            packageName = app.packageName,
                            reason = "Related to your description",
                        )
                    },
                    latestUser = latest,
                )
                val candidateNames = candidates.joinToString(" / ") { it.appName }
                return GoalChatResult(
                    reply = "I found related apps: $candidateNames. Please choose one.",
                    inferredGoal = latest,
                    targetAppName = "",
                    readyToStart = false,
                    candidates = candidates,
                    taskMode = mode,
                    searchQuery = searchQuery,
                    researchDepth = if (mode == "RESEARCH") researchDepth else 3,
                    homeworkPolicy = homeworkPolicy,
                    askOnUncertain = true,
                )
            }
        }

        return GoalChatResult(
            reply = "Please tell me the app name and what you want to do.",
            inferredGoal = latest.ifBlank { "Unknown goal" },
            targetAppName = UNKNOWN_APP,
            readyToStart = false,
            taskMode = mode,
            searchQuery = searchQuery,
            researchDepth = if (mode == "RESEARCH") researchDepth else 3,
            homeworkPolicy = homeworkPolicy,
            askOnUncertain = true,
        )
    }

    private fun inferTargetApp(text: String): String {
        if (text.isBlank()) return UNKNOWN_APP

        installedApps.firstOrNull { app -> text.contains(app.appName, ignoreCase = true) }
            ?.let { return it.appName }

        Regex("(?:open|use|start|打开)\\s*([^\\s,，。.!！？:：]{1,18})", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { extracted ->
                installedApps.firstOrNull { app ->
                    app.appName.contains(extracted, ignoreCase = true) ||
                        extracted.contains(app.appName, ignoreCase = true)
                }?.let { return it.appName }
                return extracted
            }

        return UNKNOWN_APP
    }

    private fun inferTaskMode(text: String): String {
        val normalized = text.lowercase()
        if (
            normalized.contains("homework") ||
            normalized.contains("exercise") ||
            normalized.contains("assignment") ||
            normalized.contains("作业") ||
            normalized.contains("练习") ||
            normalized.contains("题")
        ) {
            return "HOMEWORK"
        }
        if (
            normalized.contains("research") ||
            normalized.contains("summarize") ||
            normalized.contains("article") ||
            normalized.contains("整合") ||
            normalized.contains("总结") ||
            normalized.contains("写一篇")
        ) {
            return "RESEARCH"
        }
        if (
            normalized.contains("search") ||
            normalized.contains("find") ||
            normalized.contains("lookup") ||
            normalized.contains("搜索") ||
            normalized.contains("查找")
        ) {
            return "SEARCH"
        }
        return "GENERAL"
    }

    private fun inferSearchQuery(text: String): String {
        if (text.isBlank()) return ""

        val patterns = listOf(
            Regex("(?:search|find|lookup|搜索|查找)\\s*[\"“”']?([^,，。.!！？:：]{1,40})", RegexOption.IGNORE_CASE),
            Regex("(?:about|关于)\\s*[\"“”']?([^,，。.!！？:：]{1,40})", RegexOption.IGNORE_CASE),
        )

        for (pattern in patterns) {
            val result = pattern.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
            if (result.isNotBlank()) {
                return result
                    .removeSuffix("的视频")
                    .removeSuffix("视频")
                    .removeSuffix("的信息")
                    .removeSuffix("资料")
                    .trim()
            }
        }
        return ""
    }

    private fun sanitizeResearchDepth(depth: Int): Int = depth.coerceIn(1, 8)

    private fun parseTaskMode(raw: String, fallback: String): String {
        return when (raw.uppercase()) {
            "GENERAL", "SEARCH", "RESEARCH", "HOMEWORK" -> raw.uppercase()
            else -> fallback
        }
    }

    private fun parseHomeworkPolicy(raw: String, fallback: String): String {
        return when (raw.uppercase()) {
            "REFERENCE_ONLY", "NAVIGATION_ONLY" -> raw.uppercase()
            else -> fallback
        }
    }

    private fun parseCandidates(arr: JSONArray?): List<AppCandidate> {
        if (arr == null || arr.length() == 0) return emptyList()
        val result = mutableListOf<AppCandidate>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            result += AppCandidate(
                appName = obj.optString("app_name", ""),
                packageName = obj.optString("package_name", ""),
                reason = obj.optString("reason", ""),
            )
        }
        return result
    }

    private fun normalizeCandidates(candidates: List<AppCandidate>, latestUser: String): List<AppCandidate> {
        if (installedApps.isEmpty()) return candidates.distinctBy { it.packageName }.take(3)

        val installedByPkg = installedApps.associateBy { it.packageName }
        val normalized = candidates
            .asSequence()
            .mapNotNull { candidate ->
                val installed = installedByPkg[candidate.packageName] ?: return@mapNotNull null
                AppCandidate(
                    appName = installed.appName,
                    packageName = installed.packageName,
                    reason = candidate.reason.ifBlank { "Related to your request" },
                )
            }
            .distinctBy { it.packageName }
            .toMutableList()

        if (normalized.size < 2 && latestUser.isNotBlank()) {
            val fuzzy = InstalledAppScanner.fuzzyMatch(latestUser, installedApps, maxResults = 3)
            for (app in fuzzy) {
                if (normalized.any { it.packageName == app.packageName }) continue
                normalized += AppCandidate(
                    appName = app.appName,
                    packageName = app.packageName,
                    reason = "Related to your description",
                )
                if (normalized.size >= 3) break
            }
        }

        if (normalized.size < 2) {
            for (app in installedApps) {
                if (normalized.any { it.packageName == app.packageName }) continue
                normalized += AppCandidate(
                    appName = app.appName,
                    packageName = app.packageName,
                    reason = "Available on your device",
                )
                if (normalized.size >= 2) break
            }
        }
        return normalized.take(3)
    }

    private fun requestChatGoalViaBff(messages: List<SimpleChatMessage>): GoalChatResult {
        val baseUrl = BuildConfig.MOBILE_AGENT_BASE_URL.trimEnd('/')
        val url = URL("$baseUrl/api/chat-goal")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 15_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${com.immersive.ui.BuildConfig.MOBILE_AGENT_AUTH_TOKEN}")
        }

        val requestBody = JSONObject().apply {
            put(
                "messages",
                JSONArray().apply {
                    messages.takeLast(40).forEach { msg ->
                        put(
                            JSONObject().apply {
                                put("role", msg.role)
                                put("content", msg.content)
                            },
                        )
                    }
                },
            )
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(requestBody.toString())
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
        if (code !in 200..299) {
            throw IllegalStateException("chat-goal failed: HTTP $code | $body")
        }

        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) {
            throw IllegalStateException("chat-goal returned success=false")
        }

        val latestUser = messages.lastOrNull { it.role == "user" }?.content.orEmpty()
        val fallbackMode = inferTaskMode(latestUser)
        val fallbackQuery = inferSearchQuery(latestUser)
        val candidates = normalizeCandidates(
            candidates = parseCandidates(root.optJSONArray("candidates")),
            latestUser = latestUser,
        )
        return GoalChatResult(
            reply = root.optString("reply", "Please tell me your goal."),
            inferredGoal = root.optString("inferred_goal", latestUser.ifBlank { "Unknown goal" }),
            targetAppName = root.optString("target_app_name", ""),
            readyToStart = root.optBoolean("ready_to_start", false),
            candidates = candidates,
            taskMode = parseTaskMode(root.optString("task_mode", fallbackMode), fallbackMode),
            searchQuery = root.optString("search_query", fallbackQuery).ifBlank { fallbackQuery },
            researchDepth = sanitizeResearchDepth(root.optInt("research_depth", 3)),
            homeworkPolicy = parseHomeworkPolicy(
                root.optString("homework_policy", "REFERENCE_ONLY"),
                "REFERENCE_ONLY",
            ),
            askOnUncertain = root.optBoolean("ask_on_uncertain", true),
        )
    }

    private fun requestGeminiJson(prompt: String, imageBase64: String?): JSONObject {
        return requestGeminiJsonPublic(prompt, imageBase64)
    }

    fun requestGeminiJsonPublic(prompt: String, imageBase64: String?): JSONObject {
        val baseUrl = BuildConfig.MOBILE_AGENT_BASE_URL.trimEnd('/')
        val url = URL("$baseUrl/api/mobile-agent/internal/gemini-json")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 25_000
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${com.immersive.ui.BuildConfig.MOBILE_AGENT_AUTH_TOKEN}")
        }

        val requestBody = JSONObject().apply {
            put("prompt", prompt)
            if (!imageBase64.isNullOrBlank()) {
                put("image_base64", imageBase64)
            }
        }

        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(requestBody.toString())
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readText)
        if (code !in 200..299) {
            throw IllegalStateException("BFF gemini-json failed: HTTP $code | $body")
        }

        val root = JSONObject(body)
        if (!root.optBoolean("success", false)) {
            throw IllegalStateException("gemini-json returned success=false: $body")
        }
        return root.optJSONObject("json")
            ?: throw IllegalStateException("gemini-json missing json payload")
    }

    private fun parseBbox(raw: Any?): IntArray? {
        val arr = raw as? JSONArray ?: return null
        if (arr.length() != 4) return null
        val result = IntArray(4)
        for (index in 0 until 4) {
            result[index] = arr.optDouble(index, -1.0).toInt()
        }
        if (result.any { it !in 0..1000 }) return null
        if (result[2] <= result[0] || result[3] <= result[1]) return null
        return result
    }
}
