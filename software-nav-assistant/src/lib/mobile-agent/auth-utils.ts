import { jwtVerify, createRemoteJWKSet, importSPKI, type JWTPayload } from "jose";
import { NODE_ENV, SKIP_AUTH_DEV } from "@/lib/mobile-agent/env";

export interface AuthResult {
  valid: boolean;
  error?: string;
  client_id?: string;
}

const FIREBASE_APP_CHECK_JWKS_URL = "https://firebaseappcheck.googleapis.com/v1/jwks";
const firebaseJWKS = createRemoteJWKSet(new URL(FIREBASE_APP_CHECK_JWKS_URL));
const FIREBASE_PROJECT_ID = process.env.GOOGLE_CLOUD_PROJECT || "";
const JWT_PUBLIC_KEY_PEM = process.env.JWT_PUBLIC_KEY?.trim() || "";
const JWT_EXPECTED_ISSUER = process.env.JWT_EXPECTED_ISSUER?.trim() || "mobile-agent-android";
const JWT_EXPECTED_AUDIENCE = process.env.JWT_EXPECTED_AUDIENCE?.trim() || "mobile-agent-api";
const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);

let cachedPublicKey: Awaited<ReturnType<typeof importSPKI>> | null = null;

async function getJwtPublicKey() {
  if (cachedPublicKey) return cachedPublicKey;
  if (!JWT_PUBLIC_KEY_PEM) return null;
  cachedPublicKey = await importSPKI(JWT_PUBLIC_KEY_PEM, "RS256");
  return cachedPublicKey;
}

export async function verifyFirebaseAppCheck(token: string): Promise<AuthResult> {
  if (!token || token.length < 32) {
    return { valid: false, error: "invalid_app_check_token_format" };
  }

  try {
    const expectedAudience = FIREBASE_PROJECT_ID
      ? [`projects/${FIREBASE_PROJECT_ID}`]
      : undefined;

    const { payload } = await jwtVerify(token, firebaseJWKS, {
      issuer: "https://firebaseappcheck.googleapis.com/",
      audience: expectedAudience,
      clockTolerance: 30,
    });

    const appId = (payload as JWTPayload & { app_id?: string }).app_id || payload.sub || "unknown";

    return { valid: true, client_id: `appcheck:${appId}` };
  } catch (err) {
    const message = err instanceof Error ? err.message : "unknown_verification_error";
    return { valid: false, error: `app_check_verify_failed: ${message}` };
  }
}

export async function verifyJwtToken(token: string): Promise<AuthResult> {
  if (!token || !token.startsWith("ey")) {
    return { valid: false, error: "invalid_jwt_format" };
  }

  const publicKey = await getJwtPublicKey();
  if (!publicKey) {
    return {
      valid: false,
      error: "jwt_public_key_not_configured",
    };
  }

  try {
    const { payload } = await jwtVerify(token, publicKey, {
      issuer: JWT_EXPECTED_ISSUER,
      audience: JWT_EXPECTED_AUDIENCE,
      clockTolerance: 30,
      algorithms: ["RS256"],
    });

    const clientId = payload.sub || (payload as Record<string, unknown>).client_id || "unknown";
    return { valid: true, client_id: String(clientId) };
  } catch (err) {
    const message = err instanceof Error ? err.message : "unknown_verification_error";
    return { valid: false, error: `jwt_verify_failed: ${message}` };
  }
}

function normalizeAddress(value: string): string {
  let normalized = value.trim().toLowerCase();
  if (!normalized) return "";

  if (normalized.startsWith("for=")) {
    normalized = normalized.slice(4);
  }

  normalized = normalized.replace(/^"|"$/g, "");

  const semicolonIndex = normalized.indexOf(";");
  if (semicolonIndex >= 0) {
    normalized = normalized.slice(0, semicolonIndex);
  }

  if (normalized.startsWith("[")) {
    const closingIndex = normalized.indexOf("]");
    if (closingIndex >= 0) {
      normalized = normalized.slice(1, closingIndex);
    }
  }

  if (normalized.startsWith("::ffff:")) {
    normalized = normalized.slice(7);
  }

  if (/^\d+\.\d+\.\d+\.\d+:\d+$/.test(normalized)) {
    return normalized.split(":")[0];
  }

  if (normalized.includes(":") && !normalized.includes(".")) {
    return normalized;
  }

  if (normalized.includes(":")) {
    return normalized.split(":")[0];
  }

  return normalized;
}

function getForwardedAddresses(req: Request): string[] {
  const values: string[] = [];
  const forwardedFor = req.headers.get("x-forwarded-for");
  if (forwardedFor) {
    for (const part of forwardedFor.split(",")) {
      const normalized = normalizeAddress(part);
      if (normalized) values.push(normalized);
    }
  }

  const realIp = req.headers.get("x-real-ip");
  if (realIp) {
    const normalized = normalizeAddress(realIp);
    if (normalized) values.push(normalized);
  }

  const forwarded = req.headers.get("forwarded");
  if (forwarded) {
    const matches = forwarded.matchAll(/for=(?:"?\[?[^\];",]+\]?"?)/gi);
    for (const match of matches) {
      const normalized = normalizeAddress(match[0]);
      if (normalized) values.push(normalized);
    }
  }

  return values;
}

export function getRequestSource(req: Request): string {
  const forwarded = getForwardedAddresses(req);
  if (forwarded.length > 0) {
    return forwarded[0];
  }

  try {
    return normalizeAddress(new URL(req.url).hostname);
  } catch {
    return normalizeAddress(req.headers.get("host") || "") || "unknown";
  }
}

export function isLoopbackRequest(req: Request): boolean {
  const forwarded = getForwardedAddresses(req);
  if (forwarded.length > 0) {
    return forwarded.every((address) => LOOPBACK_HOSTS.has(address));
  }

  return LOOPBACK_HOSTS.has(getRequestSource(req));
}

export async function authenticateRequest(req: Request): Promise<AuthResult> {
  if (NODE_ENV === "development" && SKIP_AUTH_DEV && isLoopbackRequest(req)) {
    return { valid: true, client_id: "dev_bypass" };
  }

  const appCheckToken = req.headers.get("X-Firebase-AppCheck");
  if (appCheckToken) {
    return verifyFirebaseAppCheck(appCheckToken);
  }

  const authHeader = req.headers.get("Authorization") || req.headers.get("authorization");
  if (authHeader?.startsWith("Bearer ")) {
    const token = authHeader.slice(7);
    return verifyJwtToken(token);
  }

  return { valid: false, error: "missing_auth_credentials" };
}
