import { NextResponse } from "next/server";
import {
  MOBILE_AGENT_RATE_WINDOW_SEC,
  MOBILE_AGENT_RPM_LIMIT,
  POSTGRES_URL,
} from "@/lib/mobile-agent/env";
import { getPool } from "@/lib/mobile-agent/persistence";

export type RateLimitBackend = "postgres" | "memory" | "disabled" | "failopen";

export interface RateLimitResult {
  /** True when the request is within budget and should proceed. */
  allowed: boolean;
  /** Effective per-window request budget for the identity. */
  limit: number;
  /** Requests still available in the current window (never negative). */
  remaining: number;
  /** Seconds until the current window resets; sent as Retry-After on a 429. */
  retryAfterSec: number;
  /** Which backend produced the decision (useful for tests and logging). */
  backend: RateLimitBackend;
}

interface MemoryCounter {
  count: number;
  windowStart: number;
}

// Best-effort per-instance fallback used when Postgres is not configured. On
// multi-instance Cloud Run this only bounds a single instance, but it still
// caps a hot loop and is strictly better than no limit at all.
const memoryCounters = new Map<string, MemoryCounter>();

function windowMs(): number {
  return Math.max(1, MOBILE_AGENT_RATE_WINDOW_SEC) * 1000;
}

function currentWindowStart(nowMs: number): number {
  const size = windowMs();
  return Math.floor(nowMs / size) * size;
}

function allow(limit: number, remaining: number, backend: RateLimitBackend): RateLimitResult {
  return {
    allowed: true,
    limit,
    remaining: Math.max(0, remaining),
    retryAfterSec: 0,
    backend,
  };
}

function checkMemory(identity: string, limit: number, nowMs: number): RateLimitResult {
  const windowStart = currentWindowStart(nowMs);
  const existing = memoryCounters.get(identity);

  // Opportunistically evict counters from windows that have already elapsed so
  // the map does not grow without bound across many short-lived identities.
  if (memoryCounters.size > 10_000) {
    for (const [key, value] of memoryCounters) {
      if (value.windowStart < windowStart) memoryCounters.delete(key);
    }
  }

  const counter =
    existing && existing.windowStart === windowStart
      ? existing
      : { count: 0, windowStart };
  counter.count += 1;
  memoryCounters.set(identity, counter);

  const retryAfterSec = Math.ceil((windowStart + windowMs() - nowMs) / 1000);
  if (counter.count > limit) {
    return { allowed: false, limit, remaining: 0, retryAfterSec, backend: "memory" };
  }
  return allow(limit, limit - counter.count, "memory");
}

async function checkPostgres(
  identity: string,
  limit: number,
  nowMs: number,
): Promise<RateLimitResult> {
  const windowStart = currentWindowStart(nowMs);
  const size = windowMs();
  const expiresAt = new Date(windowStart + size).toISOString();
  const db = getPool();

  // Atomic fixed-window counter: the INSERT ... ON CONFLICT DO UPDATE both
  // creates and increments the row in one round trip, so concurrent requests
  // from the same identity cannot race past the limit. The data-modifying
  // `cleanup` CTE runs unconditionally (Postgres always executes WITH-clause
  // writes), reclaiming this identity's elapsed windows so the table stays
  // bounded to roughly one row per active identity without a separate cron.
  const result = await db.query<{ count: string }>(
    `
    WITH bump AS (
      INSERT INTO agent_rate_limits (identity, window_start, count, expires_at)
      VALUES ($1, $2, 1, $3::timestamptz)
      ON CONFLICT (identity, window_start)
      DO UPDATE SET count = agent_rate_limits.count + 1
      RETURNING count
    ),
    cleanup AS (
      DELETE FROM agent_rate_limits WHERE identity = $1 AND window_start < $2
    )
    SELECT count FROM bump
    `,
    [identity, windowStart, expiresAt],
  );

  const count = Number(result.rows[0]?.count ?? 1);
  const retryAfterSec = Math.ceil((windowStart + size - nowMs) / 1000);
  if (count > limit) {
    return { allowed: false, limit, remaining: 0, retryAfterSec, backend: "postgres" };
  }
  return allow(limit, limit - count, "postgres");
}

/**
 * Enforce a per-identity request budget for the expensive authenticated LLM
 * routes. Keyed by the authenticated device/session identity from
 * authenticateRequest so a single valid token cannot issue unbounded model
 * calls.
 *
 * Fails open by design: a limiter-backend outage (Postgres down, table missing,
 * transient error) returns `allowed: true` so the limiter can never take the API
 * down. Set MOBILE_AGENT_RPM_LIMIT<=0 to disable enforcement entirely.
 */
export async function enforceRateLimit(identity: string | undefined): Promise<RateLimitResult> {
  const limit = MOBILE_AGENT_RPM_LIMIT;
  if (!Number.isFinite(limit) || limit <= 0) {
    return allow(Number.POSITIVE_INFINITY, Number.POSITIVE_INFINITY, "disabled");
  }

  // A missing identity should never share a single global bucket with every
  // other unidentified caller (that would let one device's budget block
  // unrelated callers); fail open instead.
  const key = identity?.trim();
  if (!key) {
    return allow(limit, limit, "failopen");
  }

  const nowMs = Date.now();

  if (!POSTGRES_URL) {
    return checkMemory(key, limit, nowMs);
  }

  try {
    return await checkPostgres(key, limit, nowMs);
  } catch (error) {
    // Fail open on any backend error so a limiter outage does not 500 the API.
    console.warn(
      `[rate-limit] backend unavailable, failing open for ${key}: ${
        error instanceof Error ? error.message : String(error)
      }`,
    );
    return allow(limit, limit, "failopen");
  }
}

/**
 * Route guard: enforce the per-identity budget and, when exceeded, return a
 * ready-to-send 429 response. Returns null when the request is within budget so
 * the caller proceeds. Centralizes the 429 shape across every protected route.
 */
export async function rateLimitGuard(identity: string | undefined): Promise<NextResponse | null> {
  const result = await enforceRateLimit(identity);
  if (result.allowed) return null;

  return NextResponse.json(
    { success: false, error: "rate_limited" },
    {
      status: 429,
      headers: {
        "Retry-After": String(result.retryAfterSec),
        "X-RateLimit-Limit": String(result.limit),
        "X-RateLimit-Remaining": String(result.remaining),
      },
    },
  );
}

/** Test-only hook to reset the in-memory fallback between cases. */
export function __resetMemoryCounters() {
  memoryCounters.clear();
}
