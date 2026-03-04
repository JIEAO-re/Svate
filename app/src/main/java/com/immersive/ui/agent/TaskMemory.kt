package com.immersive.ui.agent

import android.content.Context
import com.immersive.ui.data.AgentMemoryEntity
import com.immersive.ui.data.AppDatabase
import org.json.JSONArray
import kotlin.math.min

/**
 * Long-term action memory backed by Room.
 */
data class MemoryRecord(
    val goal: String,
    val targetApp: String,
    val steps: List<String>,
    val successCount: Int,
    val lastUsed: Long,
)

object TaskMemory {

    private const val MAX_RECORDS = 80

    suspend fun saveSuccess(context: Context, goal: String, targetApp: String, steps: List<StepRecord>) {
        val stepDescriptions = steps
            .filter { it.success }
            .map { "${it.action.intent}: ${it.action.targetDesc}" }
        if (stepDescriptions.isEmpty()) return

        val db = AppDatabase.get(context)
        val dao = db.agentMemoryDao()
        val now = System.currentTimeMillis()
        val normalizedGoal = normalizeGoal(goal)
        val existing = dao.findByGoalAndApp(normalizedGoal, targetApp)
        val payload = JSONArray(stepDescriptions).toString()

        val merged = if (existing != null) {
            existing.copy(
                goal = goal,
                goalNorm = normalizedGoal,
                targetApp = targetApp,
                stepsJson = payload,
                successCount = existing.successCount + 1,
                lastUsed = now,
            )
        } else {
            AgentMemoryEntity(
                goal = goal,
                goalNorm = normalizedGoal,
                targetApp = targetApp,
                stepsJson = payload,
                successCount = 1,
                lastUsed = now,
            )
        }
        dao.upsert(merged)
        dao.trimToMaxRows(MAX_RECORDS)
    }

    suspend fun findSimilar(context: Context, goal: String, targetApp: String): String? {
        val dao = AppDatabase.get(context).agentMemoryDao()
        val records = dao.findByTargetApp(targetApp, limit = 100)
        if (records.isEmpty()) return null

        val normalizedGoal = normalizeGoal(goal)
        val queryTokens = tokenize(normalizedGoal)
        if (queryTokens.isEmpty()) return null

        val best = records
            .map { record ->
                val recordTokens = tokenize(record.goalNorm)
                val similarity = scoreSimilarity(queryTokens, recordTokens)
                val successBoost = 1f + min(record.successCount, 10) * 0.08f
                val score = similarity * successBoost
                record to score
            }
            .maxByOrNull { it.second }
            ?: return null

        val (match, score) = best
        if (score < 0.35f) return null
        val steps = parseSteps(match.stepsJson)
        if (steps.isEmpty()) return null

        return "目标：${match.goal}（成功${match.successCount}次）\n步骤：\n" +
            steps.mapIndexed { index, step -> "  ${index + 1}. $step" }.joinToString("\n")
    }

    private fun parseSteps(raw: String): List<String> {
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val step = arr.optString(i, "").trim()
                    if (step.isNotBlank()) add(step)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeGoal(goal: String): String {
        return goal
            .lowercase()
            .replace(Regex("[\\p{Punct}\\s]+"), " ")
            .trim()
            .take(200)
    }

    private fun tokenize(text: String): Set<String> {
        val words = text.split(" ").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
        val compact = text.replace(" ", "")
        if (compact.length >= 2) {
            for (i in 0 until compact.length - 1) {
                words += compact.substring(i, i + 2)
            }
        } else if (compact.isNotBlank()) {
            words += compact
        }
        return words
    }

    private fun scoreSimilarity(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val intersection = a.intersect(b).size.toFloat()
        val union = a.union(b).size.toFloat()
        return if (union == 0f) 0f else intersection / union
    }
}
