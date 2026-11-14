import assert from "node:assert/strict";
import test from "node:test";
import {
  decodeBody,
  encodeBody,
  transformBody,
} from "../src/body-transform.ts";
import {
  referenceDecodeBody,
  referenceEncodeBody,
} from "./body-transform-reference.ts";

const MAGIC_HEX = "574b5831";

test("wire transform matches the fixed native-compatible vector", () => {
  const clear = Uint8Array.from(
    Buffer.from(
      "20456a8fb4d9fe23486d92b7dc01264b7095badf04294e7398bde2072c51769b",
      "hex",
    ),
  );
  const expected =
    "574b5831cae85fa3d7ba77b6c11c24207325ffde5a6a0e540dd461a51767702465bee3f1";
  assert.equal(Buffer.from(encodeBody(clear)).toString("hex"), expected);
  assert.deepEqual(
    Buffer.from(decodeBody(Buffer.from(expected, "hex"))),
    Buffer.from(clear),
  );
});

// Deterministic xorshift32 payloads keep differential failures reproducible.
function payload(length: number, salt: number): Uint8Array {
  const data = new Uint8Array(length);
  let state = (0x9e3779b9 ^ Math.imul(length, 0x85ebca6b) ^ salt) >>> 0 || 1;
  for (let i = 0; i < length; i++) {
    state = (state ^ (state << 13)) >>> 0;
    state = (state ^ (state >>> 17)) >>> 0;
    state = (state ^ (state << 5)) >>> 0;
    data[i] = state & 0xff;
  }
  return data;
}

function assertMagic(encoded: Uint8Array, label: string): void {
  assert.equal(
    encoded.length >= 4
      ? Buffer.from(encoded.subarray(0, 4)).toString("hex")
      : "",
    MAGIC_HEX,
    `WKX1 magic missing (${label})`,
  );
}

// Lengths that exercise empty bodies, single bytes, every selector byte, the
// stride/center wrap points, and the JSON body sizes seen in production
// (575B init request, 1370B init response, ~5288B operation bodies).
const DIFFERENTIAL_LENGTHS = [
  0, 1, 2, 3, 4, 7, 16, 31, 32, 33, 47, 64, 100, 138, 159, 256, 337, 575, 576,
  1000, 1370, 4096, 5288, 8192, 16384,
];

test("encode/decode stay wire-identical to the BigInt reference for all body lengths", () => {
  for (const length of DIFFERENTIAL_LENGTHS) {
    for (const salt of [0x00000000, 0xcafebabe]) {
      const label = `length ${length}, salt 0x${salt.toString(16)}`;
      const clear = payload(length, salt);
      const expected = referenceEncodeBody(clear);
      const encoded = encodeBody(clear);

      assertMagic(encoded, label);
      assert.deepEqual(encoded, expected, `encode mismatch (${label})`);
      // Production decode of a reference-encoded wire body.
      assert.deepEqual(
        decodeBody(expected),
        clear,
        `decode(reference) mismatch (${label})`,
      );
      // Exact inverses in both call shapes.
      assert.deepEqual(
        decodeBody(encoded),
        clear,
        `decode(encode(x)) mismatch (${label})`,
      );
      assert.deepEqual(
        encoded,
        encodeBody(clear),
        `encode not deterministic (${label})`,
      );
      // The transform is affine, so both directions XOR the same keystream.
      assert.deepEqual(
        transformBody(clear, false),
        transformBody(clear, true),
        `direction keystream mismatch (${label})`,
      );
    }
  }
});

test("the reference implementation decodes its own wire bodies", () => {
  // Sanity check the test oracle itself on a couple of representative lengths.
  for (const length of [31, 575, 1370, 5288]) {
    const clear = payload(length, 0x5eed);
    const expected = referenceEncodeBody(clear);
    assert.deepEqual(
      referenceDecodeBody(expected),
      clear,
      `reference round trip mismatch at length ${length}`,
    );
  }
});

test("repeated transforms for the same length reuse the cached keystream and stay identical", () => {
  const first = payload(575, 0x1111);
  const other = payload(1370, 0x2222);
  const firstEncode = encodeBody(first);
  const otherEncode = encodeBody(other);

  assert.deepEqual(
    encodeBody(first),
    firstEncode,
    "cached encode changed (575B)",
  );
  assert.deepEqual(
    encodeBody(other),
    otherEncode,
    "cached encode changed (1370B)",
  );
  assert.deepEqual(
    decodeBody(firstEncode),
    first,
    "cached decode wrong (575B)",
  );
  assert.deepEqual(
    decodeBody(otherEncode),
    other,
    "cached decode wrong (1370B)",
  );

  // Encoding other lengths between calls must not disturb cached entries.
  for (let i = 0; i < 8; i++) encodeBody(payload(2048 + i * 7, i));
  assert.deepEqual(
    encodeBody(first),
    firstEncode,
    "cached encode changed after other lengths",
  );
  assert.deepEqual(
    decodeBody(firstEncode),
    first,
    "cached decode wrong after other lengths",
  );
});

test("bodies above the keystream cache limit transform directly and stay wire-identical", () => {
  // 32768 is the largest cached length, 32769 the smallest direct-path length.
  for (const length of [32768, 32769]) {
    const clear = payload(length, 0x5eed);
    const expected = referenceEncodeBody(clear);
    const encoded = encodeBody(clear);
    assertMagic(encoded, `length ${length}`);
    assert.deepEqual(encoded, expected, `encode mismatch at length ${length}`);
    assert.deepEqual(
      decodeBody(expected),
      clear,
      `decode(reference) mismatch at length ${length}`,
    );
    assert.deepEqual(
      decodeBody(encoded),
      clear,
      `decode(encode(x)) mismatch at length ${length}`,
    );
  }
});

test("decodeBody rejects bodies without the WKX1 marker", () => {
  const missing = /transformed body marker missing/;
  assert.throws(() => decodeBody(new Uint8Array(0)), missing);
  assert.throws(() => decodeBody(Uint8Array.from([0x57, 0x4b, 0x58])), missing);
  assert.throws(
    () => decodeBody(Uint8Array.from([0x00, 0x4b, 0x58, 0x31])),
    missing,
  );
});
