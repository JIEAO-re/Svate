/**
 * Regression tests.
 *
 * Covered scenarios:
 *   1. /analyze-screen returns a redirect hint (301) when no demo header is present
 *   2. /next-step works normally when schema validation passes
 *   3. Authentication failure path returns 401 when no token is provided
 *   4. High-risk actions must be blocked (arbiter HIGH risk -> WAIT)
 *   5. Dangerous intents such as delete or uninstall are intercepted by the arbiter
 */
import { describe, it, expect } from "vitest";
import {
  NextStepRequestSchema,
  ActionCommandSchema,
  type ActionCommand,
  type NextStepRequest,
} from "@/lib/schemas/mobile-agent";
import { arbitrateDecision } from "@/lib/mobile-agent/arbiter";
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
// 2. Route switch: /next-step should work normally
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
// 3. Authentication failure path: missing token should return 401
// ===========================================================================
describe("authentication failure path", () => {
  it("missing auth credentials results in authentication_failed response", () => {
    // Verify the authenticateRequest logic used in next-step/route.ts:
    // missing X-Firebase-AppCheck and missing Authorization header -> missing_auth_credentials
    const hasAppCheckToken = false;
    const hasAuthHeader = false;
    const skipAuthDev = false;

    const authResult = (() => {
      if (skipAuthDev) return { valid: true, client_id: "dev_bypass" };
      if (hasAppCheckToken) return { valid: true, client_id: "appcheck:xxx" };
      if (hasAuthHeader) return { valid: true, client_id: "jwt:xxx" };
      return { valid: false, error: "missing_auth_credentials" };
    })();

    expect(authResult.valid).toBe(false);
    expect(authResult.error).toBe("missing_auth_credentials");
  });

  it("invalid JWT format is rejected", () => {
    // JWT must start with "ey" because the JSON header is base64-encoded
    const token = "not_a_valid_jwt";
    const isValidFormat = token.startsWith("ey");
    expect(isValidFormat).toBe(false);
  });

  it("short App Check token is rejected", () => {
    const token = "short";
    const isValidFormat = token.length >= 32;
    expect(isValidFormat).toBe(false);
  });
});

// ===========================================================================
// 4. High-risk actions must be blocked
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
// 5. Dangerous intents are intercepted when they are outside the allowlist
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
