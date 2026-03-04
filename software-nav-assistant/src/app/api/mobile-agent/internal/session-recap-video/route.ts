import { NextResponse } from "next/server";
import { z } from "zod";
import { INTERNAL_JOB_TOKEN } from "@/lib/mobile-agent/env";
import {
  processSessionRecapVideoJob,
  type SessionRecapPayload,
} from "@/lib/mobile-agent/session-recap-video";

const SessionRecapPayloadSchema = z.object({
  job_id: z.string().min(1),
  session_id: z.string().min(1),
  trace_id: z.string().min(1),
  goal: z.string().min(1),
});

export const maxDuration = 30;

function verifyInternalAuth(req: Request): boolean {
  // In non-production, allow requests when INTERNAL_JOB_TOKEN is not configured
  if (!INTERNAL_JOB_TOKEN) {
    if (process.env.NODE_ENV === "production") return false;
    return true;
  }
  const authHeader = req.headers.get("authorization")?.trim() || "";
  return authHeader === `Bearer ${INTERNAL_JOB_TOKEN}`;
}

export async function POST(req: Request) {
  if (!verifyInternalAuth(req)) {
    return NextResponse.json(
      {
        success: false,
        error: "unauthorized_internal_job",
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
