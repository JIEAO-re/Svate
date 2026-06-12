import type { Part } from "@google/genai";

/**
 * Shared frame content abstraction used by both the planner
 * (live-turn-client) and the reviewer so GCS-referenced frames and inline
 * base64 frames flow through one code path.
 */
export interface FrameContent {
  /** Present only in inline mode. */
  imageBase64?: string;
  /** Present only in GCS reference mode. */
  gcsUri?: string;
}

/**
 * Build a single model content part from a frame.
 *
 * GCS URIs are fed to the model directly as fileData (no download or
 * transcoding); inline frames become inlineData.
 */
export function buildFramePart(frame: FrameContent, mimeType = "image/jpeg"): Part {
  if (frame.gcsUri) {
    return {
      fileData: {
        fileUri: frame.gcsUri,
        mimeType,
      },
    };
  }
  return {
    inlineData: {
      data: frame.imageBase64 ?? "",
      mimeType,
    },
  };
}

/** Build model content parts for a window of frames, preserving order. */
export function buildFrameParts(frames: FrameContent[], mimeType = "image/jpeg"): Part[] {
  return frames.map((frame) => buildFramePart(frame, mimeType));
}
