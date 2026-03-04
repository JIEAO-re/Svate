import { NextRequest, NextResponse } from "next/server";
import { Storage } from "@google-cloud/storage";
import { v4 as uuidv4 } from "uuid";
import { authenticateRequest } from "@/lib/mobile-agent/auth-utils";

/**
 * P1/P2 媒体异步化：GCS 预签名 URL 生成 API
 *
 * 架构设计：
 * - Android 端请求预签名 URL，直传 GCS，绕过 Cloud Run 带宽瓶颈
 * - 返回 signed_url（用于上传）和 gcs_uri（用于云端引用）
 * - URL 有效期 15 分钟，足够完成上传
 *
 * 安全考量：
 * - 仅允许特定 content_type（image/jpeg, image/png, image/webp）
 * - 文件路径包含 session_id 和 trace_id，便于追踪和清理
 * - 生产环境应添加 JWT/Firebase App Check 鉴权
 */

const ALLOWED_CONTENT_TYPES = [
  "image/jpeg",
  "image/png",
  "image/webp",
];

const BUCKET_NAME = process.env.GCS_BUCKET_NAME || "stave-media-uploads";
const SIGNED_URL_EXPIRATION_MS = 15 * 60 * 1000; // 15 minutes

// GCS 客户端（生产环境使用 ADC 或 Service Account）
const storage = new Storage();
const bucket = storage.bucket(BUCKET_NAME);

export async function POST(request: NextRequest) {
  const authResult = await authenticateRequest(request);
  if (!authResult.valid) {
    return NextResponse.json(
      {
        success: false,
        error: "authentication_failed",
        details: authResult.error,
      },
      { status: 401 },
    );
  }

  try {
    const body = await request.json();
    const { content_type, session_id, trace_id, frame_index } = body;

    // 1. 验证 content_type
    if (!content_type || !ALLOWED_CONTENT_TYPES.includes(content_type)) {
      return NextResponse.json(
        { error: "Invalid content_type. Allowed: image/jpeg, image/png, image/webp" },
        { status: 400 }
      );
    }

    // 2. 生成文件路径
    const extension = getExtensionFromContentType(content_type);
    const timestamp = Date.now();
    const uniqueId = uuidv4().slice(0, 8);
    const filePath = buildFilePath({
      sessionId: session_id || "unknown",
      traceId: trace_id || "unknown",
      frameIndex: frame_index ?? 0,
      timestamp,
      uniqueId,
      extension,
    });

    // 3. 生成预签名 URL
    const file = bucket.file(filePath);
    const [signedUrl] = await file.getSignedUrl({
      version: "v4",
      action: "write",
      expires: Date.now() + SIGNED_URL_EXPIRATION_MS,
      contentType: content_type,
    });

    // 4. 构建 GCS URI
    const gcsUri = `gs://${BUCKET_NAME}/${filePath}`;

    return NextResponse.json({
      signed_url: signedUrl,
      gcs_uri: gcsUri,
      expires_at: Date.now() + SIGNED_URL_EXPIRATION_MS,
      file_path: filePath,
    });
  } catch (error) {
    console.error("[GCS Signed URL] Error:", error);
    return NextResponse.json(
      { error: "Failed to generate signed URL" },
      { status: 500 }
    );
  }
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

function buildFilePath(params: {
  sessionId: string;
  traceId: string;
  frameIndex: number;
  timestamp: number;
  uniqueId: string;
  extension: string;
}): string {
  const { sessionId, traceId, frameIndex, timestamp, uniqueId, extension } = params;
  const date = new Date(timestamp);
  const datePrefix = `${date.getFullYear()}/${String(date.getMonth() + 1).padStart(2, "0")}/${String(date.getDate()).padStart(2, "0")}`;

  return `frames/${datePrefix}/${sessionId}/${traceId}/frame_${frameIndex}_${timestamp}_${uniqueId}.${extension}`;
}
