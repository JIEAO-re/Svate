import { NextResponse } from "next/server";
import { z } from "zod";
import { authenticateRequest } from "@/lib/mobile-agent/auth-utils";
import {
  ALLOWED_SCREENSHOT_UPLOAD_CONTENT_TYPES,
  createScreenshotUploadTarget,
} from "@/lib/mobile-agent/cloud-storage";

const SignedUploadRequestSchema = z.object({
  content_type: z.enum(ALLOWED_SCREENSHOT_UPLOAD_CONTENT_TYPES),
  session_id: z.string().trim().optional(),
  trace_id: z.string().trim().optional(),
  frame_index: z.number().int().min(0).optional(),
});

export async function POST(request: Request) {
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
    const parsed = SignedUploadRequestSchema.safeParse(body);
    if (!parsed.success) {
      return NextResponse.json(
        {
          success: false,
          error: "invalid_signed_url_request",
          details: parsed.error.flatten(),
        },
        { status: 400 },
      );
    }

    const target = await createScreenshotUploadTarget({
      contentType: parsed.data.content_type,
      sessionId: parsed.data.session_id,
      traceId: parsed.data.trace_id,
      frameIndex: parsed.data.frame_index,
    });

    return NextResponse.json({
      signed_url: target.signedUrl,
      gcs_uri: target.gcsUri,
      expires_at: target.expiresAt,
      file_path: target.filePath,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : "unknown_error";
    console.error("[signed-url] failed:", error);
    return NextResponse.json(
      {
        success: false,
        error: "signed_url_generation_failed",
        details: process.env.NODE_ENV === "development" ? message : undefined,
      },
      { status: 500 },
    );
  }
}
