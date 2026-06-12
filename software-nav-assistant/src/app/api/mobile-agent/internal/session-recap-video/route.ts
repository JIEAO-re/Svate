import { NextResponse } from "next/server";
import { z } from "zod";
import {
  processSessionRecapPollJob,
  processSessionRecapVideoJob,
  type SessionRecapPayload,
} from "@/lib/mobile-agent/session-recap-video";
import { verifyInternalJobAuth } from "@/lib/mobile-agent/internal-auth";

const SessionRecapPayloadSchema = z.object({
  job_id: z.string().min(1),
  session_id: z.string().min(1),
  trace_id: z.string().min(1),
  goal: z.string().min(1),
  action: z.enum(["generate", "poll"]).default("generate"),
  operation_name: z.string().min(1).optional(),
  attempt: z.number().int().min(1).optional(),
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

    // Poll tasks check the pending Veo operation and either persist the final
    // video URI, fail the job, or re-enqueue themselves until done.
    if (parsed.data.action === "poll") {
      if (!parsed.data.operation_name) {
        return NextResponse.json(
          {
            success: false,
            error: "invalid_session_recap_payload",
            details: "operation_name is required when action is poll",
          },
          { status: 400 },
        );
      }
      await processSessionRecapPollJob(parsed.data as SessionRecapPayload);
      return NextResponse.json({ success: true });
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
