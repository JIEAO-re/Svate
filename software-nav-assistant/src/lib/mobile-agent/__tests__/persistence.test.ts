import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const ORIGINAL_ENV = { ...process.env };
const state = {
  clientQuery: vi.fn(),
  dbQuery: vi.fn(),
  release: vi.fn(),
};

function resetEnv() {
  for (const key of Object.keys(process.env)) {
    if (!(key in ORIGINAL_ENV)) {
      delete process.env[key];
    }
  }

  Object.assign(process.env, ORIGINAL_ENV);
}

describe("persistence", () => {
  beforeEach(() => {
    resetEnv();
    process.env.POSTGRES_URL = "postgresql://example";
    vi.resetModules();
    vi.restoreAllMocks();
    state.clientQuery.mockReset();
    state.dbQuery.mockReset();
    state.release.mockReset();
  });

  afterEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
  });

  it("batches telemetry inserts in chunks of 100", async () => {
    state.clientQuery.mockResolvedValue({ rowCount: 1, rows: [] });
    state.dbQuery.mockResolvedValue({ rowCount: 1, rows: [] });

    vi.doMock("pg", () => ({
      Pool: vi.fn().mockImplementation(() => ({
        connect: vi.fn().mockResolvedValue({
          query: state.clientQuery,
          release: state.release,
        }),
        query: state.dbQuery,
      })),
    }));

    const { saveTelemetryEvents } = await import("@/lib/mobile-agent/persistence");

    await saveTelemetryEvents(
      Array.from({ length: 250 }, (_, index) => ({
        trace_id: `trace_${index}`,
        session_id: "sess_1",
        turn_index: index,
        event_type: "debug_dump",
        payload: { index },
        ts: "2026-03-08T00:00:00.000Z",
      })),
    );

    const insertCalls = state.clientQuery.mock.calls.filter(([sql]) =>
      String(sql).includes("INSERT INTO agent_telemetry_events"),
    );

    expect(insertCalls).toHaveLength(3);
    expect(insertCalls[0]?.[1]).toHaveLength(600);
    expect(insertCalls[1]?.[1]).toHaveLength(600);
    expect(insertCalls[2]?.[1]).toHaveLength(300);
  });

  it("skips session summary updates when turn events already exist", async () => {
    state.clientQuery.mockImplementation(async (sql: string) => {
      if (sql === "BEGIN" || sql === "COMMIT") {
        return { rowCount: 0, rows: [] };
      }

      if (sql.includes("INSERT INTO agent_turn_events")) {
        return { rowCount: 0, rows: [] };
      }

      return { rowCount: 1, rows: [] };
    });
    state.dbQuery.mockResolvedValue({ rowCount: 1, rows: [] });

    vi.doMock("pg", () => ({
      Pool: vi.fn().mockImplementation(() => ({
        connect: vi.fn().mockResolvedValue({
          query: state.clientQuery,
          release: state.release,
        }),
        query: state.dbQuery,
      })),
    }));

    const { saveTurnEvent } = await import("@/lib/mobile-agent/persistence");

    await saveTurnEvent({
      trace_id: "trace_1",
      session_id: "sess_1",
      turn_index: 0,
      planner_model: "planner",
      planner_latency_ms: 1,
      reviewer_model: "reviewer",
      reviewer_latency_ms: 1,
      reviewer_verdict: "APPROVE",
      final_intent: "CLICK",
      final_risk: "SAFE",
      block_reason: null,
      mode: "active",
      shadow_diff: null,
      created_at: "2026-03-08T00:00:00.000Z",
    });

    const sessionSummaryCalls = state.clientQuery.mock.calls.filter(([sql]) =>
      String(sql).includes("INSERT INTO agent_session_summary"),
    );

    expect(sessionSummaryCalls).toHaveLength(0);
  });

  it("uses ON CONFLICT for live metrics and shadow diffs", async () => {
    state.clientQuery.mockResolvedValue({ rowCount: 1, rows: [] });
    state.dbQuery.mockResolvedValue({ rowCount: 1, rows: [] });

    vi.doMock("pg", () => ({
      Pool: vi.fn().mockImplementation(() => ({
        connect: vi.fn().mockResolvedValue({
          query: state.clientQuery,
          release: state.release,
        }),
        query: state.dbQuery,
      })),
    }));

    const { saveLiveTurnMetric, saveShadowDiff } = await import("@/lib/mobile-agent/persistence");

    await saveLiveTurnMetric({
      trace_id: "trace_live",
      session_id: "sess_1",
      turn_index: 1,
      model: "live",
      connect_latency_ms: 1,
      inference_latency_ms: 2,
      frame_count: 1,
      observation_source: "SCREENSHOT",
      created_at: "2026-03-08T00:00:00.000Z",
    });
    await saveShadowDiff({
      trace_id: "trace_shadow",
      session_id: "sess_1",
      turn_index: 1,
      same_intent: true,
      same_target_desc: true,
      candidate_would_fail_reason: null,
      created_at: "2026-03-08T00:00:00.000Z",
    });

    const liveMetricSql = String(state.dbQuery.mock.calls[0]?.[0] || "");
    const shadowDiffSql = String(state.dbQuery.mock.calls[1]?.[0] || "");

    expect(liveMetricSql).toContain("ON CONFLICT (trace_id) DO NOTHING");
    expect(shadowDiffSql).toContain("ON CONFLICT (trace_id) DO NOTHING");
  });
});
