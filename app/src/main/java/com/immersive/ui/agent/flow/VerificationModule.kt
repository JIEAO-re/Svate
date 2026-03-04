package com.immersive.ui.agent.flow

import android.content.Context
import com.immersive.ui.agent.AgentAccessibilityService
import com.immersive.ui.agent.AgentAction
import com.immersive.ui.agent.TaskMemory
import com.immersive.ui.agent.AgentContext
import com.immersive.ui.agent.StepRecord
import com.immersive.ui.agent.UiNode

/**
 * 验证模块：执行后验证
 *
 * 从 OpenClawOrchestrator 拆分而来，负责：
 * - 执行后 UI 状态校验（checkpoint 匹配）
 * - 构建 StepRecord
 * - 成功任务记忆保存
 */
class VerificationModule(
    private val context: Context,
) {
    /**
     * 获取执行后的 UI 节点和前台包名
     */
    fun getPostExecutionState(): Pair<List<UiNode>, String?> {
        val nodes = AgentAccessibilityService.instance?.getUiNodes() ?: emptyList()
        val pkg = AgentAccessibilityService.instance?.getForegroundPackageName()
        return nodes to pkg
    }

    /**
     * 验证 checkpoint 是否匹配
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
     * 构建步骤记录
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
     * 保存成功任务到记忆
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
