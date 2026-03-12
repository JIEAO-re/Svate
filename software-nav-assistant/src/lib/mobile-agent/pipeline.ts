import { randomUUID } from "crypto";
import { after } from "next/server";
import {
  NextStepRequest,
  NextStepResponse,
  NextStepResponseSchema,
} from "@/lib/schemas/mobile-agent";
import { runPlanner } from "@/lib/mobile-agent/planner";
import { runReviewer } from "@/lib/mobile-agent/reviewer";
import { arbitrateDecision } from "@/lib/mobile-agent/arbiter";
import { compareShadowActions } from "@/lib/mobile-agent/shadow-comparator";
import {
  saveGeneratedMedia,
  saveLiveTurnMetric,
  saveShadowDiff,
  saveTurnEvent,
  saveTelemetryEvents,
} from "@/lib/mobile-agent/persistence";
import { maybeGenerateGuideImage } from "@/lib/mobile-agent/guide-media";
import { enqueueSessionRecapVideo } from "@/lib/mobile-agent/session-recap-video";

// Guide media and persistence should not stall the online decision path.
function fireAndForgetGuideMedia(params: {
  traceId: string;
  request: NextStepRequest;
  action: NextStepResponse["final_action"];
}) {
  maybeGenerateGuideImage({
    traceId: params.traceId,
    request: params.request,
    action: params.action,
  })
    .then((media) => {
      if (!media) return;
      return saveGeneratedMedia({
        trace_id: params.traceId,
        session_id: params.request.session_id,
        turn_index: params.request.turn_index,
        media_type: "IMAGE",
        storage_uri: media.storage_uri,
        signed_url: media.signed_url,
        expires_at: media.expires_at,
        created_at: new Date().toISOString(),
      });
    })
    .catch((err) => {
      console.warn("[pipeline] guide media fire-and-forget failed:", err);
    });
}

export async function runMobileAgentPipeline(
  request: NextStepRequest,
): Promise<NextStepResponse> {
  const traceId = randomUUID();

  let plannerRun = await runPlanner(request);
  let reviewerRun = await runReviewer(request, plannerRun.output.candidates);
  let replanExhausted = false;

  // REPLAN once.
  if (reviewerRun.output.verdict === "REPLAN") {
    plannerRun = await runPlanner(request);
    reviewerRun = await runReviewer(request, plannerRun.output.candidates);
    replanExhausted = reviewerRun.output.verdict === "REPLAN";
  }

  const arbiter = arbitrateDecision(
    request,
    plannerRun.output.candidates,
    reviewerRun.output,
    replanExhausted,
  );
  const finalCheckpoint = arbiter.finalAction.checkpoint ?? {
    expected_package: "",
    expected_page_type: "",
    expected_elements: [],
  };
  const expectedPackage =
    arbiter.finalAction.intent === "OPEN_APP" && arbiter.finalAction.package_name
      ? arbiter.finalAction.package_name
      : arbiter.finalAction.intent === "HOME"
        ? ""
        : finalCheckpoint.expected_package || request.observation.foreground_package;
  const observationSource = request.observation.media_window?.source ?? "SCREENSHOT";
  const frameCount = request.observation.media_window?.frames.length ?? (request.observation.screenshot_base64 ? 1 : 0);

  const response: NextStepResponse = {
    trace_id: traceId,
    planner: {
      model: plannerRun.model,
      candidates: plannerRun.output.candidates,
      latency_ms: plannerRun.latencyMs,
    },
    reviewer: {
      model: reviewerRun.model,
      verdict: reviewerRun.output.verdict,
      reason: reviewerRun.output.reason,
      approved_action_index: reviewerRun.output.approved_action_index,
      latency_ms: reviewerRun.latencyMs,
    },
    final_action: arbiter.finalAction,
    checkpoint: {
      expected_package: expectedPackage,
      expected_page_type: finalCheckpoint.expected_page_type || "UNKNOWN",
      expected_elements: finalCheckpoint.expected_elements || [],
    },
    guard: {
      risk_level: arbiter.finalAction.risk_level,
      block_reason: arbiter.blockReason,
    },
    live_runtime: {
      used_live: true,
      model: plannerRun.model,
      connect_latency_ms: plannerRun.connectLatencyMs,
      inference_latency_ms: plannerRun.inferenceLatencyMs,
    },
    guide_media: null,
  };

  const parsed = NextStepResponseSchema.parse(response);

  // Keep guide media and recap work out of the response path.
  after(() => {
    fireAndForgetGuideMedia({
      traceId,
      request,
      action: parsed.final_action,
    });

    if (parsed.final_action.intent === "FINISH") {
      void enqueueSessionRecapVideo({
        sessionId: request.session_id,
        traceId: parsed.trace_id,
        goal: request.goal,
      }).catch((err) => {
        console.warn("[pipeline] session recap enqueue failed:", err);
      });
    }
  });

  // Persist the core records before returning so turn state stays consistent.
  const shadowDiff = compareShadowActions(request.shadow_control_action, parsed.final_action);
  const pathTelemetry = {
    used_target_som_id: parsed.final_action.target_som_id != null,
    used_open_intent: parsed.final_action.intent === "OPEN_INTENT",
    used_intent_spec: parsed.final_action.intent_spec != null,
    observation_has_media_window: !!request.observation.media_window?.frames.length,
    observation_legacy_screenshot: !!request.observation.screenshot_base64,
  };

  const persistenceJobs: Promise<void>[] = [];

  if (shadowDiff) {
    persistenceJobs.push(
      saveShadowDiff({
        trace_id: parsed.trace_id,
        session_id: request.session_id,
        turn_index: request.turn_index,
        same_intent: shadowDiff.same_intent,
        same_target_desc: shadowDiff.same_target_desc,
        candidate_would_fail_reason: shadowDiff.candidate_would_fail_reason,
        created_at: new Date().toISOString(),
      }),
    );
  }

  persistenceJobs.push(
    saveLiveTurnMetric({
      trace_id: parsed.trace_id,
      session_id: request.session_id,
      turn_index: request.turn_index,
      model: parsed.live_runtime.model,
      connect_latency_ms: parsed.live_runtime.connect_latency_ms,
      inference_latency_ms: parsed.live_runtime.inference_latency_ms,
      frame_count: frameCount,
      observation_source: observationSource,
      created_at: new Date().toISOString(),
    }),
  );

  persistenceJobs.push(
    saveTurnEvent({
      trace_id: parsed.trace_id,
      session_id: request.session_id,
      turn_index: request.turn_index,
      planner_model: parsed.planner.model,
      planner_latency_ms: parsed.planner.latency_ms,
      reviewer_model: parsed.reviewer.model,
      reviewer_latency_ms: parsed.reviewer.latency_ms,
      reviewer_verdict: parsed.reviewer.verdict,
      final_intent: parsed.final_action.intent,
      final_risk: parsed.final_action.risk_level,
      block_reason: parsed.guard.block_reason,
      mode: request.mode,
      shadow_diff: {
        ...(shadowDiff || {}),
        ...pathTelemetry,
      },
      created_at: new Date().toISOString(),
    }),
  );

  // Persist core records in parallel; failures should not block the response.
  await Promise.allSettled(persistenceJobs);

  // Record per-turn cost telemetry after the main response is ready.
  const totalLatencyMs = plannerRun.latencyMs + reviewerRun.latencyMs;
  const estimatedPromptTokens = Math.ceil(
    (JSON.stringify(request.observation.ui_nodes).length + (request.goal?.length ?? 0)) / 4,
  );
  void saveTelemetryEvents([
    {
      trace_id: parsed.trace_id,
      session_id: request.session_id,
      turn_index: request.turn_index,
      event_type: "turn_cost_telemetry",
      payload: {
        planner_model: parsed.planner.model,
        planner_latency_ms: parsed.planner.latency_ms,
        reviewer_model: parsed.reviewer.model,
        reviewer_latency_ms: parsed.reviewer.latency_ms,
        total_latency_ms: totalLatencyMs,
        connect_latency_ms: parsed.live_runtime.connect_latency_ms,
        inference_latency_ms: parsed.live_runtime.inference_latency_ms,
        frame_count: frameCount,
        gcs_frame_count: plannerRun.gcsFrameCount,
        observation_source: observationSource,
        estimated_prompt_tokens: estimatedPromptTokens,
        reviewer_verdict: parsed.reviewer.verdict,
        final_intent: parsed.final_action.intent,
        replan_exhausted: replanExhausted,
      },
      ts: new Date().toISOString(),
    },
  ]).catch(() => {
    // Never block on telemetry failure
  });

  return parsed;
}

