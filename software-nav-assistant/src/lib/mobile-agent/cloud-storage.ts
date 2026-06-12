import { randomUUID } from "crypto";
import { Storage } from "@google-cloud/storage";
import {
  SCREENSHOT_UPLOAD_BUCKET,
  assertSignedUploadEnv,
} from "@/lib/mobile-agent/env";

export const ALLOWED_SCREENSHOT_UPLOAD_CONTENT_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
] as const;

const SIGNED_WRITE_URL_TTL_MS = 15 * 60 * 1000;
const SIGNED_READ_URL_TTL_MS = 15 * 60 * 1000;

let storage: Storage | null = null;

export function getStorageClient(): Storage {
  if (storage) return storage;
  storage = new Storage();
  return storage;
}

/** Parse a gs://bucket/object URI into bucket and object path, or null if malformed. */
export function parseGcsUri(gcsUri: string): { bucket: string; objectPath: string } | null {
  const match = gcsUri.trim().match(/^gs:\/\/([a-z0-9][-a-z0-9_.]*[a-z0-9])\/(.+)$/);
  if (!match) return null;
  return { bucket: match[1], objectPath: match[2] };
}

/**
 * Create a V4 signed read URL for a gs:// URI so providers that cannot read
 * GCS directly (e.g. OpenAI-compatible gateways) can fetch the object over
 * HTTPS. Throws when the URI is malformed or signing fails.
 */
export async function createSignedReadUrlForGcsUri(
  gcsUri: string,
  ttlMs: number = SIGNED_READ_URL_TTL_MS,
): Promise<string> {
  const parsed = parseGcsUri(gcsUri);
  if (!parsed) {
    throw new Error(`invalid gcs uri: ${gcsUri}`);
  }

  const [signedUrl] = await getStorageClient()
    .bucket(parsed.bucket)
    .file(parsed.objectPath)
    .getSignedUrl({
      version: "v4",
      action: "read",
      expires: Date.now() + ttlMs,
    });

  return signedUrl;
}

function getExtensionFromContentType(contentType: string): string {
  switch (contentType) {
    case "image/jpeg":
      return "jpg";
    case "image/png":
      return "png";
    case "image/webp":
      return "webp";
    default:
      return "bin";
  }
}

export function buildScreenshotUploadPath(params: {
  sessionId: string;
  traceId: string;
  frameIndex: number;
  contentType: string;
  timestamp?: number;
  uniqueId?: string;
}) {
  const timestamp = params.timestamp ?? Date.now();
  const uniqueId = params.uniqueId ?? randomUUID().slice(0, 8);
  const extension = getExtensionFromContentType(params.contentType);
  const date = new Date(timestamp);
  const datePrefix = [
    date.getFullYear(),
    String(date.getMonth() + 1).padStart(2, "0"),
    String(date.getDate()).padStart(2, "0"),
  ].join("/");

  return `frames/${datePrefix}/${params.sessionId}/${params.traceId}/frame_${params.frameIndex}_${timestamp}_${uniqueId}.${extension}`;
}

export async function createScreenshotUploadTarget(params: {
  contentType: string;
  sessionId?: string;
  traceId?: string;
  frameIndex?: number;
}) {
  assertSignedUploadEnv();

  const filePath = buildScreenshotUploadPath({
    sessionId: params.sessionId?.trim() || "unknown",
    traceId: params.traceId?.trim() || "unknown",
    frameIndex: params.frameIndex ?? 0,
    contentType: params.contentType,
  });
  const expiresAt = Date.now() + SIGNED_WRITE_URL_TTL_MS;
  const file = getStorageClient()
    .bucket(SCREENSHOT_UPLOAD_BUCKET)
    .file(filePath);

  const [signedUrl] = await file.getSignedUrl({
    version: "v4",
    action: "write",
    expires: expiresAt,
    contentType: params.contentType,
  });

  return {
    signedUrl,
    gcsUri: `gs://${SCREENSHOT_UPLOAD_BUCKET}/${filePath}`,
    expiresAt,
    filePath,
  };
}
