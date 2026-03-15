import { getGenAIClient, resolveModelWithFallback } from "@/lib/mobile-agent/genai-client";
import { PLANNER_MODEL, FRAME_WINDOW_SIZE, FRAME_DEDUP_ENABLED } from "@/lib/mobile-agent/env";
import { buildPlannerPrompt } from "@/lib/mobile-agent/prompts";
import { createHash } from "crypto";
import {
  NextStepRequest,
  PlannerOutput,
  PlannerOutputSchema,
} from "@/lib/schemas/mobile-agent";
import type { Part } from "@google/genai";

const MAX_RETRIES = Number(process.env.LIVE_TURN_MAX_RETRIES || 3);
const RETRY_BASE_DELAY_MS = Number(process.env.LIVE_TURN_RETRY_BASE_DELAY_MS || 500);

// ============================================================================
// Deduplicate by frame fingerprint so repeated frames do not waste tokens
// ============================================================================
const lastFrameFingerprintBySession = new Map<string, string>();

function computeFrameFingerprint(hint: string, uiSignature: string): string {
  return createHash("sha256").update(`${uiSignature}|${hint}`).digest("hex");
}

function cleanJsonText(raw: string): string {
  const match = raw.match(/```(?:json)?\s*([\s\S]*?)\s*```/i);
  return match ? match[1].trim() : raw.trim();
}

// ============================================================================
// Resolve the frame window directly from GCS URIs without downloading into memory
// ============================================================================

interface ResolvedFrame {
  frameId: string;
  tsMs: number;
  uiSignature: string;
  /** Present only in inline mode. */
  imageBase64?: string;
  /** Present only in GCS reference mode. */
  gcsUri?: string;
  sourceType: "inline" | "gcs";
}

function getFrameWindow(request: NextStepRequest): ResolvedFrame[] {
  const frames = request.observation.media_window?.frames ?? [];
  let resolved: ResolvedFrame[] = [];

  if (frames.length > 0) {
    resolved = frames.map((frame) => {
      if (frame.gcs_uri) {
        return {
          frameId: frame.frame_id,
          tsMs: frame.ts_ms,
          uiSignature: frame.ui_signature,
          gcsUri: frame.gcs_uri,
          sourceType: "gcs" as const,
        };
      }
      return {
        frameId: frame.frame_id,
        tsMs: frame.ts_ms,
        imageBase64: frame.image_base64 ?? "",
        uiSignature: frame.ui_signature,
        sourceType: "inline" as const,
      };
    });
  } else if (request.observation.screenshot_base64) {
    resolved = [
      {
        frameId: `legacy_${request.turn_index}`,
        tsMs: Date.now(),
        imageBase64: request.observation.screenshot_base64,
        uiSignature: "legacy_screenshot",
        sourceType: "inline" as const,
      },
    ];
  }

  if (resolved.length === 0) return [];

  // Apply FRAME_WINDOW_SIZE: keep only the latest N frames
  if (resolved.length > FRAME_WINDOW_SIZE) {
    resolved = resolved.slice(-FRAME_WINDOW_SIZE);
  }

  // Apply FRAME_DEDUP_ENABLED: skip if latest frame fingerprint matches previous turn
  if (FRAME_DEDUP_ENABLED && resolved.length > 0) {
    const latest = resolved[resolved.length - 1];
    const hint = latest.gcsUri ?? (latest.imageBase64 ?? "").slice(0, 128);
    const fp = computeFrameFingerprint(hint, latest.uiSignature);
    const previousFingerprint = lastFrameFingerprintBySession.get(request.session_id);
    if (fp === previousFingerprint) {
      return [];
    }
    lastFrameFingerprintBySession.set(request.session_id, fp);
  }

  return resolved;
}

// ============================================================================
// Build model content parts: GCS URI -> fileData, inline -> inlineData
// ============================================================================

function buildFrameParts(frameWindow: ResolvedFrame[]): Part[] {
  return frameWindow.map((frame): Part => {
    if (frame.gcsUri) {
      // Feed the GCS URI directly to the model without downloading or transcoding
      return {
        fileData: {
          fileUri: frame.gcsUri,
          mimeType: "image/jpeg",
        },
      };
    }
    return {
      inlineData: {
        data: frame.imageBase64 ?? "",
        mimeType: "image/jpeg",
      },
    };
  });
}

// ============================================================================
// Core function: stateless generateContent call that replaces the Live WebSocket anti-pattern
// ============================================================================

export async function runLivePlannerTurn(
  request: NextStepRequest,
): Promise<{
  model: string;
  output: PlannerOutput;
  connectLatencyMs: number;
  inferenceLatencyMs: number;
  frameCount: number;
  gcsFrameCount: number;
}> {
  const frameWindow = getFrameWindow(request);

  // Skip the model call when fingerprint deduplication hits and return WAIT immediately
  if (frameWindow.length === 0) {
    return {
      model: PLANNER_MODEL,
      output: {
        candidates: [
          {
            action_id: `wait_dedup_${Date.now()}`,
            intent: "WAIT",
            target_desc: "frame_dedup_skip",
            target_bbox: null,
            target_som_id: null,
            selector: null,
            input_text: null,
            package_name: null,
            intent_spec: null,
            risk_level: "SAFE",
            narration: "Screen unchanged, waiting for new content.",
            checkpoint: null,
          },
        ],
      },
      connectLatencyMs: 0,
      inferenceLatencyMs: 0,
      frameCount: 0,
      gcsFrameCount: 0,
    };
  }

  const gcsFrameCount = frameWindow.filter((f) => f.sourceType === "gcs").length;
  const ai = getGenAIClient();
  const prompt = buildPlannerPrompt(request);
  const resolvedModel = await resolveModelWithFallback(PLANNER_MODEL, [
    "gemini-2.5-flash",
  ]);

  // Build contents by appending frame parts first and the text prompt last
  const frameParts = buildFrameParts(frameWindow);
  const contents: Part[] = [...frameParts, { text: prompt }];

  let lastError: unknown;
  for (let attempt = 1; attempt <= MAX_RETRIES; attempt += 1) {
    try {
      const inferenceStarted = Date.now();

      const response = await ai.models.generateContent({
        model: resolvedModel,
        contents,
        config: {
          responseMimeType: "application/json",
          temperature: 0.1,
        },
      });

      const text = response.text || "{}";
      const parsed = PlannerOutputSchema.parse(JSON.parse(cleanJsonText(text)));

      return {
        model: resolvedModel,
        output: parsed,
        connectLatencyMs: 0, // 无连接开销
        inferenceLatencyMs: Date.now() - inferenceStarted,
        frameCount: frameWindow.length,
        gcsFrameCount,
      };
    } catch (error) {
      lastError = error;
      if (attempt < MAX_RETRIES) {
        const delay = RETRY_BASE_DELAY_MS * Math.pow(2, attempt - 1);
        console.warn(
          `[live-turn-client] attempt ${attempt}/${MAX_RETRIES} failed, retrying in ${delay}ms: ${String((error as Error)?.message || error)}`,
        );
        await new Promise((r) => setTimeout(r, delay));
      }
    }
  }

  // ============================================================================
  // Safety fallback: return WAIT after retries are exhausted so the upper layer does not crash
  // ============================================================================
  console.warn(
    `[live-turn-client] all ${MAX_RETRIES} attempts failed, returning safe WAIT fallback. Last error: ${String((lastError as Error)?.message || lastError)}`,
  );
  return {
    model: resolvedModel,
    output: {
      candidates: [
        {
          action_id: `wait_fallback_${Date.now()}`,
          intent: "WAIT",
          target_desc: "live_planner_fallback",
          target_bbox: null,
          target_som_id: null,
          selector: null,
          input_text: null,
          package_name: null,
          intent_spec: null,
          risk_level: "SAFE",
          narration: "The system encountered an issue. Waiting for the next observation.",
          checkpoint: null,
        },
      ],
    },
    connectLatencyMs: 0,
    inferenceLatencyMs: 0,
    frameCount: frameWindow.length,
    gcsFrameCount,
  };
}
