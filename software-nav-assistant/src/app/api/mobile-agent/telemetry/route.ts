import { NextResponse } from "next/server";
import {
  TelemetryBatchRequestSchema,
} from "@/lib/schemas/mobile-agent";
import { saveTelemetryEvents, getStoreSnapshot } from "@/lib/mobile-agent/persistence";
import { authenticateRequest } from "@/lib/mobile-agent/auth-utils";

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
    return NextResponse.json({
      success: true,
      accepted: parsed.data.events.length,
      snapshot: await getStoreSnapshot(),
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
