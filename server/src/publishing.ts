import { z } from "zod";
import { createRemoteJWKSet, jwtVerify, SignJWT } from "jose";
import {
  b64,
  equal,
  opaqueId,
  sha,
  unb64,
  unwrap,
  utf8,
  verify,
  wrap,
  type Wrapped,
} from "./crypto";
import { body, bytes, decodeJson, fail, hash, id, json, text } from "./http";
import { LIMITS, type Env } from "./env";
import type { ManifestEnvelope, ReleaseManifest } from "./contracts";
interface CiSession {
  subject: string;
  sha: string;
  runId: string;
}
function bearer(req: Request): string {
  const value = req.headers.get("authorization");
  if (!value?.startsWith("Bearer "))
    fail(401, "CI_AUTHENTICATION", "需要 CI 认证");
  return value.slice(7);
}
export async function ciSession(env: Env, req: Request): Promise<Response> {
  if (
    [
      env.CI_AUDIENCE,
      env.CI_REPOSITORY_ID,
      env.CI_REPOSITORY_OWNER_ID,
      env.CI_WORKFLOW_REF,
      env.CI_REF,
      env.CI_ENVIRONMENT,
    ].some((v) => typeof v !== "string" || !v)
  )
    fail(503, "CI_CONFIGURATION", "CI 信任配置未就绪", true);
  await body(req, z.strictObject({}));
  const { payload } = await jwtVerify(
    bearer(req),
    createRemoteJWKSet(
      new URL("https://token.actions.githubusercontent.com/.well-known/jwks"),
    ),
    {
      issuer: "https://token.actions.githubusercontent.com",
      audience: env.CI_AUDIENCE,
      algorithms: ["RS256"],
      maxTokenAge: "10m",
    },
  );
  if (
    payload.repository_id !== env.CI_REPOSITORY_ID ||
    payload.repository_owner_id !== env.CI_REPOSITORY_OWNER_ID ||
    payload.repository_visibility !== "private" ||
    payload.workflow_ref !== env.CI_WORKFLOW_REF ||
    (payload.job_workflow_ref !== undefined &&
      payload.job_workflow_ref !== "") ||
    (payload.job_workflow_sha !== undefined &&
      payload.job_workflow_sha !== "") ||
    payload.ref !== env.CI_REF ||
    payload.environment !== env.CI_ENVIRONMENT ||
    typeof payload.sha !== "string" ||
    !/^[0-9a-f]{40}$/.test(payload.sha) ||
    typeof payload.run_id !== "string"
  )
    fail(403, "CI_POLICY", "CI 发行来源不允许");
  const expiresAt = Date.now() + 600000,
    subject = `${env.CI_REPOSITORY_OWNER_ID}:${env.CI_REPOSITORY_ID}`;
  const token = await new SignJWT({ sha: payload.sha, runId: payload.run_id })
    .setProtectedHeader({ alg: "HS256", typ: "JWT" })
    .setIssuer("wekit-ci")
    .setAudience("wekit-publication")
    .setSubject(subject)
    .setIssuedAt()
    .setExpirationTime(Math.floor(expiresAt / 1000))
    .sign(unb64(env.CI_SESSION_SECRET, 32));
  return json({ token, expiresAt });
}
async function authenticateCi(env: Env, req: Request): Promise<CiSession> {
  const { payload } = await jwtVerify(
    bearer(req),
    unb64(env.CI_SESSION_SECRET, 32),
    {
      issuer: "wekit-ci",
      audience: "wekit-publication",
      algorithms: ["HS256"],
    },
  );
  if (
    payload.sub !== `${env.CI_REPOSITORY_OWNER_ID}:${env.CI_REPOSITORY_ID}` ||
    typeof payload.sha !== "string" ||
    typeof payload.runId !== "string"
  )
    fail(403, "CI_POLICY", "CI 权限无效");
  return { subject: payload.sub, sha: payload.sha, runId: payload.runId };
}
export const envelopeSchema = z.strictObject({
  keyId: id,
  manifest: z.string().max(90000),
  signature: z.string().max(100),
});
const manifestSchema = z.strictObject({
  payloadId: z.string().regex(/^[a-z0-9_-]{1,64}$/),
  businessVersion: text(128),
  sourceCommit: z.string().regex(/^[0-9a-f]{40}$/),
  entryClass: z
    .string()
    .regex(/^[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+$/)
    .max(256),
  apkSha256: hash,
  apkSize: z.number().int().positive().max(LIMITS.apk),
  cipherSha256: hash,
  cipherSize: z
    .number()
    .int()
    .positive()
    .max(LIMITS.apk + 16 * 128),
  encryption: z.strictObject({
    algorithm: z.literal("AES-256-GCM"),
    chunkSize: z.literal(1048576),
    noncePrefix: z.string(),
    chunkCount: z.number().int().positive().max(128),
  }),
  createdAt: z.string().datetime(),
});
export async function validateManifest(
  env: Env,
  envelope: ManifestEnvelope,
): Promise<ReleaseManifest> {
  if (utf8(JSON.stringify(envelope)).length > LIMITS.manifest)
    fail(413, "INVALID_MANIFEST", "清单信封过大");
  const keys = JSON.parse(env.RELEASE_PUBLIC_KEYS_JSON) as Record<
    string,
    string
  >;
  const spki = keys[envelope.keyId];
  if (!spki) fail(403, "RELEASE_SIGNATURE", "发行签名 keyId 不受信任");
  const clear = unb64(envelope.manifest);
  if (clear.length > LIMITS.manifest) fail(413, "INVALID_MANIFEST", "清单过大");
  const prefix = utf8("WEKIT-RELEASE-V1\0"),
    signed = new Uint8Array(prefix.length + clear.length);
  signed.set(prefix);
  signed.set(clear, prefix.length);
  await verify(spki, envelope.signature, signed);
  const result = manifestSchema.safeParse(decodeJson(clear));
  if (!result.success) fail(400, "INVALID_MANIFEST", "发行清单无效");
  const m = result.data;
  unb64(m.encryption.noncePrefix, 8);
  if (
    m.encryption.chunkCount !== Math.ceil(m.apkSize / 1048576) ||
    m.cipherSize !== m.apkSize + 16 * m.encryption.chunkCount
  )
    fail(400, "INVALID_MANIFEST", "加密长度无效");
  return m;
}
interface Job {
  job_id: string;
  payload_id: string;
  ci_subject: string;
  source_commit: string;
  run_id: string;
  upload_id: string;
  state: string;
  manifest_json: string;
  r2_key: string;
  content_key_wrapped_json: string;
  cipher_size: number;
  cipher_sha256: string;
}
const jobSql =
  "SELECT j.*,p.manifest_json,p.r2_key,p.content_key_wrapped_json,p.cipher_size,p.cipher_sha256 FROM publication_jobs j JOIN payload_releases p ON p.payload_id=j.payload_id WHERE j.job_id=? AND j.ci_subject=?";
async function job(env: Env, id: string, ci: CiSession): Promise<Job> {
  const row = await env.DB.prepare(jobSql).bind(id, ci.subject).first<Job>();
  if (!row) fail(404, "PUBLICATION_NOT_FOUND", "发布任务不存在");
  return row;
}
async function existing(env: Env, row: Job): Promise<Response> {
  const parts = await env.DB.prepare(
    "SELECT part_number AS partNumber,etag,size,sha256 FROM publication_parts WHERE job_id=? ORDER BY part_number",
  )
    .bind(row.job_id)
    .all();
  return json({
    jobId: row.job_id,
    payloadId: row.payload_id,
    uploadId: row.upload_id,
    partSize: LIMITS.part,
    state: row.state,
    parts: parts.results,
  });
}
export async function createPublication(
  env: Env,
  req: Request,
  ci: CiSession,
): Promise<Response> {
  const { value: v } = await body(
    req,
    z.strictObject({
      jobId: id,
      payloadId: id,
      manifest: envelopeSchema,
      contentKey: z.string().optional(),
    }),
    128 * 1024,
  );
  const manifest = await validateManifest(env, v.manifest);
  if (v.payloadId !== manifest.payloadId)
    fail(400, "INVALID_MANIFEST", "载荷 ID 不一致");
  const old = await env.DB.prepare(jobSql)
    .bind(v.jobId, ci.subject)
    .first<Job>();
  if (old) {
    if (
      old.payload_id !== v.payloadId ||
      old.manifest_json !== JSON.stringify(v.manifest)
    )
      fail(409, "PUBLICATION_CONFLICT", "任务内容不一致");
    if (
      v.contentKey &&
      !equal(
        unb64(v.contentKey, 32),
        await unwrap(
          env,
          JSON.parse(old.content_key_wrapped_json) as Wrapped,
          `WEKIT-CONTENT-KEY-V1\n${v.payloadId}\n`,
        ),
      )
    )
      fail(409, "PUBLICATION_CONFLICT", "内容密钥不一致");
    return existing(env, old);
  }
  if (manifest.sourceCommit !== ci.sha)
    fail(403, "CI_COMMIT_MISMATCH", "新发行源码提交与 CI 不一致");
  if (!v.contentKey) fail(400, "CONTENT_KEY_REQUIRED", "新发行需要内容密钥");
  const key = await wrap(
      env,
      unb64(v.contentKey, 32),
      `WEKIT-CONTENT-KEY-V1\n${v.payloadId}\n`,
    ),
    r2Key = `payloads/${v.payloadId}.bin`,
    upload = await env.PAYLOADS.createMultipartUpload(r2Key);
  const now = Date.now();
  try {
    await env.DB.batch([
      env.DB.prepare(
        "INSERT INTO payload_releases VALUES(?,?,?,?,?,?,?, ?,'UPLOADING',?)",
      ).bind(
        v.payloadId,
        JSON.stringify(v.manifest),
        manifest.apkSha256,
        manifest.apkSize,
        manifest.cipherSha256,
        manifest.cipherSize,
        r2Key,
        JSON.stringify(key),
        now,
      ),
      env.DB.prepare(
        "INSERT INTO publication_jobs VALUES(?,?,?,?,?,?,'UPLOADING',NULL,?,?)",
      ).bind(
        v.jobId,
        v.payloadId,
        ci.subject,
        manifest.sourceCommit,
        ci.runId,
        upload.uploadId,
        now,
        now,
      ),
    ]);
  } catch (e) {
    await upload.abort();
    const retry = await env.DB.prepare(jobSql)
      .bind(v.jobId, ci.subject)
      .first<Job>();
    if (retry && retry.manifest_json === JSON.stringify(v.manifest))
      return existing(env, retry);
    throw e;
  }
  return existing(env, await job(env, v.jobId, ci));
}
async function uploadPart(
  env: Env,
  req: Request,
  row: Job,
  partNumber: number,
): Promise<Response> {
  if (req.headers.get("content-type") !== "application/octet-stream")
    fail(415, "CONTENT_TYPE", "使用二进制上传");
  const expectedParts = Math.ceil(row.cipher_size / LIMITS.part);
  if (partNumber < 1 || partNumber > expectedParts)
    fail(400, "INVALID_PART", "分块编号无效");
  const raw = await bytes(req, LIMITS.part),
    expected =
      partNumber === expectedParts
        ? row.cipher_size - (partNumber - 1) * LIMITS.part
        : LIMITS.part;
  if (raw.length !== expected) fail(400, "INVALID_PART_SIZE", "分块长度不一致");
  const digest = await sha(raw);
  const prior = await env.DB.prepare(
    "SELECT sha256,etag FROM publication_parts WHERE job_id=? AND part_number=?",
  )
    .bind(row.job_id, partNumber)
    .first<{ sha256: string; etag: string }>();
  if (prior) {
    if (prior.sha256 !== digest)
      fail(409, "PUBLICATION_CONFLICT", "分块不可覆盖");
    return json({ partNumber, etag: prior.etag });
  }
  if (row.state !== "UPLOADING")
    fail(409, "PUBLICATION_STATE", "任务不在上传状态");
  const uploaded = await env.PAYLOADS.resumeMultipartUpload(
    row.r2_key,
    row.upload_id,
  ).uploadPart(partNumber, raw);
  await env.DB.prepare("INSERT INTO publication_parts VALUES(?,?,?,?,?)")
    .bind(row.job_id, partNumber, digest, raw.length, uploaded.etag)
    .run();
  return json({ partNumber, etag: uploaded.etag });
}
async function complete(env: Env, req: Request, row: Job): Promise<Response> {
  const { value: v } = await body(
    req,
    z.strictObject({
      parts: z
        .array(
          z.strictObject({
            partNumber: z.number().int().positive().max(32),
            etag: text(1024),
          }),
        )
        .min(1)
        .max(32),
    }),
  );
  if (["READY", "ALLOWED"].includes(row.state))
    return json({
      jobId: row.job_id,
      payloadId: row.payload_id,
      state: row.state,
    });
  if (row.state !== "UPLOADING") fail(409, "PUBLICATION_STATE", "任务状态无效");
  const stored = await env.DB.prepare(
    "SELECT part_number AS partNumber,etag FROM publication_parts WHERE job_id=? ORDER BY part_number",
  )
    .bind(row.job_id)
    .all<{ partNumber: number; etag: string }>();
  const expected = Math.ceil(row.cipher_size / LIMITS.part);
  if (
    v.parts.length !== expected ||
    stored.results.length !== expected ||
    v.parts.some(
      (p, i) => p.partNumber !== i + 1 || p.etag !== stored.results[i]?.etag,
    )
  )
    fail(400, "INVALID_PARTS", "上传分块不完整");
  let object = await env.PAYLOADS.get(row.r2_key);
  if (!object) {
    await env.PAYLOADS.resumeMultipartUpload(
      row.r2_key,
      row.upload_id,
    ).complete(v.parts);
    object = await env.PAYLOADS.get(row.r2_key);
  }
  if (!object || object.size !== row.cipher_size)
    fail(409, "PAYLOAD_INTEGRITY", "远端载荷长度不一致");
  const digester = new crypto.DigestStream("SHA-256");
  await object.body.pipeTo(digester);
  const actual = Array.from(new Uint8Array(await digester.digest), (b) =>
    b.toString(16).padStart(2, "0"),
  ).join("");
  if (actual !== row.cipher_sha256) {
    await env.DB.batch([
      env.DB.prepare(
        "UPDATE payload_releases SET status='FAILED' WHERE payload_id=? AND status='UPLOADING'",
      ).bind(row.payload_id),
      env.DB.prepare(
        "UPDATE publication_jobs SET state='FAILED',error_code='CIPHER_HASH_MISMATCH',updated_at=? WHERE job_id=? AND state='UPLOADING'",
      ).bind(Date.now(), row.job_id),
    ]);
    fail(409, "PAYLOAD_INTEGRITY", "远端密文摘要不一致");
  }
  await env.DB.batch([
    env.DB.prepare(
      "UPDATE payload_releases SET status='READY' WHERE payload_id=? AND status='UPLOADING'",
    ).bind(row.payload_id),
    env.DB.prepare(
      "UPDATE publication_jobs SET state='READY',updated_at=? WHERE job_id=? AND state='UPLOADING' AND EXISTS(SELECT 1 FROM payload_releases WHERE payload_id=? AND status='READY')",
    ).bind(Date.now(), row.job_id, row.payload_id),
  ]);
  const final = await job(env, row.job_id, {
    subject: row.ci_subject,
    sha: row.source_commit,
    runId: row.run_id,
  });
  if (final.state !== "READY") fail(409, "PUBLICATION_STATE", "发行状态已改变");
  return json({ jobId: row.job_id, payloadId: row.payload_id, state: "READY" });
}
async function activate(env: Env, req: Request, row: Job): Promise<Response> {
  const { value: v } = await body(
    req,
    z.strictObject({ channel: z.string().regex(/^[a-z0-9_-]{1,64}$/) }),
  );
  const result = await env.DB.batch([
    env.DB.prepare(
      "UPDATE payload_releases SET status='ALLOWED' WHERE payload_id=? AND status IN('READY','ALLOWED')",
    ).bind(row.payload_id),
    env.DB.prepare(
      "INSERT INTO channels SELECT ?,payload_id,? FROM payload_releases WHERE payload_id=? AND status='ALLOWED' ON CONFLICT(name) DO UPDATE SET payload_id=excluded.payload_id,updated_at=excluded.updated_at",
    ).bind(v.channel, Date.now(), row.payload_id),
    env.DB.prepare(
      "UPDATE publication_jobs SET state='ALLOWED',updated_at=? WHERE job_id=? AND EXISTS(SELECT 1 FROM payload_releases WHERE payload_id=? AND status='ALLOWED')",
    ).bind(Date.now(), row.job_id, row.payload_id),
  ]);
  if (result[0]!.meta.changes !== 1)
    fail(409, "PUBLICATION_STATE", "载荷不能发行");
  return json({
    jobId: row.job_id,
    payloadId: row.payload_id,
    state: "ALLOWED",
    channel: v.channel,
  });
}
export async function publishing(
  env: Env,
  req: Request,
  route: string,
): Promise<Response> {
  if (route === "/ci/sessions" && req.method === "POST")
    return ciSession(env, req);
  const ci = await authenticateCi(env, req);
  if (route === "/ci/publications" && req.method === "POST")
    return createPublication(env, req, ci);
  const m =
    /^\/ci\/publications\/([A-Za-z0-9_-]{1,64})(?:\/(parts\/([1-9]\d*)|complete|activate))?$/.exec(
      route,
    );
  if (!m) fail(404, "NOT_FOUND", "接口不存在");
  const row = await job(env, m[1]!, ci);
  if (!m[2] && req.method === "GET") return existing(env, row);
  if (m[3] && req.method === "PUT")
    return uploadPart(env, req, row, Number(m[3]));
  if (m[2] === "complete" && req.method === "POST")
    return complete(env, req, row);
  if (m[2] === "activate" && req.method === "POST")
    return activate(env, req, row);
  fail(405, "METHOD_NOT_ALLOWED", "方法无效");
}
