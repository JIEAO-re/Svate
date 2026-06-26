-- One-time upgrade for existing mobile-agent deployments (2026-06-12).
-- Adds client-side idempotency support for telemetry ingestion:
--   * agent_telemetry_events.client_event_id stores an optional
--     client-generated event id.
--   * A unique partial index lets the insert path use
--     ON CONFLICT (client_event_id) WHERE client_event_id IS NOT NULL
--     DO NOTHING, so retried batches no longer create duplicate rows.
-- Events without client_event_id keep the previous append-only behavior.

BEGIN;

ALTER TABLE agent_telemetry_events
  ADD COLUMN IF NOT EXISTS client_event_id TEXT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_telemetry_events_client_event_id
  ON agent_telemetry_events(client_event_id)
  WHERE client_event_id IS NOT NULL;

COMMIT;
