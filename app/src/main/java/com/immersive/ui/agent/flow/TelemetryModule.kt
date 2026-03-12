package com.immersive.ui.agent.flow

import android.content.Context
import com.immersive.ui.agent.AgentContext
import com.immersive.ui.agent.AgentPhase
import com.immersive.ui.agent.TelemetryReporter
import org.json.JSONObject

/**
 * Reports session-level telemetry for the orchestrator lifecycle.
 */
class TelemetryModule(
    context: Context,
) {
    private val reporter = TelemetryReporter(context)

    /** Report the start of a session. */
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

    /** Report the end of a session. */
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

    /** Report an arbitrary telemetry event. */
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
