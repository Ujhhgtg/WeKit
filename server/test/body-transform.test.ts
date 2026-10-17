import assert from "node:assert/strict";
import test from "node:test";
import { decodeBody, encodeBody } from "../src/body-transform.ts";

test("wire transform matches the fixed native-compatible vector", () => {
  const clear = Uint8Array.from(Buffer.from("20456a8fb4d9fe23486d92b7dc01264b7095badf04294e7398bde2072c51769b", "hex"));
  const expected = "574b5831cae85fa3d7ba77b6c11c24207325ffde5a6a0e540dd461a51767702465bee3f1";
  assert.equal(Buffer.from(encodeBody(clear)).toString("hex"), expected);
  assert.deepEqual(Buffer.from(decodeBody(Buffer.from(expected, "hex"))), Buffer.from(clear));
});
