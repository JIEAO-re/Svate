package com.immersive.ui.agent.flow

import android.content.Context
import com.immersive.ui.agent.AgentContext
import com.immersive.ui.agent.AgentPhase
import com.immersive.ui.agent.TelemetryReporter
import org.json.JSONObject

/**
 * 遥测模块：遥测上报
 *
 * 从 OpenClawOrchestrator 拆分而来，负责：
 * - session_start / session_end 事件上报
 * - 步骤级遥测（可扩展）
 */
class TelemetryModule(
    context: Context,
) {
    private val reporter = TelemetryReporter(context)

    /**
     * 上报 session 开始事件
     */
    fun reportSessionStart(ctx: AgentContext) {
        reporter.report(
            sessionId = ctx.traceId ?: "sess_unknown",
            traceId = ctx.traceId ?: "trace_unknown",
            turnIndex = 0,
            eventType = "session_start",
            payload = JSONObject().apply {
                put("goal", ctx.globalGoal)
                put("target_app", ctx.targetAppName)
                put("task_mode", ctx.taskSpec.mode.name)
                put("orchestrator", "OpenClawOrchestrator")
            },
        )
    }

    /**
     * 上报 session 结束事件
     */
    fun reportSessionEnd(ctx: AgentContext) {
        reporter.report(
            sessionId = ctx.traceId ?: "sess_unknown",
            traceId = ctx.traceId ?: "trace_unknown",
            turnIndex = ctx.stepIndex,
            eventType = "session_end",
            payload = JSONObject().apply {
                put("phase", ctx.phase.name)
                put("completed", ctx.phase == AgentPhase.COMPLETED)
                put("history_size", ctx.history.size)
            },
        )
    }

    /**
     * 通用事件上报
     */
    fun reportEvent(
        sessionId: String,
        traceId: String,
        turnIndex: Int,
        eventType: String,
        payload: JSONObject,
    ) {
        reporter.report(
            sessionId = sessionId,
            traceId = traceId,
            turnIndex = turnIndex,
            eventType = eventType,
            payload = payload,
        )
    }
}
