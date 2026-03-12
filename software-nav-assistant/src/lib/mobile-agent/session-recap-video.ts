import { randomUUID } from "crypto";
import { CloudTasksClient } from "@google-cloud/tasks";
import { getGenAIClient, resolveModelWithFallback } from "@/lib/mobile-agent/genai-client";
import {
  CLOUD_TASKS_LOCATION,
  CLOUD_TASKS_PROJECT,
  CLOUD_TASKS_QUEUE,
  ENABLE_SESSION_RECAP_VIDEO,
  GUIDE_MEDIA_BUCKET,
  GUIDE_VIDEO_MODEL,
  INTERNAL_JOB_TOKEN,
  SESSION_RECAP_JOB_URL,
  assertGuideMediaEnv,
  assertInternalJobEnv,
} from "@/lib/mobile-agent/env";
import { createMediaJob, updateMediaJob } from "@/lib/mobile-agent/persistence";

let tasksClient: CloudTasksClient | null = null;

function getCloudTasksClient(): CloudTasksClient {
  if (tasksClient) return tasksClient;
  tasksClient = new CloudTasksClient();
  return tasksClient;
}

export type SessionRecapPayload = {
  job_id: string;
  session_id: string;
  trace_id: string;
  goal: string;
};

export async function enqueueSessionRecapVideo(params: {
  sessionId: string;
  traceId: string;
  goal: string;
}): Promise<string | null> {
  if (!ENABLE_SESSION_RECAP_VIDEO) return null;
  const jobId = `media_job_${randomUUID()}`;
  const payload: SessionRecapPayload = {
    job_id: jobId,
    session_id: params.sessionId,
    trace_id: params.traceId,
    goal: params.goal,
  };

  await createMediaJob({
    job_id: jobId,
    session_id: params.sessionId,
    trace_id: params.traceId,
    status: "PENDING",
    payload,
    created_at: new Date().toISOString(),
    updated_at: new Date().toISOString(),
  });

  if (!CLOUD_TASKS_PROJECT || !CLOUD_TASKS_LOCATION || !CLOUD_TASKS_QUEUE || !SESSION_RECAP_JOB_URL) {
    return jobId;
  }

  assertInternalJobEnv();

  const client = getCloudTasksClient();
  const parent = client.queuePath(CLOUD_TASKS_PROJECT, CLOUD_TASKS_LOCATION, CLOUD_TASKS_QUEUE);
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
  };
  if (INTERNAL_JOB_TOKEN) {
    headers.Authorization = `Bearer ${INTERNAL_JOB_TOKEN}`;
  }

  await client.createTask({
    parent,
    task: {
      httpRequest: {
        httpMethod: "POST",
        url: SESSION_RECAP_JOB_URL,
        headers,
        body: Buffer.from(JSON.stringify(payload)).toString("base64"),
      },
    },
  });
  await updateMediaJob(jobId, { status: "SUBMITTED" });

  return jobId;
}

function buildRecapPrompt(payload: SessionRecapPayload): string {
  return [
    "Generate a short mobile-task recap video.",
    `Session ID: ${payload.session_id}`,
    `Trace ID: ${payload.trace_id}`,
    `Goal: ${payload.goal}`,
    "Style: instructional, concise, no personal data, no unsafe content.",
    "Duration: 3-4 seconds. Aspect ratio: 9:16.",
  ].join("\n");
}

export async function processSessionRecapVideoJob(payload: SessionRecapPayload) {
  if (!ENABLE_SESSION_RECAP_VIDEO) return;

  try {
    assertGuideMediaEnv();
    const ai = getGenAIClient();
    const model = await resolveModelWithFallback(GUIDE_VIDEO_MODEL, ["veo-2.0-generate-001"]);
    const operation = await ai.models.generateVideos({
      model,
      source: {
        prompt: buildRecapPrompt(payload),
      },
      config: {
        numberOfVideos: 1,
        outputGcsUri: `gs://${GUIDE_MEDIA_BUCKET}/session-recap/${payload.session_id}/`,
        durationSeconds: 4,
        aspectRatio: "9:16",
        resolution: "720p",
        generateAudio: false,
      },
    }) as { name?: string };

    await updateMediaJob(payload.job_id, {
      status: "SUBMITTED",
      operation_name: operation.name || null,
    });
  } catch (error) {
    await updateMediaJob(payload.job_id, {
      status: "FAILED",
      error_message: (error as Error)?.message || "session_recap_failed",
    });
    throw error;
  }
}
