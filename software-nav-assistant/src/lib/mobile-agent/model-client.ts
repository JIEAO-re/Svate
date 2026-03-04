import { z } from "zod";
import { getGenAIClient, resolveModelWithFallback } from "@/lib/mobile-agent/genai-client";

const MAX_RETRIES = 2;

type GenerateJsonOptions<T extends z.ZodTypeAny> = {
  model: string;
  prompt: string;
  imageBase64: string;
  imageMimeType?: string;
  schema: T;
  /** Optional safe fallback value returned when all retries are exhausted. */
  safeFallback?: z.infer<T>;
};

export async function generateJsonWithImage<T extends z.ZodTypeAny>(
  options: GenerateJsonOptions<T>,
): Promise<{ output: z.infer<T>; latencyMs: number; usedFallback?: boolean }> {
  const started = Date.now();
  const ai = getGenAIClient();
  const resolvedModel = await resolveModelWithFallback(options.model, ["gemini-2.5-flash"]);

  let lastError: unknown;
  for (let attempt = 1; attempt <= MAX_RETRIES; attempt += 1) {
    try {
      const response = await ai.models.generateContent({
        model: resolvedModel,
        contents: [
          options.prompt,
          {
            inlineData: {
              data: options.imageBase64,
              mimeType: options.imageMimeType || "image/jpeg",
            },
          },
        ],
        config: {
          responseMimeType: "application/json",
          temperature: 0.1,
        },
      });
      const text = response.text || "{}";
      const parsed = options.schema.parse(JSON.parse(text));
      return { output: parsed, latencyMs: Date.now() - started };
    } catch (error) {
      lastError = error;
      console.warn(
        `[model-client] attempt ${attempt}/${MAX_RETRIES} failed: ${String((error as Error)?.message || error)}`,
      );
    }
  }

  // Safe fallback: if caller provided a fallback value, return it instead of throwing
  if (options.safeFallback !== undefined) {
    console.warn(
      `[model-client] all ${MAX_RETRIES} retries exhausted, returning safeFallback`,
    );
    return { output: options.safeFallback, latencyMs: Date.now() - started, usedFallback: true };
  }

  throw new Error(`model json generation failed: ${String((lastError as Error)?.message || lastError)}`);
}
