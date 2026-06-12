/**
 * Tests for the shared frame content builders used by planner and reviewer:
 *   - gcs_uri frames become fileData parts (no download/transcode)
 *   - inline base64 frames become inlineData parts
 *   - the SoM-annotated screenshot replaces the latest frame when present
 */
import { describe, it, expect } from "vitest";
import {
  applySomAnnotatedFrame,
  buildFramePart,
  buildFrameParts,
  type FrameContent,
} from "@/lib/mobile-agent/frame-content";

describe("buildFramePart", () => {
  it("maps a GCS-referenced frame to a fileData part", () => {
    const part = buildFramePart({ gcsUri: "gs://bucket/sessions/s1/f1.jpg" });
    expect(part).toEqual({
      fileData: {
        fileUri: "gs://bucket/sessions/s1/f1.jpg",
        mimeType: "image/jpeg",
      },
    });
  });

  it("maps an inline base64 frame to an inlineData part", () => {
    const part = buildFramePart({ imageBase64: "aGVsbG8=" });
    expect(part).toEqual({
      inlineData: {
        data: "aGVsbG8=",
        mimeType: "image/jpeg",
      },
    });
  });

  it("prefers gcsUri when both sources are present", () => {
    const part = buildFramePart({
      gcsUri: "gs://bucket/f1.jpg",
      imageBase64: "aGVsbG8=",
    });
    expect(part.fileData?.fileUri).toBe("gs://bucket/f1.jpg");
    expect(part.inlineData).toBeUndefined();
  });

  it("honors a custom mime type", () => {
    const part = buildFramePart({ imageBase64: "aGVsbG8=" }, "image/png");
    expect(part.inlineData?.mimeType).toBe("image/png");
  });
});

describe("buildFrameParts", () => {
  it("preserves frame order and per-frame source mode", () => {
    const frames: FrameContent[] = [
      { gcsUri: "gs://bucket/f1.jpg" },
      { imageBase64: "ZnJhbWUy" },
    ];
    const parts = buildFrameParts(frames);
    expect(parts).toHaveLength(2);
    expect(parts[0].fileData?.fileUri).toBe("gs://bucket/f1.jpg");
    expect(parts[1].inlineData?.data).toBe("ZnJhbWUy");
  });
});

describe("applySomAnnotatedFrame", () => {
  const frames: FrameContent[] = [
    { imageBase64: "ZnJhbWUx" },
    { imageBase64: "ZnJhbWUy" },
  ];

  it("replaces only the latest frame with the annotated image", () => {
    const result = applySomAnnotatedFrame(frames, "c29tX2Fubm90YXRlZA==");
    expect(result).toHaveLength(2);
    expect(result[0]).toEqual({ imageBase64: "ZnJhbWUx" });
    expect(result[1]).toEqual({ imageBase64: "c29tX2Fubm90YXRlZA==" });
  });

  it("replaces a GCS-referenced latest frame with the inline annotated image", () => {
    const gcsFrames: FrameContent[] = [{ gcsUri: "gs://bucket/f1.jpg" }];
    const result = applySomAnnotatedFrame(gcsFrames, "c29tX2Fubm90YXRlZA==");
    expect(result[0]).toEqual({ imageBase64: "c29tX2Fubm90YXRlZA==" });
  });

  it("returns the original frames when no annotated image is provided", () => {
    expect(applySomAnnotatedFrame(frames, undefined)).toBe(frames);
  });

  it("ignores blank annotated images", () => {
    expect(applySomAnnotatedFrame(frames, "   ")).toBe(frames);
  });

  it("returns an empty window unchanged", () => {
    expect(applySomAnnotatedFrame([], "c29tX2Fubm90YXRlZA==")).toEqual([]);
  });

  it("does not mutate the input array", () => {
    const input: FrameContent[] = [{ imageBase64: "ZnJhbWUx" }];
    applySomAnnotatedFrame(input, "c29tX2Fubm90YXRlZA==");
    expect(input[0]).toEqual({ imageBase64: "ZnJhbWUx" });
  });
});
