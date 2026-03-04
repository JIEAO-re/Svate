-- PostgreSQL schema for mobile-agent telemetry and shadow comparison

CREATE TABLE IF NOT EXISTS agent_turn_events (
  id BIGSERIAL PRIMARY KEY,
  trace_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  turn_index INT NOT NULL,
  planner_model TEXT NOT NULL,
  planner_latency_ms INT NOT NULL,
  reviewer_model TEXT NOT NULL,
  reviewer_latency_ms INT NOT NULL,
  reviewer_verdict TEXT NOT NULL,
  final_intent TEXT NOT NULL,
  final_risk TEXT NOT NULL,
  block_reason TEXT NULL,
  mode TEXT NOT NULL,
  shadow_diff JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_turn_events_session_turn
  ON agent_turn_events(session_id, turn_index);

CREATE TABLE IF NOT EXISTS agent_session_summary (
  session_id TEXT PRIMARY KEY,
  last_trace_id TEXT NOT NULL,
  turns INT NOT NULL,
  last_updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS agent_shadow_diff (
  id BIGSERIAL PRIMARY KEY,
  trace_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  turn_index INT NOT NULL,
  same_intent BOOLEAN NOT NULL,
  same_target_desc BOOLEAN NOT NULL,
  candidate_would_fail_reason TEXT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_shadow_diff_session_turn
  ON agent_shadow_diff(session_id, turn_index);

CREATE TABLE IF NOT EXISTS agent_telemetry_events (
  id BIGSERIAL PRIMARY KEY,
  trace_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  turn_index INT NOT NULL,
  event_type TEXT NOT NULL,
  payload JSONB NOT NULL,
  ts TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_telemetry_events_session_turn
  ON agent_telemetry_events(session_id, turn_index);

CREATE TABLE IF NOT EXISTS agent_live_turn_metrics (
  id BIGSERIAL PRIMARY KEY,
  trace_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  turn_index INT NOT NULL,
  model TEXT NOT NULL,
  connect_latency_ms INT NOT NULL,
  inference_latency_ms INT NOT NULL,
  frame_count INT NOT NULL,
  observation_source TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_live_turn_metrics_session_turn
  ON agent_live_turn_metrics(session_id, turn_index);

CREATE TABLE IF NOT EXISTS agent_generated_media (
  id BIGSERIAL PRIMARY KEY,
  trace_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  turn_index INT NOT NULL,
  media_type TEXT NOT NULL,
  storage_uri TEXT NOT NULL,
  signed_url TEXT NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_generated_media_session_turn
  ON agent_generated_media(session_id, turn_index);

CREATE TABLE IF NOT EXISTS agent_media_jobs (
  id BIGSERIAL PRIMARY KEY,
  job_id TEXT NOT NULL UNIQUE,
  session_id TEXT NOT NULL,
  trace_id TEXT NOT NULL,
  status TEXT NOT NULL,
  operation_name TEXT NULL,
  error_message TEXT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_media_jobs_session
  ON agent_media_jobs(session_id);
