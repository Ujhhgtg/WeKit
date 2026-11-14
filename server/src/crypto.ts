import { fail } from "./http";
import type { Env } from "./env";
export const utf8 = (value: string): Uint8Array<ArrayBuffer> =>
  new Uint8Array(new TextEncoder().encode(value));
// Two-hex-digit strings indexed by byte value: formatting each byte through
// toString(16)/padStart() allocates a temporary string per byte of every digest.
const HEX = new Array<string>(256);
for (let i = 0; i < 256; i++) HEX[i] = i.toString(16).padStart(2, "0");
export const hex = (value: ArrayBuffer | Uint8Array) => {
  const bytes = value instanceof ArrayBuffer ? new Uint8Array(value) : value;
  let out = "";
  for (let i = 0; i < bytes.length; i++) out += HEX[bytes[i]!]!;
  return out;
};
// URL-safe Base64 alphabet indexed by 6-bit value.
const BASE64URL =
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
// Process at most 3 KiB of input per string chunk: small bodies still build one
// flat string, while a large ciphertext does not form an O(n) cons-string chain
// that has to be flattened later.
const BASE64_CHUNK = 3072;
export const b64 = (value: ArrayBuffer | Uint8Array) => {
  const bytes = value instanceof ArrayBuffer ? new Uint8Array(value) : value;
  const length = bytes.length;
  const full = length - (length % 3);
  const pieces: string[] = [];
  let i = 0;
  while (i < full) {
    const end = Math.min(i + BASE64_CHUNK, full);
    let chunk = "";
    for (; i < end; i += 3) {
      const n = (bytes[i]! << 16) | (bytes[i + 1]! << 8) | bytes[i + 2]!;
      chunk +=
        BASE64URL[(n >>> 18) & 63]! +
        BASE64URL[(n >>> 12) & 63]! +
        BASE64URL[(n >>> 6) & 63]! +
        BASE64URL[n & 63]!;
    }
    pieces.push(chunk);
  }
  const remainder = length % 3;
  if (remainder === 1) {
    const n = bytes[i]!;
    pieces.push(BASE64URL[n >>> 2]! + BASE64URL[(n & 3) << 4]!);
  } else if (remainder === 2) {
    const n = (bytes[i]! << 8) | bytes[i + 1]!;
    pieces.push(
      BASE64URL[(n >>> 10) & 63]! +
        BASE64URL[(n >>> 4) & 63]! +
        BASE64URL[(n & 15) << 2]!,
    );
  }
  return pieces.join("");
};
// 6-bit values for the 7-bit ASCII alphabet; -1 marks every other character
// (including '=' padding, '+' and '/', which canonical Base64url never uses).
const BASE64URL_DECODE = new Int8Array(128).fill(-1);
for (let i = 0; i < BASE64URL.length; i++)
  BASE64URL_DECODE[BASE64URL.charCodeAt(i)] = i;
const digit = (code: number): number =>
  code < 128 ? BASE64URL_DECODE[code]! : -1;
/**
 * Strict canonical Base64url decode. Rejects non-alphabet characters (so '='
 * padding is invalid), the impossible length%4===1 remainder and non-zero
 * unused trailing bits; this is exactly what the previous regex + atob +
 * re-encode round trip enforced, in one table-driven pass.
 */
export function unb64(value: string, size?: number): Uint8Array<ArrayBuffer> {
  const length = value.length;
  if (length === 0 || (length & 3) === 1)
    fail(400, "INVALID_ENCODING", "Base64url 无效");
  const remainder = length & 3;
  const full = length - remainder;
  const out = new Uint8Array(
    (full >> 2) * 3 + (remainder === 2 ? 1 : remainder === 3 ? 2 : 0),
  );
  let offset = 0;
  for (let i = 0; i < full; i += 4) {
    const a = digit(value.charCodeAt(i)),
      b = digit(value.charCodeAt(i + 1)),
      c = digit(value.charCodeAt(i + 2)),
      d = digit(value.charCodeAt(i + 3));
    if (a < 0 || b < 0 || c < 0 || d < 0)
      fail(400, "INVALID_ENCODING", "Base64url 无效");
    const n = (a << 18) | (b << 12) | (c << 6) | d;
    out[offset++] = n >>> 16;
    out[offset++] = (n >>> 8) & 255;
    out[offset++] = n & 255;
  }
  if (remainder === 2) {
    const a = digit(value.charCodeAt(full)),
      b = digit(value.charCodeAt(full + 1));
    if (a < 0 || b < 0) fail(400, "INVALID_ENCODING", "Base64url 无效");
    // A canonical encoder leaves the unused low 4 bits of the last digit zero.
    if ((b & 15) !== 0) fail(400, "INVALID_ENCODING", "二进制长度无效");
    out[offset] = (a << 2) | (b >>> 4);
  } else if (remainder === 3) {
    const a = digit(value.charCodeAt(full)),
      b = digit(value.charCodeAt(full + 1)),
      c = digit(value.charCodeAt(full + 2));
    if (a < 0 || b < 0 || c < 0)
      fail(400, "INVALID_ENCODING", "Base64url 无效");
    // A canonical encoder leaves the unused low 2 bits of the last digit zero.
    if ((c & 3) !== 0) fail(400, "INVALID_ENCODING", "二进制长度无效");
    out[offset] = (a << 2) | (b >>> 4);
    out[offset + 1] = ((b & 15) << 4) | (c >>> 2);
  }
  if (size !== undefined && out.length !== size)
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
// A registered installation signs with one P-256 key for its whole lifetime, so
// importing the same SPKI on every authenticated request is pure CPU overhead.
// The cache is FIFO and bounded so registration churn cannot grow isolate memory
// without limit; only successfully imported keys are stored, so validation is
// unchanged for every new or malformed SPKI.
const P256_CACHE_LIMIT = 4096;
const p256Cache = new Map<string, CryptoKey>();
export async function p256(spki: string): Promise<CryptoKey> {
  const cached = p256Cache.get(spki);
  if (cached) return cached;
  try {
    const key = await crypto.subtle.importKey(
      "spki",
      unb64(spki),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
    if (p256Cache.size >= P256_CACHE_LIMIT) {
      const oldest = p256Cache.keys().next();
      if (!oldest.done) p256Cache.delete(oldest.value);
    }
    p256Cache.set(spki, key);
    return key;
  } catch {
    // unb64() failures are intentionally folded into this error, as before.
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
