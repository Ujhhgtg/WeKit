import { z } from "zod";
import {
  authenticate,
  authorityFrom,
  authoritySql,
  canonicalRange,
  transact,
} from "./auth";
import { installationAttestation } from "./attestation";
import { b64, opaqueId, unwrap, type Wrapped } from "./crypto";
import { body, fail, hash, id, json, nonce, source, text } from "./http";
import { LIMITS, type Env } from "./env";
import {
  initializationResponseChallengeSpecs,
  issueChallengesBestEffort,
  reportResponseChallengeSpecs,
} from "./challenge-pipeline";
import type { ManifestEnvelope } from "./contracts";
const requestFields = {
  requestId: id,
  installationId: id,
  processNonce: nonce,
};
const initSchema = z.strictObject({
  ...requestFields,
  processName: text(160),
  hostPackage: z.literal("com.tencent.mm"),
  hostVersionCode: z.string().regex(/^\d{1,20}$/),
  hostVersionName: text(64),
  sdkInt: z.number().int().min(29).max(100),
  processAbi: z.literal("arm64-v8a"),
  bootstrapVersion: text(64),
  selfWxId: text(512).nullable(),
  selfAlias: text(512).nullable(),
  identityReadState: z.enum([
    "CURRENT",
    "ALIAS_UNAVAILABLE",
    "UNAVAILABLE_IN_PROCESS",
  ]),
  requestedPayloadId: id.nullable(),
  cachedApkSha256: hash.optional(),
});
interface InitResult {
  initializationId: string;
  grantId: string;
  grantExpiresAt: number;
  payloadId: string | null;
  payloadMode: "CLOUD" | "LOCAL";
  result: string;
  currentResult?: string;
}
function allowed(result: { result: string; currentResult?: string }): void {
  const code = result.currentResult ?? result.result;
  if (code !== "ALLOWED")
    // A permanent denial leaves the client no retry path; transient states (payload
    // not yet published, epoch reconfirmation, cache mismatch) can succeed later.
    fail(403, code, "当前安装或载荷未获授权", code !== "AUTHORIZATION_DENIED");
}
/**
 * The only trust fact these handlers need before their own SQL authority gate is
 * that the attestation snapshot is present and fresh; only registration consumes
 * the trusted roots. This is currentTrust()'s freshness query alone, without the
 * roots fetch, and keeps the same externally visible 503
 * ATTESTATION_STATUS_UNAVAILABLE (retryable) failure.
 */
async function requireFreshAttestation(env: Env): Promise<void> {
  const current = await env.DB.prepare(
    "SELECT s.fetched_at FROM attestation_current c JOIN attestation_snapshots s ON s.snapshot_id=c.snapshot_id WHERE c.singleton=1",
  ).first<{ fetched_at: number }>();
  if (!current || Date.now() - current.fetched_at >= LIMITS.attestationFreshMs)
    fail(503, "ATTESTATION_STATUS_UNAVAILABLE", "证明状态资料不可用", true);
}
/**
 * An installation whose attestation chain Google revoked after registration keeps a
 * valid credential and binding; only its hardware claim is gone. Low-assurance mode is
 * already available to any client, so downgrade it once instead of denying every
 * initialization forever.
 */
async function downgradeRevokedInstallation(
  env: Env,
  installationId: string,
): Promise<void> {
  const trust = await installationAttestation(env, installationId);
  if (!trust || trust.assurance !== "ATTESTED" || !trust.rootTrusted || !trust.fresh)
    return;
  if (trust.revoked === 0 && trust.suspended === 0) return;
  const updated = await env.DB.prepare(
    "UPDATE installations SET assurance='UNATTESTED_HARDWARE' WHERE installation_id=? AND assurance='ATTESTED'",
  )
    .bind(installationId)
    .run();
  if (updated.meta.changes === 1)
    console.log(
      JSON.stringify({
        event: "attestation_downgraded",
        installationId,
        revoked: trust.revoked,
        suspended: trust.suspended,
      }),
    );
}
export async function initialize(env: Env, req: Request): Promise<Response> {
  const {
    value: v,
    raw,
    text: rawText,
  } = await body(req, initSchema);
  if (v.requestedPayloadId !== null && !v.cachedApkSha256)
    fail(400, "CACHE_HASH_REQUIRED", "请求缓存载荷须提供摘要");
  const auth = await authenticate(env, req, raw, v);
  if (auth.installationId) {
    const installation = await env.DB.prepare(
      "SELECT assurance FROM installations WHERE installation_id=?",
    )
      .bind(auth.installationId)
      .first<{ assurance: string }>();
    if (installation?.assurance !== "UNATTESTED_HARDWARE") {
      await requireFreshAttestation(env);
      await downgradeRevokedInstallation(env, auth.installationId);
    }
  }
  const initializationId = opaqueId(),
    grantId = opaqueId(),
    now = Date.now(),
    expires = now + LIMITS.grantMs,
    origin = source(req, env);
  const decision = `CASE WHEN c.auth_epoch!=u.auth_epoch THEN 'RECONFIRM_REQUIRED' WHEN NOT(${authoritySql()}) THEN 'AUTHORIZATION_DENIED' WHEN u.release_channel='local' THEN 'ALLOWED' WHEN p.payload_id IS NULL OR p.status!='ALLOWED' THEN 'PAYLOAD_UNAVAILABLE' WHEN ? IS NOT NULL AND (?!=p.payload_id OR p.apk_sha256!=?) THEN 'CACHE_MISMATCH' ELSE 'ALLOWED' END`;
  const query = `SELECT json_object('initializationId',?,'grantId',?,'grantExpiresAt',?,'payloadId',CASE WHEN u.release_channel='local' THEN NULL ELSE p.payload_id END,'payloadMode',CASE WHEN u.release_channel='local' THEN 'LOCAL' ELSE 'CLOUD' END,'result',${decision}) AS result_json ${authorityFrom} LEFT JOIN channels ch ON ch.name=u.release_channel LEFT JOIN payload_releases p ON p.payload_id=ch.payload_id WHERE c.credential_id=?`;
  const result = await transact<InitResult>(
    env,
    req,
    auth,
    query,
    [
      initializationId,
      grantId,
      expires,
      v.requestedPayloadId,
      v.requestedPayloadId,
      v.cachedApkSha256 ?? null,
      auth.actorId,
    ],
    (g) => [
      // last_seen_at is only an admin display value; refresh it at most once
      // every 6 hours per user instead of writing a row on every startup.
      env.DB.prepare(
        `UPDATE users SET last_seen_at=? WHERE last_seen_at<? AND telegram_user_id=(SELECT b.telegram_user_id FROM credentials c JOIN bindings b ON b.binding_id=c.binding_id WHERE c.credential_id=?) AND ${g.sql}`,
      ).bind(now, now - 6 * 60 * 60 * 1000, auth.actorId, ...g.bindings),
      env.DB.prepare(
        `INSERT INTO initializations SELECT json_extract(o.result_json,'$.initializationId'),c.credential_id,b.binding_id,i.installation_id,u.telegram_user_id,?, ?,?,?,?,?,?,?,?, ?,json_extract(o.result_json,'$.result'),json_extract(o.result_json,'$.payloadId'),? FROM request_operations o ${authorityFrom.replace("FROM", "JOIN")} WHERE o.actor_type=? AND o.actor_id=? AND o.operation=? AND o.request_id=? AND c.credential_id=o.actor_id AND ${g.sql} ON CONFLICT(initialization_id) DO NOTHING`,
      ).bind(
        auth.requestId,
        auth.processNonce,
        origin.ip,
        origin.sourceType,
        v.selfWxId,
        v.selfAlias,
        v.hostVersionName,
        v.bootstrapVersion,
        v.processName,
        rawText,
        now,
        auth.actorType,
        auth.actorId,
        auth.operation,
        auth.requestId,
        ...g.bindings,
      ),
      env.DB.prepare(
        `INSERT INTO load_grants SELECT json_extract(o.result_json,'$.grantId'),json_extract(o.result_json,'$.initializationId'),c.credential_id,b.binding_id,c.auth_epoch,?,p.payload_id,p.apk_sha256,json_extract(o.result_json,'$.grantExpiresAt'),0,? FROM request_operations o ${authorityFrom.replace("FROM", "JOIN")} JOIN payload_releases p ON p.payload_id=json_extract(o.result_json,'$.payloadId') WHERE o.actor_type=? AND o.actor_id=? AND o.operation=? AND o.request_id=? AND c.credential_id=o.actor_id AND json_extract(o.result_json,'$.result')='ALLOWED' AND ${authoritySql()} AND p.status='ALLOWED' AND ${g.sql} ON CONFLICT(grant_id) DO NOTHING`,
      ).bind(
        auth.processNonce,
        now,
        auth.actorType,
        auth.actorId,
        auth.operation,
        auth.requestId,
        ...g.bindings,
      ),
    ],
    `SELECT json_set(o.result_json,'$.currentResult',CASE WHEN c.auth_epoch!=u.auth_epoch THEN 'RECONFIRM_REQUIRED' WHEN NOT(${authoritySql()}) THEN 'AUTHORIZATION_DENIED' WHEN u.release_channel='local' THEN json_extract(o.result_json,'$.result') WHEN p.payload_id IS NULL OR p.status!='ALLOWED' THEN 'PAYLOAD_UNAVAILABLE' ELSE json_extract(o.result_json,'$.result') END) AS result_json FROM request_operations o ${authorityFrom.replace("FROM", "JOIN")} LEFT JOIN payload_releases p ON p.payload_id=json_extract(o.result_json,'$.payloadId') WHERE o.actor_type=? AND o.actor_id=? AND o.operation=? AND o.request_id=? AND c.credential_id=o.actor_id`,
    [auth.actorType, auth.actorId, auth.operation, auth.requestId],
  );
  allowed(result);
  // Pipelined challenges are opt-in: a client that advertises a challenge pool gets
  // the report challenge here, so the report needs no separate /v1/challenges request.
  const challenges = await issueChallengesBestEffort(
    env,
    auth,
    initializationResponseChallengeSpecs(req),
  );
  const pipelined = challenges.length > 0 ? { challenges } : {};
  if (result.payloadMode === "LOCAL") {
    return json({
      initializationId: result.initializationId,
      grantId: result.grantId,
      grantExpiresAt: result.grantExpiresAt,
      payloadMode: "LOCAL",
      payloadId: null,
      manifest: null,
      contentKey: null,
      downloadPath: null,
      ...pipelined,
    });
  }
  const release = await env.DB.prepare(
    "SELECT manifest_json,content_key_wrapped_json FROM payload_releases WHERE payload_id=?",
  )
    .bind(result.payloadId)
    .first<{ manifest_json: string; content_key_wrapped_json: string }>();
  if (!release) fail(503, "PAYLOAD_UNAVAILABLE", "载荷不可用", true);
  const key = await unwrap(
    env,
    JSON.parse(release.content_key_wrapped_json) as Wrapped,
    `WEKIT-CONTENT-KEY-V1\n${result.payloadId}\n`,
  );
  if (key.length !== 32) throw new Error("Invalid stored content key");
  return json({
    initializationId: result.initializationId,
    grantId: result.grantId,
    grantExpiresAt: result.grantExpiresAt,
    payloadMode: "CLOUD",
    payloadId: result.payloadId,
    manifest: JSON.parse(release.manifest_json) as ManifestEnvelope,
    contentKey: b64(key),
    downloadPath: `/v1/payloads/${result.payloadId}/content`,
    ...pipelined,
  });
}
const renewSchema = z.strictObject({
  ...requestFields,
  initializationId: id,
  grantId: id,
  payloadId: id,
  apkSha256: hash,
});
export async function renew(env: Env, req: Request): Promise<Response> {
  const { value: v, raw } = await body(req, renewSchema);
  const auth = await authenticate(env, req, raw, v);
  if (auth.installationId) {
    const installation = await env.DB.prepare(
      "SELECT assurance FROM installations WHERE installation_id=?",
    )
      .bind(auth.installationId)
      .first<{ assurance: string }>();
    if (installation?.assurance !== "UNATTESTED_HARDWARE")
      await requireFreshAttestation(env);
  }
  const newGrant = opaqueId(),
    expiry = Date.now() + LIMITS.grantMs;
  const from = `${authorityFrom} JOIN initializations init ON init.credential_id=c.credential_id JOIN load_grants old ON old.initialization_id=init.initialization_id JOIN payload_releases p ON p.payload_id=old.payload_id`;
  const filter = `c.credential_id=? AND init.initialization_id=? AND old.grant_id=? AND old.payload_id=? AND old.apk_sha256=? AND old.process_nonce=? AND init.process_nonce=?`;
  const args = [
    auth.actorId,
    v.initializationId,
    v.grantId,
    v.payloadId,
    v.apkSha256,
    auth.processNonce,
    auth.processNonce,
  ];
  const r = await transact<{
    grantId: string;
    grantExpiresAt: number;
    result: string;
    currentResult: string;
  }>(
    env,
    req,
    auth,
    `SELECT json_object('grantId',?,'grantExpiresAt',?,'result',CASE WHEN ${authoritySql()} AND p.status='ALLOWED' AND old.revoked=0 AND init.result='ALLOWED' THEN 'ALLOWED' ELSE 'AUTHORIZATION_DENIED' END) AS result_json ${from} WHERE ${filter}`,
    [newGrant, expiry, ...args],
    (g) => [
      env.DB.prepare(
        `INSERT INTO load_grants SELECT json_extract(o.result_json,'$.grantId'),old.initialization_id,c.credential_id,b.binding_id,c.auth_epoch,old.process_nonce,old.payload_id,old.apk_sha256,json_extract(o.result_json,'$.grantExpiresAt'),0,? FROM request_operations o ${from.replace("FROM", "JOIN")} WHERE o.actor_type=? AND o.actor_id=? AND o.operation=? AND o.request_id=? AND ${filter} AND json_extract(o.result_json,'$.result')='ALLOWED' AND ${authoritySql()} AND p.status='ALLOWED' AND old.revoked=0 AND ${g.sql} ON CONFLICT(grant_id) DO NOTHING`,
      ).bind(
        Date.now(),
        auth.actorType,
        auth.actorId,
        auth.operation,
        auth.requestId,
        ...args,
        ...g.bindings,
      ),
    ],
    `SELECT json_set(o.result_json,'$.currentResult',CASE WHEN ${authoritySql()} AND p.status='ALLOWED' AND old.revoked=0 THEN json_extract(o.result_json,'$.result') ELSE 'AUTHORIZATION_DENIED' END) AS result_json FROM request_operations o ${from.replace("FROM", "JOIN")} WHERE o.actor_type=? AND o.actor_id=? AND o.operation=? AND o.request_id=? AND ${filter}`,
    [auth.actorType, auth.actorId, auth.operation, auth.requestId, ...args],
    {
      status: 409,
      code: "GRANT_STALE",
      message: "下载许可已失效，请重新初始化",
      retryable: true,
    },
  );
  allowed(r);
  return json({
    grantId: r.grantId,
    grantExpiresAt: r.grantExpiresAt,
    payloadId: v.payloadId,
  });
}
export async function download(
  env: Env,
  req: Request,
  payloadId: string,
): Promise<Response> {
  const fields = {
    requestId: req.headers.get("x-wekit-request-id"),
    installationId: req.headers.get("x-wekit-installation-id"),
    processNonce: req.headers.get("x-wekit-process-nonce"),
  };
  const auth = await authenticate(env, req, new Uint8Array(), fields);
  if (auth.installationId) {
    const installation = await env.DB.prepare(
      "SELECT assurance FROM installations WHERE installation_id=?",
    )
      .bind(auth.installationId)
      .first<{ assurance: string }>();
    if (installation?.assurance !== "UNATTESTED_HARDWARE")
      await requireFreshAttestation(env);
  }
  if (!auth.grantId) fail(401, "GRANT_REQUIRED", "需要下载许可");
  const from = `${authorityFrom} JOIN load_grants grantrow ON grantrow.credential_id=c.credential_id JOIN payload_releases p ON p.payload_id=grantrow.payload_id`;
  const filter = `c.credential_id=? AND grantrow.grant_id=? AND grantrow.payload_id=? AND grantrow.process_nonce=?`;
  const args = [auth.actorId, auth.grantId, payloadId, auth.processNonce];
  const valid = `${authoritySql()} AND p.status='ALLOWED' AND grantrow.auth_epoch=u.auth_epoch AND grantrow.expires_at>CAST(strftime('%s','now') AS INTEGER)*1000 AND grantrow.revoked=0`;
  const metadata = await transact<{
    result: string;
    r2Key: string;
    size: number;
    hash: string;
  }>(
    env,
    req,
    auth,
    `SELECT json_object('result',CASE WHEN ${valid} THEN 'ALLOWED' ELSE 'AUTHORIZATION_DENIED' END,'r2Key',p.r2_key,'size',p.cipher_size,'hash',p.cipher_sha256) AS result_json ${from} WHERE ${filter}`,
    args,
    () => [],
    `SELECT json_set(o.result_json,'$.result',CASE WHEN ${valid} THEN 'ALLOWED' ELSE 'AUTHORIZATION_DENIED' END) AS result_json FROM request_operations o ${from.replace("FROM", "JOIN")} WHERE o.actor_type=? AND o.actor_id=? AND o.operation=? AND o.request_id=? AND ${filter}`,
    [auth.actorType, auth.actorId, auth.operation, auth.requestId, ...args],
    {
      status: 403,
      code: "AUTHORIZATION_DENIED",
      message: "当前安装或载荷未获授权",
    },
  );
  allowed(metadata);
  const range = canonicalRange(req.headers.get("range"));
  let start = 0,
    end = metadata.size - 1;
  if (range) {
    const m = /^bytes=(\d+)-(\d*)$/.exec(range)!;
    start = Number(m[1]);
    if (m[2]) end = Math.min(Number(m[2]), end);
    if (start >= metadata.size)
      return new Response(null, {
        status: 416,
        headers: {
          "Content-Range": `bytes */${metadata.size}`,
          "Cache-Control": "no-store",
        },
      });
  }
  const object = await env.PAYLOADS.get(
    metadata.r2Key,
    range ? { range: { offset: start, length: end - start + 1 } } : undefined,
  );
  if (!object) fail(503, "PAYLOAD_UNAVAILABLE", "载荷对象不可用", true);
  const headers: Record<string, string> = {
    "Cache-Control": "no-store",
    "Content-Type": "application/octet-stream",
    "Content-Length": String(end - start + 1),
    "Accept-Ranges": "bytes",
    ETag: `"${metadata.hash}"`,
  };
  if (range)
    headers["Content-Range"] = `bytes ${start}-${end}/${metadata.size}`;
  return new Response(object.body, { status: range ? 206 : 200, headers });
}
const reportSchema = z.strictObject({
  ...requestFields,
  initializationId: id,
  stage: text(64),
  result: z.enum(["LOADED", "FAILED", "IDENTITY_UPDATE"]),
  selfWxId: text(512).nullable().optional(),
  selfAlias: text(512).nullable().optional(),
  identityReadState: text(64).optional(),
  errorCode: text(128).optional(),
  errorMessage: text(2048, true).optional(),
});
export async function report(env: Env, req: Request): Promise<Response> {
  const {
    value: v,
    raw,
    text: rawText,
  } = await body(req, reportSchema);
  const auth = await authenticate(env, req, raw, v);
  const reportId = opaqueId();
  const origin = source(req, env);
  const r = await transact<{
    reportId: string;
    result: string;
    currentResult: string;
  }>(
    env,
    req,
    auth,
    `SELECT json_object('reportId',?,'result',CASE WHEN ${authoritySql()} THEN 'ALLOWED' ELSE 'AUTHORIZATION_DENIED' END) AS result_json ${authorityFrom} JOIN initializations init ON init.credential_id=c.credential_id WHERE c.credential_id=? AND init.initialization_id=? AND init.process_nonce=?`,
    [reportId, auth.actorId, v.initializationId, auth.processNonce],
    (g) => [
      env.DB.prepare(
        `INSERT INTO initialization_reports SELECT json_extract(o.result_json,'$.reportId'),?,?,?, ?,?,?,? FROM request_operations o WHERE o.actor_type=? AND o.actor_id=? AND o.operation=? AND o.request_id=? AND json_extract(o.result_json,'$.result')='ALLOWED' AND ${g.sql} ON CONFLICT(report_id) DO NOTHING`,
      ).bind(
        v.initializationId,
        auth.requestId,
        v.stage,
        v.result,
        rawText,
        origin.ip,
        Date.now(),
        auth.actorType,
        auth.actorId,
        auth.operation,
        auth.requestId,
        ...g.bindings,
      ),
    ],
    `SELECT json_set(o.result_json,'$.currentResult',CASE WHEN ${authoritySql()} THEN json_extract(o.result_json,'$.result') ELSE 'AUTHORIZATION_DENIED' END) AS result_json FROM request_operations o ${authorityFrom.replace("FROM", "JOIN")} JOIN initializations init ON init.credential_id=c.credential_id WHERE o.actor_type=? AND o.actor_id=? AND o.operation=? AND o.request_id=? AND c.credential_id=o.actor_id AND init.initialization_id=? AND init.process_nonce=?`,
    [
      auth.actorType,
      auth.actorId,
      auth.operation,
      auth.requestId,
      v.initializationId,
      auth.processNonce,
    ],
    {
      status: 409,
      code: "INITIALIZATION_STATE_CHANGED",
      message: "初始化状态已变化，请重试",
      retryable: true,
    },
  );
  allowed(r);
  // Replenish the client's challenge pool: another report challenge for this process
  // plus floating initialization challenges for the next process starts.
  const challenges = await issueChallengesBestEffort(
    env,
    auth,
    reportResponseChallengeSpecs(req),
  );
  return json({
    reportId: r.reportId,
    ...(challenges.length > 0 ? { challenges } : {}),
  });
}
export async function unbind(env: Env, req: Request): Promise<Response> {
  const { value: v, raw } = await body(req, z.strictObject(requestFields));
  const auth = await authenticate(env, req, raw, v);
  const r = await transact<{ state: string }>(
    env,
    req,
    auth,
    `SELECT json_object('state','UNBOUND') AS result_json`,
    [],
    (g) => [
      env.DB.prepare(
        `UPDATE credentials SET status='REVOKED',revoked_at=? WHERE credential_id=? AND ${g.sql}`,
      ).bind(Date.now(), auth.actorId, ...g.bindings),
      env.DB.prepare(
        `UPDATE bindings SET status='ENDED',ended_at=? WHERE binding_id=(SELECT binding_id FROM credentials WHERE credential_id=?) AND ${g.sql}`,
      ).bind(Date.now(), auth.actorId, ...g.bindings),
    ],
  );
  return json(r);
}
