package com.immersive.ui.agent.flow

import android.content.Context
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.TaskMemory
import com.immersive.ui.agent.AgentContext
import com.immersive.ui.agent.StepRecord
import com.immersive.ui.agent.UiNode

/**
 * Verification module: post-execution validation.
 *
 * Extracted from OpenClawOrchestrator, responsible for:
 * - Validating post-execution UI state against checkpoints
 * - Building StepRecord entries
 * - Saving successful task memories
 */
class VerificationModule(
    private val context: Context,
) {
    /**
     * Get post-execution UI nodes and the foreground package name.
     */
    fun getPostExecutionState(): Pair<List<UiNode>, String?> {
        val nodes = AgentAccessibilityService.instance?.getUiNodes() ?: emptyList()
        val pkg = AgentAccessibilityService.instance?.getForegroundPackageName()
        return nodes to pkg
    }

    /**
     * Verify whether the checkpoint matches.
     */
    fun verifyCheckpoint(
        action: AgentAction,
        foregroundPackage: String?,
        nodes: List<UiNode>,
    ): Boolean {
        if (action.expectedPackage.isNullOrBlank() && action.expectedElements.isEmpty()) {
            return true
        }

        val packageMatched = action.expectedPackage.isNullOrBlank() ||
            foregroundPackage.equals(action.expectedPackage, ignoreCase = true)
        if (!packageMatched) return false

        if (action.expectedElements.isEmpty()) return true
        return action.expectedElements.any { expected ->
            nodes.any { node ->
                node.text.contains(expected, ignoreCase = true) ||
                    node.contentDesc.contains(expected, ignoreCase = true)
            }
        }
    }

    /**
     * Build a step record.
     */
    fun buildStepRecord(
        stepIndex: Int,
        action: AgentAction,
        executionSuccess: Boolean,
        checkpointMatched: Boolean,
        reviewerVerdict: String?,
        resolveScore: Int?,
        failCode: String?,
    ): StepRecord {
        val stepSuccess = executionSuccess && checkpointMatched
        return StepRecord(
            stepIndex = stepIndex,
            action = action,
            success = stepSuccess,
            resultSummary = if (stepSuccess) "Action executed." else "Action failed.",
            reviewVerdict = reviewerVerdict,
            resolveScore = resolveScore,
            checkpointMatched = checkpointMatched,
            failCode = failCode,
        )
    }

    /**
     * Save a successful task to memory.
     */
    suspend fun saveMemory(ctx: AgentContext) {
        if (ctx.history.isEmpty()) return
        TaskMemory.saveSuccess(
            context = context,
            goal = ctx.globalGoal,
            targetApp = ctx.targetAppName,
            steps = ctx.history,
        )
    }
}
