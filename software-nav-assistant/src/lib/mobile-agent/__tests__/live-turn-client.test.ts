import { beforeEach, describe, expect, it, vi } from "vitest";
import type { NextStepRequest } from "@/lib/schemas/mobile-agent";

const generateContent = vi.fn();

const PLANNER_JSON = JSON.stringify({
  candidates: [
    {
      action_id: "act_live_1",
      intent: "CLICK",
      target_desc: "button",
      target_som_id: 1,
      selector: null,
      input_text: null,
      package_name: null,
      intent_spec: null,
      risk_level: "SAFE",
      narration: "Tapping the button.",
      checkpoint: {
        expected_package: "com.example.app",
        expected_page_type: "HOME",
        expected_elements: [],
      },
    },
  ],
});

function makeRequest(sessionId: string, uiSignature = "sig_same"): NextStepRequest {
  return {
    session_id: sessionId,
    turn_index: 0,
    mode: "active",
    goal: "open settings",
    task_spec: {
      mode: "GENERAL",
      search_query: "",
      ask_on_uncertain: true,
    },
    observation: {
      observation_reason: "UI_CHANGED",
      foreground_package: "com.example.app",
      media_window: {
        source: "SCREENSHOT",
        frames: [
          {
            frame_id: "f1",
            ts_ms: 1000,
            image_base64: "aGVsbG8=",
            ui_signature: uiSignature,
          },
        ],
      },
      ui_nodes: [],
      previous_action_result: "NOT_EXECUTED",
      previous_checkpoint_match: false,
    },
    history_tail: [],
  } as unknown as NextStepRequest;
}

describe("live-turn-client frame dedup", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.restoreAllMocks();
    generateContent.mockReset();
    generateContent.mockResolvedValue({ text: PLANNER_JSON });

    vi.doMock("@/lib/mobile-agent/genai-client", () => ({
      getGenAIClient: () => ({
        models: {
          generateContent,
        },
      }),
      resolveModelWithFallback: vi.fn().mockResolvedValue("gemini-2.5-flash"),
    }));
  });

  it("dedups repeated frames but reruns the model when bypassDedup is set", async () => {
    const { runLivePlannerTurn } = await import("@/lib/mobile-agent/live-turn-client");
    const request = makeRequest("sess_dedup");

    // First call: model runs.
    const first = await runLivePlannerTurn(request);
    expect(first.usedLive).toBe(true);
    expect(first.frameCount).toBe(1);
    expect(generateContent).toHaveBeenCalledTimes(1);

    // Second call with identical frames: dedup short-circuits into WAIT.
    const second = await runLivePlannerTurn(request);
    expect(second.usedLive).toBe(false);
    expect(second.frameCount).toBe(0);
    expect(second.output.candidates[0].intent).toBe("WAIT");
    expect(second.output.candidates[0].target_desc).toBe("frame_dedup_skip");
    expect(generateContent).toHaveBeenCalledTimes(1);

    // REPLAN retry path: bypassDedup forces a real model call on the same frames.
    const third = await runLivePlannerTurn(request, { bypassDedup: true });
    expect(third.usedLive).toBe(true);
    expect(third.frameCount).toBe(1);
    expect(generateContent).toHaveBeenCalledTimes(2);
  });

  it("evicts the oldest session fingerprint once the LRU limit is exceeded", async () => {
    const { runLivePlannerTurn } = await import("@/lib/mobile-agent/live-turn-client");

    // Prime session A, then push 500 other sessions through to evict it.
    await runLivePlannerTurn(makeRequest("sess_lru_a"));
    for (let index = 0; index < 500; index += 1) {
      await runLivePlannerTurn(makeRequest(`sess_lru_${index}`));
    }

    const callsBefore = generateContent.mock.calls.length;
    const result = await runLivePlannerTurn(makeRequest("sess_lru_a"));

    // The fingerprint for session A was evicted, so the identical frame is
    // treated as new and the model is called instead of dedup-skipping.
    expect(result.usedLive).toBe(true);
    expect(generateContent.mock.calls.length).toBe(callsBefore + 1);
  });
});
