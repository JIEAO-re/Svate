import { NextResponse } from "next/server";
import { NextStepRequestSchema } from "@/lib/schemas/mobile-agent";
import { runMobileAgentPipeline } from "@/lib/mobile-agent/pipeline";
import { authenticateRequest } from "@/lib/mobile-agent/auth-utils";

export const maxDuration = 30;

export async function POST(req: Request) {
  // ========== P0 authentication guard ==========
  const authResult = await authenticateRequest(req);
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
    const body = await req.json();
    const parsed = NextStepRequestSchema.safeParse(body);
    if (!parsed.success) {
      return NextResponse.json(
        {
          success: false,
          error: "invalid_next_step_request",
          details: parsed.error.flatten(),
        },
        { status: 400 },
      );
    }

    const response = await runMobileAgentPipeline(parsed.data);
    return NextResponse.json({
      success: true,
      ...response,
    });
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : "unknown_error";
    return NextResponse.json(
      {
        success: false,
        error: "mobile_agent_next_step_failed",
        details: process.env.NODE_ENV !== "production" ? message : undefined,
      },
      { status: 500 },
    );
  }
}
