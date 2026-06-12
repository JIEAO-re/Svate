/**
 * Arbiter search_flow tests.
 *
 * history_tail is a bounded window: a successful TYPE / SUBMIT_INPUT can
 * scroll out of it on long sessions. The client-computed search_flow flags
 * are ORed with the tail scan so FINISH is not permanently blocked once the
 * search flow has actually completed.
 */
import { describe, it, expect } from "vitest";
import { arbitrateDecision } from "@/lib/mobile-agent/arbiter";
import {
  ActionCommandSchema,
  NextStepRequestSchema,
  type ActionCommand,
  type NextStepRequest,
  type ReviewerOutput,
} from "@/lib/schemas/mobile-agent";

function makeSearchRequest(overrides: Record<string, unknown> = {}): NextStepRequest {
  return NextStepRequestSchema.parse({
    session_id: "sess_search_flow",
    turn_index: 12,
    mode: "active",
    goal: "在应用里搜索天气",
    task_spec: {
      mode: "SEARCH",
      search_query: "天气",
      ask_on_uncertain: true,
    },
    observation: {
      observation_reason: "AFTER_ACTION",
      foreground_package: "com.example.app",
      media_window: {
        source: "SCREENSHOT",
        frames: [
          {
            frame_id: "f1",
            ts_ms: 1000,
            image_base64: "aGVsbG8=",
            ui_signature: "sig_results",
          },
        ],
      },
      // Plain result-page nodes: no editable search box, so the search
      // recovery heuristic cannot fabricate a TYPE action.
      ui_nodes: [
        {
          class_name: "android.widget.TextView",
          text: "今天多云 20°C",
          content_desc: "",
          resource_id: "com.example.app:id/result_title",
          package_name: "com.example.app",
          bounds: [0, 0, 540, 100],
          clickable: false,
          editable: false,
          scrollable: false,
        },
      ],
      previous_action_result: "SUCCESS",
      previous_checkpoint_match: true,
    },
    history_tail: [],
    ...overrides,
  });
}

function makeFinishAction(): ActionCommand {
  return ActionCommandSchema.parse({
    action_id: "act_finish",
    intent: "FINISH",
    target_desc: "task complete",
    target_som_id: null,
    selector: null,
    input_text: null,
    package_name: null,
    risk_level: "SAFE",
    narration: "The search result is shown; finishing the task.",
    checkpoint: null,
  });
}

function approveReview(index = 0): ReviewerOutput {
  return {
    verdict: "APPROVE",
    reason: "Result page is visible.",
    approved_action_index: index,
  };
}

describe("arbiter search_flow OR semantics", () => {
  it("blocks FINISH when history_tail is empty and no search_flow is sent", () => {
    const request = makeSearchRequest();
    const result = arbitrateDecision(request, [makeFinishAction()], approveReview(), false);

    expect(result.finalAction.intent).toBe("WAIT");
    expect(result.blockReason).toBe("search_type_missing");
  });

  it("allows FINISH when search_flow reports typed+submitted despite empty history_tail", () => {
    const request = makeSearchRequest({
      search_flow: { has_typed: true, has_submitted: true },
    });
    const result = arbitrateDecision(request, [makeFinishAction()], approveReview(), false);

    expect(result.finalAction.intent).toBe("FINISH");
    expect(result.blockReason).toBeNull();
  });

  it("substitutes a submit recovery when search_flow has typed but not submitted", () => {
    const request = makeSearchRequest({
      search_flow: { has_typed: true, has_submitted: false },
    });
    const result = arbitrateDecision(request, [makeFinishAction()], approveReview(), false);

    // typed=true via search_flow, submitted=false: the recovery heuristic
    // pushes the flow forward with a SUBMIT_INPUT instead of plain blocking.
    expect(result.finalAction.intent).toBe("SUBMIT_INPUT");
    expect(result.blockReason).toBeNull();
    expect(result.notes).toBe("search_recovery_heuristic");
  });

  it("still honors history_tail when search_flow is absent (OR, not replace)", () => {
    const request = makeSearchRequest({
      history_tail: [
        { action_intent: "TYPE", target_desc: "type query", result: "SUCCESS" },
        { action_intent: "SUBMIT_INPUT", target_desc: "submit", result: "SUCCESS" },
      ],
    });
    const result = arbitrateDecision(request, [makeFinishAction()], approveReview(), false);

    expect(result.finalAction.intent).toBe("FINISH");
    expect(result.blockReason).toBeNull();
  });
});
