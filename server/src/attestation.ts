import { fail } from "./http";
import { LIMITS, type Env } from "./env";
export interface Proof {
  spki: string;
  publicKeySha256: string;
  rootSpkiSha256: string;
  serials: string[];
  securityLevel: "TEE" | "STRONGBOX";
  provisioning: "FACTORY" | "RKP";
  deviceLocked: boolean;
  verifiedBootState: number;
  osVersion: number | null;
  osPatchLevel: number | null;
}
export interface AttestationDowngrade {
  reason:
    | "REVOKED"
    | "SUSPENDED"
    | "ATTESTATION_CERTIFICATE_TIME"
    | "ATTESTATION_LEVEL_MISMATCH";
  serials: string[];
}
export interface AttestationVerification {
  proof: Proof;
  downgrade: AttestationDowngrade | null;
}
export async function currentTrust(
  env: Env,
): Promise<{ snapshotId: string; roots: string[] }> {
  const current = await env.DB.prepare(
    "SELECT s.snapshot_id,s.fetched_at FROM attestation_current c JOIN attestation_snapshots s ON s.snapshot_id=c.snapshot_id WHERE c.singleton=1",
  ).first<{ snapshot_id: string; fetched_at: number }>();
  if (!current || Date.now() - current.fetched_at >= LIMITS.attestationFreshMs)
    fail(503, "ATTESTATION_STATUS_UNAVAILABLE", "证明状态资料不可用", true);
  const roots = await env.DB.prepare(
    "SELECT pem FROM attestation_roots WHERE snapshot_id=?",
  )
    .bind(current.snapshot_id)
    .all<{ pem: string }>();
  return {
    snapshotId: current.snapshot_id,
    roots: roots.results.map((r) => r.pem),
  };
}
export interface RevokedSerials {
  revoked: string[];
  suspended: string[];
}
/** Revocation state of the given chain serials in the current attestation snapshot. */
export async function revokedSerials(
  env: Env,
  serials: string[],
): Promise<RevokedSerials> {
  const rows = await env.DB.prepare(
    "SELECT rev.serial,rev.status FROM attestation_revocations rev JOIN attestation_current ac ON ac.snapshot_id=rev.snapshot_id WHERE ac.singleton=1 AND rev.serial IN (SELECT value FROM json_each(?))",
  )
    .bind(JSON.stringify(serials))
    .all<{ serial: string; status: string }>();
  const out: RevokedSerials = { revoked: [], suspended: [] };
  for (const row of rows.results)
    (row.status === "REVOKED" ? out.revoked : out.suspended).push(row.serial);
  return out;
}
export interface InstallationAttestation {
  assurance: "ATTESTED" | "UNATTESTED_HARDWARE";
  rootTrusted: boolean;
  fresh: boolean;
  revoked: number;
  suspended: number;
}
/**
 * Trust state of a registered installation. A single query keeps the recovery check
 * cheap enough to run on every initialization.
 */
export async function installationAttestation(
  env: Env,
  installationId: string,
): Promise<InstallationAttestation | null> {
  const row = await env.DB.prepare(
    `SELECT i.assurance,i.root_spki_sha256,COALESCE(s.fetched_at,0) AS fetched_at,
      (SELECT count(*) FROM attestation_roots r WHERE r.snapshot_id=s.snapshot_id AND r.spki_sha256=i.root_spki_sha256) AS root_trusted,
      (SELECT count(*) FROM installation_attestation_serials iss JOIN attestation_revocations rev ON rev.snapshot_id=s.snapshot_id AND rev.serial=iss.serial WHERE iss.installation_id=i.installation_id AND rev.status='REVOKED') AS revoked,
      (SELECT count(*) FROM installation_attestation_serials iss JOIN attestation_revocations rev ON rev.snapshot_id=s.snapshot_id AND rev.serial=iss.serial WHERE iss.installation_id=i.installation_id AND rev.status!='REVOKED') AS suspended
    FROM installations i
    LEFT JOIN attestation_current ac ON ac.singleton=1
    LEFT JOIN attestation_snapshots s ON s.snapshot_id=ac.snapshot_id
    WHERE i.installation_id=?`,
  )
    .bind(installationId)
    .first<{
      assurance: "ATTESTED" | "UNATTESTED_HARDWARE";
      root_spki_sha256: string;
      fetched_at: number;
      root_trusted: number;
      revoked: number;
      suspended: number;
    }>();
  if (!row) return null;
  return {
    assurance: row.assurance,
    rootTrusted: row.root_trusted > 0,
    fresh: Date.now() - row.fetched_at < LIMITS.attestationFreshMs,
    revoked: row.revoked,
    suspended: row.suspended,
  };
}
