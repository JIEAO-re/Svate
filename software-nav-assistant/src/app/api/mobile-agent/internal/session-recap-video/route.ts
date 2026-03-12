import { NextResponse } from "next/server";
import { z } from "zod";
import {
  processSessionRecapVideoJob,
  type SessionRecapPayload,
} from "@/lib/mobile-agent/session-recap-video";
import { verifyInternalJobAuth } from "@/lib/mobile-agent/internal-auth";

const SessionRecapPayloadSchema = z.object({
  job_id: z.string().min(1),
  session_id: z.string().min(1),
  trace_id: z.string().min(1),
  goal: z.string().min(1),
});

export const maxDuration = 30;

export async function POST(req: Request) {
  const authResult = verifyInternalJobAuth(req, {
    endpoint: "/api/mobile-agent/internal/session-recap-video",
  });
  if (!authResult.valid) {
    return NextResponse.json(
      {
        success: false,
        error: "unauthorized_internal_job",
        details: authResult.error,
      },
      { status: 401 },
    );
  }

  try {
    const body = await req.json();
    const parsed = SessionRecapPayloadSchema.safeParse(body);
    if (!parsed.success) {
      return NextResponse.json(
        {
          success: false,
          error: "invalid_session_recap_payload",
          details: parsed.error.flatten(),
        },
        { status: 400 },
      );
    }

    await processSessionRecapVideoJob(parsed.data as SessionRecapPayload);
    return NextResponse.json({ success: true });
  } catch (error) {
    return NextResponse.json(
      {
        success: false,
        error: "session_recap_job_failed",
        details: process.env.NODE_ENV === "development" ? (error as Error)?.message : undefined,
      },
      { status: 500 },
    );
  }
}
