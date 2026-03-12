import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const ORIGINAL_ENV = { ...process.env };

function resetEnv() {
  for (const key of Object.keys(process.env)) {
    if (!(key in ORIGINAL_ENV)) {
      delete process.env[key];
    }
  }

  Object.assign(process.env, ORIGINAL_ENV);
}

describe("env assertions", () => {
  beforeEach(() => {
    resetEnv();
    vi.resetModules();
  });

  afterEach(() => {
    resetEnv();
    vi.resetModules();
  });

  it("does not throw on import when unrelated env vars are missing", async () => {
    delete process.env.POSTGRES_URL;
    delete process.env.DATABASE_URL;
    delete process.env.GEMINI_API_KEY;
    delete process.env.GOOGLE_CLOUD_PROJECT;
    delete process.env.GOOGLE_CLOUD_LOCATION;
    delete process.env.GUIDE_MEDIA_BUCKET;

    await expect(import("@/lib/mobile-agent/env")).resolves.toBeDefined();
  });

  it("asserts persistence env lazily", async () => {
    delete process.env.POSTGRES_URL;
    delete process.env.DATABASE_URL;

    const env = await import("@/lib/mobile-agent/env");
    expect(() => env.assertPersistenceEnv()).toThrow(
      "POSTGRES_URL is required for mobile-agent persistence",
    );
  });

  it("asserts genai env lazily", async () => {
    process.env.OPENAI_COMPAT_ENABLED = "true";
    delete process.env.OPENAI_COMPAT_BASE_URL;
    delete process.env.OPENAI_COMPAT_API_KEY;

    const env = await import("@/lib/mobile-agent/env");
    expect(() => env.assertGenAiEnv()).toThrow(
      "OPENAI_COMPAT_BASE_URL is required when OPENAI_COMPAT_ENABLED is true",
    );
  });

  it("asserts signed upload env lazily", async () => {
    delete process.env.SCREENSHOT_UPLOAD_BUCKET;
    delete process.env.GCS_BUCKET_NAME;

    const env = await import("@/lib/mobile-agent/env");
    expect(() => env.assertSignedUploadEnv()).toThrow(
      "SCREENSHOT_UPLOAD_BUCKET or GCS_BUCKET_NAME is required for signed uploads",
    );
  });
});
