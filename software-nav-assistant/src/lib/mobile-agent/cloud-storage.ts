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

let storage: Storage | null = null;

export function getStorageClient(): Storage {
  if (storage) return storage;
  storage = new Storage();
  return storage;
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
