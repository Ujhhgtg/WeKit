import { fail } from "./http";
import type { Env } from "./env";
export const utf8 = (value: string): Uint8Array<ArrayBuffer> =>
  new Uint8Array(new TextEncoder().encode(value));
export const hex = (value: ArrayBuffer | Uint8Array) =>
  Array.from(
    new Uint8Array(
      value instanceof ArrayBuffer ? value : value.buffer,
      value instanceof ArrayBuffer ? 0 : value.byteOffset,
      value instanceof ArrayBuffer ? value.byteLength : value.byteLength,
    ),
    (b) => b.toString(16).padStart(2, "0"),
  ).join("");
export const b64 = (value: ArrayBuffer | Uint8Array) => {
  const a = value instanceof ArrayBuffer ? new Uint8Array(value) : value;
  let s = "";
  for (const b of a) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};
export function unb64(value: string, size?: number): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]+$/.test(value) || value.length % 4 === 1)
    fail(400, "INVALID_ENCODING", "Base64url 无效");
  let out: Uint8Array<ArrayBuffer>;
  try {
    out = Uint8Array.from(
      atob(value.replace(/-/g, "+").replace(/_/g, "/")),
      (c) => c.charCodeAt(0),
    );
  } catch {
    fail(400, "INVALID_ENCODING", "Base64url 无效");
  }
  if (b64(out) !== value || (size !== undefined && out.length !== size))
    fail(400, "INVALID_ENCODING", "二进制长度无效");
  return out;
}
export const random = (size = 16) =>
  b64(crypto.getRandomValues(new Uint8Array(size)));
export const opaqueId = () => hex(crypto.getRandomValues(new Uint8Array(16)));
export async function sha(
  value: Uint8Array<ArrayBuffer> | string,
): Promise<string> {
  return hex(
    await crypto.subtle.digest(
      "SHA-256",
      typeof value === "string" ? utf8(value) : value,
    ),
  );
}
export function equal(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  return crypto.subtle.timingSafeEqual(a, b);
}
export async function p256(spki: string): Promise<CryptoKey> {
  try {
    return await crypto.subtle.importKey(
      "spki",
      unb64(spki),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
  } catch {
    fail(400, "INVALID_PUBLIC_KEY", "公钥必须为 EC P-256");
  }
}
export async function verify(
  spki: string,
  signature: string,
  message: string | Uint8Array<ArrayBuffer>,
): Promise<void> {
  const sig = unb64(signature, 64);
  if (
    !(await crypto.subtle.verify(
      { name: "ECDSA", hash: "SHA-256" },
      await p256(spki),
      sig,
      typeof message === "string" ? utf8(message) : message,
    ))
  )
    fail(401, "INVALID_SIGNATURE", "签名无效");
}
export async function hmac(secret: string, message: string): Promise<string> {
  const k = await crypto.subtle.importKey(
    "raw",
    unb64(secret, 32),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  return b64(await crypto.subtle.sign("HMAC", k, utf8(message)));
}
export async function verifyHmac(
  secret: string,
  message: string,
  signature: string,
): Promise<boolean> {
  const k = await crypto.subtle.importKey(
    "raw",
    unb64(secret, 32),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["verify"],
  );
  return crypto.subtle.verify("HMAC", k, unb64(signature, 32), utf8(message));
}
export interface Wrapped {
  kekId: string;
  nonce: string;
  ciphertext: string;
}
function kek(env: Env, kekId: string): Uint8Array<ArrayBuffer> {
  const keys = JSON.parse(env.KEK_KEYS_JSON) as Record<string, string>;
  const v = keys[kekId];
  if (!v) fail(503, "KEK_UNAVAILABLE", "密钥版本不可用", true);
  return unb64(v, 32);
}
export async function wrap(
  env: Env,
  clear: Uint8Array<ArrayBuffer>,
  aad: string,
): Promise<Wrapped> {
  const kekId = env.CURRENT_KEK_ID;
  const nonce = random(12);
  const key = await crypto.subtle.importKey(
    "raw",
    kek(env, kekId),
    "AES-GCM",
    false,
    ["encrypt"],
  );
  return {
    kekId,
    nonce,
    ciphertext: b64(
      await crypto.subtle.encrypt(
        {
          name: "AES-GCM",
          iv: unb64(nonce),
          additionalData: utf8(aad),
          tagLength: 128,
        },
        key,
        clear,
      ),
    ),
  };
}
export async function unwrap(
  env: Env,
  value: Wrapped,
  aad: string,
): Promise<Uint8Array<ArrayBuffer>> {
  const key = await crypto.subtle.importKey(
    "raw",
    kek(env, value.kekId),
    "AES-GCM",
    false,
    ["decrypt"],
  );
  return new Uint8Array(
    await crypto.subtle.decrypt(
      {
        name: "AES-GCM",
        iv: unb64(value.nonce, 12),
        additionalData: utf8(aad),
        tagLength: 128,
      },
      key,
      unb64(value.ciphertext),
    ),
  );
}
