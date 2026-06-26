-- One-time upgrade for existing mobile-agent deployments (2026-06-13).
-- Adds per-identity rate limiting for the authenticated LLM routes
-- (agent-turn, next-step, chat-goal, internal gemini-json / session-recap).
--   * agent_rate_limits holds a fixed-window request counter keyed by the
--     authenticated device/session identity, so a single valid token cannot
--     issue unbounded expensive model calls.
--   * The route handler increments the counter atomically via
--     INSERT ... ON CONFLICT (identity, window_start) DO UPDATE, so the limit
--     is enforced across Cloud Run instances (not just per process).
--   * If this table is absent, the limiter fails open (it never blocks the API),
--     so running this migration is what activates shared-store enforcement.

BEGIN;

CREATE TABLE IF NOT EXISTS agent_rate_limits (
  identity TEXT NOT NULL,
  window_start BIGINT NOT NULL,
  count INT NOT NULL DEFAULT 0,
  expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (identity, window_start)
);

CREATE INDEX IF NOT EXISTS idx_agent_rate_limits_expires_at
  ON agent_rate_limits(expires_at);

COMMIT;
