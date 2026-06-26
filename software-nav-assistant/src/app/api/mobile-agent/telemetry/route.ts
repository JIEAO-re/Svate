import { NextResponse } from "next/server";
import {
  TelemetryBatchRequestSchema,
} from "@/lib/schemas/mobile-agent";
import { saveTelemetryEvents } from "@/lib/mobile-agent/persistence";
import { authenticateRequest } from "@/lib/mobile-agent/auth-utils";
import { rateLimitGuard } from "@/lib/mobile-agent/rate-limit";

export async function POST(req: Request) {
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

  // Cap telemetry ingestion per authenticated device so a single valid token
  // cannot spam unbounded writes. Use a telemetry-namespaced identity so it has
  // its own budget and never consumes the model-call budget shared by the
  // next-step / agent-turn routes. A missing identity fails open (see
  // enforceRateLimit), matching the other guarded routes.
  const limited = await rateLimitGuard(
    authResult.client_id ? `telemetry:${authResult.client_id}` : undefined,
  );
  if (limited) return limited;

  try {
    const body = await req.json();
    const parsed = TelemetryBatchRequestSchema.safeParse(body);
    if (!parsed.success) {
      return NextResponse.json(
        {
          success: false,
          error: "invalid_telemetry_payload",
          details: parsed.error.flatten(),
        },
        { status: 400 },
      );
    }

    await saveTelemetryEvents(parsed.data.events);
    // Do not return a global store snapshot here: it leaks aggregate row counts to
    // any authenticated device and runs several COUNT(*) queries per telemetry POST.
    return NextResponse.json({
      success: true,
      accepted: parsed.data.events.length,
    });
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : "unknown_error";
    return NextResponse.json(
      {
        success: false,
        error: "telemetry_store_failed",
        details: process.env.NODE_ENV === "development" ? message : undefined,
      },
      { status: 500 },
    );
  }
}
