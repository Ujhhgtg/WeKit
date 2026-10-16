import { createRemoteJWKSet, jwtVerify } from "jose";
import { z } from "zod";
import { b64, hmac, opaqueId, unb64, utf8, verifyHmac } from "./crypto";
import {
  body,
  decodeJson,
  endpoint,
  fail,
  id,
  json,
  source,
  text,
} from "./http";
import type { Env } from "./env";
import { refreshAttestation } from "./attestation";
interface AdminIdentity {
  subject: string;
  email: string;
}
async function identity(env: Env, req: Request): Promise<AdminIdentity> {
  if (new URL(req.url).origin !== env.ADMIN_ORIGIN)
    fail(403, "ADMIN_ORIGIN", "管理入口不允许");
  const token = req.headers.get("cf-access-jwt-assertion");
  if (!token) fail(403, "ACCESS_REQUIRED", "需要管理员登录");
  const team = new URL(env.ACCESS_TEAM_ORIGIN);
  if (
    team.protocol !== "https:" ||
    !team.hostname.endsWith(".cloudflareaccess.com") ||
    team.pathname !== "/"
  )
    throw new Error("Invalid Access team configuration");
  const { payload } = await jwtVerify(
    token,
    createRemoteJWKSet(new URL("/cdn-cgi/access/certs", team)),
    {
      issuer: team.origin,
      audience: env.ACCESS_AUDIENCE,
      algorithms: ["RS256"],
    },
  );
  if (typeof payload.sub !== "string" || typeof payload.email !== "string")
    fail(403, "ACCESS_IDENTITY", "管理员身份无效");
  const email = payload.email.toLowerCase();
  const allowed = JSON.parse(env.ADMIN_EMAILS_JSON) as string[];
  if (!allowed.map((v) => v.toLowerCase()).includes(email))
    fail(403, "ADMIN_NOT_ALLOWED", "此邮箱没有管理权限");
  return { subject: payload.sub, email };
}
async function csrfToken(env: Env, user: AdminIdentity): Promise<string> {
  const payload = b64(
    utf8(
      JSON.stringify({
        subject: user.subject,
        expiresAt: Date.now() + 600000,
        nonce: opaqueId(),
      }),
    ),
  );
  return `${payload}.${await hmac(env.CSRF_SECRET, `WEKIT-CSRF-V1\n${env.ADMIN_ORIGIN}\n${env.ACCESS_AUDIENCE}\n${payload}`)}`;
}
async function checkCsrf(
  env: Env,
  req: Request,
  user: AdminIdentity,
): Promise<void> {
  if (req.headers.get("origin") !== env.ADMIN_ORIGIN)
    fail(403, "CSRF_ORIGIN", "请求来源无效");
  const token = req.headers.get("x-wekit-csrf-token");
  if (!token || token.length > 1024)
    fail(403, "CSRF_REQUIRED", "需要 CSRF token");
  const parts = token.split(".");
  if (
    parts.length !== 2 ||
    !(await verifyHmac(
      env.CSRF_SECRET,
      `WEKIT-CSRF-V1\n${env.ADMIN_ORIGIN}\n${env.ACCESS_AUDIENCE}\n${parts[0]}`,
      parts[1]!,
    ))
  )
    fail(403, "CSRF_INVALID", "CSRF token 无效");
  const v = z
    .strictObject({
      subject: z.string(),
      expiresAt: z.number().int(),
      nonce: id,
    })
    .safeParse(decodeJson(unb64(parts[0]!)));
  if (
    !v.success ||
    v.data.subject !== user.subject ||
    v.data.expiresAt <= Date.now() ||
    v.data.expiresAt > Date.now() + 600000
  )
    fail(403, "CSRF_INVALID", "CSRF token 过期");
}
interface Listing {
  table: string;
  key: string;
  columns: string;
  filters: Record<string, string>;
}
const listings: Record<string, Listing> = {
  users: {
    table: "users",
    key: "telegram_user_id",
    columns:
      "telegram_user_id,username,display_name,status,auth_epoch,release_channel,created_at,last_seen_at,notes",
    filters: { telegramUserId: "telegram_user_id", status: "status" },
  },
  initializations: {
    table: "initializations",
    key: "initialization_id",
    columns:
      "initialization_id,credential_id,binding_id,installation_id,telegram_user_id,request_id,process_nonce,source_ip,source_type,self_wx_id,self_alias,host_version_name,bootstrap_version,process_name,report_json,result,payload_id,created_at",
    filters: {
      telegramUserId: "telegram_user_id",
      installationId: "installation_id",
      selfWxId: "self_wx_id",
      selfAlias: "self_alias",
      sourceIp: "source_ip",
      payloadId: "payload_id",
      result: "result",
    },
  },
  releases: {
    table: "payload_releases",
    key: "payload_id",
    columns:
      "payload_id,manifest_json,apk_sha256,apk_size,cipher_sha256,cipher_size,status,created_at,deleted_at",
    filters: { payloadId: "payload_id", status: "status" },
  },
  audit: {
    table: "admin_audit",
    key: "audit_id",
    columns:
      "audit_id,subject,email,action,target,reason,before_json,after_json,source_ip,created_at",
    filters: { subject: "subject", target: "target", action: "action" },
  },
  publications: {
    table: "publication_jobs",
    key: "job_id",
    columns:
      "job_id,payload_id,source_commit,run_id,state,error_code,created_at,updated_at",
    filters: { payloadId: "payload_id", state: "state" },
  },
};
async function list(
  env: Env,
  req: Request,
  kind: string,
): Promise<{ data: Record<string, unknown>[]; nextCursor: string | null }> {
  const definition = listings[kind];
  if (!definition) fail(404, "NOT_FOUND", "列表不存在");
  const params = new URL(req.url).searchParams;
  const limit = Number(params.get("limit") ?? 50);
  if (!Number.isInteger(limit) || limit < 1 || limit > 200)
    fail(400, "INVALID_LIMIT", "分页大小无效");
  const clauses: string[] = [],
    bindings: (string | number)[] = [];
  const seen = new Set<string>();
  for (const [field, value] of params) {
    if (seen.has(field)) fail(400, "INVALID_FILTER", "过滤字段重复");
    seen.add(field);
    if (field === "cursor" || field === "limit") continue;
    if (kind === "users" && (field === "selfWxId" || field === "selfAlias")) {
      if (utf8(value).length > 512) fail(400, "INVALID_FILTER", "身份过长");
      clauses.push(
        `telegram_user_id IN(SELECT telegram_user_id FROM initializations WHERE ${field === "selfWxId" ? "self_wx_id" : "self_alias"}=?)`,
      );
      bindings.push(value);
      continue;
    }
    const column = definition.filters[field];
    if (!column || utf8(value).length > 512)
      fail(400, "INVALID_FILTER", "过滤字段无效");
    clauses.push(`${column}=?`);
    bindings.push(value);
  }
  const cursor = params.get("cursor");
  if (cursor) {
    const c = z
      .strictObject({
        time: z.number().int().nonnegative(),
        key: z.union([z.string().max(64), z.number().int().nonnegative()]),
      })
      .safeParse(decodeJson(unb64(cursor)));
    if (!c.success) fail(400, "INVALID_CURSOR", "分页游标无效");
    clauses.push(`(created_at<? OR (created_at=? AND ${definition.key}<?))`);
    bindings.push(c.data.time, c.data.time, c.data.key);
  }
  const query = `SELECT ${definition.columns} FROM ${definition.table}${clauses.length ? " WHERE " + clauses.join(" AND ") : ""} ORDER BY created_at DESC,${definition.key} DESC LIMIT ?`;
  const result = await env.DB.prepare(query)
    .bind(...bindings, limit + 1)
    .all<Record<string, unknown>>();
  const more = result.results.length > limit;
  const data = result.results.slice(0, limit);
  const last = data.at(-1);
  return {
    data,
    nextCursor:
      more && last
        ? b64(
            utf8(
              JSON.stringify({
                time: last.created_at,
                key: last[definition.key],
              }),
            ),
          )
        : null,
  };
}
async function audit(
  env: Env,
  req: Request,
  user: AdminIdentity,
  action: string,
  target: string,
  reason: string,
  before: string,
  after: string,
  bindings: (string | number | null)[],
  from: string,
  mutations: D1PreparedStatement[],
): Promise<void> {
  const origin = source(req, env);
  const results = await env.DB.batch([
    env.DB.prepare(
      `INSERT INTO admin_audit(subject,email,action,target,reason,before_json,after_json,source_ip,created_at) SELECT ?,?,?,?,?,${before},${after},?,? ${from}`,
    ).bind(
      user.subject,
      user.email,
      action,
      target,
      reason,
      ...bindings.slice(0, -1),
      origin.ip,
      Date.now(),
      ...bindings.slice(-1),
    ),
    ...mutations,
  ]);
  if (results[0]!.meta.changes !== 1) fail(404, "NOT_FOUND", "管理目标不存在");
}
export async function adminRequest(req: Request, env: Env): Promise<Response> {
  const user = await identity(env, req),
    route = new URL(req.url).pathname;
  if (!route.startsWith("/admin/api/")) {
    if (!env.ASSETS)
      fail(503, "ADMIN_UI_UNAVAILABLE", "管理界面尚未配置", true);
    return env.ASSETS.fetch(req);
  }
  if (route === "/admin/api/session" && req.method === "GET")
    return json({ data: { ...user, csrfToken: await csrfToken(env, user) } });
  if (req.method !== "GET") await checkCsrf(env, req, user);
  if (route === "/admin/api/attestation/refresh" && req.method === "POST") {
    const { value: v } = await body(req, z.strictObject({}));
    await refreshAttestation(env);
    await env.DB.prepare(
      "INSERT INTO admin_audit(subject,email,action,target,reason,before_json,after_json,source_ip,created_at) VALUES(?,?,'attestation_refresh','official',?,'{}','{}',?,?)",
    )
      .bind(user.subject, user.email, "", source(req, env).ip, Date.now())
      .run();
    return json({ data: { updated: true } });
  }
  if (route === "/admin/api/overview" && req.method === "GET") {
    const counts = await env.DB.prepare(
      "SELECT (SELECT count(*) FROM users) AS users,(SELECT count(*) FROM installations) AS installations",
    ).first();
    const recent = await env.DB.prepare(
      "SELECT initialization_id,telegram_user_id,result,payload_id,created_at FROM initializations ORDER BY created_at DESC LIMIT 20",
    ).all();
    const payloads = await env.DB.prepare(
      "SELECT payload_id,status,deleted_at,created_at FROM payload_releases ORDER BY created_at DESC LIMIT 20",
    ).all();
    const snapshot = await env.DB.prepare(
      "SELECT s.snapshot_id,s.fetched_at FROM attestation_current c JOIN attestation_snapshots s ON s.snapshot_id=c.snapshot_id",
    ).first();
    const channels = await env.DB.prepare(
      "SELECT name,payload_id,updated_at FROM channels ORDER BY name LIMIT 200",
    ).all();
    return json({
      data: {
        ...counts,
        recentInitializations: recent.results,
        payloads: payloads.results,
        attestationSnapshot: snapshot,
        channels: channels.results,
        defaultChannel: env.DEFAULT_CHANNEL,
      },
    });
  }
  if (route === "/admin/api/channels" && req.method === "GET") {
    const rows = await env.DB.prepare(
      "SELECT name,payload_id,updated_at FROM channels ORDER BY name LIMIT 200",
    ).all();
    return json({ data: rows.results });
  }
  const kind = route.slice("/admin/api/".length);
  if (req.method === "GET" && listings[kind])
    return json(await list(env, req, kind));
  let m = /^\/admin\/api\/users\/(\d+)$/.exec(route);
  if (m && req.method === "GET") {
    const uid = m[1]!;
    const userRow = await env.DB.prepare(
      "SELECT telegram_user_id,username,display_name,status,auth_epoch,release_channel,created_at,last_seen_at,notes FROM users WHERE telegram_user_id=?",
    )
      .bind(uid)
      .first();
    if (!userRow) fail(404, "NOT_FOUND", "用户不存在");
    const bindings = await env.DB.prepare(
      "SELECT * FROM bindings WHERE telegram_user_id=? ORDER BY created_at DESC",
    )
      .bind(uid)
      .all();
    const installations = await env.DB.prepare(
      "SELECT i.installation_id,i.public_key_sha256,i.security_level,i.assurance,i.device_json,i.created_at FROM installations i WHERE i.installation_id IN(SELECT installation_id FROM bindings WHERE telegram_user_id=?) ORDER BY i.created_at DESC",
    )
      .bind(uid)
      .all();
    const history = await env.DB.prepare(
      "SELECT initialization_id,source_ip,self_wx_id,self_alias,result,payload_id,created_at FROM initializations WHERE telegram_user_id=? ORDER BY created_at DESC LIMIT 50",
    )
      .bind(uid)
      .all();
    return json({
      data: {
        user: userRow,
        bindings: bindings.results,
        installations: installations.results,
        initializations: history.results,
      },
    });
  }
  m = /^\/admin\/api\/installations\/([A-Za-z0-9_-]{1,64})$/.exec(route);
  if (m && req.method === "GET") {
    const installation = await env.DB.prepare(
      "SELECT installation_id,public_key_sha256,root_spki_sha256,security_level,assurance,evidence_sha256,attestation_json,device_json,created_at FROM installations WHERE installation_id=?",
    )
      .bind(m[1]!)
      .first();
    if (!installation) fail(404, "NOT_FOUND", "安装不存在");
    const bindings = await env.DB.prepare(
      "SELECT * FROM bindings WHERE installation_id=? ORDER BY created_at DESC",
    )
      .bind(m[1]!)
      .all();
    const credentials = await env.DB.prepare(
      "SELECT credential_id,binding_id,auth_epoch,status,created_at,revoked_at FROM credentials WHERE installation_id=?",
    )
      .bind(m[1]!)
      .all();
    return json({
      data: {
        installation,
        bindings: bindings.results,
        credentials: credentials.results,
      },
    });
  }
  m = /^\/admin\/api\/users\/(\d+)\/actions$/.exec(route);
  if (m && req.method === "POST") {
    const { value: v } = await body(
      req,
      z.strictObject({
        action: z.enum(["disable", "restore", "reconfirm", "channel"]),
        channel: z
          .string()
          .regex(/^[a-z0-9_-]{1,64}$/)
          .optional(),
      }),
    );
    const uid = m[1]!;
    if (v.action === "channel") {
      if (!v.channel) fail(400, "CHANNEL_REQUIRED", "必须指定发布渠道");
      const channel = await env.DB.prepare(
        "SELECT name FROM channels WHERE name=?",
      )
        .bind(v.channel)
        .first();
      if (!channel) fail(404, "CHANNEL_NOT_FOUND", "发布渠道不存在");
      await audit(
        env,
        req,
        user,
        "channel",
        uid,
        "",
        "json_object('releaseChannel',release_channel)",
        "json_object('releaseChannel',?)",
        [v.channel, uid],
        "FROM users WHERE telegram_user_id=?",
        [
          env.DB.prepare(
            "UPDATE users SET release_channel=? WHERE telegram_user_id=?",
          ).bind(v.channel, uid),
        ],
      );
      return json({ data: { updated: true, releaseChannel: v.channel } });
    }
    const state = v.action === "disable" ? "DISABLED" : "ACTIVE";
    const after =
      v.action === "reconfirm"
        ? "json_object('status',status,'authEpoch',auth_epoch+1)"
        : "json_object('status',?,'authEpoch',auth_epoch)";
    const params = v.action === "reconfirm" ? [uid] : [state, uid];
    await audit(
      env,
      req,
      user,
      v.action,
      uid,
      "",
      "json_object('status',status,'authEpoch',auth_epoch)",
      after,
      params,
      "FROM users WHERE telegram_user_id=?",
      [
        env.DB.prepare(
          v.action === "reconfirm"
            ? "UPDATE users SET auth_epoch=auth_epoch+1 WHERE telegram_user_id=?"
            : "UPDATE users SET status=? WHERE telegram_user_id=?",
        ).bind(...params),
      ],
    );
    return json({ data: { updated: true } });
  }
  m = /^\/admin\/api\/users\/(\d+)\/notes$/.exec(route);
  if (m && req.method === "PATCH") {
    const { value: v } = await body(
      req,
      z.strictObject({
        notes: text(16384, true),
      }),
    );
    await audit(
      env,
      req,
      user,
      "notes",
      m[1]!,
      "",
      "json_object('notes',notes)",
      "json_object('notes',?)",
      [v.notes, m[1]!],
      "FROM users WHERE telegram_user_id=?",
      [
        env.DB.prepare(
          "UPDATE users SET notes=? WHERE telegram_user_id=?",
        ).bind(v.notes, m[1]!),
      ],
    );
    return json({ data: { updated: true } });
  }
  m = /^\/admin\/api\/installations\/([A-Za-z0-9_-]{1,64})\/revoke$/.exec(
    route,
  );
  if (m && req.method === "POST") {
    const { value: v } = await body(req, z.strictObject({}));
    const target = m[1]!;
    await audit(
      env,
      req,
      user,
      "revoke",
      target,
      "",
      "json_object('activeCredentials',(SELECT count(*) FROM credentials WHERE installation_id=installations.installation_id AND status='ACTIVE'))",
      "json_object('activeCredentials',0)",
      [target],
      "FROM installations WHERE installation_id=?",
      [
        env.DB.prepare(
          "UPDATE credentials SET status='REVOKED',revoked_at=? WHERE installation_id=? AND status='ACTIVE'",
        ).bind(Date.now(), target),
        env.DB.prepare(
          "UPDATE load_grants SET revoked=1 WHERE credential_id IN(SELECT credential_id FROM credentials WHERE installation_id=?)",
        ).bind(target),
      ],
    );
    return json({ data: { updated: true } });
  }
  m = /^\/admin\/api\/releases\/([a-z0-9_-]{1,64})\/actions$/.exec(route);
  if (m && req.method === "POST") {
    const { value: v } = await body(
      req,
      z.strictObject({
        action: z.enum(["allow", "stop"]),
      }),
    );
    const state = v.action === "allow" ? "ALLOWED" : "STOPPED",
      target = m[1]!;
    const predicate =
      v.action === "allow"
        ? " AND deleted_at IS NULL AND status IN('READY','ALLOWED','STOPPED') AND EXISTS(SELECT 1 FROM publication_jobs j WHERE j.payload_id=payload_releases.payload_id AND j.state IN('READY','ALLOWED'))"
        : "";
    await audit(
      env,
      req,
      user,
      v.action,
      target,
      "",
      "json_object('status',status)",
      "json_object('status',?)",
      [state, target],
      `FROM payload_releases WHERE payload_id=?${predicate}`,
      [
        env.DB.prepare(
          `UPDATE payload_releases SET status=? WHERE payload_id=?${predicate}`,
        ).bind(state, target),
      ],
    );
    return json({ data: { updated: true } });
  }
  m = /^\/admin\/api\/releases\/([a-z0-9_-]{1,64})$/.exec(route);
  if (m && req.method === "DELETE") {
    await body(req, z.strictObject({}));
    const target = m[1]!;
    const release = await env.DB.prepare(
      "SELECT status,r2_key,deleted_at FROM payload_releases WHERE payload_id=?",
    )
      .bind(target)
      .first<{ status: string; r2_key: string; deleted_at: number | null }>();
    if (!release) fail(404, "NOT_FOUND", "管理目标不存在");
    if (release.deleted_at !== null)
      fail(409, "RELEASE_DELETED", "发行已经删除");
    if (release.status === "UPLOADING")
      fail(409, "RELEASE_UPLOADING", "发行仍在上传，无法删除");
    // Tombstone: the package and its content key are destroyed, the release
    // can never be allowed again, and every record that references it stays.
    const deletable =
      "status IN('READY','ALLOWED','STOPPED','FAILED') AND deleted_at IS NULL";
    await audit(
      env,
      req,
      user,
      "delete",
      target,
      "",
      "json_object('status',status)",
      "json_object('status','DELETED')",
      [target],
      `FROM payload_releases WHERE payload_id=? AND ${deletable}`,
      [
        env.DB.prepare(
          `UPDATE payload_releases SET status='STOPPED',deleted_at=?,content_key_wrapped_json='' WHERE payload_id=? AND ${deletable}`,
        ).bind(Date.now(), target),
        env.DB.prepare(
          "UPDATE load_grants SET revoked=1 WHERE payload_id=? AND EXISTS(SELECT 1 FROM payload_releases WHERE payload_id=? AND deleted_at IS NOT NULL)",
        ).bind(target, target),
      ],
    );
    await env.PAYLOADS.delete(release.r2_key);
    return json({ data: { deleted: true } });
  }
  m = /^\/admin\/api\/channels\/([a-z0-9_-]{1,64})$/.exec(route);
  if (m && req.method === "POST") {
    const { value: v } = await body(req, z.strictObject({ payloadId: id }));
    await audit(
      env,
      req,
      user,
      "channel",
      m[1]!,
      "",
      "json_object('payloadId',(SELECT payload_id FROM channels WHERE name=?))",
      "json_object('payloadId',?)",
      [m[1]!, v.payloadId, v.payloadId],
      "FROM payload_releases WHERE payload_id=? AND status='ALLOWED'",
      [
        env.DB.prepare(
          "INSERT INTO channels SELECT ?,payload_id,? FROM payload_releases WHERE payload_id=? AND status='ALLOWED' ON CONFLICT(name) DO UPDATE SET payload_id=excluded.payload_id,updated_at=excluded.updated_at",
        ).bind(m[1]!, Date.now(), v.payloadId),
      ],
    );
    return json({ data: { updated: true } });
  }
  m = /^\/admin\/api\/exports\/(initializations|audit)$/.exec(route);
  if (m && req.method === "GET") {
    const result = await list(env, req, m[1]!);
    await env.DB.prepare(
      "INSERT INTO admin_audit(subject,email,action,target,reason,before_json,after_json,source_ip,created_at) VALUES(?,?,'export',?,?,'{}',?, ?,?)",
    )
      .bind(
        user.subject,
        user.email,
        m[1]!,
        "管理员过滤导出",
        JSON.stringify({ rows: result.data.length }),
        source(req, env).ip,
        Date.now(),
      )
      .run();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        for (const row of result.data)
          controller.enqueue(utf8(JSON.stringify(row) + "\n"));
        controller.close();
      },
    });
    return new Response(stream, {
      headers: {
        "Content-Type": "application/x-ndjson; charset=utf-8",
        "Cache-Control": "no-store",
        ...(result.nextCursor ? { "X-Next-Cursor": result.nextCursor } : {}),
      },
    });
  }
  fail(404, "NOT_FOUND", "管理接口不存在");
}
export default {
  fetch(req: Request, env: Env) {
    return endpoint(req, () => adminRequest(req, env));
  },
} satisfies ExportedHandler<Env>;
