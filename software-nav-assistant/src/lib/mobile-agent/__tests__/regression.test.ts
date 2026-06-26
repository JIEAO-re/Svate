/**
 * Regression tests.
 *
 * Covered scenarios:
 *   1. /next-step works normally when schema validation passes
 *   2. Authentication failure paths reject invalid or missing credentials
 *      (exercising the real auth-utils implementation)
 *   3. High-risk actions must be blocked (arbiter HIGH risk -> WAIT)
 *   4. Dangerous intents such as delete or uninstall are intercepted by the arbiter
 */
import { afterEach, describe, it, expect, vi } from "vitest";
import {
  NextStepRequestSchema,
  ActionCommandSchema,
  type ActionCommand,
  type NextStepRequest,
} from "@/lib/schemas/mobile-agent";
import { arbitrateDecision } from "@/lib/mobile-agent/arbiter";
import {
  authenticateRequest,
  verifyFirebaseAppCheck,
  verifyJwtToken,
} from "@/lib/mobile-agent/auth-utils";
import type { ReviewerOutput } from "@/lib/schemas/mobile-agent";

// ---------------------------------------------------------------------------
// Helper factory
// ---------------------------------------------------------------------------
function makeObservation(overrides: Record<string, unknown> = {}) {
  return {
    observation_reason: "UI_CHANGED" as const,
    foreground_package: "com.example.app",
    media_window: {
      source: "SCREEN_RECORDING" as const,
      frames: [
        {
          frame_id: "f1",
          ts_ms: 1000,
          image_base64: "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJ",
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
        bounds: [0, 0, 540, 100] as [number, number, number, number],
        clickable: true,
        editable: false,
        scrollable: false,
      },
    ],
    previous_action_result: "NOT_EXECUTED" as const,
    previous_checkpoint_match: false,
    ...overrides,
  };
}

function makeRequest(overrides: Record<string, unknown> = {}): NextStepRequest {
  const raw = {
    session_id: "sess_test_001",
    turn_index: 0,
    mode: "active",
    goal: "打开设置查看存储",
    task_spec: {
      mode: "GENERAL",
      search_query: "",
      ask_on_uncertain: true,
    },
    observation: makeObservation(),
    history_tail: [],
    ...overrides,
  };
  return NextStepRequestSchema.parse(raw);
}

function makeAction(overrides: Record<string, unknown> = {}): ActionCommand {
  const raw = {
    action_id: "act_001",
    intent: "CLICK",
    target_desc: "tap settings icon",
    target_som_id: 1,
    selector: {
      package_name: "com.example.app",
      resource_id: "com.example.app:id/settings",
      text: "设置",
      content_desc: "",
      class_name: "android.widget.ImageView",
      bounds_hint_0_1000: [100, 200, 400, 260],
      node_signature: "com.example.app|ImageView|settings",
    },
    input_text: null,
    package_name: null,
    risk_level: "SAFE",
    narration: "Tapping the settings icon.",
    checkpoint: {
      expected_package: "com.example.app",
      expected_page_type: "SETTINGS",
      expected_elements: [],
    },
    ...overrides,
  };
  return ActionCommandSchema.parse(raw);
}

function approveReview(index = 0): ReviewerOutput {
  return {
    verdict: "APPROVE",
    reason: "Action is safe and correct.",
    approved_action_index: index,
  };
}


// ===================================================================
// 1. Route switch: /next-step should work normally
// ===========================================================================
describe("next-step normal processing", () => {
  it("valid request passes schema validation and arbiter produces a final action", () => {
    const request = makeRequest();
    const candidate = makeAction();
    const review = approveReview(0);

    const result = arbitrateDecision(request, [candidate], review, false);

    expect(result.finalAction).toBeDefined();
    expect(result.finalAction.intent).toBe("CLICK");
    expect(result.blockReason).toBeNull();
  });

  it("arbiter returns approved candidate when reviewer approves", () => {
    const request = makeRequest();
    const candidates = [
      makeAction({ action_id: "c0" }),
      makeAction({ action_id: "c1", target_desc: "alternative" }),
    ];
    const review = approveReview(1);

    const result = arbitrateDecision(request, candidates, review, false);

    expect(result.finalAction.action_id).toBe("c1");
    expect(result.blockReason).toBeNull();
  });
});

// ===========================================================================
// 2. Authentication failure paths exercised against the real auth-utils
// ===========================================================================
describe("authentication failure path", () => {
  const originalGoogleCloudProject = process.env.GOOGLE_CLOUD_PROJECT;

  afterEach(() => {
    if (originalGoogleCloudProject === undefined) {
      delete process.env.GOOGLE_CLOUD_PROJECT;
    } else {
      process.env.GOOGLE_CLOUD_PROJECT = originalGoogleCloudProject;
    }
    vi.restoreAllMocks();
  });

  it("missing auth credentials results in missing_auth_credentials", async () => {
    const result = await authenticateRequest(
      new Request("https://example.com/api/mobile-agent/next-step", {
        method: "POST",
        headers: { "x-forwarded-for": "203.0.113.10" },
      }),
    );

    expect(result.valid).toBe(false);
    expect(result.error).toBe("missing_auth_credentials");
  });

  it("invalid JWT format is rejected by verifyJwtToken", async () => {
    const result = await verifyJwtToken("not_a_valid_jwt");

    expect(result.valid).toBe(false);
    expect(result.error).toBe("invalid_jwt_format");
  });

  it("short App Check token is rejected by verifyFirebaseAppCheck", async () => {
    const result = await verifyFirebaseAppCheck("short");

    expect(result.valid).toBe(false);
    expect(result.error).toBe("invalid_app_check_token_format");
  });

  it("App Check fails closed when GOOGLE_CLOUD_PROJECT is not configured", async () => {
    delete process.env.GOOGLE_CLOUD_PROJECT;
    const errorSpy = vi.spyOn(console, "error").mockImplementation(() => undefined);

    // Long enough to pass the format pre-check; must still be rejected
    // before any JWKS verification because the audience cannot be checked.
    const result = await verifyFirebaseAppCheck("a".repeat(64));

    expect(result.valid).toBe(false);
    expect(result.error).toBe("app_check_project_not_configured");
    expect(errorSpy).toHaveBeenCalled();
  });
});

// ===========================================================================
// 3. High-risk actions must be blocked
// ===========================================================================
describe("high risk action blocking", () => {
  it("HIGH risk action is blocked by arbiter even when reviewer approves", () => {
    const request = makeRequest();
    const candidate = makeAction({ risk_level: "HIGH" });
    const review = approveReview(0);

    const result = arbitrateDecision(request, [candidate], review, false);

    expect(result.finalAction.intent).toBe("WAIT");
    expect(result.blockReason).toBe("high_risk_blocked");
  });

  it("ESCALATE verdict from reviewer results in block", () => {
    const request = makeRequest();
    const candidate = makeAction();
    const review: ReviewerOutput = {
      verdict: "ESCALATE",
      reason: "Potentially dangerous operation detected.",
      approved_action_index: null,
    };

    const result = arbitrateDecision(request, [candidate], review, false);

    expect(result.finalAction.intent).toBe("WAIT");
    expect(result.finalAction.risk_level).toBe("HIGH");
    expect(result.blockReason).toBeTruthy();
  });

  it("replan exhausted results in manual intervention required", () => {
    const request = makeRequest();
    const candidate = makeAction();
    const review: ReviewerOutput = {
      verdict: "REPLAN",
      reason: "Still not good enough.",
      approved_action_index: null,
    };

    const result = arbitrateDecision(request, [candidate], review, true);

    expect(result.finalAction.intent).toBe("WAIT");
    expect(result.blockReason).toBe("replan_exhausted_manual_required");
  });
});

// =========================================================================
// 4. Dangerous intents are intercepted when they are outside the allowlist
// ===========================================================================
describe("dangerous intent blocking", () => {
  it("OPEN_INTENT with non-whitelisted action (DELETE) is blocked", () => {
    const request = makeRequest();
    const candidate = makeAction({
      intent: "OPEN_INTENT",
      selector: null,
      target_som_id: null,
      intent_spec: {
        action: "android.intent.action.DELETE",
        data_uri: "package:com.example.victim",
        package_name: null,
        extras: {},
      },
    });
    const review = approveReview(0);

    const result = arbitrateDecision(request, [candidate], review, false);

    expect(result.finalAction.intent).toBe("WAIT");
    expect(result.blockReason).toBe("open_intent_action_not_allowed");
  });

  it("OPEN_INTENT with non-whitelisted action (UNINSTALL) is blocked", () => {
    const request = makeRequest();
    const candidate = makeAction({
      intent: "OPEN_INTENT",
      selector: null,
      target_som_id: null,
      intent_spec: {
        action: "android.intent.action.UNINSTALL_PACKAGE",
        data_uri: "package:com.example.app",
        package_name: null,
        extras: {},
      },
    });
    const review = approveReview(0);

    const result = arbitrateDecision(request, [candidate], review, false);

    expect(result.finalAction.intent).toBe("WAIT");
    expect(result.blockReason).toBe("open_intent_action_not_allowed");
  });

  it("OPEN_INTENT with non-whitelisted package is blocked", () => {
    const request = makeRequest();
    const candidate = makeAction({
      intent: "OPEN_INTENT",
      selector: null,
      target_som_id: null,
      intent_spec: {
        action: "android.intent.action.view",
        data_uri: null,
        package_name: "com.malicious.app",
        extras: {},
      },
    });
    const review = approveReview(0);

    const result = arbitrateDecision(request, [candidate], review, false);

    expect(result.finalAction.intent).toBe("WAIT");
    expect(result.blockReason).toBe("open_intent_package_not_allowed");
  });

  it("OPEN_INTENT with whitelisted action + package passes", () => {
    const request = makeRequest();
    const candidate = makeAction({
      intent: "OPEN_INTENT",
      selector: null,
      target_som_id: null,
      intent_spec: {
        action: "android.intent.action.view",
        data_uri: "https://example.com",
        package_name: "com.android.chrome",
        extras: {},
      },
    });
    const review = approveReview(0);

    const result = arbitrateDecision(request, [candidate], review, false);

    expect(result.finalAction.intent).toBe("OPEN_INTENT");
    expect(result.blockReason).toBeNull();
  });

  it("invalid approved_action_index results in block", () => {
    const request = makeRequest();
    const candidate = makeAction();
    const review: ReviewerOutput = {
      verdict: "APPROVE",
      reason: "Looks good.",
      approved_action_index: 5, // out of bounds
    };

    const result = arbitrateDecision(request, [candidate], review, false);

    expect(result.finalAction.intent).toBe("WAIT");
    expect(result.blockReason).toBe("invalid_approved_index");
  });
});
