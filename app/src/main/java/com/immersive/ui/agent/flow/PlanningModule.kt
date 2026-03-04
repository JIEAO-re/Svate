package com.immersive.ui.agent.flow

import android.content.Context
import com.immersive.ui.agent.AgentContext
import com.immersive.ui.agent.ObservationReason
import com.immersive.ui.agent.TaskMemory
import com.immersive.ui.agent.TaskPlan
import com.immersive.ui.agent.TaskPlanner
import com.immersive.ui.agent.TaskSpec

/**
 * 规划模块：决策规划逻辑
 *
 * 从 OpenClawOrchestrator 拆分而来，负责：
 * - 任务分解（调用 TaskPlanner）
 * - 请求云端决策（通过 LiveDecisionChannel）
 * - 历史记忆检索
 */
class PlanningModule(
    private val context: Context,
    private val decisionChannel: LiveDecisionChannel,
) {
    /**
     * 分解任务为子步骤计划
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
     * 请求云端决策
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
