import { NextResponse } from "next/server";
import { z } from "zod";
import { getGenAIClient } from "@/lib/mobile-agent/genai-client";
import { LEGACY_DEMO_MODEL, INTERNAL_JOB_TOKEN } from "@/lib/mobile-agent/env";

const RequestSchema = z.object({
  prompt: z.string().min(1).max(50_000),
  image_base64: z.string().min(1).optional(),
});

function cleanJsonText(raw: string): string {
  const match = raw.match(/```(?:json)?\s*([\s\S]*?)\s*```/i);
  return match ? match[1].trim() : raw.trim();
}

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
