import { beforeEach, describe, expect, it, vi } from "vitest";
import type { NextStepRequest } from "@/lib/schemas/mobile-agent";

const generateContent = vi.fn();

function makeRequest(frame: Record<string, unknown>): NextStepRequest {
  return {
    session_id: "sess_reviewer_test",
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
        frames: [frame],
      },
      ui_nodes: [],
      previous_action_result: "NOT_EXECUTED",
      previous_checkpoint_match: false,
    },
    history_tail: [],
  } as unknown as NextStepRequest;
}

const CANDIDATE = {
  action_id: "act_1",
  intent: "CLICK" as const,
  target_desc: "settings icon",
  target_som_id: 1,
  selector: null,
  input_text: null,
  package_name: null,
  intent_spec: null,
  risk_level: "SAFE" as const,
  narration: "Tapping settings.",
  checkpoint: {
    expected_package: "com.example.app",
    expected_page_type: "SETTINGS",
    expected_elements: [],
  },
};

describe("reviewer frame handling", () => {
  beforeEach(() => {
    vi.resetModules();
    vi.restoreAllMocks();
    generateContent.mockReset();
    generateContent.mockResolvedValue({
      text: JSON.stringify({
        verdict: "APPROVE",
        reason: "Action looks safe.",
        approved_action_index: 0,
      }),
    });

    vi.doMock("@/lib/mobile-agent/genai-client", () => ({
      getGenAIClient: () => ({
        models: {
          generateContent,
        },
      }),
      resolveModelWithFallback: vi.fn().mockResolvedValue("gemini-2.5-pro"),
    }));
  });

  it("supports GCS-referenced frames without base64 payloads", async () => {
    const { runReviewer } = await import("@/lib/mobile-agent/reviewer");

    const request = makeRequest({
      frame_id: "f_gcs",
      ts_ms: 1000,
      gcs_uri: "gs://shots-bucket/frames/f_gcs.jpg",
      ui_signature: "sig_gcs",
    });

    const result = await runReviewer(request, [CANDIDATE]);

    expect(result.output.verdict).toBe("APPROVE");
    expect(generateContent).toHaveBeenCalledOnce();

    const contents = generateContent.mock.calls[0][0].contents as unknown[];
    const imagePart = contents[1] as { fileData?: { fileUri?: string } };
    expect(imagePart.fileData?.fileUri).toBe("gs://shots-bucket/frames/f_gcs.jpg");
  });

  it("supports inline base64 frames via the same shared builder", async () => {
    const { runReviewer } = await import("@/lib/mobile-agent/reviewer");

    const request = makeRequest({
      frame_id: "f_inline",
      ts_ms: 1000,
      image_base64: "aGVsbG8=",
      ui_signature: "sig_inline",
    });

    const result = await runReviewer(request, [CANDIDATE]);

    expect(result.output.verdict).toBe("APPROVE");
    const contents = generateContent.mock.calls[0][0].contents as unknown[];
    const imagePart = contents[1] as { inlineData?: { data?: string } };
    expect(imagePart.inlineData?.data).toBe("aGVsbG8=");
  });

  it("throws only when no frame source exists at all", async () => {
    const { runReviewer } = await import("@/lib/mobile-agent/reviewer");

    const request = makeRequest({
      frame_id: "f_empty",
      ts_ms: 1000,
      ui_signature: "sig_empty",
    });

    await expect(runReviewer(request, [CANDIDATE])).rejects.toThrow(
      "reviewer requires at least one observation frame",
    );
    expect(generateContent).not.toHaveBeenCalled();
  });
});
