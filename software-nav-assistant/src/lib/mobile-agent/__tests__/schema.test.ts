import { describe, it, expect } from "vitest";
import {
  NextStepRequestSchema,
  NextStepResponseSchema,
  ActionCommandSchema,
  MobileObservationSchema,
  LiveFrameSchema,
  ActionCheckpointSchema,
  PlannerOutputSchema,
  ReviewerOutputSchema,
  AgentIntentSchema,
  AgentRiskLevelSchema,
  SpatialCoordinatesSchema,
  GcsUriSchema,
  TelemetryEventSchema,
  TelemetryBatchRequestSchema,
} from "@/lib/schemas/mobile-agent";

// ============================================================================
// Fixtures
// ============================================================================

function makeValidFrame(overrides: Record<string, unknown> = {}) {
  return {
    frame_id: "frame_001",
    ts_ms: 1700000000000,
    image_base64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk",
    ui_signature: "sha256:abc123",
    ...overrides,
  };
}

function makeValidObservation(overrides: Record<string, unknown> = {}) {
  return {
    observation_reason: "AFTER_ACTION",
    foreground_package: "com.tencent.mm",
    media_window: {
      source: "SCREENSHOT",
      frames: [makeValidFrame()],
    },
    ui_nodes: [],
    previous_action_result: "SUCCESS",
    previous_checkpoint_match: true,
    ...overrides,
  };
}

function makeValidAction(overrides: Record<string, unknown> = {}) {
  return {
    action_id: "act_001",
    intent: "CLICK",
    target_desc: "微信聊天按钮",
    spatial_coordinates: [0.5, 0.3] as [number, number],
    target_bbox: null,
    target_som_id: null,
    selector: null,
    input_text: null,
    package_name: null,
    intent_spec: null,
    risk_level: "SAFE",
    narration: "点击微信聊天按钮进入聊天列表",
    checkpoint: {
      expected_package: "com.tencent.mm",
      expected_page_type: "聊天列表",
      expected_elements: ["聊天", "通讯录"],
    },
    ...overrides,
  };
}

function makeValidRequest(overrides: Record<string, unknown> = {}) {
  return {
    session_id: "sess_abc123",
    turn_index: 0,
    mode: "active",
    goal: "打开微信给妈妈发消息",
    task_spec: {
      mode: "GENERAL",
      search_query: "",
      ask_on_uncertain: true,
    },
    observation: makeValidObservation(),
    history_tail: [],
    ...overrides,
  };
}

function makeValidResponse(overrides: Record<string, unknown> = {}) {
  return {
    trace_id: "trace_001",
    planner: {
      model: "gemini-live-2.5-flash-preview",
      candidates: [makeValidAction()],
      latency_ms: 320,
    },
    reviewer: {
      model: "gemini-2.5-pro",
      verdict: "APPROVE",
      reason: "Action is safe and aligned with goal",
      approved_action_index: 0,
      latency_ms: 150,
    },
    final_action: makeValidAction(),
    checkpoint: {
      expected_package: "com.tencent.mm",
      expected_page_type: "聊天列表",
      expected_elements: ["聊天"],
    },
    guard: {
      risk_level: "SAFE",
      block_reason: null,
    },
    live_runtime: {
      used_live: true,
      model: "gemini-live-2.5-flash-preview",
      connect_latency_ms: 80,
      inference_latency_ms: 240,
    },
    guide_media: null,
    ...overrides,
  };
}

// ============================================================================
// NextStepRequest
// ============================================================================

describe("NextStepRequestSchema", () => {
  it("accepts a valid request", () => {
    const result = NextStepRequestSchema.safeParse(makeValidRequest());
    expect(result.success).toBe(true);
  });

  it("accepts shadow mode", () => {
    const result = NextStepRequestSchema.safeParse(
      makeValidRequest({ mode: "shadow" }),
    );
    expect(result.success).toBe(true);
  });

  it("rejects missing session_id", () => {
    const result = NextStepRequestSchema.safeParse(
      makeValidRequest({ session_id: "" }),
    );
    expect(result.success).toBe(false);
  });

  it("rejects negative turn_index", () => {
    const result = NextStepRequestSchema.safeParse(
      makeValidRequest({ turn_index: -1 }),
    );
    expect(result.success).toBe(false);
  });

  it("rejects empty goal", () => {
    const result = NextStepRequestSchema.safeParse(
      makeValidRequest({ goal: "" }),
    );
    expect(result.success).toBe(false);
  });

  it("rejects invalid mode", () => {
    const result = NextStepRequestSchema.safeParse(
      makeValidRequest({ mode: "invalid" }),
    );
    expect(result.success).toBe(false);
  });

  it("accepts optional shadow_control_action", () => {
    const result = NextStepRequestSchema.safeParse(
      makeValidRequest({ shadow_control_action: makeValidAction() }),
    );
    expect(result.success).toBe(true);
  });

  it("accepts request with legacy screenshot_base64 instead of media_window", () => {
    const obs = makeValidObservation({
      media_window: undefined,
      screenshot_base64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ",
    });
    const result = NextStepRequestSchema.safeParse(
      makeValidRequest({ observation: obs }),
    );
    expect(result.success).toBe(true);
  });

  it("rejects observation with neither media_window nor screenshot_base64", () => {
    const obs = makeValidObservation({
      media_window: undefined,
      screenshot_base64: undefined,
    });
    const result = NextStepRequestSchema.safeParse(
      makeValidRequest({ observation: obs }),
    );
    expect(result.success).toBe(false);
  });
});

// ============================================================================
// NextStepResponse
// ============================================================================

describe("NextStepResponseSchema", () => {
  it("accepts a valid response", () => {
    const result = NextStepResponseSchema.safeParse(makeValidResponse());
    expect(result.success).toBe(true);
  });

  it("accepts response with guide_media", () => {
    const result = NextStepResponseSchema.safeParse(
      makeValidResponse({
        guide_media: {
          type: "IMAGE",
          storage_uri: "gs://bucket/path/image.jpg",
          signed_url: "https://storage.googleapis.com/bucket/path/image.jpg?sig=abc",
          expires_at: "2025-12-31T23:59:59+00:00",
        },
      }),
    );
    expect(result.success).toBe(true);
  });

  it("rejects empty trace_id", () => {
    const result = NextStepResponseSchema.safeParse(
      makeValidResponse({ trace_id: "" }),
    );
    expect(result.success).toBe(false);
  });

  it("rejects negative latency_ms in planner", () => {
    const resp = makeValidResponse();
    (resp.planner as Record<string, unknown>).latency_ms = -1;
    const result = NextStepResponseSchema.safeParse(resp);
    expect(result.success).toBe(false);
  });

  it("rejects invalid reviewer verdict", () => {
    const resp = makeValidResponse();
    (resp.reviewer as Record<string, unknown>).verdict = "INVALID";
    const result = NextStepResponseSchema.safeParse(resp);
    expect(result.success).toBe(false);
  });

  it("accepts all valid reviewer verdicts", () => {
    for (const verdict of ["APPROVE", "REPLAN", "ESCALATE"]) {
      const resp = makeValidResponse();
      (resp.reviewer as Record<string, unknown>).verdict = verdict;
      const result = NextStepResponseSchema.safeParse(resp);
      expect(result.success).toBe(true);
    }
  });
});

// ============================================================================
// ActionCommand
// ============================================================================

describe("ActionCommandSchema", () => {
  it("accepts CLICK with spatial_coordinates", () => {
    const result = ActionCommandSchema.safeParse(makeValidAction());
    expect(result.success).toBe(true);
  });

  it("accepts CLICK with target_som_id instead of spatial_coordinates", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        spatial_coordinates: null,
        target_som_id: 5,
      }),
    );
    expect(result.success).toBe(true);
  });

  it("rejects CLICK with no targeting method", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        spatial_coordinates: null,
        target_bbox: null,
        target_som_id: null,
        selector: null,
      }),
    );
    expect(result.success).toBe(false);
  });

  it("rejects TYPE without input_text", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        intent: "TYPE",
        input_text: "",
      }),
    );
    expect(result.success).toBe(false);
  });

  it("accepts TYPE with input_text", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        intent: "TYPE",
        input_text: "你好妈妈",
      }),
    );
    expect(result.success).toBe(true);
  });

  it("rejects OPEN_APP without package_name", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        intent: "OPEN_APP",
        spatial_coordinates: null,
        package_name: "",
      }),
    );
    expect(result.success).toBe(false);
  });

  it("accepts OPEN_APP with package_name", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        intent: "OPEN_APP",
        spatial_coordinates: null,
        package_name: "com.tencent.mm",
      }),
    );
    expect(result.success).toBe(true);
  });

  it("accepts WAIT without checkpoint", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        intent: "WAIT",
        spatial_coordinates: null,
        checkpoint: null,
      }),
    );
    expect(result.success).toBe(true);
  });

  it("accepts FINISH without checkpoint", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        intent: "FINISH",
        spatial_coordinates: null,
        checkpoint: null,
      }),
    );
    expect(result.success).toBe(true);
  });

  it("rejects SCROLL_UP without checkpoint", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        intent: "SCROLL_UP",
        spatial_coordinates: null,
        checkpoint: null,
      }),
    );
    expect(result.success).toBe(false);
  });

  it("accepts all valid intents", () => {
    const intents = [
      "CLICK", "TYPE", "SUBMIT_INPUT", "SCROLL_UP", "SCROLL_DOWN",
      "SCROLL_LEFT", "SCROLL_RIGHT", "BACK", "HOME", "OPEN_APP",
      "OPEN_INTENT", "WAIT", "FINISH",
    ];
    for (const intent of intents) {
      const result = AgentIntentSchema.safeParse(intent);
      expect(result.success).toBe(true);
    }
  });

  it("rejects OPEN_INTENT without intent_spec", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        intent: "OPEN_INTENT",
        spatial_coordinates: null,
        intent_spec: null,
      }),
    );
    expect(result.success).toBe(false);
  });

  it("accepts OPEN_INTENT with intent_spec", () => {
    const result = ActionCommandSchema.safeParse(
      makeValidAction({
        intent: "OPEN_INTENT",
        spatial_coordinates: null,
        intent_spec: {
          action: "android.intent.action.VIEW",
          data_uri: "https://example.com",
          package_name: null,
          extras: {},
        },
      }),
    );
    expect(result.success).toBe(true);
  });
});

// ============================================================================
// SpatialCoordinates
// ============================================================================

describe("SpatialCoordinatesSchema", () => {
  it("accepts valid coordinates [0.5, 0.3]", () => {
    expect(SpatialCoordinatesSchema.safeParse([0.5, 0.3]).success).toBe(true);
  });

  it("accepts boundary [0, 0]", () => {
    expect(SpatialCoordinatesSchema.safeParse([0, 0]).success).toBe(true);
  });

  it("accepts boundary [1, 1]", () => {
    expect(SpatialCoordinatesSchema.safeParse([1, 1]).success).toBe(true);
  });

  it("rejects out-of-range x > 1", () => {
    expect(SpatialCoordinatesSchema.safeParse([1.1, 0.5]).success).toBe(false);
  });

  it("rejects negative y", () => {
    expect(SpatialCoordinatesSchema.safeParse([0.5, -0.1]).success).toBe(false);
  });
});

// ============================================================================
// LiveFrame & GCS URI
// ============================================================================

describe("LiveFrameSchema", () => {
  it("accepts frame with image_base64", () => {
    const result = LiveFrameSchema.safeParse(makeValidFrame());
    expect(result.success).toBe(true);
  });

  it("accepts frame with gcs_uri", () => {
    const result = LiveFrameSchema.safeParse(
      makeValidFrame({
        image_base64: undefined,
        gcs_uri: "gs://my-bucket/screenshots/frame_001.jpg",
      }),
    );
    expect(result.success).toBe(true);
  });

  it("rejects frame with neither image_base64 nor gcs_uri", () => {
    const result = LiveFrameSchema.safeParse(
      makeValidFrame({
        image_base64: undefined,
        gcs_uri: undefined,
      }),
    );
    expect(result.success).toBe(false);
  });
});

describe("GcsUriSchema", () => {
  it("accepts valid gs:// URI", () => {
    expect(GcsUriSchema.safeParse("gs://my-bucket/path/to/file.jpg").success).toBe(true);
  });

  it("rejects non-gs:// URI", () => {
    expect(GcsUriSchema.safeParse("https://storage.googleapis.com/bucket/file").success).toBe(false);
  });

  it("rejects empty string", () => {
    expect(GcsUriSchema.safeParse("").success).toBe(false);
  });
});

// ============================================================================
// Risk levels
// ============================================================================

describe("AgentRiskLevelSchema", () => {
  it("accepts SAFE, WARNING, HIGH", () => {
    for (const level of ["SAFE", "WARNING", "HIGH"]) {
      expect(AgentRiskLevelSchema.safeParse(level).success).toBe(true);
    }
  });

  it("rejects unknown risk level", () => {
    expect(AgentRiskLevelSchema.safeParse("CRITICAL").success).toBe(false);
  });
});

// ============================================================================
// PlannerOutput & ReviewerOutput
// ============================================================================

describe("PlannerOutputSchema", () => {
  it("accepts 1-3 candidates", () => {
    const result = PlannerOutputSchema.safeParse({
      candidates: [makeValidAction(), makeValidAction({ action_id: "act_002" })],
    });
    expect(result.success).toBe(true);
  });

  it("rejects empty candidates", () => {
    const result = PlannerOutputSchema.safeParse({ candidates: [] });
    expect(result.success).toBe(false);
  });

  it("rejects more than 3 candidates", () => {
    const result = PlannerOutputSchema.safeParse({
      candidates: Array.from({ length: 4 }, (_, i) =>
        makeValidAction({ action_id: `act_${i}` }),
      ),
    });
    expect(result.success).toBe(false);
  });
});

describe("ReviewerOutputSchema", () => {
  it("accepts APPROVE with index", () => {
    const result = ReviewerOutputSchema.safeParse({
      verdict: "APPROVE",
      reason: "Looks good",
      approved_action_index: 0,
    });
    expect(result.success).toBe(true);
  });

  it("accepts REPLAN with null index", () => {
    const result = ReviewerOutputSchema.safeParse({
      verdict: "REPLAN",
      reason: "Action is risky",
      approved_action_index: null,
    });
    expect(result.success).toBe(true);
  });

  it("accepts ESCALATE", () => {
    const result = ReviewerOutputSchema.safeParse({
      verdict: "ESCALATE",
      reason: "Needs human review",
      approved_action_index: null,
    });
    expect(result.success).toBe(true);
  });

  it("rejects empty reason", () => {
    const result = ReviewerOutputSchema.safeParse({
      verdict: "APPROVE",
      reason: "",
      approved_action_index: 0,
    });
    expect(result.success).toBe(false);
  });
});

// ============================================================================
// Telemetry
// ============================================================================

describe("TelemetryEventSchema", () => {
  it("accepts valid telemetry event", () => {
    const result = TelemetryEventSchema.safeParse({
      trace_id: "trace_001",
      session_id: "sess_001",
      turn_index: 0,
      event_type: "action_executed",
      payload: { intent: "CLICK", latency_ms: 200 },
    });
    expect(result.success).toBe(true);
  });

  it("rejects payload containing screenshot_base64", () => {
    const result = TelemetryEventSchema.safeParse({
      trace_id: "trace_001",
      session_id: "sess_001",
      turn_index: 0,
      event_type: "debug_dump",
      payload: { screenshot_base64: "should_not_be_here" },
    });
    expect(result.success).toBe(false);
  });

  it("rejects payload containing nested image_base64", () => {
    const result = TelemetryEventSchema.safeParse({
      trace_id: "trace_001",
      session_id: "sess_001",
      turn_index: 0,
      event_type: "debug_dump",
      payload: { nested: { image_base64: "should_not_be_here" } },
    });
    expect(result.success).toBe(false);
  });
});

describe("TelemetryBatchRequestSchema", () => {
  it("accepts 1-200 events", () => {
    const result = TelemetryBatchRequestSchema.safeParse({
      events: [
        {
          trace_id: "t1",
          session_id: "s1",
          turn_index: 0,
          event_type: "test",
          payload: {},
        },
      ],
    });
    expect(result.success).toBe(true);
  });

  it("rejects empty events array", () => {
    const result = TelemetryBatchRequestSchema.safeParse({ events: [] });
    expect(result.success).toBe(false);
  });
});

// ============================================================================
// Observation
// ============================================================================

describe("MobileObservationSchema", () => {
  it("accepts valid observation with media_window", () => {
    const result = MobileObservationSchema.safeParse(makeValidObservation());
    expect(result.success).toBe(true);
  });

  it("accepts all observation reasons", () => {
    for (const reason of ["APP_START", "UI_CHANGED", "AFTER_ACTION", "RECOVERY", "TIMEOUT"]) {
      const result = MobileObservationSchema.safeParse(
        makeValidObservation({ observation_reason: reason }),
      );
      expect(result.success).toBe(true);
    }
  });

  it("accepts observation with SoM markers", () => {
    const result = MobileObservationSchema.safeParse(
      makeValidObservation({
        som_markers: [
          {
            id: 1,
            text: "聊天",
            content_desc: "",
            resource_id: "btn_chat",
            class_name: "android.widget.Button",
            package_name: "com.tencent.mm",
            bounds: [0, 0, 100, 50],
            clickable: true,
            editable: false,
            scrollable: false,
          },
        ],
      }),
    );
    expect(result.success).toBe(true);
  });

  it("rejects observation with too many SoM markers (>60)", () => {
    const markers = Array.from({ length: 61 }, (_, i) => ({
      id: i + 1,
      text: `marker_${i}`,
      content_desc: "",
      resource_id: "",
      class_name: "android.widget.View",
      package_name: "com.test",
      bounds: [0, 0, 10, 10] as [number, number, number, number],
      clickable: false,
      editable: false,
      scrollable: false,
    }));
    const result = MobileObservationSchema.safeParse(
      makeValidObservation({ som_markers: markers }),
    );
    expect(result.success).toBe(false);
  });
});

// ============================================================================
// ActionCheckpoint
// ============================================================================

describe("ActionCheckpointSchema", () => {
  it("accepts valid checkpoint", () => {
    const result = ActionCheckpointSchema.safeParse({
      expected_package: "com.tencent.mm",
      expected_page_type: "聊天列表",
      expected_elements: ["聊天", "通讯录"],
    });
    expect(result.success).toBe(true);
  });

  it("applies defaults for missing fields", () => {
    const result = ActionCheckpointSchema.safeParse({});
    expect(result.success).toBe(true);
    if (result.success) {
      expect(result.data.expected_package).toBe("");
      expect(result.data.expected_page_type).toBe("");
      expect(result.data.expected_elements).toEqual([]);
    }
  });
});
