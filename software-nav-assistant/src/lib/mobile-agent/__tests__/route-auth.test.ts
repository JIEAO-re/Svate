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

// process.env.NODE_ENV is typed read-only in Next.js; tests need to override it.
function setNodeEnv(value: string) {
  (process.env as Record<string, string | undefined>).NODE_ENV = value;
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
    setNodeEnv("development");
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
      details: {
        internal_auth: "internal_job_token_not_configured",
        device_auth: "missing_auth_credentials",
      },
    });
  });

  it("allows gemini route through device auth without the internal token", async () => {
    // Loopback dev bypass goes through authenticateRequest (the device auth
    // path), proving the route no longer requires the internal job secret.
    setNodeEnv("development");
    process.env.INTERNAL_DEV_BYPASS = "false";
    process.env.SKIP_AUTH_DEV = "true";
    delete process.env.INTERNAL_JOB_TOKEN;
    process.env.GEMINI_API_KEY = "test-key";

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

  it("rejects session recap route when token is wrong", async () => {
    setNodeEnv("production");
    process.env.INTERNAL_JOB_TOKEN = "expected-secret";

    vi.doMock("@/lib/mobile-agent/session-recap-video", () => ({
      processSessionRecapVideoJob: vi.fn().mockResolvedValue(undefined),
      processSessionRecapPollJob: vi.fn().mockResolvedValue(undefined),
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
    setNodeEnv("development");
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
    setNodeEnv("development");
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
    setNodeEnv("development");
    process.env.INTERNAL_DEV_BYPASS = "true";
    delete process.env.INTERNAL_JOB_TOKEN;

    const processSessionRecapVideoJob = vi.fn().mockResolvedValue(undefined);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.doMock("@/lib/mobile-agent/session-recap-video", () => ({
      processSessionRecapVideoJob,
      processSessionRecapPollJob: vi.fn().mockResolvedValue(undefined),
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

  it("dispatches poll payloads to the poll handler", async () => {
    setNodeEnv("development");
    process.env.INTERNAL_DEV_BYPASS = "true";
    delete process.env.INTERNAL_JOB_TOKEN;

    const processSessionRecapVideoJob = vi.fn().mockResolvedValue(undefined);
    const processSessionRecapPollJob = vi.fn().mockResolvedValue(undefined);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.doMock("@/lib/mobile-agent/session-recap-video", () => ({
      processSessionRecapVideoJob,
      processSessionRecapPollJob,
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
          action: "poll",
          operation_name: "operations/video_op_1",
          attempt: 2,
        }),
      }),
    );

    expect(response.status).toBe(200);
    expect(processSessionRecapPollJob).toHaveBeenCalledOnce();
    expect(processSessionRecapVideoJob).not.toHaveBeenCalled();
  });

  it("rejects poll payloads without operation_name", async () => {
    setNodeEnv("development");
    process.env.INTERNAL_DEV_BYPASS = "true";
    delete process.env.INTERNAL_JOB_TOKEN;

    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.doMock("@/lib/mobile-agent/session-recap-video", () => ({
      processSessionRecapVideoJob: vi.fn().mockResolvedValue(undefined),
      processSessionRecapPollJob: vi.fn().mockResolvedValue(undefined),
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
          action: "poll",
        }),
      }),
    );

    expect(response.status).toBe(400);
  });

  it("rejects signed-url requests without auth", async () => {
    setNodeEnv("development");
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
    setNodeEnv("development");
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

  it("rejects signed-url identifiers with path separators", async () => {
    setNodeEnv("development");
    process.env.SKIP_AUTH_DEV = "true";
    process.env.SCREENSHOT_UPLOAD_BUCKET = "shots-dev";

    const { POST } = await import("@/app/api/gcs/signed-url/route");
    const response = await POST(
      new Request("http://localhost/api/gcs/signed-url", {
        method: "POST",
        headers: {
          "content-type": "application/json",
        },
        body: JSON.stringify({
          content_type: "image/jpeg",
          session_id: "../escape",
          trace_id: "trace/../../other",
        }),
      }),
    );

    expect(response.status).toBe(400);
    await expect(response.json()).resolves.toMatchObject({
      success: false,
      error: "invalid_signed_url_request",
    });
  });

  it("generates a signed upload target", async () => {
    setNodeEnv("development");
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
