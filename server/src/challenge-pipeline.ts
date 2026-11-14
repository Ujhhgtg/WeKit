// Pipelined challenges: a signed operation response can carry single-use challenges for
// the client's anticipated next operations. This removes the extra POST /v1/challenges
// round trip per operation without changing the request wire format: the response only
// gains an optional "challenges" array, which older clients ignore.
//
// Two kinds of tickets exist:
// - bound (processNonce present): only usable by the process that received it; used for
//   the report immediately following an initialization.
// - floating (processNonce absent, stored as ''): usable by any process of the same
//   credential; used so the next WeChat process start can initialize without a
//   challenge request. Floating challenges are still single-use, actor/path scoped and
//   short-lived (LIMITS.pipelinedChallengeMs); the request signature is unchanged.
//
// Issuance is opt-in: a client that does not send CHALLENGE_POOL_HEADER receives no
// pipelined challenges, so an old client never causes extra D1 challenge rows.
import { opaqueId, random } from "./crypto";
import type { Auth } from "./auth";
import { LIMITS, type Env } from "./env";
import type { ChallengeTicket } from "./contracts";

/** Header the bootstrap client uses to report how many floating init tickets it holds. */
export const CHALLENGE_POOL_HEADER = "x-wekit-challenge-pool";
/** How many floating tickets a fully provisioned client should hold. */
export const CHALLENGE_POOL_TARGET = 2;

/**
 * Parses the client's floating-ticket hint. Returns -1 when the header is absent (an old
 * client): callers must not issue pipelined challenges in that case, otherwise every
 * report would write rows nobody consumes.
 */
export function challengePoolHint(request: Request): number {
  const raw = request.headers.get(CHALLENGE_POOL_HEADER);
  if (raw === null) return -1;
  if (!/^[0-9]{1,2}$/.test(raw)) return 0;
  return Math.min(Number(raw), 16);
}

export interface ChallengeSpec {
  method: "POST" | "GET";
  path: string;
  ttlMs: number;
  /** Bound tickets are restricted to the issuing process nonce; floating ones are not. */
  bound: boolean;
}

/**
 * Issues the requested challenges in one D1 batch and returns them as wire tickets.
 *
 * Security note: floating tickets are not process-bound, so challenge consumption checks
 * `process_nonce IN (?, '')`. Every other property still applies: the challenge is
 * single-use, bound to the authenticated actor, method, path and grant, short-lived, and
 * the signed request still has to prove possession of the installation key.
 */
export async function issueChallenges(
  env: Env,
  auth: Auth,
  specs: ChallengeSpec[],
): Promise<ChallengeTicket[]> {
  if (specs.length === 0) return [];
  const now = Date.now();
  const tickets: ChallengeTicket[] = [];
  const statements: D1PreparedStatement[] = [];
  for (const spec of specs) {
    const ticket: ChallengeTicket = {
      challengeId: opaqueId(),
      serverNonce: random(32),
      expiresAt: now + spec.ttlMs,
      method: spec.method,
      path: spec.path,
    };
    if (spec.bound) ticket.processNonce = auth.processNonce;
    tickets.push(ticket);
    statements.push(
      env.DB.prepare(
        "INSERT INTO challenges(challenge_id,actor_type,actor_id,method,path,process_nonce,grant_id,nonce,expires_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
      ).bind(
        ticket.challengeId,
        auth.actorType,
        auth.actorId,
        ticket.method,
        ticket.path,
        ticket.processNonce ?? "",
        "",
        ticket.serverNonce,
        ticket.expiresAt,
        now,
      ),
    );
  }
  await env.DB.batch(statements);
  return tickets;
}

/** Challenge for the report that follows a successful initialization in this process. */
function reportChallengeSpec(): ChallengeSpec {
  return {
    method: "POST",
    path: "/v1/initializations/report",
    ttlMs: LIMITS.challengeMs,
    bound: true,
  };
}

/** Extra floating initializations to hand out after a report, based on the client hint. */
function floatingInitializationSpecs(hint: number): ChallengeSpec[] {
  const missing = Math.max(0, CHALLENGE_POOL_TARGET - hint);
  return Array.from({ length: missing }, () => ({
    method: "POST" as const,
    path: "/v1/initializations",
    ttlMs: LIMITS.pipelinedChallengeMs,
    bound: false,
  }));
}

/** Challenges attached to POST /v1/initializations. */
export function initializationResponseChallengeSpecs(
  request: Request,
): ChallengeSpec[] {
  return challengePoolHint(request) >= 0 ? [reportChallengeSpec()] : [];
}

/**
 * Challenges attached to POST /v1/initializations/report: another report challenge for
 * this process plus floating initialization challenges for the next process starts.
 */
export function reportResponseChallengeSpecs(
  request: Request,
): ChallengeSpec[] {
  const hint = challengePoolHint(request);
  if (hint < 0) return [];
  return [reportChallengeSpec(), ...floatingInitializationSpecs(hint)];
}

/**
 * Best-effort issuance: a bookkeeping failure must not fail an operation that already
 * committed, so the caller replies without pipelined challenges and the client falls back
 * to POST /v1/challenges on its next request.
 */
export async function issueChallengesBestEffort(
  env: Env,
  auth: Auth,
  specs: ChallengeSpec[],
): Promise<ChallengeTicket[]> {
  if (specs.length === 0) return [];
  try {
    return await issueChallenges(env, auth, specs);
  } catch {
    console.error(
      JSON.stringify({
        event: "pipelined_challenge_issue_failed",
        path: auth.path,
      }),
    );
    return [];
  }
}
