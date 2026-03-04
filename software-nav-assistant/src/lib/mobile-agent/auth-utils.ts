import { jwtVerify, createRemoteJWKSet, importSPKI, type JWTPayload } from "jose";

// ============================================================================
// P0 安全防线：端云强鉴权中间件
// ============================================================================
// 支持两种鉴权模式：
//   1. Firebase App Check Token (X-Firebase-AppCheck header)
//      - 通过 Google 公开 JWKS 端点验证签名
//   2. Android Keystore 签名的 JWT (Authorization: Bearer <token>)
//      - 通过 JWT_PUBLIC_KEY 环境变量中的 PEM 公钥验证签名
//
// 生产环境 (NODE_ENV=production) 强制鉴权
// 开发环境可通过 SKIP_AUTH_DEV=true 跳过
// ============================================================================

export interface AuthResult {
  valid: boolean;
  error?: string;
  client_id?: string;
}

// Firebase App Check JWKS endpoint for signature verification
const FIREBASE_APP_CHECK_JWKS_URL = "https://firebaseappcheck.googleapis.com/v1/jwks";
const firebaseJWKS = createRemoteJWKSet(new URL(FIREBASE_APP_CHECK_JWKS_URL));

// Expected audience for Firebase App Check tokens
const FIREBASE_PROJECT_ID = process.env.GOOGLE_CLOUD_PROJECT || "";

// PEM-encoded public key for Android Keystore JWT verification
// Set via environment variable: JWT_PUBLIC_KEY
const JWT_PUBLIC_KEY_PEM = process.env.JWT_PUBLIC_KEY?.trim() || "";
const JWT_EXPECTED_ISSUER = process.env.JWT_EXPECTED_ISSUER?.trim() || "mobile-agent-android";
const JWT_EXPECTED_AUDIENCE = process.env.JWT_EXPECTED_AUDIENCE?.trim() || "mobile-agent-api";

let cachedPublicKey: Awaited<ReturnType<typeof importSPKI>> | null = null;

async function getJwtPublicKey() {
  if (cachedPublicKey) return cachedPublicKey;
  if (!JWT_PUBLIC_KEY_PEM) return null;
  cachedPublicKey = await importSPKI(JWT_PUBLIC_KEY_PEM, "RS256");
  return cachedPublicKey;
}

/**
 * Verify Firebase App Check token using Google's public JWKS.
 * Validates signature, expiration, issuer, and audience.
 */
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
      clockTolerance: 30, // 30 seconds clock skew tolerance
    });

    const appId = (payload as JWTPayload & { app_id?: string }).app_id || payload.sub || "unknown";

    return { valid: true, client_id: `appcheck:${appId}` };
  } catch (err) {
    const message = err instanceof Error ? err.message : "unknown_verification_error";
    return { valid: false, error: `app_check_verify_failed: ${message}` };
  }
}

/**
 * Verify Android Keystore signed JWT using a pre-registered PEM public key.
 * Validates RS256 signature, expiration, issuer, and audience claims.
 */
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

/**
 * 统一鉴权入口：检查 Firebase App Check 或 JWT Bearer Token
 * 开发环境可通过 SKIP_AUTH_DEV=true 跳过鉴权
 * 生产环境强制鉴权
 */
export async function authenticateRequest(req: Request): Promise<AuthResult> {
  // 开发环境跳过鉴权（仅限本地调试）
  const skipAuthDev = process.env.SKIP_AUTH_DEV?.toLowerCase() === "true";
  if (skipAuthDev && process.env.NODE_ENV !== "production") {
    return { valid: true, client_id: "dev_bypass" };
  }

  // 生产环境强制鉴权
  // 优先检查 Firebase App Check
  const appCheckToken = req.headers.get("X-Firebase-AppCheck");
  if (appCheckToken) {
    return verifyFirebaseAppCheck(appCheckToken);
  }

  // 其次检查 Authorization Bearer JWT
  const authHeader = req.headers.get("Authorization");
  if (authHeader?.startsWith("Bearer ")) {
    const token = authHeader.slice(7);
    return verifyJwtToken(token);
  }

  return { valid: false, error: "missing_auth_credentials" };
}
