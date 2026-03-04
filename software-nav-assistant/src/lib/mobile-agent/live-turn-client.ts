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
// 帧指纹去重：避免连续相同帧浪费 token
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
// 帧窗口解析：GCS URI 直通模型，不再下载到内存
// ============================================================================

interface ResolvedFrame {
  frameId: string;
  tsMs: number;
  uiSignature: string;
  /** 仅当 inline 模式时存在 */
  imageBase64?: string;
  /** 仅当 GCS 引用模式时存在 */
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
// 构建模型内容部件：GCS URI → fileData, inline → inlineData
// ============================================================================

function buildFrameParts(frameWindow: ResolvedFrame[]): Part[] {
  return frameWindow.map((frame): Part => {
    if (frame.gcsUri) {
      // 直接将 GCS URI 喂给模型，不下载、不转码
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
// 核心函数：无状态 generateContent 调用（取代 Live WebSocket 反模式）
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

  // 帧去重：fingerprint 命中时直接返回 WAIT，免去模型调用
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

  // 构建 contents：先放图片帧部件，最后放文本 prompt
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
  // 安全兜底：所有重试耗尽后返回 WAIT 动作，避免上层崩溃
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
