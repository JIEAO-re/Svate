/**
 * Client-server contract tests.
 *
 * Verify the payload structure sent by Android CloudDecisionClient.kt
 * stays aligned with the server-side Zod schemas (NextStepRequestSchema / NextStepResponseSchema).
 *
 * Each case simulates real JSON from Android and uses Zod validation to prevent contract regressions.
 */
import { describe, it, expect } from "vitest";
import {
  NextStepRequestSchema,
  NextStepResponseSchema,
  ActionCommandSchema,
  TelemetryBatchRequestSchema,
} from "@/lib/schemas/mobile-agent";

// ---------------------------------------------------------------------------
// Helper: build a minimal valid observation with media_window
// ---------------------------------------------------------------------------
function makeObservation(overrides: Record<string, unknown> = {}) {
  return {
    observation_reason: "UI_CHANGED",
    foreground_package: "com.example.app",
    media_window: {
      source: "SCREEN_RECORDING",
      frames: [
        {
          frame_id: "f1",
          ts_ms: 1000,
          image_base64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk",
          ui_signature: "sig_abc",
        },
      ],
    },
    ui_nodes: [
      {
        class_name: "android.widget.TextView",
        text: "Hello",
        content_desc: "",
        resource_id: "com.example.app:id/title",
        package_name: "com.example.app",
        bounds: [0, 0, 540, 100],
        clickable: true,
        editable: false,
        scrollable: false,
      },
    ],
    previous_action_result: "NOT_EXECUTED",
    previous_checkpoint_match: false,
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Helper: build a minimal valid NextStepRequest that mirrors the Android payload
// ---------------------------------------------------------------------------
function makeRequest(overrides: Record<string, unknown> = {}) {
  return {
    session_id: "sess_1709000000000",
    turn_index: 0,
    mode: "active",
    goal: "打开微信搜索天气",
    task_spec: {
      mode: "SEARCH",
      search_query: "天气",
      ask_on_uncertain: true,
    },
    observation: makeObservation(),
    history_tail: [],
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Helper: build a minimal valid ActionCommand
// ---------------------------------------------------------------------------
function makeAction(overrides: Record<string, unknown> = {}) {
  return {
    action_id: "act_001",
    intent: "CLICK",
    target_desc: "tap the search box",
    target_som_id: 1,
    selector: {
      package_name: "com.example.app",
      resource_id: "com.example.app:id/search",
      text: "搜索",
      content_desc: "",
      class_name: "android.widget.EditText",
      bounds_hint_0_1000: [100, 200, 400, 260],
      node_signature: "com.example.app|android.widget.EditText|search",
    },
    input_text: null,
    package_name: null,
    risk_level: "SAFE",
    narration: "Tapping the search box.",
    checkpoint: {
      expected_package: "com.example.app",
      expected_page_type: "SEARCH",
      expected_elements: [],
    },
    ...overrides,
  };
}

// ===========================================================================
// 1. NextStepRequest contract tests
// ===========================================================================
describe("NextStepRequestSchema contract", () => {
  it("accepts a valid Android-style payload with media_window", () => {
    const payload = makeRequest();
    const result = NextStepRequestSchema.safeParse(payload);
    expect(result.success).toBe(true);
  });

  it("accepts a valid payload with legacy screenshot_base64 (backward compat)", () => {
    const payload = makeRequest({
      observation: makeObservation({
        media_window: undefined,
        screenshot_base64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ",
      }),
    });
    const result = NextStepRequestSchema.safeParse(payload);
    expect(result.success).toBe(true);
  });

  it("rejects payload missing session_id", () => {
    const payload = makeRequest();
    delete (payload as Record<string, unknown>).session_id;
    const result = NextStepRequestSchema.safeParse(payload);
    expect(result.success).toBe(false);
  });

  it("rejects payload with empty goal", () => {
    const payload = makeRequest({ goal: "" });
    const result = NextStepRequestSchema.safeParse(payload);
    expect(result.success).toBe(false);
  });

  it("rejects observation missing both media_window and screenshot_base64", () => {
    const payload = makeRequest({
      observation: makeObservation({
        media_window: undefined,
        screenshot_base64: undefined,
      }),
    });
    const result = NextStepRequestSchema.safeParse(payload);
    expect(result.success).toBe(false);
  });

  it("accepts payload with shadow_control_action (shadow mode)", () => {
    const payload = makeRequest({
      mode: "shadow",
      shadow_control_action: makeAction(),
    });
    const result = NextStepRequestSchema.safeParse(payload);
    expect(result.success).toBe(true);
  });

  it("rejects invalid mode value", () => {
    const payload = makeRequest({ mode: "unknown_mode" });
    const result = NextStepRequestSchema.safeParse(payload);
    expect(result.success).toBe(false);
  });

  it("rejects negative turn_index", () => {
    const payload = makeRequest({ turn_index: -1 });
    const result = NextStepRequestSchema.safeParse(payload);
    expect(result.success).toBe(false);
  });
});

// ===========================================================================
// 2. ActionCommand contract tests
// ===========================================================================
describe("ActionCommandSchema contract", () => {
  it("accepts a valid CLICK action with selector", () => {
    const action = makeAction();
    const result = ActionCommandSchema.safeParse(action);
    expect(result.success).toBe(true);
  });

  it("accepts a CLICK action with spatial_coordinates only (P1 blind-tap)", () => {
    const action = makeAction({
      selector: null,
      target_som_id: null,
      spatial_coordinates: [0.5, 0.3],
    });
    const result = ActionCommandSchema.safeParse(action);
    expect(result.success).toBe(true);
  });

  it("rejects CLICK action with no targeting method at all", () => {
    const action = makeAction({
      selector: null,
      target_som_id: null,
      spatial_coordinates: null,
      target_bbox: null,
    });
    const result = ActionCommandSchema.safeParse(action);
    expect(result.success).toBe(false);
  });

  it("rejects TYPE action without input_text", () => {
    const action = makeAction({
      intent: "TYPE",
      input_text: "",
    });
    const result = ActionCommandSchema.safeParse(action);
    expect(result.success).toBe(false);
  });

  it("rejects OPEN_APP action without package_name", () => {
    const action = makeAction({
      intent: "OPEN_APP",
      package_name: "",
      selector: null,
      target_som_id: null,
    });
    const result = ActionCommandSchema.safeParse(action);
    expect(result.success).toBe(false);
  });

  it("rejects OPEN_INTENT action without intent_spec", () => {
    const action = makeAction({
      intent: "OPEN_INTENT",
      intent_spec: null,
      selector: null,
      target_som_id: null,
    });
    const result = ActionCommandSchema.safeParse(action);
    expect(result.success).toBe(false);
  });

  it("accepts WAIT action without checkpoint", () => {
    const action = makeAction({
      intent: "WAIT",
      checkpoint: null,
      selector: null,
      target_som_id: null,
    });
    const result = ActionCommandSchema.safeParse(action);
    expect(result.success).toBe(true);
  });

  it("accepts FINISH action without checkpoint", () => {
    const action = makeAction({
      intent: "FINISH",
      checkpoint: null,
      selector: null,
      target_som_id: null,
    });
    const result = ActionCommandSchema.safeParse(action);
    expect(result.success).toBe(true);
  });

  it("rejects spatial_coordinates out of 0-1 range", () => {
    const action = makeAction({
      selector: null,
      target_som_id: null,
      spatial_coordinates: [1.5, 0.3],
    });
    const result = ActionCommandSchema.safeParse(action);
    expect(result.success).toBe(false);
  });
});

// ===========================================================================
// 3. NextStepResponse contract tests
// ===========================================================================
describe("NextStepResponseSchema contract", () => {
  it("accepts a valid full response", () => {
    const response = {
      trace_id: "tr_abc123",
      planner: {
        model: "gemini-2.0-flash",
        candidates: [makeAction()],
        latency_ms: 320,
      },
      reviewer: {
        model: "gemini-2.0-flash",
        verdict: "APPROVE",
        reason: "Action looks safe and correct.",
        approved_action_index: 0,
        latency_ms: 150,
      },
      final_action: makeAction(),
      checkpoint: {
        expected_package: "com.example.app",
        expected_page_type: "SEARCH",
        expected_elements: [],
      },
      guard: {
        risk_level: "SAFE",
        block_reason: null,
      },
      live_runtime: {
        used_live: true,
        model: "gemini-2.0-flash",
        connect_latency_ms: 80,
        inference_latency_ms: 240,
      },
      guide_media: null,
    };
    const result = NextStepResponseSchema.safeParse(response);
    expect(result.success).toBe(true);
  });

  it("rejects response missing trace_id", () => {
    const response = {
      planner: { model: "m", candidates: [makeAction()], latency_ms: 0 },
      reviewer: { model: "m", verdict: "APPROVE", reason: "ok", approved_action_index: 0, latency_ms: 0 },
      final_action: makeAction(),
      checkpoint: { expected_package: "", expected_page_type: "", expected_elements: [] },
      guard: { risk_level: "SAFE", block_reason: null },
      live_runtime: { used_live: true, model: "m", connect_latency_ms: 0, inference_latency_ms: 0 },
      guide_media: null,
    };
    const result = NextStepResponseSchema.safeParse(response);
    expect(result.success).toBe(false);
  });
});

// ===========================================================================
// 4. TelemetryBatchRequest contract tests
// ===========================================================================
describe("TelemetryBatchRequestSchema contract", () => {
  it("accepts a valid telemetry batch", () => {
    const batch = {
      events: [
        {
          trace_id: "tr_001",
          session_id: "sess_001",
          turn_index: 0,
          event_type: "action_executed",
          payload: { duration_ms: 120 },
        },
      ],
    };
    const result = TelemetryBatchRequestSchema.safeParse(batch);
    expect(result.success).toBe(true);
  });

  it("rejects telemetry payload containing screenshot_base64 (forbidden key)", () => {
    const batch = {
      events: [
        {
          trace_id: "tr_001",
          session_id: "sess_001",
          turn_index: 0,
          event_type: "debug_dump",
          payload: { screenshot_base64: "should_not_be_here" },
        },
      ],
    };
    const result = TelemetryBatchRequestSchema.safeParse(batch);
    expect(result.success).toBe(false);
  });
});

// ===========================================================================
// 5. Validate field alignment with Android CloudDecisionClient
// ===========================================================================
describe("Android CloudDecisionClient field alignment", () => {
  it("Android payload structure matches NextStepRequestSchema exactly", () => {
    // This payload mirrors CloudDecisionClient.buildRequestPayload() output
    const androidPayload = {
      session_id: "sess_1709000000000",
      turn_index: 3,
      mode: "active",
      goal: "帮我在淘宝搜索运动鞋",
      task_spec: {
        mode: "SEARCH",
        search_query: "运动鞋",
        ask_on_uncertain: true,
      },
      observation: {
        observation_reason: "AFTER_ACTION",
        foreground_package: "com.taobao.taobao",
        media_window: {
          source: "SCREEN_RECORDING",
          frames: [
            {
              frame_id: "frame_001",
              ts_ms: 1709000100,
              image_base64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ",
              ui_signature: "taobao_home_v2",
            },
          ],
        },
        som_annotated_image_base64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ",
        som_markers: [
          {
            id: 1,
            text: "搜索",
            content_desc: "搜索框",
            resource_id: "com.taobao.taobao:id/search_bar",
            class_name: "android.widget.EditText",
            package_name: "com.taobao.taobao",
            bounds: [20, 80, 520, 140],
            clickable: true,
            editable: true,
            scrollable: false,
          },
        ],
        ui_node_stats: {
          raw_count: 85,
          pruned_count: 32,
        },
        frame_fingerprint: "fp_taobao_home_001",
        screenshot_base64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ",
        ui_nodes: [
          {
            class_name: "android.widget.EditText",
            text: "",
            content_desc: "搜索框",
            resource_id: "com.taobao.taobao:id/search_bar",
            package_name: "com.taobao.taobao",
            bounds: [20, 80, 520, 140],
            clickable: true,
            editable: true,
            scrollable: false,
            visible_to_user: true,
            within_screen: true,
          },
        ],
        previous_action_result: "SUCCESS",
        previous_checkpoint_match: true,
      },
      history_tail: [
        {
          action_intent: "OPEN_APP",
          target_desc: "open Taobao",
          result: "app launched successfully",
        },
      ],
    };

    const result = NextStepRequestSchema.safeParse(androidPayload);
    expect(result.success).toBe(true);
  });

  it("Android payload with GCS URI frame reference passes validation", () => {
    const payload = makeRequest({
      observation: makeObservation({
        media_window: {
          source: "SCREEN_RECORDING",
          frames: [
            {
              frame_id: "f_gcs_001",
              ts_ms: 2000,
              gcs_uri: "gs://my-bucket/sessions/sess_001/frame_001.png",
              ui_signature: "sig_gcs",
            },
          ],
        },
      }),
    });
    const result = NextStepRequestSchema.safeParse(payload);
    expect(result.success).toBe(true);
  });
});
