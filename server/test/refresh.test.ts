import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { build } from "esbuild";
import { Miniflare } from "miniflare";

test("real workerd refresh bulk-inserts 1759 revocations atomically and rolls back a failed bulk snapshot", async () => {
  await build({
    stdin: {
      contents:
        'import {refreshAttestation} from "./src/attestation-pkijs.ts"; export default {async fetch(request,env){await refreshAttestation(env);return Response.json({ok:true});}}',
      resolveDir: process.cwd(),
      sourcefile: "refresh-test.ts",
      loader: "ts",
    },
    bundle: true,
    format: "esm",
    platform: "browser",
    outfile: "dist/refresh-test.js",
  });
  const roots = JSON.parse(
    readFileSync("test/fixtures/google-roots.json", "utf8"),
  );
  const entries: Record<string, { status: string }> = {};
  for (let i = 1; i <= 1759; i++)
    entries[i.toString(16)] = { status: i % 2 ? "REVOKED" : "SUSPENDED" };
  const mf = new Miniflare({
    modules: true,
    scriptPath: "dist/refresh-test.js",
    compatibilityDate: "2026-07-30",
    d1Databases: { DB: "refresh-test-db" },
    outboundService: async (req: Request) => {
      const url = new URL(req.url);
      assert.equal(url.hostname, "android.googleapis.com");
      if (url.pathname === "/attestation/root") return Response.json(roots);
      if (url.pathname === "/attestation/status")
        return Response.json({ entries });
      throw new Error("Unexpected endpoint");
    },
  });
  try {
    const db = await mf.getD1Database("DB");
    await db.exec(readFileSync("migrations/0001_remote_payload.sql", "utf8"));
    await db.exec(
      readFileSync("migrations/0002_unattested_hardware.sql", "utf8"),
    );
    await db.exec(
      readFileSync("migrations/0003_user_release_channels.sql", "utf8"),
    );
    const response = await mf.dispatchFetch("https://refresh.test/");
    assert.equal(response.status, 200, await response.text());
    const first = await db
      .prepare("SELECT snapshot_id FROM attestation_current WHERE singleton=1")
      .first<{ snapshot_id: string }>();
    assert.ok(first);
    assert.equal(
      (await db
        .prepare(
          "SELECT count(*) AS n FROM attestation_roots WHERE snapshot_id=?",
        )
        .bind(first.snapshot_id)
        .first<{ n: number }>())!.n,
      2,
    );
    assert.equal(
      (await db
        .prepare(
          "SELECT count(*) AS n FROM attestation_revocations WHERE snapshot_id=?",
        )
        .bind(first.snapshot_id)
        .first<{ n: number }>())!.n,
      1759,
    );
    await db.exec(
      "CREATE TRIGGER reject_bad_revocation BEFORE INSERT ON attestation_revocations WHEN NEW.serial='dead' BEGIN SELECT RAISE(ABORT,'test rollback'); END;",
    );
    entries.dead = { status: "REVOKED" };
    const failed = await mf.dispatchFetch("https://refresh.test/");
    assert.equal(failed.status, 500);
    assert.equal(
      (await db
        .prepare(
          "SELECT snapshot_id FROM attestation_current WHERE singleton=1",
        )
        .first<{ snapshot_id: string }>())!.snapshot_id,
      first.snapshot_id,
    );
    assert.equal(
      (await db
        .prepare("SELECT count(*) AS n FROM attestation_snapshots")
        .first<{ n: number }>())!.n,
      1,
    );
    assert.equal(
      (await db
        .prepare("SELECT count(*) AS n FROM attestation_roots")
        .first<{ n: number }>())!.n,
      2,
    );
    assert.equal(
      (await db
        .prepare("SELECT count(*) AS n FROM attestation_revocations")
        .first<{ n: number }>())!.n,
      1759,
    );
  } finally {
    await mf.dispose();
  }
});
