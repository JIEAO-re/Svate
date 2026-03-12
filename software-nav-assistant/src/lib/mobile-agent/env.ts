const useVertex = process.env.GOOGLE_GENAI_USE_VERTEXAI?.trim().toLowerCase() === "true";
const useOpenAICompat = process.env.OPENAI_COMPAT_ENABLED?.trim().toLowerCase() === "true";

function invariant(condition: unknown, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

export const NODE_ENV = process.env.NODE_ENV?.trim().toLowerCase() || "development";
export const GOOGLE_GENAI_USE_VERTEXAI = useVertex;
export const OPENAI_COMPAT_ENABLED = useOpenAICompat;
export const OPENAI_COMPAT_BASE_URL =
  process.env.OPENAI_COMPAT_BASE_URL?.trim().replace(/\/+$/, "") || "";
export const OPENAI_COMPAT_API_KEY = process.env.OPENAI_COMPAT_API_KEY?.trim() || "";
export const GOOGLE_CLOUD_PROJECT = process.env.GOOGLE_CLOUD_PROJECT?.trim() || "";
export const GOOGLE_CLOUD_LOCATION = process.env.GOOGLE_CLOUD_LOCATION?.trim() || "";
export const GEMINI_API_KEY = process.env.GEMINI_API_KEY?.trim() || "";

export const GENAI_CLIENT_ENABLED = OPENAI_COMPAT_ENABLED || GOOGLE_GENAI_USE_VERTEXAI || !!GEMINI_API_KEY;

export const LEGACY_DEMO_MODEL = process.env.LEGACY_DEMO_MODEL?.trim() || "gemini-2.5-flash";
export const LIVE_MODEL = process.env.LIVE_MODEL?.trim() || "gemini-live-2.5-flash-preview";
export const PLANNER_MODEL = process.env.PLANNER_MODEL?.trim() || LIVE_MODEL;
export const REVIEWER_MODEL = process.env.REVIEWER_MODEL?.trim() || "gemini-2.5-pro";
export const GUIDE_IMAGE_MODEL = process.env.GUIDE_IMAGE_MODEL?.trim() || "gemini-2.5-flash-image-preview";
export const GUIDE_VIDEO_MODEL = process.env.GUIDE_VIDEO_MODEL?.trim() || "veo-2.0-generate-001";
export const POSTGRES_URL = process.env.POSTGRES_URL?.trim() || process.env.DATABASE_URL?.trim() || "";
export const GUIDE_MEDIA_BUCKET = process.env.GUIDE_MEDIA_BUCKET?.trim() || "";
export const LEGACY_GCS_BUCKET_NAME = process.env.GCS_BUCKET_NAME?.trim() || "";
export const SCREENSHOT_UPLOAD_BUCKET =
  process.env.SCREENSHOT_UPLOAD_BUCKET?.trim() || LEGACY_GCS_BUCKET_NAME;
export const GUIDE_MEDIA_SIGNED_URL_TTL_SEC = Number(process.env.GUIDE_MEDIA_SIGNED_URL_TTL_SEC || 1800);
export const CLOUD_TASKS_QUEUE = process.env.CLOUD_TASKS_QUEUE?.trim() || "";
export const CLOUD_TASKS_LOCATION = process.env.CLOUD_TASKS_LOCATION?.trim() || GOOGLE_CLOUD_LOCATION;
export const CLOUD_TASKS_PROJECT = process.env.CLOUD_TASKS_PROJECT?.trim() || GOOGLE_CLOUD_PROJECT;
export const SESSION_RECAP_JOB_URL = process.env.SESSION_RECAP_JOB_URL?.trim() || "";
export const INTERNAL_JOB_TOKEN = process.env.INTERNAL_JOB_TOKEN?.trim() || "";
export const INTERNAL_DEV_BYPASS =
  process.env.INTERNAL_DEV_BYPASS?.trim().toLowerCase() === "true";
export const SKIP_AUTH_DEV = process.env.SKIP_AUTH_DEV?.trim().toLowerCase() === "true";
export const ENABLE_LEGACY_DEMO = process.env.ENABLE_LEGACY_DEMO?.trim().toLowerCase() === "true";
export const ENABLE_GUIDE_MEDIA = process.env.ENABLE_GUIDE_MEDIA?.trim().toLowerCase() !== "false";
export const ENABLE_SESSION_RECAP_VIDEO =
  process.env.ENABLE_SESSION_RECAP_VIDEO?.trim().toLowerCase() === "true";

// Frame window and dedup configuration.
export const FRAME_WINDOW_SIZE = Number(process.env.FRAME_WINDOW_SIZE || 3);
export const FRAME_DEDUP_ENABLED =
  process.env.FRAME_DEDUP_ENABLED?.trim().toLowerCase() !== "false";

export function assertGenAiEnv() {
  if (OPENAI_COMPAT_ENABLED) {
    invariant(
      OPENAI_COMPAT_BASE_URL,
      "OPENAI_COMPAT_BASE_URL is required when OPENAI_COMPAT_ENABLED is true",
    );
    invariant(
      OPENAI_COMPAT_API_KEY,
      "OPENAI_COMPAT_API_KEY is required when OPENAI_COMPAT_ENABLED is true",
    );
    return;
  }

  if (GOOGLE_GENAI_USE_VERTEXAI) {
    invariant(GOOGLE_CLOUD_PROJECT, "GOOGLE_CLOUD_PROJECT is required for Vertex AI mode");
    invariant(GOOGLE_CLOUD_LOCATION, "GOOGLE_CLOUD_LOCATION is required for Vertex AI mode");
    return;
  }

  invariant(
    GEMINI_API_KEY,
    "GEMINI_API_KEY is required when GOOGLE_GENAI_USE_VERTEXAI is not enabled",
  );
}

export function assertPersistenceEnv() {
  invariant(POSTGRES_URL, "POSTGRES_URL is required for mobile-agent persistence");
}

export function assertGuideMediaEnv() {
  if (!ENABLE_GUIDE_MEDIA) return;

  invariant(
    GUIDE_MEDIA_BUCKET,
    "GUIDE_MEDIA_BUCKET is required when ENABLE_GUIDE_MEDIA is enabled",
  );
}

export function assertInternalJobEnv() {
  if (NODE_ENV === "production") {
    invariant(INTERNAL_JOB_TOKEN, "INTERNAL_JOB_TOKEN is required in production");
  }
}

export function assertSignedUploadEnv() {
  invariant(
    SCREENSHOT_UPLOAD_BUCKET,
    "SCREENSHOT_UPLOAD_BUCKET or GCS_BUCKET_NAME is required for signed uploads",
  );
}
