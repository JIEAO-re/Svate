import { NextResponse } from "next/server";
import { AgentTurnRequestSchema } from "@/lib/schemas/agent-turn";
import { runAgentTurn } from "@/lib/mobile-agent/agent-turn";
import { authenticateRequest } from "@/lib/mobile-agent/auth-utils";
import { rateLimitGuard } from "@/lib/mobile-agent/rate-limit";

// One model round-trip per loop step; allow the same headroom as next-step.
export const maxDuration = 60;

export async function POST(req: Request) {
  // ========== P0 authentication guard (same device auth as next-step) ==========
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

  // Per-device rate limit: cap expensive model calls per authenticated identity.
  const limited = await rateLimitGuard(authResult.client_id);
  if (limited) return limited;

  try {
    const body = await req.json();
    const parsed = AgentTurnRequestSchema.safeParse(body);
    if (!parsed.success) {
      return NextResponse.json(
        {
          success: false,
          error: "invalid_agent_turn_request",
          details: parsed.error.flatten(),
        },
        { status: 400 },
      );
    }

    const response = await runAgentTurn(parsed.data);
    return NextResponse.json({
      success: true,
      ...response,
    });
  } catch (error: unknown) {
    // Never leak stack traces; surface the message only outside production.
    const message = error instanceof Error ? error.message : "unknown_error";
    return NextResponse.json(
      {
        success: false,
        error: "mobile_agent_agent_turn_failed",
        details: process.env.NODE_ENV !== "production" ? message : undefined,
      },
      { status: 500 },
    );
  }
}
