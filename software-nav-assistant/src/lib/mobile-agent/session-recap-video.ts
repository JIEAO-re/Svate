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
import { createMediaJob, updateMediaJob, getMediaJob } from "@/lib/mobile-agent/persistence";

// Poll cadence for the Veo long-running operation.
const POLL_DELAY_SECONDS = 30;
const MAX_POLL_ATTEMPTS = 20;

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
  /** "generate" submits the Veo request; "poll" checks the pending operation. */
  action?: "generate" | "poll";
  /** Long-running operation name, present for action=poll. */
  operation_name?: string;
  /** Poll attempt counter (1-based), present for action=poll. */
  attempt?: number;
};

function isCloudTasksConfigured(): boolean {
  return !!(CLOUD_TASKS_PROJECT && CLOUD_TASKS_LOCATION && CLOUD_TASKS_QUEUE && SESSION_RECAP_JOB_URL);
}

async function enqueueRecapTask(payload: SessionRecapPayload, delaySeconds = 0) {
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
      ...(delaySeconds > 0
        ? {
            scheduleTime: {
              seconds: Math.floor(Date.now() / 1000) + delaySeconds,
            },
          }
        : {}),
    },
  });
}

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
    action: "generate",
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

  if (!isCloudTasksConfigured()) {
    return jobId;
  }

  await enqueueRecapTask(payload);
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

  // Idempotency: Cloud Tasks can redeliver this job. If a prior attempt already
  // submitted to Veo (operation_name recorded), do NOT submit again — that would
  // bill a second generation. Re-enqueue the poll for the existing operation and
  // return. If the read fails, fall through to normal submission (no worse than
  // the prior behavior).
  try {
    const existing = await getMediaJob(payload.job_id);
    if (existing?.operation_name) {
      if (isCloudTasksConfigured()) {
        await enqueueRecapTask(
          {
            ...payload,
            action: "poll",
            operation_name: existing.operation_name,
            attempt: 1,
          },
          POLL_DELAY_SECONDS,
        );
      }
      return;
    }
  } catch (error) {
    console.warn(
      `[session-recap-video] idempotency check failed for job ${payload.job_id}; proceeding`,
      error,
    );
  }

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

    const operationName = operation.name || null;
    await updateMediaJob(payload.job_id, {
      status: "SUBMITTED",
      operation_name: operationName,
    });

    // Close the loop: re-enqueue a poll task so the operation result is
    // eventually written back to the media job instead of being dropped.
    if (!operationName) {
      await updateMediaJob(payload.job_id, {
        status: "FAILED",
        error_message: "generate_videos_missing_operation_name",
      });
      return;
    }

    if (!isCloudTasksConfigured()) {
      console.warn(
        `[session-recap-video] Cloud Tasks is not configured; operation ${operationName} for job ${payload.job_id} will not be polled`,
      );
      return;
    }

    await enqueueRecapTask(
      {
        ...payload,
        action: "poll",
        operation_name: operationName,
        attempt: 1,
      },
      POLL_DELAY_SECONDS,
    );
  } catch (error) {
    await updateMediaJob(payload.job_id, {
      status: "FAILED",
      error_message: (error as Error)?.message || "session_recap_failed",
    });
    throw error;
  }
}

type VideosOperationResult = {
  done?: boolean;
  error?: { message?: string };
  response?: {
    generatedVideos?: Array<{
      video?: {
        uri?: string;
      };
    }>;
  };
};

export async function processSessionRecapPollJob(payload: SessionRecapPayload) {
  if (!ENABLE_SESSION_RECAP_VIDEO) return;

  const operationName = payload.operation_name?.trim() || "";
  const attempt = payload.attempt && payload.attempt > 0 ? payload.attempt : 1;

  if (!operationName) {
    await updateMediaJob(payload.job_id, {
      status: "FAILED",
      error_message: "poll_missing_operation_name",
    });
    return;
  }

  try {
    const ai = getGenAIClient();
    if (!ai.operations?.getVideosOperation) {
      await updateMediaJob(payload.job_id, {
        status: "FAILED",
        operation_name: operationName,
        error_message: "video_operation_polling_unsupported",
      });
      return;
    }

    const operation = (await ai.operations.getVideosOperation({
      operation: { name: operationName },
    })) as VideosOperationResult;

    if (!operation.done) {
      if (attempt >= MAX_POLL_ATTEMPTS) {
        await updateMediaJob(payload.job_id, {
          status: "FAILED",
          operation_name: operationName,
          error_message: `poll_attempts_exhausted_after_${attempt}`,
        });
        return;
      }
      await enqueueRecapTask(
        {
          ...payload,
          action: "poll",
          operation_name: operationName,
          attempt: attempt + 1,
        },
        POLL_DELAY_SECONDS,
      );
      return;
    }

    if (operation.error) {
      await updateMediaJob(payload.job_id, {
        status: "FAILED",
        operation_name: operationName,
        error_message: operation.error.message || "video_operation_failed",
      });
      return;
    }

    const videoUri = operation.response?.generatedVideos?.[0]?.video?.uri?.trim() || "";
    if (!videoUri) {
      await updateMediaJob(payload.job_id, {
        status: "FAILED",
        operation_name: operationName,
        error_message: "video_operation_done_without_uri",
      });
      return;
    }

    await updateMediaJob(payload.job_id, {
      status: "SUCCEEDED",
      operation_name: operationName,
      video_uri: videoUri,
    });
  } catch (error) {
    await updateMediaJob(payload.job_id, {
      status: "FAILED",
      operation_name: operationName,
      error_message: (error as Error)?.message || "session_recap_poll_failed",
    });
    throw error;
  }
}
