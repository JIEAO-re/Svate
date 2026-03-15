package com.immersive.ui.agent.flow

import android.content.Context
import com.immersive.ui.agent.AgentContext
import com.immersive.ui.agent.ObservationReason
import com.immersive.ui.agent.TaskMemory
import com.immersive.ui.agent.TaskPlan
import com.immersive.ui.agent.TaskPlanner
import com.immersive.ui.agent.TaskSpec

/**
 * Planning module: decision planning logic.
 *
 * Extracted from OpenClawOrchestrator, responsible for:
 * - Task decomposition through TaskPlanner
 * - Requesting cloud decisions through LiveDecisionChannel
 * - Retrieving historical memory
 */
class PlanningModule(
    private val context: Context,
    private val decisionChannel: LiveDecisionChannel,
) {
    /**
     * Decompose a task into a sub-step plan.
     */
    suspend fun decompose(
        goal: String,
        targetApp: String,
        taskSpec: TaskSpec,
    ): TaskPlan? {
        val memoryHint = TaskMemory.findSimilar(context, goal, targetApp)
        return TaskPlanner.decompose(
            goal = goal,
            targetApp = targetApp,
            taskSpec = taskSpec,
            memoryHint = memoryHint,
        )
    }

    /**
     * Request a decision from the cloud.
     */
    suspend fun requestDecision(
        ctx: AgentContext,
        snapshot: PerceptionSnapshot,
        observationReason: ObservationReason,
        gcsUri: String? = null,
    ): DecisionResult? {
        return decisionChannel.requestDecision(
            ctx = ctx,
            snapshot = snapshot,
            observationReason = observationReason,
            gcsUri = gcsUri,
        )
    }
}
