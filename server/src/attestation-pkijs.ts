import * as asn1 from "asn1js";
import { Certificate, CryptoEngine, setEngine } from "pkijs";
import type { Proof } from "./attestation";
import { equal, hex, opaqueId, sha, unb64 } from "./crypto";
import { bytes, decodeJson, fail } from "./http";
import { LIMITS, type Env } from "./env";
type Node = ReturnType<typeof asn1.fromBER>["result"];
const ATTESTATION = "1.3.6.1.4.1.11129.2.1.17";
const PROVISIONING = "1.3.6.1.4.1.11129.2.1.30";
function sequence(n: Node): Node[] {
  if (!(n instanceof asn1.Sequence))
    fail(400, "INVALID_ATTESTATION", "证明 SEQUENCE 无效");
  return n.valueBlock.value;
}
function integer(n: Node | undefined): number {
  if (
    !(n instanceof asn1.Integer) ||
    n.valueBlock.isHexOnly ||
    !Number.isSafeInteger(n.valueBlock.valueDec) ||
    n.valueBlock.valueDec < 0
  )
    fail(400, "INVALID_ATTESTATION", "证明整数无效");
  return n.valueBlock.valueDec;
}
function octets(n: Node | undefined): Uint8Array<ArrayBuffer> {
  if (!(n instanceof asn1.OctetString) || n.idBlock.isConstructed)
    fail(400, "INVALID_ATTESTATION", "证明字节无效");
  return new Uint8Array(n.valueBlock.valueHexView);
}
function parse(raw: Uint8Array<ArrayBuffer>): Node {
  const result = asn1.fromBER(raw);
  if (result.offset !== raw.length || result.result.error)
    fail(400, "INVALID_ATTESTATION", "DER 无效");
  return result.result;
}
export function certificate(encoded: string): Certificate {
  const raw = encoded.startsWith("-----BEGIN CERTIFICATE-----")
    ? Uint8Array.from(
        atob(encoded.replace(/-----[^-]+-----/g, "").replace(/\s/g, "")),
        (c) => c.charCodeAt(0),
      )
    : unb64(encoded);
  if (raw.length > 16384) fail(400, "INVALID_ATTESTATION", "证书过大");
  return new Certificate({ schema: parse(raw) });
}
export function certSpki(c: Certificate): Uint8Array<ArrayBuffer> {
  return new Uint8Array(c.subjectPublicKeyInfo.toSchema().toBER(false));
}
function serial(c: Certificate): string {
  return hex(c.serialNumber.valueBlock.valueHexView).replace(/^0+/, "") || "0";
}
function dn(c: Certificate, oid: string): string | null {
  const values = c.subject.typesAndValues.filter((x) => x.type === oid);
  if (values.length !== 1) return null;
  const json = values[0]!.value.toJSON() as {
    valueBlock?: { value?: unknown };
  };
  return typeof json.valueBlock?.value === "string"
    ? json.valueBlock.value
    : null;
}
function attributes(n: Node): Map<number, Node> {
  const out = new Map<number, Node>();
  let count = 0;
  for (const tag of sequence(n)) {
    if (
      ++count > 128 ||
      tag.idBlock.tagClass !== 3 ||
      !tag.idBlock.isConstructed ||
      out.has(tag.idBlock.tagNumber)
    )
      fail(400, "INVALID_ATTESTATION", "授权字段无效");
    const children = (tag as asn1.Constructed).valueBlock.value;
    if (children.length !== 1)
      fail(400, "INVALID_ATTESTATION", "授权字段长度无效");
    out.set(tag.idBlock.tagNumber, children[0]!);
  }
  return out;
}
function contains(n: Node | undefined, value: number): boolean {
  return (
    n instanceof asn1.Set &&
    n.valueBlock.value.some((x) => integer(x) === value)
  );
}
export async function verifyAttestation(
  encodedChain: string[],
  spki: string,
  challenge: string,
  roots: string[],
  now = Date.now(),
): Promise<Proof> {
  setEngine(
    "workers",
    crypto,
    Object.assign(
      new CryptoEngine({ name: "workers", crypto, subtle: crypto.subtle }),
      { timingSafeEqual: crypto.subtle.timingSafeEqual.bind(crypto.subtle) },
    ),
  );
  if (encodedChain.length < 3 || encodedChain.length > 5)
    fail(400, "INVALID_ATTESTATION", "证书链结构无效");
  const chain = encodedChain.map(certificate);
  const root = chain.at(-1)!;
  const anchor = roots
    .map(certificate)
    .find(
      (c) =>
        equal(certSpki(c), certSpki(root)) && c.subject.isEqual(root.subject),
    );
  if (
    !anchor ||
    !root.subject.isEqual(root.issuer) ||
    !(await root.verify(anchor))
  )
    fail(403, "UNTRUSTED_ATTESTATION", "不受信任的证明根");
  const intermediate = chain.at(-2)!;
  const factory = dn(intermediate, "2.5.4.5") !== null;
  const rkp =
    dn(intermediate, "2.5.4.3") === "Droid CA2" &&
    dn(intermediate, "2.5.4.10") === "Google LLC";
  if (
    (factory && chain.length !== 4) ||
    (rkp && chain.length !== 5) ||
    (!factory && !rkp)
  )
    fail(400, "INVALID_ATTESTATION", "证明链角色无效");
  const proofIndices = chain.flatMap((c, i) =>
    c.extensions?.some((e) => e.extnID === ATTESTATION) ? [i] : [],
  );
  const provisionIndices = chain.flatMap((c, i) =>
    c.extensions?.some((e) => e.extnID === PROVISIONING) ? [i] : [],
  );
  if (
    proofIndices.length !== 1 ||
    proofIndices[0] !== 0 ||
    provisionIndices.length > 1 ||
    (provisionIndices.length === 1 && (!rkp || provisionIndices[0] !== 1))
  )
    fail(400, "INVALID_ATTESTATION", "证明扩展位置无效");
  // Android's official specialized validator deliberately does not impose
  // generic CA/PKIX constraints on legacy factory chains. Signature/name,
  // provisioning roles and extension placement remain strict.
  for (let index = chain.length - 2; index >= 0; index--) {
    const c = chain[index]!;
    const parent = index === chain.length - 2 ? anchor : chain[index + 1]!;
    if (!c.issuer.isEqual(parent.subject) || !(await c.verify(parent)))
      fail(403, "INVALID_ATTESTATION_SIGNATURE", "证书链签名无效");
    if (
      index > 0 &&
      (c.notBefore.value.getTime() > now ||
        (!factory && c.notAfter.value.getTime() < now))
    )
      fail(403, "ATTESTATION_CERTIFICATE_TIME", "证明证书时间无效");
  }
  const target = chain[0]!;
  if (!equal(certSpki(target), unb64(spki)))
    fail(403, "ATTESTATION_KEY_MISMATCH", "证明公钥不一致");
  const extension = target.extensions!.find((e) => e.extnID === ATTESTATION)!;
  const values = sequence(
    parse(new Uint8Array(extension.extnValue.valueBlock.valueHexView)),
  );
  if (values.length !== 8)
    fail(400, "INVALID_ATTESTATION", "KeyDescription 字段无效");
  const version = integer(values[0]);
  if (![1, 2, 3, 4, 100, 200, 300, 400, 500].includes(version))
    fail(400, "INVALID_ATTESTATION", "未知证明版本");
  const level = integer(values[1]);
  const keyLevel = integer(values[3]);
  if (level !== keyLevel || ![1, 2].includes(level))
    fail(403, "SOFTWARE_KEY_REJECTED", "必须使用硬件密钥");
  if (!equal(octets(values[4]), unb64(challenge)))
    fail(403, "ATTESTATION_CHALLENGE_MISMATCH", "证明挑战不一致");
  const hardware = attributes(values[7]!);
  attributes(values[6]!);
  if (
    integer(hardware.get(702)) !== 0 ||
    integer(hardware.get(2)) !== 3 ||
    integer(hardware.get(3)) !== 256 ||
    integer(hardware.get(10)) !== 1 ||
    !contains(hardware.get(1), 2) ||
    !contains(hardware.get(5), 4) ||
    !(hardware.get(503) instanceof asn1.Null) ||
    hardware.has(504)
  )
    fail(403, "ATTESTATION_KEY_POLICY", "硬件密钥用途不符合要求");
  const rootOfTrust = hardware.get(704);
  if (!rootOfTrust) fail(403, "ATTESTATION_ROOT_OF_TRUST", "缺少硬件根状态");
  const rot = sequence(rootOfTrust);
  if (rot.length < 3 || rot.length > 4 || !(rot[1] instanceof asn1.Boolean))
    fail(400, "INVALID_ATTESTATION", "RootOfTrust 无效");
  octets(rot[0]);
  const state = integer(rot[2]);
  if (state > 3) fail(400, "INVALID_ATTESTATION", "VerifiedBootState 无效");
  // Ensure the target's actual SPKI is EC P-256, independently of metadata.
  try {
    await crypto.subtle.importKey(
      "spki",
      unb64(spki),
      { name: "ECDSA", namedCurve: "P-256" },
      false,
      ["verify"],
    );
  } catch {
    fail(403, "ATTESTATION_KEY_POLICY", "密钥必须 EC P-256");
  }
  if (
    level === 2 &&
    !chain
      .slice(1, -1)
      .some((c) =>
        c.subject.typesAndValues.some((v) =>
          JSON.stringify(v.value.toJSON()).toLowerCase().includes("strongbox"),
        ),
      )
  )
    fail(403, "ATTESTATION_LEVEL_MISMATCH", "StrongBox 链声明不一致");
  if (rkp) {
    const certified = dn(chain[1]!, "2.5.4.10");
    if (certified !== (level === 2 ? "StrongBox" : "TEE"))
      fail(403, "ATTESTATION_LEVEL_MISMATCH", "硬件级别不一致");
  }
  return {
    spki,
    publicKeySha256: await sha(unb64(spki)),
    rootSpkiSha256: await sha(certSpki(anchor)),
    serials: chain.map(serial),
    securityLevel: level === 2 ? "STRONGBOX" : "TEE",
    provisioning: factory ? "FACTORY" : "RKP",
    deviceLocked: rot[1].valueBlock.value,
    verifiedBootState: state,
    osVersion: hardware.has(705) ? integer(hardware.get(705)) : null,
    osPatchLevel: hardware.has(706) ? integer(hardware.get(706)) : null,
  };
}
export async function refreshAttestation(env: Env): Promise<void> {
  const responses = await Promise.all([
    fetch("https://android.googleapis.com/attestation/root", {
      redirect: "manual",
    }),
    fetch("https://android.googleapis.com/attestation/status", {
      redirect: "manual",
    }),
  ]);
  if (responses.some((r) => !r.ok))
    throw new Error("Attestation refresh failed");
  const [rootsRaw, statusRaw] = await Promise.all([
    bytes(responses[0]!, 65536),
    bytes(responses[1]!, 2_000_000),
  ]);
  const roots = decodeJson(rootsRaw);
  const status = decodeJson(statusRaw);
  if (
    !Array.isArray(roots) ||
    roots.length === 0 ||
    roots.length > 20 ||
    roots.some((r) => typeof r !== "string")
  )
    throw new Error("Invalid official roots");
  const entries = (status as { entries?: unknown }).entries;
  if (typeof entries !== "object" || entries === null || Array.isArray(entries))
    throw new Error("Invalid official revocations");
  const snapshot = opaqueId();
  const now = Date.now();
  const rootRows: { spkiSha256: string; pem: string }[] = [];
  for (const pem of roots) {
    const c = certificate(pem as string);
    rootRows.push({ spkiSha256: await sha(certSpki(c)), pem: pem as string });
  }
  for (const [number, entry] of Object.entries(entries)) {
    if (
      !/^[1-9a-f][0-9a-f]*$/.test(number) ||
      typeof entry !== "object" ||
      entry === null ||
      !["REVOKED", "SUSPENDED"].includes((entry as { status: string }).status)
    )
      throw new Error("Invalid official revocation entry");
  }
  // A statement per revoked serial exhausts the invocation's D1 query budget.
  // JSON1 inserts the fully validated snapshot in four statements and one
  // transaction; a failed bulk insert leaves the old pointer and no partial
  // snapshot behind. Keep the original dictionary to stay within input bounds.
  const rootsJson = JSON.stringify(rootRows),
    revocationsJson = JSON.stringify(entries);
  if (
    new TextEncoder().encode(rootsJson).length > 2_000_000 ||
    new TextEncoder().encode(revocationsJson).length > 2_000_000
  )
    throw new Error("Attestation snapshot exceeds D1 binding limit");
  await env.DB.batch([
    env.DB.prepare("INSERT INTO attestation_snapshots VALUES(?,?,?,?)").bind(
      snapshot,
      now,
      await sha(rootsRaw),
      await sha(statusRaw),
    ),
    env.DB.prepare(
      "INSERT INTO attestation_roots SELECT ?,json_extract(value,'$.spkiSha256'),json_extract(value,'$.pem') FROM json_each(?)",
    ).bind(snapshot, rootsJson),
    env.DB.prepare(
      "INSERT INTO attestation_revocations SELECT ?,key,json_extract(value,'$.status') FROM json_each(?)",
    ).bind(snapshot, revocationsJson),
    env.DB.prepare(
      "INSERT INTO attestation_current VALUES(1,?) ON CONFLICT(singleton) DO UPDATE SET snapshot_id=excluded.snapshot_id",
    ).bind(snapshot),
  ]);
}
