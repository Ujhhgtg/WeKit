import assert from "node:assert/strict";
import { test } from "node:test";
import { readFileSync } from "node:fs";
import { build } from "esbuild";
import { Miniflare } from "miniflare";
import { SignJWT } from "jose";
import {
  b64,
  opaqueId,
  random,
  sha,
  unb64,
  utf8,
  wrap,
} from "../src/crypto.ts";
import { hardwareClaimFixture } from "./certificate-fixture.ts";

test("real workerd: registration reuse, Bot approval/claim, publish, authentication replay, revocation, range and renewal", async () => {
  await build({
    entryPoints: ["src/api.ts", "src/bot.ts", "src/admin.ts"],
    bundle: true,
    outdir: "dist",
    format: "esm",
    platform: "browser",
  });
  const jwtKey = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const jwk = {
    ...(await crypto.subtle.exportKey("jwk", jwtKey.publicKey)),
    kid: "test-jwt",
    alg: "RS256",
    use: "sig",
  };
  const installKey = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const spki = b64(await crypto.subtle.exportKey("spki", installKey.publicKey));
  const releaseKey = await crypto.subtle.generateKey(
    { name: "ECDSA", namedCurve: "P-256" },
    true,
    ["sign", "verify"],
  );
  const env = {
    KEK_KEYS_JSON: JSON.stringify({ test: random(32) }),
    CURRENT_KEK_ID: "test",
    BIND_CODE_PEPPER: random(32),
    RELEASE_PUBLIC_KEYS_JSON: JSON.stringify({
      test: b64(await crypto.subtle.exportKey("spki", releaseKey.publicKey)),
    }),
    TELEGRAM_BOT_TOKEN: "123456:test-token-never-sent-to-telegram",
    TELEGRAM_WEBHOOK_SECRET: random(32),
    TELEGRAM_BOT_ID: "123456",
    TELEGRAM_BOT_USERNAME: "test_bot",
    ACCESS_TEAM_ORIGIN: "https://test-team.cloudflareaccess.com",
    ACCESS_AUDIENCE: "test-audience",
    ADMIN_EMAILS_JSON: '["admin@example.com"]',
    ADMIN_ORIGIN: "https://admin.example",
    CSRF_SECRET: random(32),
    CI_AUDIENCE: "test-ci",
    CI_REPOSITORY_ID: "42",
    CI_REPOSITORY_OWNER_ID: "99",
    CI_WORKFLOW_REF: "owner/repo/.github/workflows/ci.yml@refs/heads/dev",
    CI_REF: "refs/heads/dev",
    CI_ENVIRONMENT: "test",
    CI_SESSION_SECRET: random(32),
    DEFAULT_CHANNEL: "stable",
  };
  const outboundService = async (req: Request) => {
    const host = new URL(req.url).hostname;
    if (host === "api.telegram.org")
      return Response.json({ ok: true, result: {} });
    if (
      host === "token.actions.githubusercontent.com" ||
      host === "test-team.cloudflareaccess.com"
    )
      return Response.json({ keys: [jwk] });
    throw new Error("Unexpected external request in isolated test");
  };
  const mf = new Miniflare({
    workers: ["api", "bot", "admin"].map((name) => ({
      name,
      modules: true,
      scriptPath: `dist/${name}.js`,
      routes: [`${name === "api" ? "auth" : name}.example/*`],
      compatibilityDate: "2026-07-30",
      bindings: env,
      d1Databases: { DB: "test-db" },
      r2Buckets: { PAYLOADS: "test-r2" },
      outboundService,
    })),
  });
  try {
    const db = await mf.getD1Database("DB", "api");
    await db.exec(readFileSync("migrations/0001_remote_payload.sql", "utf8"));
    await db.exec(
      readFileSync("migrations/0002_unattested_hardware.sql", "utf8"),
    );
    await db.exec(
      readFileSync("migrations/0003_user_release_channels.sql", "utf8"),
    );
    await db.exec(readFileSync("migrations/0004_release_deletion.sql", "utf8"));
    const api = await mf.getWorker("api"),
      bot = await mf.getWorker("bot"),
      admin = await mf.getWorker("admin");
    async function send(
      _worker: any,
      url: string,
      method = "GET",
      value?: unknown,
      headers: Record<string, string> = {},
    ) {
      return mf.dispatchFetch(url, {
        method,
        headers: {
          "cf-connecting-ip": "198.51.100.1",
          ...(value !== undefined
            ? { "content-type": "application/json" }
            : {}),
          ...headers,
        },
        ...(value !== undefined ? { body: JSON.stringify(value) } : {}),
      });
    }
    async function ok(response: Response, status = 200) {
      const value = await response.json();
      assert.equal(response.status, status, JSON.stringify(value));
      return value as any;
    }
    const now = Date.now(),
      installationId = opaqueId(),
      rootHash =
        "feb2ea7551ee316ed4bb443c8293b884dbfdea40b603ee3e4f4a897e4580fbae",
      roots = JSON.parse(
        readFileSync("test/fixtures/google-roots.json", "utf8"),
      );
    await db.batch([
      db
        .prepare("INSERT INTO attestation_snapshots VALUES(?,?,?,?)")
        .bind("snapshot", now, "roots-hash", "status-hash"),
      db
        .prepare("INSERT INTO attestation_roots VALUES(?,?,?)")
        .bind("snapshot", rootHash, roots[0]),
      db
        .prepare("INSERT INTO attestation_current VALUES(1,?)")
        .bind("snapshot"),
      // Seed a previously verified installation to test REGISTERED_KEY. This
      // explicitly does not claim the ephemeral software test key is hardware.
      db
        .prepare("INSERT INTO installations VALUES(?,?,?,?,?,?,?,?,?,?,?)")
        .bind(
          installationId,
          await sha(unb64(spki)),
          spki,
          rootHash,
          "TEE",
          "test-evidence",
          "test-hash",
          "{}",
          "{}",
          now,
          "ATTESTED",
        ),
      db
        .prepare("INSERT INTO installation_attestation_serials VALUES(?,?)")
        .bind(installationId, "test-serial"),
    ]);
    const token = random(32),
      registrationNonce = random(32),
      tokenDigest = await sha(unb64(token));
    const context = await ok(
      await send(
        api,
        "https://auth.example/v1/registration/challenges",
        "POST",
        {
          mode: "REGISTERED_KEY",
          tokenSha256: tokenDigest,
          registrationNonce,
          existingInstallationId: installationId,
        },
      ),
    );
    assert.equal(context.attestationChallenge, null);
    const sameContext = await ok(
      await send(
        api,
        "https://auth.example/v1/registration/challenges",
        "POST",
        {
          mode: "REGISTERED_KEY",
          tokenSha256: tokenDigest,
          registrationNonce,
          existingInstallationId: installationId,
        },
      ),
    );
    assert.equal(context.registrationId, sameContext.registrationId);
    async function signed(
      route: string,
      value: Record<string, unknown>,
      options: {
        nonce?: string;
        registration?: boolean;
        grant?: string;
        range?: string;
        requestId?: string;
        method?: string;
      } = {},
    ) {
      const processNonce = options.nonce ?? registrationNonce,
        method = options.method ?? "POST",
        registration = options.registration ?? true,
        requestId = options.requestId ?? opaqueId();
      const payload =
        method === "GET"
          ? undefined
          : {
              ...value,
              requestId,
              ...(registration
                ? {
                    registrationId: context.registrationId,
                    registrationNonce: processNonce,
                  }
                : { installationId, processNonce }),
            };
      const challenge = await ok(
        await send(
          api,
          "https://auth.example/v1/challenges",
          "POST",
          {
            ...(registration
              ? { registrationId: context.registrationId }
              : { installationId }),
            method,
            path: route,
            processNonce,
            ...(options.grant ? { grantId: options.grant } : {}),
          },
          { authorization: `Bearer ${token}` },
        ),
      );
      const raw = payload ? utf8(JSON.stringify(payload)) : new Uint8Array();
      const canonical = [
        "WEKIT-REQUEST-V1",
        method,
        route,
        await sha(raw),
        tokenDigest,
        challenge.challengeId,
        challenge.serverNonce,
        requestId,
        processNonce,
        options.range ?? "",
        options.grant ?? "",
        "",
      ].join("\n");
      const signature = b64(
        await crypto.subtle.sign(
          { name: "ECDSA", hash: "SHA-256" },
          installKey.privateKey,
          utf8(canonical),
        ),
      );
      const headers = {
        authorization: `Bearer ${token}`,
        "x-wekit-request-id": requestId,
        "x-wekit-challenge-id": challenge.challengeId,
        "x-wekit-process-nonce": processNonce,
        "x-wekit-signature": signature,
        ...(registration
          ? { "x-wekit-registration-id": context.registrationId }
          : { "x-wekit-installation-id": installationId }),
        ...(options.grant ? { "x-wekit-grant-id": options.grant } : {}),
        ...(options.range ? { range: options.range } : {}),
      };
      return {
        response: await send(
          api,
          "https://auth.example" + route,
          method,
          payload,
          headers,
        ),
        payload,
        headers,
        route,
        method,
      };
    }
    const registration = await ok(
      (await signed("/v1/registrations", { deviceInfo: { model: "test" } }))
        .response,
    );
    assert.equal(registration.state, "PENDING");
    assert.match(registration.bindingCode, /^[0-9A-Z]{12}$/);
    const recoveredRegistration = await ok(
      (await signed("/v1/registrations", { deviceInfo: { model: "test" } }))
        .response,
    );
    assert.equal(
      recoveredRegistration.installationId,
      registration.installationId,
    );
    assert.equal(
      recoveredRegistration.bindingSessionId,
      registration.bindingSessionId,
    );
    assert.equal(recoveredRegistration.bindingCode, registration.bindingCode);
    await ok(
      await send(
        bot,
        "https://bot.example/webhook",
        "POST",
        {
          update_id: 1,
          message: {
            from: { id: 1001, is_bot: false, first_name: "Test" },
            chat: { id: 1001, type: "private" },
            text: registration.bindingCode,
          },
        },
        { "x-telegram-bot-api-secret-token": env.TELEGRAM_WEBHOOK_SECRET },
      ),
    );
    await ok(
      await send(
        bot,
        "https://bot.example/webhook",
        "POST",
        {
          update_id: 3,
          message: {
            from: { id: 1002, is_bot: false, first_name: "Other" },
            chat: { id: 1002, type: "private" },
            text: registration.bindingCode,
          },
        },
        { "x-telegram-bot-api-secret-token": env.TELEGRAM_WEBHOOK_SECRET },
      ),
    );
    assert.equal(
      (await db
        .prepare(
          "SELECT candidate_user_id FROM enrollment_sessions WHERE binding_session_id=?",
        )
        .bind(registration.bindingSessionId)
        .first<{ candidate_user_id: string }>())!.candidate_user_id,
      "1001",
    );
    const confirmation = await db
      .prepare(
        "SELECT confirm_nonce FROM enrollment_sessions WHERE binding_session_id=?",
      )
      .bind(registration.bindingSessionId)
      .first<{ confirm_nonce: string }>();
    const update = {
      update_id: 2,
      callback_query: {
        id: "callback-test",
        from: { id: 1001, is_bot: false, first_name: "Test" },
        message: { chat: { id: 1001, type: "private" } },
        data: `bind:${confirmation!.confirm_nonce}`,
      },
    };
    await ok(
      await send(bot, "https://bot.example/webhook", "POST", update, {
        "x-telegram-bot-api-secret-token": env.TELEGRAM_WEBHOOK_SECRET,
      }),
    );
    await ok(
      await send(bot, "https://bot.example/webhook", "POST", update, {
        "x-telegram-bot-api-secret-token": env.TELEGRAM_WEBHOOK_SECRET,
      }),
    );
    const claim = await ok(
      (
        await signed("/v1/bindings/claim", {
          bindingSessionId: registration.bindingSessionId,
        })
      ).response,
    );
    assert.equal(claim.state, "CLAIMED");
    assert.equal(
      (await db
        .prepare("SELECT count(*) AS n FROM credentials")
        .first<{ n: number }>())!.n,
      1,
    );
    const ciClaims = {
      repository_id: "42",
      repository_owner_id: "99",
      repository_visibility: "private",
      workflow_ref: env.CI_WORKFLOW_REF,
      ref: env.CI_REF,
      environment: "test",
      sha: "0".repeat(40),
      run_id: "1",
    };
    const ciJwt = await new SignJWT(ciClaims)
      .setProtectedHeader({ alg: "RS256", kid: "test-jwt" })
      .setIssuer("https://token.actions.githubusercontent.com")
      .setAudience("test-ci")
      .setIssuedAt()
      .setExpirationTime("5m")
      .sign(jwtKey.privateKey);
    for (const overrides of [
      { repository_visibility: "public" },
      {
        job_workflow_ref:
          "attacker/repo/.github/workflows/publish.yml@refs/heads/main",
        job_workflow_sha: "f".repeat(40),
      },
      { job_workflow_sha: "f".repeat(40) },
    ]) {
      const deniedJwt = await new SignJWT({ ...ciClaims, ...overrides })
        .setProtectedHeader({ alg: "RS256", kid: "test-jwt" })
        .setIssuer("https://token.actions.githubusercontent.com")
        .setAudience("test-ci")
        .setIssuedAt()
        .setExpirationTime("5m")
        .sign(jwtKey.privateKey);
      const denied = await ok(
        await send(
          api,
          "https://auth.example/ci/sessions",
          "POST",
          {},
          { authorization: `Bearer ${deniedJwt}` },
        ),
        403,
      );
      assert.equal(denied.error.code, "CI_POLICY");
    }
    const ciSession = await ok(
      await send(
        api,
        "https://auth.example/ci/sessions",
        "POST",
        {},
        { authorization: `Bearer ${ciJwt}` },
      ),
    );
    const ciHeaders = { authorization: `Bearer ${ciSession.token}` };
    const clear = utf8("test APK content"),
      contentKey = random(32),
      iv = crypto.getRandomValues(new Uint8Array(12));
    const key = await crypto.subtle.importKey(
      "raw",
      unb64(contentKey),
      "AES-GCM",
      false,
      ["encrypt"],
    );
    const cipher = new Uint8Array(
      await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, clear),
    );
    const manifest = {
      payloadId: "test-payload",
      businessVersion: "test",
      sourceCommit: "0".repeat(40),
      entryClass: "test.Payload",
      apkSha256: await sha(clear),
      apkSize: clear.length,
      cipherSha256: await sha(cipher),
      cipherSize: cipher.length,
      encryption: {
        algorithm: "AES-256-GCM",
        chunkSize: 1048576,
        noncePrefix: random(8),
        chunkCount: 1,
      },
      createdAt: new Date(now).toISOString(),
    };
    const manifestBytes = utf8(JSON.stringify(manifest)),
      prefix = utf8("WEKIT-RELEASE-V1\0"),
      signedManifest = new Uint8Array(prefix.length + manifestBytes.length);
    signedManifest.set(prefix);
    signedManifest.set(manifestBytes, prefix.length);
    const envelope = {
      keyId: "test",
      manifest: b64(manifestBytes),
      signature: b64(
        await crypto.subtle.sign(
          { name: "ECDSA", hash: "SHA-256" },
          releaseKey.privateKey,
          signedManifest,
        ),
      ),
    };
    const publication = {
      jobId: "test-job",
      payloadId: manifest.payloadId,
      manifest: envelope,
      contentKey,
    };
    await ok(
      await send(
        api,
        "https://auth.example/ci/publications",
        "POST",
        publication,
        ciHeaders,
      ),
    );
    await ok(
      await send(
        api,
        "https://auth.example/ci/publications",
        "POST",
        { ...publication, contentKey: undefined },
        ciHeaders,
      ),
    );
    const upload = await api.fetch(
      "https://auth.example/ci/publications/test-job/parts/1",
      {
        method: "PUT",
        headers: { ...ciHeaders, "content-type": "application/octet-stream" },
        body: cipher,
      },
    );
    const part = await ok(upload);
    assert.equal(typeof part.etag, "string");
    await ok(
      await send(
        api,
        "https://auth.example/ci/publications/test-job/complete",
        "POST",
        { parts: [{ partNumber: 1, etag: part.etag }] },
        ciHeaders,
      ),
    );
    await ok(
      await send(
        api,
        "https://auth.example/ci/publications/test-job/activate",
        "POST",
        { channel: "stable" },
        ciHeaders,
      ),
    );
    const processNonce = random(32);
    const initBody = {
      processName: "com.tencent.mm",
      hostPackage: "com.tencent.mm",
      hostVersionCode: "1",
      hostVersionName: "8.0.79",
      sdkInt: 35,
      processAbi: "arm64-v8a",
      bootstrapVersion: "test",
      selfWxId: "wxid_test",
      selfAlias: null,
      identityReadState: "ALIAS_UNAVAILABLE",
      requestedPayloadId: null,
    };
    const requestId = opaqueId();
    const init = await signed("/v1/initializations", initBody, {
      registration: false,
      nonce: processNonce,
      requestId,
    });
    const initialized = await ok(init.response);
    assert.equal(initialized.contentKey, contentKey);
    const replay = await send(
      api,
      "https://auth.example" + init.route,
      "POST",
      init.payload,
      init.headers,
    );
    assert.equal(replay.status, 401);
    const retry = await ok(
      (
        await signed("/v1/initializations", initBody, {
          registration: false,
          nonce: processNonce,
          requestId,
        })
      ).response,
    );
    assert.equal(retry.initializationId, initialized.initializationId);
    assert.equal(retry.grantId, initialized.grantId);
    assert.equal(
      (await db
        .prepare("SELECT count(*) AS n FROM initializations")
        .first<{ n: number }>())!.n,
      1,
    );
    const conflict = await signed(
      "/v1/initializations",
      { ...initBody, selfAlias: "different-alias" },
      { registration: false, nonce: processNonce, requestId },
    );
    assert.equal(conflict.response.status, 409);
    assert.equal(
      (await db
        .prepare(
          "SELECT self_alias FROM initializations WHERE initialization_id=?",
        )
        .bind(initialized.initializationId)
        .first<{ self_alias: string | null }>())!.self_alias,
      null,
    );
    const download = await signed(
      "/v1/payloads/test-payload/content",
      {},
      {
        method: "GET",
        registration: false,
        nonce: processNonce,
        grant: initialized.grantId,
        range: "bytes=0-3",
      },
    );
    assert.equal(download.response.status, 206);
    assert.deepEqual(
      new Uint8Array(await download.response.arrayBuffer()),
      cipher.slice(0, 4),
    );
    await db
      .prepare("UPDATE load_grants SET expires_at=0 WHERE grant_id=?")
      .bind(initialized.grantId)
      .run();
    const renewal = await ok(
      (
        await signed(
          "/v1/load-grants/renew",
          {
            initializationId: initialized.initializationId,
            grantId: initialized.grantId,
            payloadId: "test-payload",
            apkSha256: manifest.apkSha256,
          },
          { registration: false, nonce: processNonce },
        )
      ).response,
    );
    assert.notEqual(renewal.grantId, initialized.grantId);
    const reportRequestId = opaqueId();
    const reportBody = {
      initializationId: initialized.initializationId,
      stage: "entry",
      result: "LOADED",
    };
    assert.equal(
      (await db
        .prepare("SELECT count(*) AS n FROM initializations")
        .first<{ n: number }>())!.n,
      1,
    );
    await ok(
      (
        await signed("/v1/initializations/report", reportBody, {
          registration: false,
          nonce: processNonce,
          requestId: reportRequestId,
        })
      ).response,
    );
    await db.prepare("DELETE FROM channels WHERE name='stable'").run();
    const noDefault = await signed("/v1/initializations", initBody, {
      registration: false,
      nonce: processNonce,
    });
    const noDefaultError = await ok(noDefault.response, 403);
    assert.equal(noDefaultError.error.code, "PAYLOAD_UNAVAILABLE");
    await db
      .prepare("INSERT INTO channels VALUES(?,?,?)")
      .bind("stable", "test-payload", Date.now())
      .run();
    const access = await new SignJWT({ email: "admin@example.com" })
      .setProtectedHeader({ alg: "RS256", kid: "test-jwt" })
      .setIssuer(env.ACCESS_TEAM_ORIGIN)
      .setAudience(env.ACCESS_AUDIENCE)
      .setSubject("admin-test")
      .setIssuedAt()
      .setExpirationTime("5m")
      .sign(jwtKey.privateKey);
    const accessHeaders = { "cf-access-jwt-assertion": access };
    const adminSession = await ok(
      await send(
        admin,
        "https://admin.example/admin/api/session",
        "GET",
        undefined,
        accessHeaders,
      ),
    );
    await ok(
      await send(
        api,
        "https://auth.example/ci/publications/test-job/activate",
        "POST",
        { channel: "beta" },
        ciHeaders,
      ),
    );
    await ok(
      await send(
        admin,
        "https://admin.example/admin/api/users/1001/actions",
        "POST",
        { action: "channel", channel: "beta" },
        {
          ...accessHeaders,
          origin: env.ADMIN_ORIGIN,
          "x-wekit-csrf-token": adminSession.data.csrfToken,
        },
      ),
    );
    assert.equal(
      (await db
        .prepare(
          "SELECT release_channel FROM users WHERE telegram_user_id='1001'",
        )
        .first<{ release_channel: string }>())!.release_channel,
      "beta",
    );
    await ok(
      await send(
        admin,
        "https://admin.example/admin/api/users/1001/actions",
        "POST",
        { action: "disable" },
        {
          ...accessHeaders,
          origin: env.ADMIN_ORIGIN,
          "x-wekit-csrf-token": adminSession.data.csrfToken,
        },
      ),
    );
    const revoked = await signed("/v1/initializations", initBody, {
      registration: false,
      nonce: processNonce,
      requestId,
    });
    assert.equal(revoked.response.status, 403);
    assert.equal(
      (await db
        .prepare("SELECT count(*) AS n FROM initializations")
        .first<{ n: number }>())!.n,
      2,
    );
    const revokedReport = await signed(
      "/v1/initializations/report",
      reportBody,
      { registration: false, nonce: processNonce, requestId: reportRequestId },
    );
    assert.equal(revokedReport.response.status, 403);
    assert.equal(
      (await db
        .prepare("SELECT count(*) AS n FROM initialization_reports")
        .first<{ n: number }>())!.n,
      1,
    );
    const forbidden = await send(
      admin,
      "https://admin.example/admin/api/overview",
    );
    assert.equal(forbidden.status, 403);
    const audit = await ok(
      await send(
        admin,
        "https://admin.example/admin/api/audit",
        "GET",
        undefined,
        accessHeaders,
      ),
    );
    assert.equal(audit.data[0].action, "disable");
    assert.deepEqual(JSON.parse(audit.data[0].before_json), {
      status: "ACTIVE",
      authEpoch: 1,
    });
    assert.deepEqual(JSON.parse(audit.data[0].after_json), {
      status: "DISABLED",
      authEpoch: 1,
    });
    const adminWrite = {
      ...accessHeaders,
      origin: env.ADMIN_ORIGIN,
      "x-wekit-csrf-token": adminSession.data.csrfToken,
    };
    await db
      .prepare(
        "INSERT INTO payload_releases(payload_id,manifest_json,apk_sha256,apk_size,cipher_sha256,cipher_size,r2_key,content_key_wrapped_json,status,created_at) VALUES('doomed-release','{}',?,1,?,1,'doomed/key','{}','ALLOWED',?)",
      )
      .bind("0".repeat(64), "1".repeat(64), Date.now())
      .run();
    await db
      .prepare("INSERT INTO channels VALUES('doomed','doomed-release',?)")
      .bind(Date.now())
      .run();
    await ok(
      await send(
        admin,
        "https://admin.example/admin/api/releases/doomed-release",
        "DELETE",
        {},
        adminWrite,
      ),
    );
    const tombstone = await db
      .prepare(
        "SELECT status,deleted_at,content_key_wrapped_json AS key FROM payload_releases WHERE payload_id='doomed-release'",
      )
      .first<{ status: string; deleted_at: number | null; key: string }>();
    assert.equal(tombstone!.status, "STOPPED");
    assert.notEqual(tombstone!.deleted_at, null);
    assert.equal(tombstone!.key, "");
    assert.equal(
      (await db
        .prepare("SELECT count(*) AS n FROM channels WHERE name='doomed'")
        .first<{ n: number }>())!.n,
      1,
    );
    await ok(
      await send(
        admin,
        "https://admin.example/admin/api/releases/doomed-release",
        "DELETE",
        {},
        adminWrite,
      ),
      409,
    );
    await ok(
      await send(
        admin,
        "https://admin.example/admin/api/releases/doomed-release/actions",
        "POST",
        { action: "allow" },
        adminWrite,
      ),
      404,
    );
    const newToken = random(32),
      newNonce = random(32),
      newKey = await crypto.subtle.generateKey(
        { name: "ECDSA", namedCurve: "P-256" },
        true,
        ["sign", "verify"],
      );
    const prepared = await ok(
      await send(
        api,
        "https://auth.example/v1/registration/challenges",
        "POST",
        {
          mode: "NEW_KEY",
          tokenSha256: await sha(unb64(newToken)),
          registrationNonce: newNonce,
        },
      ),
    );
    const hardware = await hardwareClaimFixture(
      newKey.publicKey,
      prepared.attestationChallenge,
    );
    await db
      .prepare("INSERT INTO attestation_roots VALUES(?,?,?)")
      .bind("snapshot", await sha(unb64(hardware.rootSpki)), hardware.root)
      .run();
    const ch = await ok(
      await send(
        api,
        "https://auth.example/v1/challenges",
        "POST",
        {
          registrationId: prepared.registrationId,
          method: "POST",
          path: "/v1/registrations",
          processNonce: newNonce,
        },
        { authorization: `Bearer ${newToken}` },
      ),
    );
    const newRequestId = opaqueId(),
      registrationBody = {
        requestId: newRequestId,
        registrationId: prepared.registrationId,
        registrationNonce: newNonce,
        spki: b64(await crypto.subtle.exportKey("spki", newKey.publicKey)),
        certificateChain: hardware.chain,
        deviceInfo: { sdkInt: 35 },
      };
    const newMessage = [
      "WEKIT-REQUEST-V1",
      "POST",
      "/v1/registrations",
      await sha(utf8(JSON.stringify(registrationBody))),
      await sha(unb64(newToken)),
      ch.challengeId,
      ch.serverNonce,
      newRequestId,
      newNonce,
      "",
      "",
      "",
    ].join("\n");
    const rawSignature = new Uint8Array(
      await crypto.subtle.sign(
        { name: "ECDSA", hash: "SHA-256" },
        newKey.privateKey,
        utf8(newMessage),
      ),
    );
    const newHeaders = {
      authorization: `Bearer ${newToken}`,
      "x-wekit-registration-id": prepared.registrationId,
      "x-wekit-request-id": newRequestId,
      "x-wekit-process-nonce": newNonce,
      "x-wekit-challenge-id": ch.challengeId,
      "x-wekit-signature": b64(rawSignature),
    };
    const wrong = rawSignature.slice();
    wrong[0] ^= 1;
    assert.equal(
      (
        await send(
          api,
          "https://auth.example/v1/registrations",
          "POST",
          registrationBody,
          { ...newHeaders, "x-wekit-signature": b64(wrong) },
        )
      ).status,
      401,
    );
    const created = await ok(
      await send(
        api,
        "https://auth.example/v1/registrations",
        "POST",
        registrationBody,
        newHeaders,
      ),
    );
    assert.equal(created.state, "PENDING");
    assert.notEqual(created.installationId, installationId);
    const evidence = await db
      .prepare(
        "SELECT evidence_sha256 FROM installations WHERE installation_id=?",
      )
      .bind(created.installationId)
      .first<{ evidence_sha256: string }>();
    const bucket = await mf.getR2Bucket("PAYLOADS", "api");
    const saved = await bucket.get(
      `attestation/${created.installationId}.json`,
    );
    assert.equal(
      evidence!.evidence_sha256,
      await sha(new Uint8Array(await saved!.arrayBuffer())),
    );
    assert.equal(
      (await db
        .prepare(
          "SELECT count(*) AS n FROM request_attempts WHERE current_result='AUTHORIZATION_DENIED'",
        )
        .first<{ n: number }>())!.n,
      2,
    );
    await db.prepare("UPDATE attestation_snapshots SET fetched_at=0").run();
    const stale = await signed("/v1/initializations", initBody, {
      registration: false,
      nonce: processNonce,
    });
    assert.equal(stale.response.status, 503);

    // An unlocked device may have a usable P-256 key but no Google
    // attestation chain after its TEE/RKP state is invalidated. The server
    // accepts that path only when the client explicitly selects
    // UNATTESTED_KEY + hardwareKey=true; Telegram approval and normal
    // signed-request authorization remain required.
    const lowKey = await crypto.subtle.generateKey(
        { name: "ECDSA", namedCurve: "P-256" },
        true,
        ["sign", "verify"],
      ),
      lowToken = random(32),
      lowNonce = random(32),
      lowDigest = await sha(unb64(lowToken)),
      lowSpki = b64(await crypto.subtle.exportKey("spki", lowKey.publicKey));
    const lowContext = await ok(
      await send(
        api,
        "https://auth.example/v1/registration/challenges",
        "POST",
        {
          mode: "UNATTESTED_KEY",
          tokenSha256: lowDigest,
          registrationNonce: lowNonce,
        },
      ),
    );
    assert.equal(lowContext.attestationChallenge, null);
    async function lowSigned(
      route: string,
      value: Record<string, unknown>,
      requestId = opaqueId(),
    ) {
      const payload = {
        ...value,
        requestId,
        registrationId: lowContext.registrationId,
        registrationNonce: lowNonce,
      };
      const challenge = await ok(
        await send(
          api,
          "https://auth.example/v1/challenges",
          "POST",
          {
            registrationId: lowContext.registrationId,
            method: "POST",
            path: route,
            processNonce: lowNonce,
          },
          { authorization: `Bearer ${lowToken}` },
        ),
      );
      const raw = utf8(JSON.stringify(payload));
      const signature = b64(
        await crypto.subtle.sign(
          { name: "ECDSA", hash: "SHA-256" },
          lowKey.privateKey,
          utf8(
            [
              "WEKIT-REQUEST-V1",
              "POST",
              route,
              await sha(raw),
              lowDigest,
              challenge.challengeId,
              challenge.serverNonce,
              requestId,
              lowNonce,
              "",
              "",
              "",
            ].join("\n"),
          ),
        ),
      );
      return send(api, "https://auth.example" + route, "POST", payload, {
        authorization: `Bearer ${lowToken}`,
        "x-wekit-registration-id": lowContext.registrationId,
        "x-wekit-request-id": requestId,
        "x-wekit-process-nonce": lowNonce,
        "x-wekit-challenge-id": challenge.challengeId,
        "x-wekit-signature": signature,
      });
    }
    const lowRegistration = await ok(
      await lowSigned("/v1/registrations", {
        spki: lowSpki,
        hardwareKey: true,
        deviceInfo: { model: "unlocked-test" },
      }),
    );
    assert.equal(lowRegistration.state, "PENDING");
    const lowRecovered = await ok(
      await lowSigned("/v1/registrations", {
        spki: lowSpki,
        hardwareKey: true,
        deviceInfo: { model: "unlocked-test" },
      }),
    );
    assert.equal(
      lowRecovered.bindingSessionId,
      lowRegistration.bindingSessionId,
    );
    assert.equal(lowRecovered.bindingCode, lowRegistration.bindingCode);
    assert.equal(
      (await db
        .prepare(
          "SELECT assurance,security_level,root_spki_sha256 FROM installations WHERE installation_id=?",
        )
        .bind(lowRegistration.installationId)
        .first<{
          assurance: string;
          security_level: string;
          root_spki_sha256: string;
        }>())!.assurance,
      "UNATTESTED_HARDWARE",
    );
    const lowInstall = await db
      .prepare("SELECT assurance FROM installations WHERE installation_id=?")
      .bind(lowRegistration.installationId)
      .first<{ assurance: string }>();
    assert.equal(lowInstall!.assurance, "UNATTESTED_HARDWARE");
    await ok(
      await send(
        bot,
        "https://bot.example/webhook",
        "POST",
        {
          update_id: 100,
          message: {
            from: { id: 2001, is_bot: false, first_name: "Low" },
            chat: { id: 2001, type: "private" },
            text: lowRegistration.bindingCode,
          },
        },
        { "x-telegram-bot-api-secret-token": env.TELEGRAM_WEBHOOK_SECRET },
      ),
    );
    const lowConfirmation = await db
      .prepare(
        "SELECT confirm_nonce FROM enrollment_sessions WHERE binding_session_id=?",
      )
      .bind(lowRegistration.bindingSessionId)
      .first<{ confirm_nonce: string }>();
    await ok(
      await send(
        bot,
        "https://bot.example/webhook",
        "POST",
        {
          update_id: 101,
          callback_query: {
            id: "low-callback",
            from: { id: 2001, is_bot: false, first_name: "Low" },
            message: { chat: { id: 2001, type: "private" } },
            data: `bind:${lowConfirmation!.confirm_nonce}`,
          },
        },
        { "x-telegram-bot-api-secret-token": env.TELEGRAM_WEBHOOK_SECRET },
      ),
    );
    const lowClaim = await lowSigned("/v1/bindings/claim", {
      bindingSessionId: lowRegistration.bindingSessionId,
    });
    assert.equal((await ok(lowClaim)).state, "CLAIMED");
  } finally {
    await mf.dispose();
  }
});
