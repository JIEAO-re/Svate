import { afterEach, beforeEach, describe, expect, it } from "vitest";

const ORIGINAL_ENV = { ...process.env };

function resetEnv() {
  for (const key of Object.keys(process.env)) {
    if (!(key in ORIGINAL_ENV)) {
      delete process.env[key];
    }
  }

  Object.assign(process.env, ORIGINAL_ENV);
}

describe("auth-utils", () => {
  beforeEach(() => {
    resetEnv();
  });

  afterEach(() => {
    resetEnv();
  });

  it("allows SKIP_AUTH_DEV only for localhost in development", async () => {
    process.env.NODE_ENV = "development";
    process.env.SKIP_AUTH_DEV = "true";

    const { authenticateRequest } = await import("@/lib/mobile-agent/auth-utils");

    const localResult = await authenticateRequest(
      new Request("http://localhost/api/mobile-agent/next-step", { method: "POST" }),
    );
    const remoteResult = await authenticateRequest(
      new Request("https://example.com/api/mobile-agent/next-step", {
        method: "POST",
        headers: {
          "x-forwarded-for": "203.0.113.10",
        },
      }),
    );

    expect(localResult).toEqual({ valid: true, client_id: "dev_bypass" });
    expect(remoteResult.valid).toBe(false);
    expect(remoteResult.error).toBe("missing_auth_credentials");
  });

  it("detects loopback requests correctly", async () => {
    const { isLoopbackRequest } = await import("@/lib/mobile-agent/auth-utils");

    expect(
      isLoopbackRequest(
        new Request("http://localhost/api/mobile-agent/internal/gemini-json", {
          method: "POST",
        }),
      ),
    ).toBe(true);

    expect(
      isLoopbackRequest(
        new Request("https://example.com/api/mobile-agent/internal/gemini-json", {
          method: "POST",
          headers: {
            "x-forwarded-for": "203.0.113.10",
          },
        }),
      ),
    ).toBe(false);
  });
});
