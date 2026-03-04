import { Pool } from "pg";
import { TelemetryEvent } from "@/lib/schemas/mobile-agent";
import { POSTGRES_URL } from "@/lib/mobile-agent/env";

type TurnEventRecord = {
  trace_id: string;
  session_id: string;
  turn_index: number;
  planner_model: string;
  planner_latency_ms: number;
  reviewer_model: string;
  reviewer_latency_ms: number;
  reviewer_verdict: string;
  final_intent: string;
  final_risk: string;
  block_reason: string | null;
  mode: "shadow" | "active";
  shadow_diff: Record<string, unknown> | null;
  created_at: string;
};

type SessionSummaryRecord = {
  session_id: string;
  last_trace_id: string;
  turns: number;
  last_updated_at: string;
};

type ShadowDiffRecord = {
  trace_id: string;
  session_id: string;
  turn_index: number;
  same_intent: boolean;
  same_target_desc: boolean;
  candidate_would_fail_reason: string | null;
  created_at: string;
};

type LiveTurnMetricRecord = {
  trace_id: string;
  session_id: string;
  turn_index: number;
  model: string;
  connect_latency_ms: number;
  inference_latency_ms: number;
  frame_count: number;
  observation_source: "SCREENSHOT" | "SCREEN_RECORDING";
  created_at: string;
};

type GeneratedMediaRecord = {
  trace_id: string;
  session_id: string;
  turn_index: number;
  media_type: "IMAGE" | "VIDEO";
  storage_uri: string;
  signed_url: string;
  expires_at: string;
  created_at: string;
};

type MediaJobStatus = "PENDING" | "SUBMITTED" | "FAILED";

type MediaJobRecord = {
  job_id: string;
  session_id: string;
  trace_id: string;
  status: MediaJobStatus;
  payload: Record<string, unknown>;
  operation_name?: string | null;
  error_message?: string | null;
  created_at: string;
  updated_at: string;
};

let pool: Pool | null = null;

function getPool(): Pool {
  if (pool) return pool;

  pool = new Pool({
    connectionString: POSTGRES_URL,
    ssl:
      process.env.DATABASE_SSL === "true"
        ? { rejectUnauthorized: false }
        : undefined,
    max: Number(process.env.DATABASE_POOL_MAX || 10),
  });

  return pool;
}

export async function saveTurnEvent(record: TurnEventRecord) {
  const db = getPool();
  const client = await db.connect();
  try {
    await client.query("BEGIN");

    await client.query(
      `
      INSERT INTO agent_turn_events (
        trace_id,
        session_id,
        turn_index,
        planner_model,
        planner_latency_ms,
        reviewer_model,
        reviewer_latency_ms,
        reviewer_verdict,
        final_intent,
        final_risk,
        block_reason,
        mode,
        shadow_diff,
        created_at
      ) VALUES (
        $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13::jsonb, $14::timestamptz
      )
      `,
      [
        record.trace_id,
        record.session_id,
        record.turn_index,
        record.planner_model,
        record.planner_latency_ms,
        record.reviewer_model,
        record.reviewer_latency_ms,
        record.reviewer_verdict,
        record.final_intent,
        record.final_risk,
        record.block_reason,
        record.mode,
        record.shadow_diff ? JSON.stringify(record.shadow_diff) : null,
        record.created_at,
      ],
    );

    await client.query(
      `
      INSERT INTO agent_session_summary (
        session_id,
        last_trace_id,
        turns,
        last_updated_at
      ) VALUES ($1, $2, 1, $3::timestamptz)
      ON CONFLICT (session_id)
      DO UPDATE SET
        last_trace_id = EXCLUDED.last_trace_id,
        turns = agent_session_summary.turns + 1,
        last_updated_at = EXCLUDED.last_updated_at
      `,
      [record.session_id, record.trace_id, record.created_at],
    );

    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

export async function saveLiveTurnMetric(record: LiveTurnMetricRecord) {
  const db = getPool();
  await db.query(
    `
    INSERT INTO agent_live_turn_metrics (
      trace_id,
      session_id,
      turn_index,
      model,
      connect_latency_ms,
      inference_latency_ms,
      frame_count,
      observation_source,
      created_at
    ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::timestamptz)
    `,
    [
      record.trace_id,
      record.session_id,
      record.turn_index,
      record.model,
      record.connect_latency_ms,
      record.inference_latency_ms,
      record.frame_count,
      record.observation_source,
      record.created_at,
    ],
  );
}

export async function saveGeneratedMedia(record: GeneratedMediaRecord) {
  const db = getPool();
  await db.query(
    `
    INSERT INTO agent_generated_media (
      trace_id,
      session_id,
      turn_index,
      media_type,
      storage_uri,
      signed_url,
      expires_at,
      created_at
    ) VALUES ($1, $2, $3, $4, $5, $6, $7::timestamptz, $8::timestamptz)
    `,
    [
      record.trace_id,
      record.session_id,
      record.turn_index,
      record.media_type,
      record.storage_uri,
      record.signed_url,
      record.expires_at,
      record.created_at,
    ],
  );
}

export async function createMediaJob(record: MediaJobRecord) {
  const db = getPool();
  await db.query(
    `
    INSERT INTO agent_media_jobs (
      job_id,
      session_id,
      trace_id,
      status,
      operation_name,
      error_message,
      payload,
      created_at,
      updated_at
    ) VALUES ($1, $2, $3, $4, $5, $6, $7::jsonb, $8::timestamptz, $9::timestamptz)
    ON CONFLICT (job_id)
    DO UPDATE SET
      status = EXCLUDED.status,
      operation_name = EXCLUDED.operation_name,
      error_message = EXCLUDED.error_message,
      payload = EXCLUDED.payload,
      updated_at = EXCLUDED.updated_at
    `,
    [
      record.job_id,
      record.session_id,
      record.trace_id,
      record.status,
      record.operation_name ?? null,
      record.error_message ?? null,
      JSON.stringify(record.payload),
      record.created_at,
      record.updated_at,
    ],
  );
}

export async function updateMediaJob(
  jobId: string,
  params: {
    status: MediaJobStatus;
    operation_name?: string | null;
    error_message?: string | null;
  },
) {
  const db = getPool();
  await db.query(
    `
    UPDATE agent_media_jobs
    SET
      status = $2,
      operation_name = $3,
      error_message = $4,
      updated_at = NOW()
    WHERE job_id = $1
    `,
    [
      jobId,
      params.status,
      params.operation_name ?? null,
      params.error_message ?? null,
    ],
  );
}

export async function saveShadowDiff(record: ShadowDiffRecord) {
  const db = getPool();
  await db.query(
    `
    INSERT INTO agent_shadow_diff (
      trace_id,
      session_id,
      turn_index,
      same_intent,
      same_target_desc,
      candidate_would_fail_reason,
      created_at
    ) VALUES ($1, $2, $3, $4, $5, $6, $7::timestamptz)
    `,
    [
      record.trace_id,
      record.session_id,
      record.turn_index,
      record.same_intent,
      record.same_target_desc,
      record.candidate_would_fail_reason,
      record.created_at,
    ],
  );
}

export async function saveTelemetryEvents(events: TelemetryEvent[]) {
  if (events.length === 0) return;

  const db = getPool();
  const client = await db.connect();
  try {
    await client.query("BEGIN");
    for (const event of events) {
      await client.query(
        `
        INSERT INTO agent_telemetry_events (
          trace_id,
          session_id,
          turn_index,
          event_type,
          payload,
          ts
        ) VALUES ($1, $2, $3, $4, $5::jsonb, COALESCE($6::timestamptz, NOW()))
        `,
        [
          event.trace_id,
          event.session_id,
          event.turn_index,
          event.event_type,
          JSON.stringify(event.payload),
          event.ts ?? null,
        ],
      );
    }
    await client.query("COMMIT");
  } catch (error) {
    await client.query("ROLLBACK");
    throw error;
  } finally {
    client.release();
  }
}

export async function getStoreSnapshot() {
  const db = getPool();
  const [turnEvents, sessionSummary, shadowDiff, telemetryEvents, liveMetrics, generatedMedia, mediaJobs] = await Promise.all([
    db.query<{ count: string }>("SELECT COUNT(*)::text AS count FROM agent_turn_events"),
    db.query<{ count: string }>("SELECT COUNT(*)::text AS count FROM agent_session_summary"),
    db.query<{ count: string }>("SELECT COUNT(*)::text AS count FROM agent_shadow_diff"),
    db.query<{ count: string }>("SELECT COUNT(*)::text AS count FROM agent_telemetry_events"),
    db.query<{ count: string }>("SELECT COUNT(*)::text AS count FROM agent_live_turn_metrics"),
    db.query<{ count: string }>("SELECT COUNT(*)::text AS count FROM agent_generated_media"),
    db.query<{ count: string }>("SELECT COUNT(*)::text AS count FROM agent_media_jobs"),
  ]);

  return {
    turnEvents: Number(turnEvents.rows[0]?.count || 0),
    sessionSummary: Number(sessionSummary.rows[0]?.count || 0),
    shadowDiff: Number(shadowDiff.rows[0]?.count || 0),
    telemetryEvents: Number(telemetryEvents.rows[0]?.count || 0),
    liveMetrics: Number(liveMetrics.rows[0]?.count || 0),
    generatedMedia: Number(generatedMedia.rows[0]?.count || 0),
    mediaJobs: Number(mediaJobs.rows[0]?.count || 0),
  };
}

export type {
  GeneratedMediaRecord,
  LiveTurnMetricRecord,
  MediaJobRecord,
  SessionSummaryRecord,
  ShadowDiffRecord,
  TurnEventRecord,
};
