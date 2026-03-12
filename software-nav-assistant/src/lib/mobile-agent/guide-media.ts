import { Storage } from "@google-cloud/storage";
import { getGenAIClient, resolveModelWithFallback } from "@/lib/mobile-agent/genai-client";
import {
  ENABLE_GUIDE_MEDIA,
  GUIDE_IMAGE_MODEL,
  GUIDE_MEDIA_BUCKET,
  GUIDE_MEDIA_SIGNED_URL_TTL_SEC,
  assertGuideMediaEnv,
} from "@/lib/mobile-agent/env";
import { getStorageClient } from "@/lib/mobile-agent/cloud-storage";
import { ActionCommand, NextStepRequest } from "@/lib/schemas/mobile-agent";

const ELIGIBLE_INTENTS = new Set([
  "CLICK",
  "TYPE",
  "SUBMIT_INPUT",
]);

let storage: Storage | null = null;

function getStorage(): Storage {
  if (storage) return storage;
  storage = getStorageClient();
  return storage;
}

function buildGuideImagePrompt(request: NextStepRequest, action: ActionCommand): string {
  const expected = action.checkpoint?.expected_elements?.join(", ") || "none";
  return [
    "Generate an instructional overlay image for a mobile automation assistant.",
    "Style: clear, high contrast, no real personal data, no branded logos recreation.",
    `Goal: ${request.goal}`,
    `Current foreground package: ${request.observation.foreground_package}`,
    `Action intent: ${action.intent}`,
    `Action target: ${action.target_desc}`,
    `Input text: ${action.input_text ?? "(none)"}`,
    `Expected elements after action: ${expected}`,
    "Output should highlight where to act with arrows and concise instruction text.",
  ].join("\n");
}

async function uploadImageToCloudStorage(params: {
  traceId: string;
  sessionId: string;
  turnIndex: number;
  imageBase64: string;
  contentType: string;
}) {
  assertGuideMediaEnv();
  const client = getStorage();
  const bucket = client.bucket(GUIDE_MEDIA_BUCKET);
  const objectName = `guide-images/${params.sessionId}/${params.turnIndex}_${params.traceId}.jpg`;
  const file = bucket.file(objectName);
  const imageBuffer = Buffer.from(params.imageBase64, "base64");

  await file.save(imageBuffer, {
    contentType: params.contentType,
    resumable: false,
    metadata: {
      cacheControl: "private, max-age=60",
    },
  });

  const expires = new Date(Date.now() + GUIDE_MEDIA_SIGNED_URL_TTL_SEC * 1000);
  const [signedUrl] = await file.getSignedUrl({
    action: "read",
    expires,
  });

  return {
    storageUri: `gs://${GUIDE_MEDIA_BUCKET}/${objectName}`,
    signedUrl,
    expiresAt: expires.toISOString(),
  };
}

export async function maybeGenerateGuideImage(params: {
  traceId: string;
  request: NextStepRequest;
  action: ActionCommand;
}): Promise<{
  type: "IMAGE";
  storage_uri: string;
  signed_url: string;
  expires_at: string;
} | null> {
  if (!ENABLE_GUIDE_MEDIA) return null;
  if (!ELIGIBLE_INTENTS.has(params.action.intent)) return null;

  const ai = getGenAIClient();
  const model = await resolveModelWithFallback(GUIDE_IMAGE_MODEL, [
    "gemini-2.5-flash-image-preview",
    "imagen-3.0-generate-002",
  ]);
  const prompt = buildGuideImagePrompt(params.request, params.action);

  const generated = await ai.models.generateImages({
    model,
    prompt,
    config: {
      numberOfImages: 1,
      outputMimeType: "image/jpeg",
      outputCompressionQuality: 80,
      aspectRatio: "9:16",
      addWatermark: true,
      includeRaiReason: true,
    },
  }) as {
    generatedImages?: Array<{
      image?: {
        imageBytes?: string;
      };
    }>;
  };

  const imageBytes = generated.generatedImages?.[0]?.image?.imageBytes;
  if (!imageBytes) return null;

  const uploaded = await uploadImageToCloudStorage({
    traceId: params.traceId,
    sessionId: params.request.session_id,
    turnIndex: params.request.turn_index,
    imageBase64: imageBytes,
    contentType: "image/jpeg",
  });

  return {
    type: "IMAGE",
    storage_uri: uploaded.storageUri,
    signed_url: uploaded.signedUrl,
    expires_at: uploaded.expiresAt,
  };
}
