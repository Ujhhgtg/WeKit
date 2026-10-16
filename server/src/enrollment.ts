import { z } from "zod";
import {
  authenticate,
  authoritySql,
  hardwareSql,
  transact,
  type RegistrationContext,
} from "./auth";
import { currentTrust, verifyAttestation, type Proof } from "./attestation";
import {
  b64,
  hmac,
  opaqueId,
  random,
  sha,
  unb64,
  unwrap,
  utf8,
  wrap,
  type Wrapped,
} from "./crypto";
import { body, fail, hash, id, json, nonce, text } from "./http";
import { LIMITS, type Env } from "./env";
export async function prepareRegistration(
  env: Env,
  req: Request,
): Promise<Response> {
  const schema = z.strictObject({
    mode: z.enum(["NEW_KEY", "REGISTERED_KEY", "UNATTESTED_KEY"]),
    tokenSha256: hash,
    registrationNonce: nonce,
    existingInstallationId: id.optional(),
  });
  const { value: v } = await body(req, schema);
  unb64(v.registrationNonce, 32);
  if ((v.mode === "REGISTERED_KEY") !== Boolean(v.existingInstallationId))
    fail(400, "REGISTRATION_MODE", "登记模式字段不一致");
  if (
    v.existingInstallationId &&
    !(await env.DB.prepare(
      "SELECT installation_id FROM installations WHERE installation_id=?",
    )
      .bind(v.existingInstallationId)
      .first())
  )
    fail(404, "INSTALLATION_NOT_FOUND", "安装不存在");
  const persistedMode = v.mode === "UNATTESTED_KEY" ? "NEW_KEY" : v.mode;
  const assurance =
    v.mode === "UNATTESTED_KEY" ? "UNATTESTED_HARDWARE" : "ATTESTED";
  const registrationId = opaqueId(),
    expiresAt = Date.now() + LIMITS.bindingMs;
  await env.DB.prepare(
    "INSERT INTO registration_contexts(registration_id,mode,existing_installation_id,token_sha256,registration_nonce,attestation_challenge,state,expires_at,created_at,assurance) VALUES(?,?,?,?,?,?,'PREPARED',?,?,?) ON CONFLICT(token_sha256,registration_nonce,mode) DO NOTHING",
  )
    .bind(
      registrationId,
      persistedMode,
      v.existingInstallationId ?? null,
      v.tokenSha256,
      v.registrationNonce,
      v.mode === "NEW_KEY" ? random(32) : null,
      expiresAt,
      Date.now(),
      assurance,
    )
    .run();
  const ctx = await env.DB.prepare(
    "SELECT * FROM registration_contexts WHERE token_sha256=? AND registration_nonce=? AND mode=?",
  )
    .bind(v.tokenSha256, v.registrationNonce, persistedMode)
    .first<RegistrationContext>();
  if (
    !ctx ||
    ctx.existing_installation_id !== (v.existingInstallationId ?? null) ||
    ctx.assurance !== assurance
  )
    fail(409, "OPERATION_CONFLICT", "登记主体不一致");
  if (ctx.state === "PREPARED" && ctx.expires_at <= Date.now())
    fail(410, "REGISTRATION_EXPIRED", "请使用新的登记 nonce");
  return json({
    registrationId: ctx.registration_id,
    attestationChallenge: ctx.attestation_challenge,
    expiresAt: ctx.expires_at,
  });
}
const deviceSchema = z.strictObject({
  manufacturer: text(128).optional(),
  model: text(128).optional(),
  androidVersion: text(64).optional(),
  sdkInt: z.number().int().min(29).max(100).optional(),
  hostPackage: z.literal("com.tencent.mm").optional(),
  hostVersionName: text(64).optional(),
});
const registerSchema = z.strictObject({
  requestId: id,
  registrationId: id,
  registrationNonce: nonce,
  spki: z.string().max(1024).optional(),
  certificateChain: z.array(z.string().max(24000)).min(3).max(5).optional(),
  hardwareKey: z.boolean().optional(),
  deviceInfo: deviceSchema.optional(),
});
function newCode(): string {
  const alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  return Array.from(
    crypto.getRandomValues(new Uint8Array(12)),
    (b) => alphabet[b & 31],
  ).join("");
}
export function normalizeCode(value: string): string {
  const code = value
    .toUpperCase()
    .replace(/-/g, "")
    .replace(/[IL]/g, "1")
    .replace(/O/g, "0");
  if (!/^[0-9ABCDEFGHJKMNPQRSTVWXYZ]{12}$/.test(code))
    fail(400, "INVALID_BINDING_CODE", "绑定码无效");
  return code;
}
export const codeAad = (session: string, expiry: number) =>
  `WEKIT-BINDING-CODE-V1\n${session}\n${expiry}\n`;
export async function register(env: Env, req: Request): Promise<Response> {
  const { value: v, raw } = await body(
    req,
    registerSchema,
    LIMITS.registration,
  );
  const auth = await authenticate(env, req, raw, v, v.spki);
  const ctx = await env.DB.prepare(
    "SELECT * FROM registration_contexts WHERE registration_id=?",
  )
    .bind(v.registrationId)
    .first<RegistrationContext>();
  if (!ctx) fail(404, "REGISTRATION_NOT_FOUND", "登记不存在");
  const lowAssurance = ctx.assurance === "UNATTESTED_HARDWARE";
  if (lowAssurance !== (v.hardwareKey === true))
    fail(
      400,
      "REGISTRATION_ASSURANCE",
      lowAssurance
        ? "低保证登记必须明确声明 hardwareKey=true"
        : "硬件证明登记不能声明 hardwareKey=true",
    );
  if (lowAssurance && (v.certificateChain || !v.spki))
    fail(400, "ATTESTATION_NOT_ACCEPTED", "低保证登记不接受证明证书链");
  const trust = lowAssurance ? null : await currentTrust(env);
  let proof: Proof | undefined;
  let installationId = ctx.installation_id ?? ctx.existing_installation_id;
  let submissionHash: string | null = null;
  if (lowAssurance) {
    if (!v.spki) fail(400, "PUBLIC_KEY_REQUIRED", "低保证登记需要公钥");
    try {
      await crypto.subtle.importKey(
        "spki",
        unb64(v.spki),
        { name: "ECDSA", namedCurve: "P-256" },
        false,
        ["verify"],
      );
    } catch {
      fail(400, "INVALID_PUBLIC_KEY", "公钥必须是 P-256 SPKI");
    }
    if (ctx.state === "PREPARED") {
      const duplicate = await env.DB.prepare(
        "SELECT installation_id FROM installations WHERE public_key_sha256=?",
      )
        .bind(await sha(unb64(v.spki)))
        .first();
      if (duplicate)
        fail(409, "KEY_ALREADY_REGISTERED", "请使用 REGISTERED_KEY");
    }
    submissionHash = await sha(
      JSON.stringify({ spki: v.spki, certificateChain: null, hardwareKey: true }),
    );
    if (
      ctx.state === "REGISTERED" &&
      submissionHash !== ctx.evidence_submission_sha256
    )
      fail(409, "REGISTRATION_EVIDENCE_MISMATCH", "登记公钥不能更换");
    if (ctx.state === "PREPARED") installationId = opaqueId();
  } else if (ctx.mode === "REGISTERED_KEY") {
    if (v.spki || v.certificateChain)
      fail(400, "REGISTRATION_MODE", "复用密钥不能替换证明");
  } else {
    if (!v.spki || !v.certificateChain || !ctx.attestation_challenge)
      fail(400, "ATTESTATION_REQUIRED", "需要硬件证明");
    submissionHash = await sha(
      JSON.stringify({ spki: v.spki, certificateChain: v.certificateChain }),
    );
    if (
      ctx.state === "REGISTERED" &&
      submissionHash !== ctx.evidence_submission_sha256
    )
      fail(409, "REGISTRATION_EVIDENCE_MISMATCH", "登记证据不能更换");
    if (ctx.state === "PREPARED") {
      proof = await verifyAttestation(
        v.certificateChain,
        v.spki,
        ctx.attestation_challenge,
        trust!.roots,
      );
      if (
        await env.DB.prepare(
          "SELECT installation_id FROM installations WHERE public_key_sha256=?",
        )
          .bind(proof.publicKeySha256)
          .first()
      )
        fail(409, "KEY_ALREADY_REGISTERED", "请使用 REGISTERED_KEY");
      installationId = opaqueId();
    }
  }
  if (!installationId) fail(400, "REGISTRATION_MODE", "缺少安装信息");
  const priorSession = await env.DB.prepare(
    "SELECT binding_session_id FROM enrollment_sessions WHERE registration_id=?",
  )
    .bind(ctx.registration_id)
    .first<{ binding_session_id: string }>();
  const sessionId = priorSession?.binding_session_id ?? opaqueId(),
    expiresAt = Date.now() + LIMITS.bindingMs,
    code = newCode(),
    codeHash = await hmac(
      env.BIND_CODE_PEPPER,
      `WEKIT-BINDING-CODE-V1\n${code}`,
    ),
    wrapped = await wrap(env, utf8(code), codeAad(sessionId, expiresAt));
  let proofGate: string;
  let proofArgs: (string | number)[];
  let evidenceHash = "";
  let assurance: "ATTESTED" | "UNATTESTED_HARDWARE" = lowAssurance
    ? "UNATTESTED_HARDWARE"
    : "ATTESTED";
  let evidenceKey = `attestation/${installationId}.json`;
  const unattestedPublicKeyHash =
    lowAssurance && v.spki ? await sha(unb64(v.spki)) : null;
  if (lowAssurance) {
    proofGate = "1";
    proofArgs = [];
    const evidence = JSON.stringify({
      type: "UNATTESTED_HARDWARE",
      hardwareKey: true,
      spki: v.spki,
      deviceInfo: v.deviceInfo ?? {},
      declaredAt: Date.now(),
    });
    evidenceHash = await sha(evidence);
    evidenceKey = `unattested/${installationId}.json`;
    await env.PAYLOADS.put(evidenceKey, evidence);
  } else if (proof) {
    proofGate = `EXISTS(SELECT 1 FROM attestation_current ac JOIN attestation_snapshots s ON s.snapshot_id=ac.snapshot_id JOIN attestation_roots r ON r.snapshot_id=ac.snapshot_id WHERE s.fetched_at>? AND r.spki_sha256=? AND NOT EXISTS(SELECT 1 FROM json_each(?) serial JOIN attestation_revocations rev ON rev.snapshot_id=ac.snapshot_id AND rev.serial=serial.value))`;
    proofArgs = [
      Date.now() - LIMITS.attestationFreshMs,
      proof.rootSpkiSha256,
      JSON.stringify(proof.serials),
    ];
    const evidence = JSON.stringify({
      certificateChain: v.certificateChain,
      proof,
    });
    evidenceHash = await sha(evidence);
    await env.PAYLOADS.put(evidenceKey, evidence);
  } else {
    proofGate = `EXISTS(SELECT 1 FROM installations i WHERE i.installation_id=? AND ${hardwareSql()})`;
    proofArgs = [installationId];
  }
  const registered = await transact<{
    installationId: string;
    bindingSessionId: string;
  }>(
    env,
    req,
    auth,
    "SELECT json_object('installationId',?,'bindingSessionId',?) AS result_json WHERE " +
      proofGate,
    [installationId, sessionId, ...proofArgs],
    (g) => {
      const statements: D1PreparedStatement[] = [];
      const op = `(SELECT result_json FROM request_operations WHERE actor_type=? AND actor_id=? AND operation=? AND request_id=?)`;
      const opArgs = [
        auth.actorType,
        auth.actorId,
        auth.operation,
        auth.requestId,
      ];
      if (proof || lowAssurance) {
        const publicKeySha256 = proof
          ? proof.publicKeySha256
          : unattestedPublicKeyHash!;
        const rootSpkiSha256 = proof ? proof.rootSpkiSha256 : "";
        const securityLevel = proof ? proof.securityLevel : "TEE";
        const attestationJson = proof
          ? JSON.stringify(proof)
          : JSON.stringify({
              type: "UNATTESTED_HARDWARE",
              hardwareKey: true,
              spki: v.spki,
            });
        statements.push(
          env.DB.prepare(
            `INSERT INTO installations SELECT ?,?,?,?,?,?,?,?,?,?,? WHERE ${g.sql} ON CONFLICT(installation_id) DO NOTHING`,
          ).bind(
            installationId,
            publicKeySha256,
            v.spki!,
            rootSpkiSha256,
            securityLevel,
            evidenceKey,
            evidenceHash,
            attestationJson,
            JSON.stringify(v.deviceInfo ?? {}),
            Date.now(),
            assurance,
            ...g.bindings,
          ),
        );
        if (proof)
          for (const serial of proof.serials)
            statements.push(
              env.DB.prepare(
                `INSERT INTO installation_attestation_serials SELECT ?,? WHERE ${g.sql} ON CONFLICT DO NOTHING`,
              ).bind(installationId, serial, ...g.bindings),
            );
      }
      statements.push(
        env.DB.prepare(
          `INSERT INTO enrollment_sessions(binding_session_id,registration_id,installation_id,token_sha256,code_hmac,code_wrapped_json,state,expires_at,created_at) SELECT json_extract(${op},'$.bindingSessionId'),?,?,?, ?,?,'PENDING',?,? WHERE ${g.sql} ON CONFLICT(registration_id) DO NOTHING`,
        ).bind(
          ...opArgs,
          ctx.registration_id,
          installationId,
          auth.tokenHash,
          codeHash,
          JSON.stringify(wrapped),
          expiresAt,
          Date.now(),
          ...g.bindings,
        ),
      );
      statements.push(
        env.DB.prepare(
          `UPDATE registration_contexts SET state='REGISTERED',installation_id=?,evidence_submission_sha256=? WHERE registration_id=? AND state='PREPARED' AND ${g.sql}`,
        ).bind(
          installationId,
          submissionHash,
          ctx.registration_id,
          ...g.bindings,
        ),
      );
      return statements;
    },
  );
  const session = await env.DB.prepare(
    "SELECT state,expires_at,code_wrapped_json FROM enrollment_sessions WHERE binding_session_id=?",
  )
    .bind(registered.bindingSessionId)
    .first<{
      state: string;
      expires_at: number;
      code_wrapped_json: string | null;
    }>();
  if (!session) fail(503, "REGISTRATION_UNAVAILABLE", "登记尚未完成", true);
  const bindingCode =
    session.code_wrapped_json && session.expires_at > Date.now()
      ? new TextDecoder().decode(
          await unwrap(
            env,
            JSON.parse(session.code_wrapped_json) as Wrapped,
            codeAad(registered.bindingSessionId, session.expires_at),
          ),
        )
      : null;
  return json({
    ...registered,
    bindingCode,
    expiresAt: session.expires_at,
    state:
      session.expires_at <= Date.now() && session.state !== "CLAIMED"
        ? "EXPIRED"
        : session.state,
  });
}
const sessionSchema = z.strictObject({
  requestId: id,
  registrationId: id,
  registrationNonce: nonce,
  bindingSessionId: id,
});
export async function binding(
  env: Env,
  req: Request,
  action: "status" | "claim" | "cancel",
): Promise<Response> {
  const { value: v, raw } = await body(req, sessionSchema);
  const auth = await authenticate(env, req, raw, v);
  const session = await env.DB.prepare(
    "SELECT e.installation_id,i.assurance FROM enrollment_sessions e JOIN installations i ON i.installation_id=e.installation_id WHERE e.binding_session_id=? AND e.registration_id=? AND e.token_sha256=?",
  )
    .bind(v.bindingSessionId, v.registrationId, auth.tokenHash)
    .first<{ installation_id: string; assurance: string }>();
  if (!session) fail(404, "BINDING_NOT_FOUND", "绑定不存在");
  const bindingId = opaqueId(),
    credentialId = opaqueId();
  if (action === "claim" && session.assurance !== "UNATTESTED_HARDWARE")
    await currentTrust(env);
  const opSql = `(SELECT result_json FROM request_operations WHERE actor_type=? AND actor_id=? AND operation=? AND request_id=?)`;
  const opArgs = [auth.actorType, auth.actorId, auth.operation, auth.requestId];
  const allowed = `e.state='APPROVED' AND e.expires_at>? AND u.status='ACTIVE' AND u.auth_epoch=e.candidate_epoch AND ${hardwareSql()}`;
  const resultSql =
    action === "claim"
      ? `SELECT json_object('bindingId',?,'credentialId',?,'result',CASE WHEN ${allowed} THEN 'ALLOWED' WHEN e.state='CLAIMED' THEN 'CLAIMED' ELSE 'DENIED' END) AS result_json FROM enrollment_sessions e JOIN installations i ON i.installation_id=e.installation_id LEFT JOIN users u ON u.telegram_user_id=e.candidate_user_id WHERE e.binding_session_id=?`
      : `SELECT json_object('bindingSessionId',?) AS result_json`;
  const resultBindings =
    action === "claim"
      ? [bindingId, credentialId, Date.now(), v.bindingSessionId]
      : [v.bindingSessionId];
  await transact(env, req, auth, resultSql, resultBindings, (g) => {
    const out = [
      env.DB.prepare(
        `UPDATE enrollment_sessions SET state='EXPIRED',code_wrapped_json=NULL,code_hmac=NULL,confirm_nonce=NULL WHERE binding_session_id=? AND state IN('PENDING','APPROVED') AND expires_at<=? AND ${g.sql}`,
      ).bind(v.bindingSessionId, Date.now(), ...g.bindings),
    ];
    if (action === "cancel") {
      out.push(
        env.DB.prepare(
          `UPDATE enrollment_sessions SET state='CANCELLED',code_wrapped_json=NULL,code_hmac=NULL,confirm_nonce=NULL WHERE binding_session_id=? AND state IN('PENDING','APPROVED') AND ${g.sql}`,
        ).bind(v.bindingSessionId, ...g.bindings),
      );
      return out;
    }
    if (action !== "claim") return out;
    const gate = `${g.sql} AND EXISTS(SELECT 1 FROM enrollment_sessions e JOIN installations i ON i.installation_id=e.installation_id JOIN users u ON u.telegram_user_id=e.candidate_user_id WHERE e.binding_session_id=? AND ${allowed}) AND json_extract(${opSql},'$.result')='ALLOWED'`;
    const args = [...g.bindings, v.bindingSessionId, Date.now(), ...opArgs];
    out.push(
      env.DB.prepare(
        `UPDATE credentials SET status='REVOKED',revoked_at=? WHERE installation_id=? AND status='ACTIVE' AND ${gate}`,
      ).bind(Date.now(), session.installation_id, ...args),
    );
    out.push(
      env.DB.prepare(
        `UPDATE bindings SET status='ENDED',ended_at=? WHERE installation_id=? AND status='ACTIVE' AND ${gate}`,
      ).bind(Date.now(), session.installation_id, ...args),
    );
    out.push(
      env.DB.prepare(
        `INSERT INTO bindings SELECT json_extract(${opSql},'$.bindingId'),installation_id,candidate_user_id,'ACTIVE',?,NULL FROM enrollment_sessions WHERE binding_session_id=? AND ${gate} ON CONFLICT(binding_id) DO NOTHING`,
      ).bind(...opArgs, Date.now(), v.bindingSessionId, ...args),
    );
    out.push(
      env.DB.prepare(
        `INSERT INTO credentials SELECT json_extract(${opSql},'$.credentialId'),installation_id,json_extract(${opSql},'$.bindingId'),token_sha256,candidate_epoch,'ACTIVE',?,NULL FROM enrollment_sessions WHERE binding_session_id=? AND ${gate} ON CONFLICT(credential_id) DO NOTHING`,
      ).bind(...opArgs, ...opArgs, Date.now(), v.bindingSessionId, ...args),
    );
    out.push(
      env.DB.prepare(
        `UPDATE enrollment_sessions SET state='CLAIMED',binding_id=json_extract(${opSql},'$.bindingId'),credential_id=json_extract(${opSql},'$.credentialId'),claimed_at=?,code_hmac=NULL,code_wrapped_json=NULL,confirm_nonce=NULL WHERE binding_session_id=? AND ${gate}`,
      ).bind(...opArgs, ...opArgs, Date.now(), v.bindingSessionId, ...args),
    );
    return out;
  });
  const final = await env.DB.prepare(
    `SELECT e.state,e.installation_id,e.binding_id,e.credential_id,e.candidate_user_id,e.expires_at,CASE WHEN ${authoritySql()} THEN 1 ELSE 0 END AS credential_valid FROM enrollment_sessions e JOIN installations i ON i.installation_id=e.installation_id LEFT JOIN credentials c ON c.credential_id=e.credential_id LEFT JOIN bindings b ON b.binding_id=c.binding_id LEFT JOIN users u ON u.telegram_user_id=e.candidate_user_id WHERE e.binding_session_id=?`,
  )
    .bind(v.bindingSessionId)
    .first<{
      state: string;
      installation_id: string;
      binding_id: string | null;
      credential_id: string | null;
      candidate_user_id: string | null;
      expires_at: number;
      credential_valid: number;
    }>();
  if (!final) fail(404, "BINDING_NOT_FOUND", "绑定不存在");
  if (
    action === "claim" &&
    (final.state !== "CLAIMED" || final.credential_valid !== 1)
  )
    fail(403, "BINDING_NOT_APPROVED", "绑定未确认或凭证已撤销");
  return json({
    state: final.state,
    installationId: final.installation_id,
    bindingSessionId: v.bindingSessionId,
    bindingId: final.binding_id,
    credentialId: final.credential_id,
    telegramUserId: final.candidate_user_id,
    expiresAt: final.expires_at,
  });
}
