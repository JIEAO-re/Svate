import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const ORIGINAL_ENV = { ...process.env };

function resetEnv() {
  for (const key of Object.keys(process.env)) {
    if (!(key in ORIGINAL_ENV)) {
      delete process.env[key];
    }
  }

  Object.assign(process.env, ORIGINAL_ENV);
}

// process.env.NODE_ENV is typed read-only in Next.js; tests need to override it.
function setNodeEnv(value: string) {
  (process.env as Record<string, string | undefined>).NODE_ENV = value;
}

// Reusable pg mock that returns the given count for the increment query.
function mockPgReturningCount(count: number) {
  const dbQuery = vi.fn().mockResolvedValue({ rows: [{ count: String(count) }] });
  vi.doMock("pg", () => ({
    Pool: vi.fn().mockImplementation(() => ({
      connect: vi.fn(),
      query: dbQuery,
    })),
  }));
  return dbQuery;
}

function mockPgThrowing(error: Error) {
  const dbQuery = vi.fn().mockRejectedValue(error);
  vi.doMock("pg", () => ({
    Pool: vi.fn().mockImplementation(() => ({
      connect: vi.fn(),
      query: dbQuery,
    })),
  }));
  return dbQuery;
}

describe("enforceRateLimit", () => {
  beforeEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it("is disabled (always allowed) when the limit is <= 0", async () => {
    delete process.env.POSTGRES_URL;
    process.env.MOBILE_AGENT_RPM_LIMIT = "0";

    const { enforceRateLimit } = await import("@/lib/mobile-agent/rate-limit");
    const result = await enforceRateLimit("device-a");

    expect(result.allowed).toBe(true);
    expect(result.backend).toBe("disabled");
  });

  it("fails open when no identity is provided", async () => {
    delete process.env.POSTGRES_URL;
    process.env.MOBILE_AGENT_RPM_LIMIT = "1";

    const { enforceRateLimit } = await import("@/lib/mobile-agent/rate-limit");
    const first = await enforceRateLimit(undefined);
    const second = await enforceRateLimit("   ");

    expect(first.allowed).toBe(true);
    expect(first.backend).toBe("failopen");
    expect(second.allowed).toBe(true);
    expect(second.backend).toBe("failopen");
  });

  it("allows up to the limit then blocks with the in-memory fallback", async () => {
    delete process.env.POSTGRES_URL;
    process.env.MOBILE_AGENT_RPM_LIMIT = "2";

    const { enforceRateLimit } = await import("@/lib/mobile-agent/rate-limit");

    const a = await enforceRateLimit("device-a");
    const b = await enforceRateLimit("device-a");
    const c = await enforceRateLimit("device-a");

    expect(a).toMatchObject({ allowed: true, backend: "memory", remaining: 1 });
    expect(b).toMatchObject({ allowed: true, backend: "memory", remaining: 0 });
    expect(c.allowed).toBe(false);
    expect(c.retryAfterSec).toBeGreaterThan(0);
  });

  it("tracks separate in-memory budgets per identity", async () => {
    delete process.env.POSTGRES_URL;
    process.env.MOBILE_AGENT_RPM_LIMIT = "1";

    const { enforceRateLimit } = await import("@/lib/mobile-agent/rate-limit");

    expect((await enforceRateLimit("device-a")).allowed).toBe(true);
    expect((await enforceRateLimit("device-a")).allowed).toBe(false);
    // A different identity has its own fresh budget.
    expect((await enforceRateLimit("device-b")).allowed).toBe(true);
  });

  it("resets the in-memory window after it elapses", async () => {
    delete process.env.POSTGRES_URL;
    process.env.MOBILE_AGENT_RPM_LIMIT = "1";
    process.env.MOBILE_AGENT_RATE_WINDOW_SEC = "60";
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-06-13T00:00:00.000Z"));

    const { enforceRateLimit } = await import("@/lib/mobile-agent/rate-limit");

    expect((await enforceRateLimit("device-a")).allowed).toBe(true);
    expect((await enforceRateLimit("device-a")).allowed).toBe(false);

    // Advance past the window boundary; the counter should reset.
    vi.setSystemTime(new Date("2026-06-13T00:01:01.000Z"));
    expect((await enforceRateLimit("device-a")).allowed).toBe(true);
  });

  it("uses the shared Postgres counter when configured", async () => {
    process.env.POSTGRES_URL = "postgresql://example";
    process.env.MOBILE_AGENT_RPM_LIMIT = "5";
    const dbQuery = mockPgReturningCount(3);

    const { enforceRateLimit } = await import("@/lib/mobile-agent/rate-limit");
    const result = await enforceRateLimit("device-a");

    expect(result).toMatchObject({ allowed: true, backend: "postgres", remaining: 2 });
    const sql = String(dbQuery.mock.calls[0]?.[0] || "");
    expect(sql).toContain("INSERT INTO agent_rate_limits");
    expect(sql).toContain("ON CONFLICT (identity, window_start)");
  });

  it("blocks via Postgres when the window count exceeds the limit", async () => {
    process.env.POSTGRES_URL = "postgresql://example";
    process.env.MOBILE_AGENT_RPM_LIMIT = "5";
    mockPgReturningCount(6);

    const { enforceRateLimit } = await import("@/lib/mobile-agent/rate-limit");
    const result = await enforceRateLimit("device-a");

    expect(result.allowed).toBe(false);
    expect(result.backend).toBe("postgres");
    expect(result.remaining).toBe(0);
  });

  it("fails open when the Postgres backend errors", async () => {
    process.env.POSTGRES_URL = "postgresql://example";
    process.env.MOBILE_AGENT_RPM_LIMIT = "1";
    mockPgThrowing(new Error("relation \"agent_rate_limits\" does not exist"));
    vi.spyOn(console, "warn").mockImplementation(() => undefined);

    const { enforceRateLimit } = await import("@/lib/mobile-agent/rate-limit");
    const result = await enforceRateLimit("device-a");

    expect(result.allowed).toBe(true);
    expect(result.backend).toBe("failopen");
  });
});

describe("rateLimitGuard", () => {
  beforeEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
  });

  it("returns null while within budget", async () => {
    delete process.env.POSTGRES_URL;
    process.env.MOBILE_AGENT_RPM_LIMIT = "5";

    const { rateLimitGuard } = await import("@/lib/mobile-agent/rate-limit");
    expect(await rateLimitGuard("device-a")).toBeNull();
  });

  it("returns a 429 with a rate_limited body once exceeded", async () => {
    delete process.env.POSTGRES_URL;
    process.env.MOBILE_AGENT_RPM_LIMIT = "1";

    const { rateLimitGuard } = await import("@/lib/mobile-agent/rate-limit");
    expect(await rateLimitGuard("device-a")).toBeNull();

    const blocked = await rateLimitGuard("device-a");
    expect(blocked).not.toBeNull();
    expect(blocked?.status).toBe(429);
    expect(blocked?.headers.get("Retry-After")).toBeTruthy();
    await expect(blocked?.json()).resolves.toMatchObject({
      success: false,
      error: "rate_limited",
    });
  });
});

describe("rate limiting on the agent-turn route", () => {
  beforeEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
  });

  function validBody() {
    return {
      session_id: "sess_1",
      trace_id: "trace_1",
      contents: [{ role: "user", parts: [{ text: "open settings" }] }],
    };
  }

  async function postAgentTurn() {
    const { POST } = await import("@/app/api/mobile-agent/agent-turn/route");
    return POST(
      new Request("http://localhost/api/mobile-agent/agent-turn", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(validBody()),
      }),
    );
  }

  it("returns 429 after the per-device budget is exhausted", async () => {
    setNodeEnv("development");
    process.env.SKIP_AUTH_DEV = "true"; // loopback dev bypass -> client_id "dev_bypass"
    delete process.env.POSTGRES_URL;
    process.env.MOBILE_AGENT_RPM_LIMIT = "1";

    // Stub the expensive agent turn so the first (allowed) request resolves
    // without a real model call.
    vi.doMock("@/lib/mobile-agent/agent-turn", () => ({
      runAgentTurn: vi.fn().mockResolvedValue({ done: true }),
    }));

    const first = await postAgentTurn();
    expect(first.status).toBe(200);

    const second = await postAgentTurn();
    expect(second.status).toBe(429);
    await expect(second.json()).resolves.toMatchObject({
      success: false,
      error: "rate_limited",
    });
  });
});
