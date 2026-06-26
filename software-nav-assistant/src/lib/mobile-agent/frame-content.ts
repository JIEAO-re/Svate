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

/**
 * Replace the latest frame with the SoM-annotated screenshot when one is
 * provided. The annotated image is the raw screenshot overlaid with numbered
 * marker boxes, i.e. a strict superset of the original frame, so it replaces
 * the latest frame instead of being appended — same token cost, and the
 * planner can ground target_som_id visually. Earlier frames are untouched.
 *
 * Returns a new array; the input is never mutated.
 */
export function applySomAnnotatedFrame(
  frames: FrameContent[],
  somAnnotatedImageBase64?: string,
): FrameContent[] {
  const annotated = somAnnotatedImageBase64?.trim();
  if (!annotated || frames.length === 0) return frames;
  const next = frames.slice();
  // The annotated image only exists inline, so a GCS-referenced latest frame
  // is also swapped for inline data here.
  next[next.length - 1] = { imageBase64: annotated };
  return next;
}
