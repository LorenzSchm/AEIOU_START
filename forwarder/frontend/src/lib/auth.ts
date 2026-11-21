import { MOCK_MODE } from "./mock-mode";

/**
 * Password gate for the control panel. Runs in the proxy and in server actions,
 * so it only uses Web Crypto. The cookie holds HMAC-SHA256(APP_PASSWORD, label),
 * never the password itself.
 */
export const AUTH_COOKIE = "fwd_session";
export const AUTH_MAX_AGE_SECONDS = 60 * 60 * 24 * 14;
const TOKEN_LABEL = "forwarder.control-panel.session.v1";

/** In mock mode the panel works out of the box with the password "demo". */
export function configuredPassword(): string | null {
  const pw = process.env.APP_PASSWORD;
  if (pw && pw.length > 0) return pw;
  return MOCK_MODE ? "demo" : null;
}

const encoder = new TextEncoder();

async function hmacHex(key: string, message: string): Promise<string> {
  const cryptoKey = await crypto.subtle.importKey(
    "raw",
    encoder.encode(key),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", cryptoKey, encoder.encode(message));
  return Array.from(new Uint8Array(sig), (b) => b.toString(16).padStart(2, "0")).join("");
}

export async function sessionToken(password: string): Promise<string> {
  return hmacHex(password, TOKEN_LABEL);
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export async function isValidToken(token: string | undefined): Promise<boolean> {
  const pw = configuredPassword();
  if (!pw || !token) return false;
  return timingSafeEqual(token, await sessionToken(pw));
}

/** Compares a submitted password by comparing both HMACs, so the check is constant-time. */
export async function checkPassword(candidate: string): Promise<boolean> {
  const pw = configuredPassword();
  if (!pw) return false;
  const [a, b] = await Promise.all([sessionToken(candidate), sessionToken(pw)]);
  return timingSafeEqual(a, b);
}
