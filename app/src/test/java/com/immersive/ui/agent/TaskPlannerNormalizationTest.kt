package com.immersive.ui.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPlannerNormalizationTest {

    @Test
    fun normalizePlan_forSearch_buildsFiveCoreSteps() {
        val taskSpec = TaskSpec(
            mode = TaskMode.SEARCH,
            searchQuery = "sima ku",
        )
        val plan = TaskPlanner.normalizePlan(
            goal = "Open YouTube and search",
            targetApp = "YouTube",
            taskSpec = taskSpec,
            baseSteps = emptyList(),
        )

        assertEquals(5, plan.steps.size)
        assertTrue(plan.steps.all { it.description.isNotBlank() })
        assertTrue(plan.steps.all { it.expectedResult.isNotBlank() })
    }

    @Test
    fun normalizePlan_forResearch_respectsDepthAndAddsSummary() {
        val taskSpec = TaskSpec(
            mode = TaskMode.RESEARCH,
            searchQuery = "Svate",
            researchDepth = 3,
        )
        val plan = TaskPlanner.normalizePlan(
            goal = "Use Chrome to research Svate and summarize",
            targetApp = "Chrome",
            taskSpec = taskSpec,
            baseSteps = emptyList(),
        )

        assertEquals(8, plan.steps.size)
        assertTrue(plan.steps.last().description.isNotBlank())
    }

    @Test
    fun normalizePlan_forHomework_doesNotPlanAutoSubmit() {
        val taskSpec = TaskSpec(
            mode = TaskMode.HOMEWORK,
            homeworkPolicy = HomeworkPolicy.REFERENCE_ONLY,
        )
        val plan = TaskPlanner.normalizePlan(
            goal = "Open homework app and prepare a reference answer",
            targetApp = "StudyApp",
            taskSpec = taskSpec,
            baseSteps = emptyList(),
        )

        val stepText = plan.steps.joinToString(" ") { it.description + " " + it.expectedResult }
        assertEquals(5, plan.steps.size)
        assertFalse(stepText.contains("submit", ignoreCase = true))
    }
}
