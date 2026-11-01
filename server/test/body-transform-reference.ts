// Test-only differential reference: the original BigInt implementation of the
// WKX1 wire transform. It is deliberately kept out of src/ so production code
// cannot import it; the production module uses the 32-bit keystream-cache port.
// Any wire-format drift must show up as a mismatch against this code.

const MASK = (1n << 64n) - 1n;
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

const u64 = (value: bigint) => value & MASK;
const rotl8 = (value: number, count: number) =>
  ((value << (count & 7)) | (value >>> ((8 - count) & 7))) & 255;
const rotr8 = (value: number, count: number) => rotl8(value, 8 - (count & 7));
const gcd = (a: number, b: number): number => {
  while (b) [a, b] = [b, a % b];
  return a;
};

function streamByte(
  seed: number[],
  length: number,
  tag: number,
  index: number,
): number {
  let x = u64((BigInt(length) * 0x9e3779b97f4a7c15n) ^ (BigInt(tag) << 29n));
  for (let i = 0; i < 8; i++)
    x = u64(x ^ (BigInt(seed[(i * 5 + 3) & 31]!) << BigInt(i * 8)));
  x = u64(x ^ (BigInt(index) * 0xd6e8feb86659fd93n));
  for (let round = 0; round < 8; round++) {
    x = u64(x ^ (x << 7n));
    x = u64(x ^ (x >> 9n));
    x = u64(x * 0xd6e8feb86659fd93n);
    const count = seed[(index + round) & 31]! & 63;
    if (count) x = u64((x >> BigInt(count)) | (x << BigInt(64 - count)));
  }
  return Number(
    (x ^ BigInt(seed[index & 31]!) ^ BigInt((index * 99) & 255)) & 255n,
  );
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

export function referenceTransformBody(
  input: Uint8Array,
  encode: boolean,
): Uint8Array {
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

export function referenceEncodeBody(input: Uint8Array): Uint8Array {
  const data = referenceTransformBody(input, true);
  const output = new Uint8Array(data.length + 4);
  output.set([0x57, 0x4b, 0x58, 0x31]);
  output.set(data, 4);
  return output;
}

export function referenceDecodeBody(input: Uint8Array): Uint8Array {
  if (
    input.length < 4 ||
    input[0] !== 0x57 ||
    input[1] !== 0x4b ||
    input[2] !== 0x58 ||
    input[3] !== 0x31
  )
    throw new Error("transformed body marker missing");
  return referenceTransformBody(input.slice(4), false);
}
