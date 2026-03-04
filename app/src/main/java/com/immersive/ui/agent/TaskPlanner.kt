package com.immersive.ui.agent

import com.immersive.ui.guide.GuideAiEngines
import org.json.JSONObject

data class SubStep(
    val index: Int,
    val description: String,
    val expectedResult: String,
    val maxAttempts: Int = 5,
)

data class TaskPlan(
    val goal: String,
    val targetApp: String,
    val steps: List<SubStep>,
)

object TaskPlanner {
    private val PLANNER_PROMPT = """
You are a mobile task planner.
Decompose the user's goal into 3-8 concrete sub-steps.
Return strict JSON only:
{
  "steps": [
    {
      "index": 0,
      "description": "what to do",
      "expected_result": "how to verify done"
    }
  ]
}

Rules:
1) First step is usually opening target app if not open.
2) One atomic operation per step.
3) expected_result must be observable on UI.
4) Never include payment/password/high-risk execution.
5) For SEARCH tasks include: focus search box -> type query -> submit -> open first result.
6) For RESEARCH tasks cover multiple sources and final synthesis.
7) For HOMEWORK tasks only generate reference draft flow, never submit.
    """.trimIndent()

    fun decompose(
        goal: String,
        targetApp: String,
        taskSpec: TaskSpec = TaskSpec(),
        memoryHint: String? = null,
    ): TaskPlan? {
        val contextPart = buildString {
            appendLine("Goal: $goal")
            appendLine("Target app: $targetApp")
            appendLine("Task mode: ${taskSpec.mode}")
            appendLine("Search query: ${taskSpec.searchQuery.ifBlank { "(none)" }}")
            appendLine("Research depth: ${taskSpec.researchDepth}")
            appendLine("Homework policy: ${taskSpec.homeworkPolicy}")
            if (!memoryHint.isNullOrBlank()) {
                appendLine()
                appendLine("Reference from similar successful runs:")
                appendLine(memoryHint)
            }
        }

        val fullPrompt = "$PLANNER_PROMPT\n\n$contextPart"
        return try {
            val json = GuideAiEngines.requestGeminiJsonPublic(fullPrompt, imageBase64 = null)
            val aiPlan = parsePlan(goal, targetApp, json)
            normalizePlan(goal, targetApp, taskSpec, aiPlan?.steps.orEmpty())
        } catch (_: Exception) {
            normalizePlan(goal, targetApp, taskSpec, emptyList())
        }
    }

    private fun parsePlan(goal: String, targetApp: String, json: JSONObject): TaskPlan? {
        val stepsArr = json.optJSONArray("steps") ?: return null
        if (stepsArr.length() == 0) return null

        val steps = mutableListOf<SubStep>()
        for (i in 0 until stepsArr.length()) {
            val obj = stepsArr.optJSONObject(i) ?: continue
            val desc = obj.optString("description", "").trim()
            val expected = obj.optString("expected_result", "").trim()
            if (desc.isBlank() || expected.isBlank()) continue
            steps += SubStep(
                index = obj.optInt("index", i),
                description = desc,
                expectedResult = expected,
            )
        }
        if (steps.isEmpty()) return null
        return TaskPlan(goal = goal, targetApp = targetApp, steps = steps)
    }

    fun normalizePlan(
        goal: String,
        targetApp: String,
        taskSpec: TaskSpec,
        baseSteps: List<SubStep>,
    ): TaskPlan {
        val app = targetApp.ifBlank { "Target App" }
        val query = taskSpec.searchQuery.ifBlank { inferSearchQueryFromGoal(goal) }
        val normalizedSteps = when (taskSpec.mode) {
            TaskMode.SEARCH -> buildSearchPlan(app, query)
            TaskMode.RESEARCH -> buildResearchPlan(app, query, taskSpec.researchDepth)
            TaskMode.HOMEWORK -> buildHomeworkPlan(app, goal)
            TaskMode.GENERAL -> normalizeGeneralPlan(app, goal, baseSteps)
        }
        return TaskPlan(
            goal = goal,
            targetApp = app,
            steps = normalizedSteps.mapIndexed { idx, step -> step.copy(index = idx) },
        )
    }

    private fun normalizeGeneralPlan(app: String, goal: String, baseSteps: List<SubStep>): List<SubStep> {
        if (baseSteps.size >= 3) {
            return baseSteps.mapIndexed { idx, step ->
                step.copy(
                    index = idx,
                    description = step.description.ifBlank { "Complete step ${idx + 1}" },
                    expectedResult = step.expectedResult.ifBlank { "UI changes to the next actionable page" },
                )
            }
        }
        return listOf(
            SubStep(
                index = 0,
                description = "Open $app",
                expectedResult = "Successfully enters $app home page",
            ),
            SubStep(
                index = 1,
                description = "Find the feature entry related to the goal",
                expectedResult = "An entry relevant to \"$goal\" is visible",
            ),
            SubStep(
                index = 2,
                description = "Perform the target operation and stay on the result page",
                expectedResult = "The result page is visible for user verification",
            ),
        )
    }

    private fun buildSearchPlan(app: String, query: String): List<SubStep> {
        val safeQuery = query.ifBlank { "target keyword" }
        return listOf(
            SubStep(
                index = 0,
                description = "Open $app",
                expectedResult = "Successfully enters $app home page",
            ),
            SubStep(
                index = 1,
                description = "Tap the search entry and focus the input box",
                expectedResult = "A searchable text input is focused",
            ),
            SubStep(
                index = 2,
                description = "Type \"$safeQuery\"",
                expectedResult = "The input box shows \"$safeQuery\"",
            ),
            SubStep(
                index = 3,
                description = "Submit the search",
                expectedResult = "Search result list for \"$safeQuery\" appears",
            ),
            SubStep(
                index = 4,
                description = "Open the first result",
                expectedResult = "The first result detail page is opened",
            ),
        )
    }

    private fun buildResearchPlan(app: String, query: String, depth: Int): List<SubStep> {
        val safeDepth = depth.coerceIn(1, 8)
        val safeQuery = query.ifBlank { "target keyword" }
        val steps = mutableListOf<SubStep>()
        steps += SubStep(
            index = 0,
            description = "Open $app",
            expectedResult = "Successfully enters $app home page",
        )
        steps += SubStep(
            index = 1,
            description = "Search \"$safeQuery\"",
            expectedResult = "A result list for \"$safeQuery\" appears",
        )
        for (i in 1..safeDepth) {
            steps += SubStep(
                index = steps.size,
                description = "Open source #$i and extract key points",
                expectedResult = "Key points from source #$i are captured",
            )
            if (i < safeDepth) {
                steps += SubStep(
                    index = steps.size,
                    description = "Go back to search results",
                    expectedResult = "Search result list is visible again",
                )
            }
        }
        steps += SubStep(
            index = steps.size,
            description = "Synthesize captured information into a structured summary",
            expectedResult = "A copyable summary is posted in chat",
        )
        return steps
    }

    private fun buildHomeworkPlan(app: String, goal: String): List<SubStep> {
        val homeworkLabel = if (goal.contains("chapter", ignoreCase = true)) "target chapter homework" else "target homework"
        return listOf(
            SubStep(
                index = 0,
                description = "Open $app and enter homework section",
                expectedResult = "Homework list or section entry appears",
            ),
            SubStep(
                index = 1,
                description = "Open \"$homeworkLabel\"",
                expectedResult = "Question content is visible",
            ),
            SubStep(
                index = 2,
                description = "Extract key question information",
                expectedResult = "Question text is captured for drafting",
            ),
            SubStep(
                index = 3,
                description = "Generate a reference draft only",
                expectedResult = "A draft answer is shown in chat",
            ),
            SubStep(
                index = 4,
                description = "Remind user to review and hand in manually",
                expectedResult = "No automatic hand-in action is executed",
            ),
        )
    }

    private fun inferSearchQueryFromGoal(goal: String): String {
        val patterns = listOf(
            Regex("(?:search|find|lookup|搜索|查找)\\s*[\"“”']?([^,，。.!！？:：]{1,40})", RegexOption.IGNORE_CASE),
            Regex("(?:about|关于)\\s*[\"“”']?([^,，。.!！？:：]{1,40})", RegexOption.IGNORE_CASE),
        )
        for (pattern in patterns) {
            val matched = pattern.find(goal)?.groupValues?.getOrNull(1).orEmpty().trim()
            if (matched.isNotBlank()) {
                return matched
                    .removeSuffix("的视频")
                    .removeSuffix("视频")
                    .removeSuffix("的信息")
                    .removeSuffix("资料")
                    .trim()
            }
        }
        return ""
    }
}
