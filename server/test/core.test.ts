import assert from "node:assert/strict";
import { test } from "node:test";
import { readFileSync } from "node:fs";
import { timingSafeEqual } from "node:crypto";
import * as asn1 from "asn1js";
import { strictJson } from "../src/http.ts";
import { b64, utf8 } from "../src/crypto.ts";
import {
  certificate,
  certSpki,
  verifyAttestation,
} from "../src/attestation.ts";
Object.assign(crypto.subtle, { timingSafeEqual });
test("strict JSON rejects escaped duplicate keys, nested duplicates, trailing and excessive nesting", () => {
  assert.deepEqual(strictJson('{"a":[{"x":1},{"x":2}],"b":"\\\""}'), {
    a: [{ x: 1 }, { x: 2 }],
    b: '"',
  });
  for (const input of [
    '{"a":1,"\\u0061":2}',
    '{"outer":{"x":1,"x":2}}',
    "[1,]",
    "{} true",
    "[01]",
    '{"__proto__":1,"__proto__":2}',
    "[".repeat(34) + "0" + "]".repeat(34),
  ])
    assert.throws(() => strictJson(input));
});
// Public certificate fixtures: android/keyattestation, Apache-2.0,
// 3f550f94a9e3c010dda7cdcad8bca764cd76d4f4. No private device keys.
const roots = JSON.parse(
  readFileSync(new URL("fixtures/google-roots.json", import.meta.url), "utf8"),
);
function fixture(name: string) {
  const pem = readFileSync(
    new URL(`fixtures/${name}.pem`, import.meta.url),
    "utf8",
  );
  const chain = pem
    .match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/g)!
    .map((v) =>
      b64(
        Uint8Array.from(
          atob(v.replace(/-----[^-]+-----/g, "").replace(/\s/g, "")),
          (c) => c.charCodeAt(0),
        ),
      ),
    );
  const cert = certificate(chain[0]!);
  const ext = cert.extensions!.find(
    (v) => v.extnID === "1.3.6.1.4.1.11129.2.1.17",
  )!;
  const values = (
    asn1.fromBER(ext.extnValue.valueBlock.valueHexView).result as asn1.Sequence
  ).valueBlock.value;
  const challenge = b64(
    (values[4] as asn1.OctetString).valueBlock.valueHexView,
  );
  return { chain, spki: b64(certSpki(cert)), challenge };
}
test("Android specialized verifier validates old/new RKP roots and matching hardware policies", async () => {
  for (const [name, now, level] of [
    ["rkp-tee", "2025-09-29T16:22:10.500Z", "TEE"],
    ["rkp-2026-tee", "2026-03-01T00:16:08.500Z", "TEE"],
    ["rkp-2026-strongbox", "2026-02-28T00:43:21.500Z", "STRONGBOX"],
  ] as const) {
    const f = fixture(name);
    const result = await verifyAttestation(
      f.chain,
      f.spki,
      f.challenge,
      roots,
      Date.parse(now),
    );
    assert.equal(result.securityLevel, level);
    assert.equal(result.provisioning, "RKP");
  }
});
test("Android specialized verifier rejects challenge/SPKI substitution, untrusted roots, appended target and expired RKP", async () => {
  const f = fixture("rkp-2026-tee"),
    now = Date.parse("2026-03-01T00:16:08.500Z");
  await assert.rejects(
    verifyAttestation(
      f.chain,
      f.spki,
      b64(utf8("wrong-challenge")),
      roots,
      now,
    ),
  );
  await assert.rejects(
    verifyAttestation(
      f.chain,
      b64(certSpki(certificate(f.chain[1]!))),
      f.challenge,
      roots,
      now,
    ),
  );
  await assert.rejects(
    verifyAttestation(f.chain, f.spki, f.challenge, [], now),
  );
  await assert.rejects(
    verifyAttestation(
      [f.chain[0]!, ...f.chain],
      f.spki,
      f.challenge,
      roots,
      now,
    ),
  );
  await assert.rejects(
    verifyAttestation(
      f.chain,
      f.spki,
      f.challenge,
      roots,
      Date.parse("2030-01-01"),
    ),
  );
  const corrupted = Uint8Array.from(
    atob(f.chain[0]!.replace(/-/g, "+").replace(/_/g, "/")),
    (c) => c.charCodeAt(0),
  );
  corrupted[corrupted.length - 1] ^= 1;
  await assert.rejects(
    verifyAttestation(
      [b64(corrupted), ...f.chain.slice(1)],
      f.spki,
      f.challenge,
      roots,
      now,
    ),
  );
});
test("legacy Sony path passes specialized path checks but SHA-512-only key is rejected for this SHA-256 protocol", async () => {
  const f = fixture("factory-sony");
  await assert.rejects(
    verifyAttestation(
      f.chain,
      f.spki,
      f.challenge,
      roots,
      Date.parse("2024-04-21T17:34:49.500Z"),
    ),
    (e) => (e as { code: string }).code === "ATTESTATION_KEY_POLICY",
  );
});
