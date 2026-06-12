import { createHash, timingSafeEqual } from "crypto";
import {
  INTERNAL_DEV_BYPASS,
  INTERNAL_JOB_TOKEN,
  NODE_ENV,
} from "@/lib/mobile-agent/env";
import { getRequestSource, isLoopbackRequest } from "@/lib/mobile-agent/auth-utils";

/**
 * Constant-time string comparison. Hashing both inputs first keeps the
 * comparison constant-time even when the lengths differ, so neither token
 * length nor content leaks through timing.
 */
function safeTokenEqual(provided: string, expected: string): boolean {
  const providedDigest = createHash("sha256").update(provided).digest();
  const expectedDigest = createHash("sha256").update(expected).digest();
  return timingSafeEqual(providedDigest, expectedDigest);
}

export type InternalAuthResult = {
  valid: boolean;
  bypassed: boolean;
  client_id?: string;
  error?: string;
  source: string;
};

export function verifyInternalJobAuth(
  req: Request,
  options: { endpoint: string },
): InternalAuthResult {
  const source = getRequestSource(req);
  const authHeader = req.headers.get("authorization")?.trim()
    || req.headers.get("Authorization")?.trim()
    || "";

  if (INTERNAL_JOB_TOKEN && safeTokenEqual(authHeader, `Bearer ${INTERNAL_JOB_TOKEN}`)) {
    return {
      valid: true,
      bypassed: false,
      client_id: "internal_job",
      source,
    };
  }

  if (NODE_ENV === "development" && INTERNAL_DEV_BYPASS && isLoopbackRequest(req)) {
    console.warn(JSON.stringify({
      scope: "internal_job_auth",
      endpoint: options.endpoint,
      source,
      bypassed: true,
      timestamp: new Date().toISOString(),
    }));
    return {
      valid: true,
      bypassed: true,
      client_id: "internal_dev_bypass",
      source,
    };
  }

  return {
    valid: false,
    bypassed: false,
    error: INTERNAL_JOB_TOKEN ? "invalid_internal_job_token" : "internal_job_token_not_configured",
    source,
  };
}
