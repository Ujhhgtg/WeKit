// Fixed reversible wire transforms adapted from the removed native client.
// They are deliberately an obfuscation layer, not an authentication scheme.
//
// All three stages XOR per-byte mask material into positions that depend only on
// the body length, never on the body contents. Each stage therefore commutes
// with every other stage (and with itself), so the whole transform is affine
// over GF(2):
//
//   transformBody(input, encode) === input XOR K(input.length)
//   transformBody(input, decode) === input XOR K(input.length)
//
// where K is the keystream produced by running the encode-side stages over a
// zero-filled buffer. The same keystream serves both directions, so the hot
// path is a single XOR pass instead of three BigInt stage passes. Wire
// compatibility with the original BigInt implementation is covered by
// test/body-transform-reference.ts (test-only) and the fixed native vector.

// The keystream cache is bounded in bytes; the transform functions are fully
// synchronous, so a plain Map is safe even though requests interleave across
// await points (an isolate never switches mid-function).
const CACHE_BYTES_LIMIT = 2 * 1024 * 1024;
// Larger bodies are transformed directly instead of caching their keystream, so
// no single entry can dominate the cache budget.
const CACHE_LENGTH_LIMIT = 32768;

const XOR_SEED = [
  0xeb, 0x6f, 0xf0, 0x9b, 0x17, 0xb8, 0xc5, 0x39, 0x39, 0xa9, 0xa3, 0x12, 0x22,
  0xff, 0xb9, 0x13, 0x33, 0x0c, 0x02, 0x83, 0x56, 0x67, 0x5e, 0xc7, 0x9e, 0x4d,
  0x46, 0xc1, 0x88, 0x95, 0x4e, 0x2f,
];
const CENTER_SELECTOR = [
  0x25, 0xbe, 0xe2, 0x86, 0xf5, 0xd7, 0x79, 0xea, 0xb4, 0x65, 0xef, 0x61, 0x58,
  0x15, 0x8b, 0xda, 0x83, 0xb6, 0x82, 0x3a, 0x0f, 0x54, 0x39, 0x0f, 0x85, 0x6f,
  0x7f, 0xa9, 0x63, 0x84, 0xd6, 0x29,
];
const CENTER_SEED = [
  0x73, 0x83, 0xf0, 0xb4, 0x23, 0x2b, 0x2e, 0x76, 0x63, 0x5b, 0x26, 0x04, 0x14,
  0xf2, 0x99, 0x94, 0x8a, 0x9f, 0x37, 0x46, 0x93, 0x8e, 0x2b, 0xfa, 0x24, 0x51,
  0x5a, 0x0f, 0x74, 0xc7, 0xf3, 0x00,
];
const STRIDE_SELECTOR = [
  0xd5, 0x95, 0xff, 0x8b, 0x81, 0x85, 0x97, 0x4a, 0x6e, 0x5b, 0x39, 0x75, 0x25,
  0xb3, 0xe6, 0x40, 0x94, 0xda, 0xbf, 0x4e, 0x66, 0xcb, 0x10, 0xae, 0x24, 0xfe,
  0xf7, 0x29, 0x51, 0x39, 0xb0, 0xb0,
];
const STRIDE_SEED = [
  0xde, 0xa0, 0xb9, 0x10, 0x9d, 0x47, 0x66, 0x27, 0x5a, 0xc1, 0xb9, 0xfa, 0x2e,
  0x48, 0xf0, 0x4d, 0x13, 0xbc, 0x94, 0x31, 0xb3, 0x42, 0x2e, 0x9b, 0x8c, 0x3a,
  0x1b, 0xd2, 0x22, 0x75, 0x59, 0x9f,
];

// The original per-byte mixer multiplies a u64 by the two constants below; with
// the BigInt implementation each byte costs ~450 BigInt ops and ~9KB of garbage,
// which is why it only survives in the test reference. They are split into
// 32-bit halves for the allocation-free port:
//   C1 = 0x9e3779b97f4a7c15 (length multiplier)
//   C2 = 0xd6e8feb86659fd93 (index and round multiplier)
const C1_HI = 0x9e3779b9;
const C1_LO = 0x7f4a7c15;
const C2_HI = 0xd6e8feb8;
const C2_LO = 0x6659fd93;

const rotl8 = (value: number, count: number) =>
  ((value << (count & 7)) | (value >>> ((8 - count) & 7))) & 255;
const rotr8 = (value: number, count: number) => rotl8(value, 8 - (count & 7));
const gcd = (a: number, b: number): number => {
  while (b) {
    const next = a % b;
    a = b;
    b = next;
  }
  return a;
};

// ---------------------------------------------------------------------------
// Allocation-free 64-bit arithmetic on (hi, lo) uint32 pairs.
//
// mul64() takes its two u64 operands as 32-bit hi/lo halves and leaves the
// truncated 64-bit product in the module scratch instead of returning a tuple:
// keystream generation happens on the request path, and this keeps it
// allocation-free (no per-call array/object churn). The transforms are fully
// synchronous, so the scratch cannot be observed by interleaved requests.
// All limb products below stay under 2^34, so doubles represent them exactly.
let productHi = 0;
let productLo = 0;

function mul64(aHi: number, aLo: number, bHi: number, bLo: number): void {
  // 16-bit limbs, little-endian: [a0, a1, a2, a3].
  const a0 = aLo & 0xffff,
    a1 = (aLo >>> 16) & 0xffff;
  const a2 = aHi & 0xffff,
    a3 = (aHi >>> 16) & 0xffff;
  const b0 = bLo & 0xffff,
    b1 = (bLo >>> 16) & 0xffff;
  const b2 = bHi & 0xffff,
    b3 = (bHi >>> 16) & 0xffff;
  // Only the low four 16-bit limbs of the full product survive truncation.
  const r0 = a0 * b0;
  const r1 = a0 * b1 + a1 * b0 + Math.floor(r0 / 65536);
  const r2 = a0 * b2 + a1 * b1 + a2 * b0 + Math.floor(r1 / 65536);
  const r3 = a0 * b3 + a1 * b2 + a2 * b1 + a3 * b0 + Math.floor(r2 / 65536);
  productLo = ((r0 & 0xffff) | ((r1 & 0xffff) << 16)) >>> 0;
  productHi = ((r2 & 0xffff) | ((r3 & 0xffff) << 16)) >>> 0;
}

// 32-bit hi/lo port of the original BigInt streamByte(): same u64 mixing,
// 8 seed-byte injections, 8 xor-shift-multiply-rotate rounds, then the final
// seed/index fold. Only used while generating a keystream; the transform hot
// path never calls it.
function streamByte(
  seed: number[],
  length: number,
  tag: number,
  index: number,
): number {
  // x = length * C1 ^ (tag << 29); length and tag fit in 32 bits.
  mul64(0, length >>> 0, C1_HI, C1_LO);
  let hi = (productHi ^ (tag >>> 3)) >>> 0;
  let lo = (productLo ^ ((tag << 29) >>> 0)) >>> 0;
  // Mix the per-stage seed bytes into the 64-bit state.
  for (let i = 0; i < 8; i++) {
    const shift = i * 8;
    const value = seed[(i * 5 + 3) & 31]!;
    if (shift < 32) lo = (lo ^ (value << shift)) >>> 0;
    else hi = (hi ^ (value << (shift - 32))) >>> 0;
  }
  // x ^= index * C2
  mul64(0, index >>> 0, C2_HI, C2_LO);
  hi = (hi ^ productHi) >>> 0;
  lo = (lo ^ productLo) >>> 0;
  for (let round = 0; round < 8; round++) {
    // x ^= x << 7
    const shiftedHi = ((hi << 7) | (lo >>> 25)) >>> 0;
    lo = (lo ^ ((lo << 7) >>> 0)) >>> 0;
    hi = (hi ^ shiftedHi) >>> 0;
    // x ^= x >> 9
    lo = (lo ^ ((lo >>> 9) | (hi << 23))) >>> 0;
    hi = (hi ^ (hi >>> 9)) >>> 0;
    // x *= C2
    mul64(hi, lo, C2_HI, C2_LO);
    hi = productHi;
    lo = productLo;
    // x = rotateRight(x, seed[(index + round) & 31] & 63)
    const count = seed[(index + round) & 31]! & 63;
    if (count < 32) {
      if (count !== 0) {
        const nextHi = ((hi >>> count) | (lo << (32 - count))) >>> 0;
        const nextLo = ((lo >>> count) | (hi << (32 - count))) >>> 0;
        hi = nextHi;
        lo = nextLo;
      }
    } else if (count === 32) {
      const swap = hi;
      hi = lo;
      lo = swap;
    } else {
      const countLo = count - 32;
      const nextHi = ((lo >>> countLo) | (hi << (32 - countLo))) >>> 0;
      const nextLo = ((hi >>> countLo) | (lo << (32 - countLo))) >>> 0;
      hi = nextHi;
      lo = nextLo;
    }
  }
  return (lo ^ seed[index & 31]! ^ ((index * 99) & 255)) & 255;
}

function selectorParameters(
  selector: number[],
  length: number,
  offset: number,
): [number, number] {
  let step = ((selector[offset]! | (selector[offset + 1]! << 8)) % length) | 1;
  while (gcd(step, length) !== 1) {
    step = (step + 2) % length;
    if (step < 2) step = 1;
  }
  return [
    step,
    (selector[offset + 2]! | (selector[offset + 3]! << 8)) % length,
  ];
}

// The three stages below are the original transform writes, unchanged apart
// from the 32-bit streamByte(). Run over a zero-filled buffer they emit the
// keystream K; run over real data they perform the in-place transform.
function xorStage(data: Uint8Array): void {
  for (let i = 0; i < data.length; i++)
    data[i] = data[i]! ^ streamByte(XOR_SEED, data.length, 0x1c1f7500, i);
}
function centerStage(data: Uint8Array): void {
  if (!data.length) return;
  const [step, start] = selectorParameters(CENTER_SELECTOR, data.length, 0);
  for (let index = 0; index < data.length; index++) {
    const center =
      (index & 1) === 0
        ? (data.length +
            Math.floor(data.length / 2) -
            ((Math.floor(index / 2) + 1) % data.length)) %
          data.length
        : (Math.floor(index / 2) + Math.floor(data.length / 2)) % data.length;
    const position = (start + center * step) % data.length;
    data[position] =
      data[position]! ^
      CENTER_SELECTOR[(position + index) & 31]! ^
      ((index * 0x5d) & 255) ^
      rotl8(position & 255, index & 7) ^
      streamByte(CENTER_SEED, data.length, 0x6d3a5b17, index);
  }
}
function strideStage(data: Uint8Array): void {
  if (!data.length) return;
  const [step, start] = selectorParameters(STRIDE_SELECTOR, data.length, 8);
  for (let index = 0; index < data.length; index++) {
    const position = (start + index * step) % data.length;
    data[position] =
      data[position]! ^
      streamByte(STRIDE_SEED, data.length, 0x2e4981c3, index) ^
      ((index * 0xa7) & 255) ^
      rotr8(position & 255, index & 7) ^
      ((STRIDE_SELECTOR[(position ^ index) & 31]! + position) & 255);
  }
}

function generateKeystream(length: number): Uint8Array {
  const keystream = new Uint8Array(length);
  xorStage(keystream);
  centerStage(keystream);
  strideStage(keystream);
  return keystream;
}

const keystreamCache = new Map<number, Uint8Array>();
let cachedBytes = 0;

// Keystream for a cacheable length, or null when the body is large enough to
// transform directly. Cache entries are never mutated, so sharing them between
// requests is safe.
function keystreamFor(length: number): Uint8Array | null {
  const cached = keystreamCache.get(length);
  if (cached !== undefined) return cached;
  if (length > CACHE_LENGTH_LIMIT) return null;
  const keystream = generateKeystream(length);
  keystreamCache.set(length, keystream);
  cachedBytes += length;
  // FIFO: Map iteration follows insertion order, so evict from the front until
  // the byte budget is respected again.
  while (cachedBytes > CACHE_BYTES_LIMIT) {
    const oldest = keystreamCache.keys().next();
    if (oldest.done) break;
    const evicted = keystreamCache.get(oldest.value);
    keystreamCache.delete(oldest.value);
    if (evicted !== undefined) cachedBytes -= evicted.length;
  }
  return keystream;
}

function transformWithKeystream(
  input: Uint8Array,
  keystream: Uint8Array,
): Uint8Array {
  const data = new Uint8Array(input.length);
  for (let i = 0; i < data.length; i++) data[i] = input[i]! ^ keystream[i]!;
  return data;
}

// Fallback for bodies above CACHE_LENGTH_LIMIT: run the three stages in place
// on a copy, in the same order the original implementation used per direction.
function transformUncached(input: Uint8Array, encode: boolean): Uint8Array {
  const data = input.slice();
  if (encode) {
    xorStage(data);
    centerStage(data);
    strideStage(data);
  } else {
    strideStage(data);
    centerStage(data);
    xorStage(data);
  }
  return data;
}

export function transformBody(input: Uint8Array, encode: boolean): Uint8Array {
  const keystream = keystreamFor(input.length);
  if (keystream === null) return transformUncached(input, encode);
  return transformWithKeystream(input, keystream);
}

export function encodeBody(input: Uint8Array): Uint8Array {
  const data = transformBody(input, true);
  const output = new Uint8Array(data.length + 4);
  output.set([0x57, 0x4b, 0x58, 0x31]);
  output.set(data, 4);
  return output;
}

export function decodeBody(input: Uint8Array): Uint8Array {
  if (
    input.length < 4 ||
    input[0] !== 0x57 ||
    input[1] !== 0x4b ||
    input[2] !== 0x58 ||
    input[3] !== 0x31
  )
    throw new Error("transformed body marker missing");
  return transformBody(input.subarray(4), false);
}
