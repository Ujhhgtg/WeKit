// Binding types come from the current official Workers types. Deployment values
// are supplied by cf; intentionally no domains, account IDs or signing secrets.
export interface Env {
  DB: D1Database;
  PAYLOADS: R2Bucket;
  ASSETS?: Fetcher;
  KEK_KEYS_JSON: string;
  CURRENT_KEK_ID: string;
  BIND_CODE_PEPPER: string;
  RELEASE_PUBLIC_KEYS_JSON: string;
  TELEGRAM_BOT_TOKEN: string;
  TELEGRAM_WEBHOOK_SECRET: string;
  TELEGRAM_BOT_ID: string;
  TELEGRAM_BOT_USERNAME: string;
  ACCESS_TEAM_ORIGIN: string;
  ACCESS_AUDIENCE: string;
  ADMIN_EMAILS_JSON: string;
  ADMIN_ORIGIN: string;
  CSRF_SECRET: string;
  CI_AUDIENCE: string;
  CI_REPOSITORY_ID: string;
  CI_REPOSITORY_OWNER_ID: string;
  CI_WORKFLOW_REF: string;
  CI_REF: string;
  CI_ENVIRONMENT: string;
  CI_SESSION_SECRET: string;
  DEFAULT_CHANNEL: string;
  PSEUDO_IPV4_MODE?: "OFF" | "OVERWRITE";
}
export const LIMITS = {
  json: 32 * 1024,
  registration: 256 * 1024,
  manifest: 64 * 1024,
  apk: 128 * 1024 * 1024,
  part: 8 * 1024 * 1024,
  challengeMs: 120_000,
  bindingMs: 600_000,
  grantMs: 600_000,
  attestationFreshMs: 86_400_000,
} as const;
