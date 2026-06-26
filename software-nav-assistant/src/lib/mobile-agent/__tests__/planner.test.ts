import { describe, it, expect, vi, beforeEach } from "vitest";

// runPlanner is a thin adapter over the live planner turn: it maps fields and
// derives latencyMs = connectLatencyMs + inferenceLatencyMs. Mock the live turn
// so we test that contract deterministically without a model call.
vi.mock("@/lib/mobile-agent/live-turn-client", () => ({
  runLivePlannerTurn: vi.fn(),
}));

import { runPlanner } from "@/lib/mobile-agent/planner";
import { runLivePlannerTurn } from "@/lib/mobile-agent/live-turn-client";

const mockedLive = vi.mocked(runLivePlannerTurn);

describe("runPlanner", () => {
  beforeEach(() => vi.clearAllMocks());

  it("maps the live result and derives latencyMs = connect + inference", async () => {
    mockedLive.mockResolvedValue({
      model: "planner-model",
      output: { action: "WAIT" } as never,
      connectLatencyMs: 30,
      inferenceLatencyMs: 70,
      frameCount: 2,
      gcsFrameCount: 1,
      usedLive: true,
    } as never);

    const res = await runPlanner({ session_id: "s" } as never);

    expect(res.model).toBe("planner-model");
    expect(res.connectLatencyMs).toBe(30);
    expect(res.inferenceLatencyMs).toBe(70);
    expect(res.latencyMs).toBe(100); // 30 + 70
    expect(res.frameCount).toBe(2);
    expect(res.gcsFrameCount).toBe(1);
    expect(res.usedLive).toBe(true);
  });

  it("forwards frame-window options to the live turn", async () => {
    mockedLive.mockResolvedValue({
      model: "m",
      output: {} as never,
      connectLatencyMs: 0,
      inferenceLatencyMs: 0,
      frameCount: 0,
      gcsFrameCount: 0,
      usedLive: false,
    } as never);

    const options = { windowSize: 5 } as never;
    await runPlanner({ session_id: "s" } as never, options);

    expect(mockedLive).toHaveBeenCalledWith({ session_id: "s" }, options);
  });
});
