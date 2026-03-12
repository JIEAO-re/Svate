-- One-time upgrade for existing mobile-agent deployments.
-- Apply this script before enabling the new ON CONFLICT persistence logic.

BEGIN;

WITH ranked AS (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY trace_id ORDER BY id DESC) AS row_num
  FROM agent_turn_events
)
DELETE FROM agent_turn_events
WHERE id IN (SELECT id FROM ranked WHERE row_num > 1);

WITH ranked AS (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY trace_id ORDER BY id DESC) AS row_num
  FROM agent_shadow_diff
)
DELETE FROM agent_shadow_diff
WHERE id IN (SELECT id FROM ranked WHERE row_num > 1);

WITH ranked AS (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY trace_id ORDER BY id DESC) AS row_num
  FROM agent_live_turn_metrics
)
DELETE FROM agent_live_turn_metrics
WHERE id IN (SELECT id FROM ranked WHERE row_num > 1);

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_turn_events_trace_id
  ON agent_turn_events(trace_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_shadow_diff_trace_id
  ON agent_shadow_diff(trace_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_live_turn_metrics_trace_id
  ON agent_live_turn_metrics(trace_id);

WITH turn_stats AS (
  SELECT
    session_id,
    COUNT(*)::int AS turns,
    (ARRAY_AGG(trace_id ORDER BY created_at DESC, id DESC))[1] AS last_trace_id,
    MAX(created_at) AS last_updated_at
  FROM agent_turn_events
  GROUP BY session_id
)
INSERT INTO agent_session_summary (session_id, last_trace_id, turns, last_updated_at)
SELECT session_id, last_trace_id, turns, last_updated_at
FROM turn_stats
ON CONFLICT (session_id)
DO UPDATE SET
  last_trace_id = EXCLUDED.last_trace_id,
  turns = EXCLUDED.turns,
  last_updated_at = EXCLUDED.last_updated_at;

COMMIT;
