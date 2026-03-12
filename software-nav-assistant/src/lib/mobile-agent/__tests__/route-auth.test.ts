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

describe("internal route auth and signed uploads", () => {
  beforeEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
  });

  afterEach(() => {
    resetEnv();
    vi.resetModules();
    vi.restoreAllMocks();
  });

  it("rejects internal gemini route when token is missing and bypass is off", async () => {
    process.env.NODE_ENV = "development";
    process.env.INTERNAL_DEV_BYPASS = "false";
    delete process.env.INTERNAL_JOB_TOKEN;

    const { POST } = await import("@/app/api/mobile-agent/internal/gemini-json/route");
    const response = await POST(
      new Request("http://localhost/api/mobile-agent/internal/gemini-json", {
        method: "POST",
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify({ prompt: "hello" }),
      }),
    );

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({
      success: false,
      error: "unauthorized_internal_job",
      details: "internal_job_token_not_configured",
    });
  });

  it("rejects session recap route when token is wrong", async () => {
    process.env.NODE_ENV = "production";
    process.env.INTERNAL_JOB_TOKEN = "expected-secret";

    vi.doMock("@/lib/mobile-agent/session-recap-video", () => ({
      processSessionRecapVideoJob: vi.fn().mockResolvedValue(undefined),
    }));

    const { POST } = await import("@/app/api/mobile-agent/internal/session-recap-video/route");
    const response = await POST(
      new Request("https://example.com/api/mobile-agent/internal/session-recap-video", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          authorization: "Bearer wrong-secret",
        },
        body: JSON.stringify({
          job_id: "job_1",
          session_id: "sess_1",
          trace_id: "trace_1",
          goal: "finish",
        }),
      }),
    );

    expect(response.status).toBe(401);
    await expect(response.json()).resolves.toMatchObject({
      success: false,
      error: "unauthorized_internal_job",
      details: "invalid_internal_job_token",
    });
  });

  it("allows gemini route through local dev bypass", async () => {
    process.env.NODE_ENV = "development";
    process.env.INTERNAL_DEV_BYPASS = "true";
    delete process.env.INTERNAL_JOB_TOKEN;
    process.env.GEMINI_API_KEY = "test-key";

    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.doMock("@/lib/mobile-agent/genai-client", () => ({
      getGenAIClient: () => ({
        models: {
          generateContent: vi.fn().mockResolvedValue({ text: "{\"ok\":true}" }),
        },
      }),
    }));

    const { POST } = await import("@/app/api/mobile-agent/internal/gemini-json/route");
    const response = await POST(
      new Request("http://localhost/api/mobile-agent/internal/gemini-json", {
        method: "POST",
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify({ prompt: "hello" }),
      }),
    );

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({
      success: true,
      json: { ok: true },
    });
  });

  it("rejects dev bypass from non-local sources", async () => {
    process.env.NODE_ENV = "development";
    process.env.INTERNAL_DEV_BYPASS = "true";
    delete process.env.INTERNAL_JOB_TOKEN;

    const { POST } = await import("@/app/api/mobile-agent/internal/gemini-json/route");
    const response = await POST(
      new Request("https://example.com/api/mobile-agent/internal/gemini-json", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-forwarded-for": "203.0.113.10",
        },
        body: JSON.stringify({ prompt: "hello" }),
      }),
    );

    expect(response.status).toBe(401);
  });

  it("allows session recap route through local dev bypass", async () => {
    process.env.NODE_ENV = "development";
    process.env.INTERNAL_DEV_BYPASS = "true";
    delete process.env.INTERNAL_JOB_TOKEN;

    const processSessionRecapVideoJob = vi.fn().mockResolvedValue(undefined);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.doMock("@/lib/mobile-agent/session-recap-video", () => ({
      processSessionRecapVideoJob,
    }));

    const { POST } = await import("@/app/api/mobile-agent/internal/session-recap-video/route");
    const response = await POST(
      new Request("http://localhost/api/mobile-agent/internal/session-recap-video", {
        method: "POST",
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify({
          job_id: "job_1",
          session_id: "sess_1",
          trace_id: "trace_1",
          goal: "finish",
        }),
      }),
    );

    expect(response.status).toBe(200);
    expect(processSessionRecapVideoJob).toHaveBeenCalledOnce();
  });

  it("rejects signed-url requests without auth", async () => {
    process.env.NODE_ENV = "development";
    process.env.SKIP_AUTH_DEV = "false";
    process.env.SCREENSHOT_UPLOAD_BUCKET = "shots-dev";

    const { POST } = await import("@/app/api/gcs/signed-url/route");
    const response = await POST(
      new Request("http://localhost/api/gcs/signed-url", {
        method: "POST",
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify({ content_type: "image/jpeg" }),
      }),
    );

    expect(response.status).toBe(401);
  });

  it("rejects invalid signed-url content types", async () => {
    process.env.NODE_ENV = "development";
    process.env.SKIP_AUTH_DEV = "true";
    process.env.SCREENSHOT_UPLOAD_BUCKET = "shots-dev";

    const { POST } = await import("@/app/api/gcs/signed-url/route");
    const response = await POST(
      new Request("http://localhost/api/gcs/signed-url", {
        method: "POST",
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify({ content_type: "image/gif" }),
      }),
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({
      success: false,
      error: "invalid_signed_url_request",
    });
  });

  it("generates a signed upload target", async () => {
    process.env.NODE_ENV = "development";
    process.env.SKIP_AUTH_DEV = "true";
    process.env.SCREENSHOT_UPLOAD_BUCKET = "shots-dev";

    const getSignedUrl = vi.fn().mockResolvedValue(["https://signed.example/upload"]);
    const file = vi.fn().mockReturnValue({ getSignedUrl });
    const bucket = vi.fn().mockReturnValue({ file });

    vi.doMock("@google-cloud/storage", () => ({
      Storage: vi.fn().mockImplementation(() => ({
        bucket,
      })),
    }));

    const { POST } = await import("@/app/api/gcs/signed-url/route");
    const response = await POST(
      new Request("http://localhost/api/gcs/signed-url", {
        method: "POST",
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify({
          content_type: "image/jpeg",
          session_id: "sess_1",
          trace_id: "trace_1",
          frame_index: 2,
        }),
      }),
    );

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toMatchObject({
      signed_url: "https://signed.example/upload",
      gcs_uri: expect.stringContaining("gs://shots-dev/frames/"),
      file_path: expect.stringContaining("/sess_1/trace_1/"),
    });
    expect(bucket).toHaveBeenCalledWith("shots-dev");
    expect(getSignedUrl).toHaveBeenCalledOnce();
  });
});
