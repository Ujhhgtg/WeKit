import { z } from "zod";
import { opaqueId, random, sha, unb64, verify } from "./crypto";
import { binary, body, fail, id, nonce, path, source } from "./http";
import { LIMITS, type Env } from "./env";
export interface Auth {
  actorType: "REGISTRATION" | "CREDENTIAL";
  actorId: string;
  installationId: string | null;
  registrationId: string | null;
  requestId: string;
  challengeId: string;
  processNonce: string;
  grantId: string;
  tokenHash: string;
  operation: string;
  digest: string;
  spki: string;
  method: string;
  path: string;
}
export interface RegistrationContext {
  registration_id: string;
  mode: "NEW_KEY" | "REGISTERED_KEY";
  existing_installation_id: string | null;
  token_sha256: string;
  registration_nonce: string;
  attestation_challenge: string | null;
  state: string;
  expires_at: number;
  installation_id: string | null;
  evidence_submission_sha256: string | null;
  assurance: "ATTESTED" | "UNATTESTED_HARDWARE";
}
export async function tokenHash(request: Request): Promise<string> {
  const header = request.headers.get("authorization");
  if (!header || !/^Bearer [A-Za-z0-9_-]{43}$/.test(header))
    fail(401, "INVALID_TOKEN", "安装 token 无效");
  return sha(unb64(header.slice(7), 32));
}
function header(
  request: Request,
  name: string,
  type: z.ZodType<string> = id,
): string {
  const r = type.safeParse(request.headers.get(name));
  if (!r.success) fail(400, "INVALID_HEADER", `${name} 无效`);
  return r.data;
}
const registeredPaths = [
  "/v1/registrations",
  "/v1/bindings/status",
  "/v1/bindings/claim",
  "/v1/bindings/cancel",
];
const installationPaths = [
  "/v1/initializations",
  "/v1/load-grants/renew",
  "/v1/initializations/report",
  "/v1/installations/unbind",
];
export async function issueChallenge(
  env: Env,
  req: Request,
): Promise<Response> {
  const schema = z.strictObject({
    registrationId: id.optional(),
    installationId: id.optional(),
    method: z.enum(["POST", "GET"]),
    path: z.string().max(160),
    processNonce: nonce,
    grantId: id.optional(),
  });
  const { value: v } = await body(req, schema);
  const digest = await tokenHash(req);
  unb64(v.processNonce, 32);
  if (Boolean(v.registrationId) === Boolean(v.installationId))
    fail(400, "INVALID_INPUT", "选择登记或安装主体");
  let actorId: string;
  let actorType: "REGISTRATION" | "CREDENTIAL";
  if (v.registrationId) {
    const ctx = await env.DB.prepare(
      "SELECT * FROM registration_contexts WHERE registration_id=? AND token_sha256=?",
    )
      .bind(v.registrationId, digest)
      .first<RegistrationContext>();
    if (
      !ctx ||
      (ctx.state === "PREPARED" && ctx.expires_at <= Date.now()) ||
      ctx.state === "EXPIRED" ||
      ctx.registration_nonce !== v.processNonce
    )
      fail(401, "REGISTRATION_EXPIRED", "登记上下文无效");
    if (v.method !== "POST" || !registeredPaths.includes(v.path) || v.grantId)
      fail(400, "CHALLENGE_SCOPE", "登记操作无效");
    actorId = ctx.registration_id;
    actorType = "REGISTRATION";
  } else {
    const cred = await env.DB.prepare(
      "SELECT credential_id FROM credentials WHERE token_sha256=? AND installation_id=?",
    )
      .bind(digest, v.installationId!)
      .first<{ credential_id: string }>();
    if (!cred) fail(401, "INVALID_TOKEN", "安装 token 无效");
    const download = /^\/v1\/payloads\/[a-z0-9_-]{1,64}\/content$/.test(v.path);
    if (
      (download && (v.method !== "GET" || !v.grantId)) ||
      (!download &&
        (v.method !== "POST" ||
          !installationPaths.includes(v.path) ||
          v.grantId))
    )
      fail(400, "CHALLENGE_SCOPE", "安装操作无效");
    actorId = cred.credential_id;
    actorType = "CREDENTIAL";
  }
  const challengeId = opaqueId(),
    serverNonce = random(32),
    expiresAt = Date.now() + LIMITS.challengeMs;
  await env.DB.prepare(
    "INSERT INTO challenges(challenge_id,actor_type,actor_id,method,path,process_nonce,grant_id,nonce,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
  )
    .bind(
      challengeId,
      actorType,
      actorId,
      v.method,
      v.path,
      v.processNonce,
      v.grantId ?? "",
      serverNonce,
      expiresAt,
      Date.now(),
    )
    .run();
  return Response.json(
    { challengeId, serverNonce, expiresAt },
    { headers: { "Cache-Control": "no-store" } },
  );
}
export async function authenticate(
  env: Env,
  req: Request,
  raw: Uint8Array<ArrayBuffer>,
  fields: Record<string, unknown>,
  newSpki?: string,
): Promise<Auth> {
  const token = await tokenHash(req),
    requestId = header(req, "x-wekit-request-id"),
    challengeId = header(req, "x-wekit-challenge-id"),
    processNonce = header(req, "x-wekit-process-nonce", nonce);
  unb64(processNonce, 32);
  const installationId = req.headers.get("x-wekit-installation-id"),
    registrationId = req.headers.get("x-wekit-registration-id");
  if (Boolean(installationId) === Boolean(registrationId))
    fail(400, "INVALID_HEADER", "主体头无效");
  let actorId: string;
  let actorType: "REGISTRATION" | "CREDENTIAL";
  let spki: string;
  let installed: string | null = installationId;
  if (!id.safeParse(registrationId ?? installationId).success)
    fail(400, "INVALID_HEADER", "主体 ID 无效");
  if (registrationId) {
    const ctx = await env.DB.prepare(
      "SELECT * FROM registration_contexts WHERE registration_id=? AND token_sha256=?",
    )
      .bind(registrationId, token)
      .first<RegistrationContext>();
    if (
      !ctx ||
      ctx.registration_nonce !== processNonce ||
      ctx.state === "EXPIRED" ||
      (ctx.state === "PREPARED" && ctx.expires_at <= Date.now())
    )
      fail(401, "REGISTRATION_EXPIRED", "登记上下文无效");
    actorType = "REGISTRATION";
    actorId = registrationId;
    installed = ctx.installation_id ?? ctx.existing_installation_id;
    const key = installed
      ? await env.DB.prepare(
          "SELECT spki FROM installations WHERE installation_id=?",
        )
          .bind(installed)
          .first<{ spki: string }>()
      : null;
    spki = key?.spki ?? newSpki ?? "";
    if (!spki) fail(401, "REGISTRATION_NOT_VERIFIED", "先提交硬件证明");
  } else {
    const row = await env.DB.prepare(
      "SELECT c.credential_id,i.spki FROM credentials c JOIN installations i ON i.installation_id=c.installation_id WHERE c.token_sha256=? AND c.installation_id=?",
    )
      .bind(token, installationId!)
      .first<{ credential_id: string; spki: string }>();
    if (!row) fail(401, "INVALID_TOKEN", "安装 token 无效");
    actorType = "CREDENTIAL";
    actorId = row.credential_id;
    spki = row.spki;
  }
  const route = path(req),
    grantId = req.headers.get("x-wekit-grant-id") ?? "";
  if (grantId && !id.safeParse(grantId).success)
    fail(400, "INVALID_HEADER", "grant ID 无效");
  const range = canonicalRange(req.headers.get("range"));
  if (req.method !== "GET" && (range || grantId))
    fail(400, "INVALID_HEADER", "非下载请求不能有 Range/grant 头");
  if (
    fields.requestId !== requestId ||
    (registrationId
      ? fields.registrationId !== registrationId
      : fields.installationId !== installationId) ||
    (fields.processNonce ?? fields.registrationNonce) !== processNonce
  )
    fail(400, "HEADER_BODY_MISMATCH", "头与 body 不一致");
  const c = await env.DB.prepare(
    "SELECT nonce FROM challenges WHERE challenge_id=? AND actor_type=? AND actor_id=? AND method=? AND path=? AND process_nonce=? AND grant_id=? AND expires_at>? AND consumption_id IS NULL",
  )
    .bind(
      challengeId,
      actorType,
      actorId,
      req.method,
      route,
      processNonce,
      grantId,
      Date.now(),
    )
    .first<{ nonce: string }>();
  if (!c) fail(401, "CHALLENGE_INVALID", "挑战过期或已使用");
  const bodyHash = await sha(raw);
  await verify(
    spki,
    header(req, "x-wekit-signature", binary.length(86)),
    [
      "WEKIT-REQUEST-V1",
      req.method,
      route,
      bodyHash,
      token,
      challengeId,
      c.nonce,
      requestId,
      processNonce,
      range,
      grantId,
      "",
    ].join("\n"),
  );
  const digest = await sha(
    [
      "WEKIT-OPERATION-V1",
      req.method,
      route,
      bodyHash,
      token,
      processNonce,
      range,
      grantId,
      "",
    ].join("\n"),
  );
  return {
    actorType,
    actorId,
    installationId: installed,
    registrationId,
    requestId,
    challengeId,
    processNonce,
    grantId,
    tokenHash: token,
    operation: `${req.method} ${route}`,
    digest,
    spki,
    method: req.method,
    path: route,
  };
}
export function canonicalRange(value: string | null): string {
  if (!value) return "";
  const m = /^bytes=(0|[1-9]\d*)-(0|[1-9]\d*)?$/.exec(value);
  if (
    !m ||
    !Number.isSafeInteger(Number(m[1])) ||
    (m[2] !== undefined &&
      (!Number.isSafeInteger(Number(m[2])) || Number(m[1]) > Number(m[2])))
  )
    fail(416, "INVALID_RANGE", "下载范围无效");
  return value;
}
export interface Guard {
  sql: string;
  bindings: (string | number)[];
  attemptId: string;
}
export function guard(auth: Auth, attemptId: string): Guard {
  return {
    attemptId,
    sql: `EXISTS(SELECT 1 FROM challenges WHERE challenge_id=? AND consumption_id=?) AND EXISTS(SELECT 1 FROM request_operations WHERE actor_type=? AND actor_id=? AND operation=? AND request_id=? AND operation_digest=?)`,
    bindings: [
      auth.challengeId,
      attemptId,
      auth.actorType,
      auth.actorId,
      auth.operation,
      auth.requestId,
      auth.digest,
    ],
  };
}
export async function transact<T>(
  env: Env,
  req: Request,
  auth: Auth,
  resultSql: string,
  resultBindings: (string | number | null)[],
  extras: (g: Guard) => D1PreparedStatement[],
  finalSql = "SELECT result_json FROM request_operations WHERE actor_type=? AND actor_id=? AND operation=? AND request_id=?",
  finalBindings: (string | number)[] = [
    auth.actorType,
    auth.actorId,
    auth.operation,
    auth.requestId,
  ],
): Promise<T> {
  const attempt = opaqueId(),
    g = guard(auth, attempt);
  const now = Date.now();
  const origin = source(req, env);
  const statements = [
    env.DB.prepare(
      "UPDATE challenges SET consumption_id=?,consumed_request_id=? WHERE challenge_id=? AND actor_type=? AND actor_id=? AND method=? AND path=? AND process_nonce=? AND grant_id=? AND expires_at>? AND consumption_id IS NULL",
    ).bind(
      attempt,
      auth.requestId,
      auth.challengeId,
      auth.actorType,
      auth.actorId,
      auth.method,
      auth.path,
      auth.processNonce,
      auth.grantId,
      now,
    ),
    env.DB.prepare(
      `INSERT INTO request_operations(actor_type,actor_id,operation,request_id,operation_digest,result_json,created_at) SELECT ?,?,?,?,?,q.result_json,? FROM (${resultSql}) q WHERE EXISTS(SELECT 1 FROM challenges WHERE challenge_id=? AND consumption_id=?) ON CONFLICT(actor_type,actor_id,operation,request_id) DO NOTHING`,
    ).bind(
      auth.actorType,
      auth.actorId,
      auth.operation,
      auth.requestId,
      auth.digest,
      now,
      ...resultBindings,
      auth.challengeId,
      attempt,
    ),
    ...extras(g),
    env.DB.prepare(
      `INSERT INTO request_attempts SELECT ?,?,?,?,?,COALESCE(json_extract(q.result_json,'$.currentResult'),json_extract(q.result_json,'$.result'),'COMMITTED'),?,?,? FROM (${finalSql} AND ${g.sql}) q`,
    ).bind(
      attempt,
      auth.actorType,
      auth.actorId,
      auth.operation,
      auth.requestId,
      origin.ip,
      origin.sourceType,
      now,
      ...finalBindings,
      ...g.bindings,
    ),
    env.DB.prepare(`${finalSql} AND ${g.sql}`).bind(
      ...finalBindings,
      ...g.bindings,
    ),
  ];
  const results = await env.DB.batch(statements);
  if (results[0]!.meta.changes !== 1)
    fail(401, "CHALLENGE_INVALID", "挑战已使用");
  const last = results.at(-1)!.results as { result_json?: string }[];
  if (!last[0]?.result_json)
    fail(409, "OPERATION_CONFLICT", "请求 ID 与操作不一致或状态已改变");
  return JSON.parse(last[0].result_json) as T;
}
export function hardwareSql(installation = "i"): string {
  // A low-assurance installation has no verifiable Google chain. It is still
  // gated by the active Telegram binding/credential and every request is
  // signed by its registered P-256 key; only the hardware-origin claim is
  // unavailable. Attested installations retain the fresh-root/revocation
  // gate below.
  return `(${installation}.assurance='UNATTESTED_HARDWARE' OR EXISTS(SELECT 1 FROM attestation_current ac JOIN attestation_snapshots ats ON ats.snapshot_id=ac.snapshot_id JOIN attestation_roots ar ON ar.snapshot_id=ac.snapshot_id WHERE ac.singleton=1 AND ats.fetched_at>CAST(strftime('%s','now') AS INTEGER)*1000-${LIMITS.attestationFreshMs} AND ar.spki_sha256=${installation}.root_spki_sha256 AND NOT EXISTS(SELECT 1 FROM installation_attestation_serials iss JOIN attestation_revocations rev ON rev.serial=iss.serial AND rev.snapshot_id=ac.snapshot_id WHERE iss.installation_id=${installation}.installation_id)))`;
}
export function authoritySql(): string {
  return `c.status='ACTIVE' AND b.status='ACTIVE' AND u.status='ACTIVE' AND c.auth_epoch=u.auth_epoch AND ${hardwareSql()}`;
}
export const authorityFrom =
  "FROM credentials c JOIN bindings b ON b.binding_id=c.binding_id JOIN users u ON u.telegram_user_id=b.telegram_user_id JOIN installations i ON i.installation_id=c.installation_id";
