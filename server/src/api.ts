import { endpoint, fail, path } from "./http";
import { issueChallenge } from "./auth";
import { prepareRegistration, register, binding } from "./enrollment";
import { initialize, download, renew, report, unbind } from "./initialization";
import type { Env } from "./env";
export async function apiRequest(req: Request, env: Env): Promise<Response> {
  if (new URL(req.url).protocol !== "https:")
    fail(400, "HTTPS_REQUIRED", "接口只接受 HTTPS");
  const route = path(req);
  if (route.startsWith("/ci/")) {
    // jose is only needed by the CI publishing routes; load it lazily.
    const { publishing } = await import("./publishing");
    return publishing(env, req, route);
  }
  if (req.method === "POST") {
    switch (route) {
      case "/v1/registration/challenges":
        return prepareRegistration(env, req);
      case "/v1/registrations":
        return register(env, req);
      case "/v1/challenges":
        return issueChallenge(env, req);
      case "/v1/bindings/status":
        return binding(env, req, "status");
      case "/v1/bindings/claim":
        return binding(env, req, "claim");
      case "/v1/bindings/cancel":
        return binding(env, req, "cancel");
      case "/v1/initializations":
        return initialize(env, req);
      case "/v1/load-grants/renew":
        return renew(env, req);
      case "/v1/initializations/report":
        return report(env, req);
      case "/v1/installations/unbind":
        return unbind(env, req);
    }
  }
  const match = /^\/v1\/payloads\/([a-z0-9_-]{1,64})\/content$/.exec(route);
  if (req.method === "GET" && match) return download(env, req, match[1]!);
  fail(404, "NOT_FOUND", "接口不存在");
}
export default {
  fetch(req: Request, env: Env) {
    return endpoint(req, () => apiRequest(req, env), env);
  },
  scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext) {
    ctx.waitUntil(
      (async () => {
        // pkijs/asn1js are only needed by the attestation refresh; load lazily.
        const { refreshAttestation } = await import("./attestation-pkijs");
        await refreshAttestation(env);
        await env.DB.prepare(
          "UPDATE enrollment_sessions SET state='EXPIRED',code_hmac=NULL,code_wrapped_json=NULL,confirm_nonce=NULL WHERE state IN('PENDING','APPROVED') AND expires_at<=?",
        )
          .bind(Date.now())
          .run();
      })(),
    );
  },
} satisfies ExportedHandler<Env>;
