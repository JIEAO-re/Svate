import { GoogleGenAI } from "@google/genai";
import {
  GEMINI_API_KEY,
  GOOGLE_CLOUD_LOCATION,
  GOOGLE_CLOUD_PROJECT,
  GOOGLE_GENAI_USE_VERTEXAI,
  OPENAI_COMPAT_API_KEY,
  OPENAI_COMPAT_BASE_URL,
  OPENAI_COMPAT_ENABLED,
  assertGenAiEnv,
} from "@/lib/mobile-agent/env";
import { createSignedReadUrlForGcsUri } from "@/lib/mobile-agent/cloud-storage";

type GenerateContentParams = {
  model: string;
  contents: unknown;
  config?: {
    responseMimeType?: string;
    temperature?: number;
    /** Upper bound on generated tokens; forwarded to the provider verbatim. */
    maxOutputTokens?: number;
    /** System prompt; forwarded to the provider verbatim. */
    systemInstruction?: unknown;
    /** Function-calling tool declarations; forwarded to the provider verbatim. */
    tools?: unknown;
  };
};

/** One part of a model candidate; mirrors the subset of the Gemini Part shape
 * the agent loop consumes (text and functionCall). */
type GenerateContentPart = {
  text?: string;
  functionCall?: {
    name?: string;
    args?: Record<string, unknown>;
  };
};

/** One model candidate; mirrors the subset of the Gemini Candidate shape the
 * agent loop consumes. */
type GenerateContentCandidate = {
  content?: {
    parts?: GenerateContentPart[];
  };
};

type GenerateContentResponse = {
  text?: string;
  /** Raw candidates, exposed so function-calling callers can read functionCall
   * parts that the flattened `text` getter drops. Present for the Gemini SDK
   * client; absent for the OpenAI-compatible adapter. */
  candidates?: GenerateContentCandidate[];
  /** Adapter metadata; set when input had to be degraded for the provider. */
  meta?: {
    /** True when one or more image inputs could not be delivered to the model. */
    vision_input_missing?: boolean;
  };
};

type ModelListResponse = {
  page?: Array<{ name?: string }>;
};

export type GenAIClientLike = {
  models: {
    generateContent(params: GenerateContentParams): Promise<GenerateContentResponse>;
    list(params?: unknown): Promise<ModelListResponse>;
    generateImages(params: unknown): Promise<unknown>;
    generateVideos(params: unknown): Promise<unknown>;
  };
  /** Long-running operation polling; absent for providers without support. */
  operations?: {
    getVideosOperation(params: { operation: { name: string } }): Promise<unknown>;
  };
};

let client: GenAIClientLike | null = null;
let modelCache:
  | {
      expiresAt: number;
      modelNames: Set<string>;
    }
  | null = null;

function normalizeModelName(name: string): string {
  return name.trim().replace(/^models\//, "");
}

type OpenAITextPart = {
  type: "text";
  text: string;
};

type OpenAIImagePart = {
  type: "image_url";
  image_url: {
    url: string;
  };
};

type OpenAIMessageContent = string | Array<OpenAITextPart | OpenAIImagePart>;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

type OpenAIConversionState = {
  visionInputMissing: boolean;
};

async function appendOpenAIContentPart(
  value: unknown,
  output: Array<OpenAITextPart | OpenAIImagePart>,
  state: OpenAIConversionState,
) {
  if (typeof value === "string") {
    if (value.trim()) output.push({ type: "text", text: value });
    return;
  }

  if (!isRecord(value)) {
    if (value != null) output.push({ type: "text", text: String(value) });
    return;
  }

  const text = value.text;
  if (typeof text === "string" && text.trim()) {
    output.push({ type: "text", text });
  }

  const inlineData = value.inlineData;
  if (isRecord(inlineData)) {
    const data = typeof inlineData.data === "string" ? inlineData.data.trim() : "";
    if (data) {
      const mimeType =
        typeof inlineData.mimeType === "string" && inlineData.mimeType.trim()
          ? inlineData.mimeType.trim()
          : "image/jpeg";
      output.push({
        type: "image_url",
        image_url: {
          url: `data:${mimeType};base64,${data}`,
        },
      });
    }
  }

  const fileData = value.fileData;
  if (isRecord(fileData)) {
    const fileUri = typeof fileData.fileUri === "string" ? fileData.fileUri.trim() : "";
    if (!fileUri) return;

    if (/^(https?:\/\/|data:)/i.test(fileUri)) {
      output.push({
        type: "image_url",
        image_url: { url: fileUri },
      });
      return;
    }

    if (/^gs:\/\//i.test(fileUri)) {
      // OpenAI-compatible gateways cannot read GCS, so exchange the gs://
      // reference for a signed HTTPS read URL. Never drop the image silently:
      // signing failures are logged and surfaced via response metadata.
      try {
        const signedUrl = await createSignedReadUrlForGcsUri(fileUri);
        output.push({
          type: "image_url",
          image_url: { url: signedUrl },
        });
      } catch (error) {
        console.warn(
          `[genai-client] failed to create signed read URL for ${fileUri}; vision input is missing for this request: ${String((error as Error)?.message || error)}`,
        );
        state.visionInputMissing = true;
      }
      return;
    }

    // Unknown URI scheme: the image cannot be delivered to the provider.
    console.warn(
      `[genai-client] unsupported file URI scheme for OpenAI-compatible provider, vision input is missing: ${fileUri}`,
    );
    state.visionInputMissing = true;
  }
}

async function normalizeContentsToOpenAI(
  contents: unknown,
  state: OpenAIConversionState,
): Promise<OpenAIMessageContent> {
  if (typeof contents === "string") return contents;

  const normalized: Array<OpenAITextPart | OpenAIImagePart> = [];
  if (Array.isArray(contents)) {
    for (const entry of contents) {
      await appendOpenAIContentPart(entry, normalized, state);
    }
  } else {
    await appendOpenAIContentPart(contents, normalized, state);
  }

  return normalized.length > 0 ? normalized : "";
}

function extractTextFromOpenAIResponse(payload: unknown): string {
  if (!isRecord(payload)) return "";
  const choices = payload.choices;
  if (!Array.isArray(choices) || choices.length === 0) return "";

  const first = choices[0];
  if (!isRecord(first)) return "";
  const message = first.message;
  if (!isRecord(message)) return "";

  const content = message.content;
  if (typeof content === "string") return content;

  if (Array.isArray(content)) {
    return content
      .map((item) => {
        if (typeof item === "string") return item;
        if (isRecord(item) && typeof item.text === "string") return item.text;
        return "";
      })
      .join("\n")
      .trim();
  }

  return "";
}

async function postOpenAIChatCompletion(
  params: GenerateContentParams,
  includeJsonResponseFormat: boolean,
): Promise<GenerateContentResponse> {
  const conversionState: OpenAIConversionState = { visionInputMissing: false };
  const body: Record<string, unknown> = {
    model: params.model,
    messages: [
      {
        role: "user",
        content: await normalizeContentsToOpenAI(params.contents, conversionState),
      },
    ],
    temperature: params.config?.temperature ?? 0.1,
  };

  if (includeJsonResponseFormat && params.config?.responseMimeType === "application/json") {
    body.response_format = { type: "json_object" };
  }

  const response = await fetch(`${OPENAI_COMPAT_BASE_URL}/chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${OPENAI_COMPAT_API_KEY}`,
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(
      `[openai-compat] chat completion failed: HTTP ${response.status} | ${text}`,
    );
  }

  const json = await response.json();
  return {
    text: extractTextFromOpenAIResponse(json),
    ...(conversionState.visionInputMissing
      ? { meta: { vision_input_missing: true } }
      : {}),
  };
}

function createOpenAICompatClient(): GenAIClientLike {
  return {
    models: {
      async generateContent(params: GenerateContentParams): Promise<GenerateContentResponse> {
        try {
          return await postOpenAIChatCompletion(params, true);
        } catch (error) {
          const message = String((error as Error)?.message || error);
          const shouldRetryWithoutResponseFormat =
            params.config?.responseMimeType === "application/json" &&
            /response_format|unsupported|invalid_request_error/i.test(message);
          if (!shouldRetryWithoutResponseFormat) throw error;
          return postOpenAIChatCompletion(params, false);
        }
      },
      async list(): Promise<ModelListResponse> {
        try {
          const response = await fetch(`${OPENAI_COMPAT_BASE_URL}/models`, {
            method: "GET",
            headers: {
              Authorization: `Bearer ${OPENAI_COMPAT_API_KEY}`,
            },
          });
          if (!response.ok) return { page: [] };
          const json = await response.json();
          if (!isRecord(json) || !Array.isArray(json.data)) return { page: [] };

          const page = json.data
            .map((entry) => {
              if (!isRecord(entry)) return null;
              const modelName =
                typeof entry.id === "string"
                  ? entry.id
                  : typeof entry.name === "string"
                    ? entry.name
                    : "";
              return modelName ? { name: modelName } : null;
            })
            .filter((entry): entry is { name: string } => entry !== null);

          return { page };
        } catch {
          return { page: [] };
        }
      },
      async generateImages(): Promise<unknown> {
        throw new Error(
          "OPENAI_COMPAT does not support models.generateImages in this project",
        );
      },
      async generateVideos(): Promise<unknown> {
        throw new Error(
          "OPENAI_COMPAT does not support models.generateVideos in this project",
        );
      },
    },
  };
}

export function getGenAIClient(): GenAIClientLike {
  if (client) return client;
  assertGenAiEnv();

  if (OPENAI_COMPAT_ENABLED) {
    client = createOpenAICompatClient();
    return client;
  }

  client = (
    GOOGLE_GENAI_USE_VERTEXAI
      ? new GoogleGenAI({
          vertexai: true,
          project: GOOGLE_CLOUD_PROJECT,
          location: GOOGLE_CLOUD_LOCATION,
        })
      : new GoogleGenAI({
          apiKey: GEMINI_API_KEY,
        })
  ) as unknown as GenAIClientLike;

  return client;
}

async function getCachedModelNames(): Promise<Set<string>> {
  const now = Date.now();
  if (modelCache && modelCache.expiresAt > now) {
    return modelCache.modelNames;
  }

  const ai = getGenAIClient();
  const pager = await ai.models.list({
    config: {
      pageSize: 200,
      queryBase: true,
    },
  });

  const names = new Set<string>();
  for (const model of pager.page ?? []) {
    const name = model.name?.trim();
    if (!name) continue;
    names.add(normalizeModelName(name));
  }

  modelCache = {
    expiresAt: now + 10 * 60_000,
    modelNames: names,
  };
  return names;
}

export async function resolveModelWithFallback(
  preferred: string,
  fallbacks: string[] = [],
): Promise<string> {
  const normalizedPreferred = normalizeModelName(preferred);
  const candidates = [normalizedPreferred, ...fallbacks.map(normalizeModelName)];

  try {
    const available = await getCachedModelNames();
    for (const candidate of candidates) {
      if (available.has(candidate)) return candidate;
    }
  } catch {
    // Fail open: preserve runtime availability even if model listing fails.
  }

  return normalizedPreferred;
}

export { normalizeModelName };
