import { NextResponse } from "next/server";
import { z } from "zod";
import { getGenAIClient } from "@/lib/mobile-agent/genai-client";
import { LEGACY_DEMO_MODEL } from "@/lib/mobile-agent/env";
import { verifyInternalJobAuth } from "@/lib/mobile-agent/internal-auth";
import { authenticateRequest } from "@/lib/mobile-agent/auth-utils";

const RequestSchema = z.object({
  prompt: z.string().min(1).max(50_000),
  image_base64: z.string().min(1).optional(),
});

function cleanJsonText(raw: string): string {
  const match = raw.match(/```(?:json)?\s*([\s\S]*?)\s*```/i);
  return match ? match[1].trim() : raw.trim();
}

export async function POST(req: Request) {
  // Accept either credential type: the internal job token (server-to-server)
  // or regular device authentication (App Check / device JWT). Clients no
  // longer need the server-side internal secret baked into the APK.
  const internalAuth = verifyInternalJobAuth(req, {
    endpoint: "/api/mobile-agent/internal/gemini-json",
  });
  if (!internalAuth.valid) {
    const deviceAuth = await authenticateRequest(req);
    if (!deviceAuth.valid) {
      return NextResponse.json(
        {
          success: false,
          error: "unauthorized_internal_job",
          details: {
            internal_auth: internalAuth.error,
            device_auth: deviceAuth.error,
          },
        },
        { status: 401 },
      );
    }
  }

  try {
    const body = await req.json();
    const parsed = RequestSchema.safeParse(body);
    if (!parsed.success) {
      return NextResponse.json(
        {
          success: false,
          error: "invalid_gemini_json_request",
          details: parsed.error.flatten(),
        },
        { status: 400 },
      );
    }

    const ai = getGenAIClient();
    const model = process.env.GEMINI_MODEL?.trim() || LEGACY_DEMO_MODEL;
    const parts: Array<string | { inlineData: { data: string; mimeType: string } }> = [
      parsed.data.prompt,
    ];
    if (parsed.data.image_base64) {
      parts.push({
        inlineData: {
          data: parsed.data.image_base64,
          mimeType: "image/jpeg",
        },
      });
    }

    const response = await ai.models.generateContent({
      model,
      contents: parts,
      config: {
        responseMimeType: "application/json",
        temperature: 0.2,
      },
    });

    const rawText = response.text || "{}";
    const json = JSON.parse(cleanJsonText(rawText)) as Record<string, unknown>;
    return NextResponse.json({
      success: true,
      json,
    });
  } catch (error: unknown) {
    const message = error instanceof Error ? error.message : "unknown_error";
    return NextResponse.json(
      {
        success: false,
        error: "gemini_json_failed",
        details: process.env.NODE_ENV === "development" ? message : undefined,
      },
      { status: 500 },
    );
  }
}
