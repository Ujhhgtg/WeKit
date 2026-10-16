import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { bindings, defineConfig, triggers, type WorkerConfig } from 'cf/config';

export type WorkerRole = 'api' | 'bot' | 'admin';

function required(name: string): string {
    const value = process.env[name];
    if (!value) throw new Error(`Missing required deployment input: ${name}`);
    return value;
}

/** Public deployment inputs only; secrets are declared by name and supplied by cf. */
export function workerConfig(role: WorkerRole) {
    const bootstrap = JSON.parse(readFileSync(
        new URL('../../bootstrap/src/main/assets/remote-loader.json', import.meta.url), 'utf8',
    )) as { releasePublicKeys: Record<string, string> };
    const env: NonNullable<WorkerConfig['env']> = {
        DB: bindings.d1({ id: required('WEKIT_D1_DATABASE_ID'), name: 'wekit-remote-prod' }),
        PAYLOADS: bindings.r2({ name: 'wekit-remote-payloads-prod' }),
        DEFAULT_CHANNEL: bindings.text('stable'),
        PSEUDO_IPV4_MODE: bindings.text('OFF'),
    };
    if (role === 'api') {
        Object.assign(env, {
            KEK_KEYS_JSON: bindings.secret(),
            CURRENT_KEK_ID: bindings.text(required('WEKIT_CURRENT_KEK_ID')),
            BIND_CODE_PEPPER: bindings.secret(),
            RELEASE_PUBLIC_KEYS_JSON: bindings.text(JSON.stringify(bootstrap.releasePublicKeys)),
            CI_AUDIENCE: bindings.text(required('WEKIT_CI_AUDIENCE')),
            CI_REPOSITORY_ID: bindings.text(required('WEKIT_CI_REPOSITORY_ID')),
            CI_REPOSITORY_OWNER_ID: bindings.text(required('WEKIT_CI_REPOSITORY_OWNER_ID')),
            CI_WORKFLOW_REF: bindings.text('Ujhhgtg/WeKit-Dev/.github/workflows/payload.yml@refs/heads/master'),
            CI_REF: bindings.text('refs/heads/master'),
            CI_ENVIRONMENT: bindings.text('payload-release'),
            CI_SESSION_SECRET: bindings.secret(),
        });
    } else if (role === 'bot') {
        Object.assign(env, {
            TELEGRAM_BOT_TOKEN: bindings.secret(),
            TELEGRAM_WEBHOOK_SECRET: bindings.secret(),
            TELEGRAM_BOT_ID: bindings.text(required('WEKIT_TELEGRAM_BOT_ID')),
            TELEGRAM_BOT_USERNAME: bindings.text('ujhhgtg_wekit_bot'),
            BIND_CODE_PEPPER: bindings.secret(),
        });
    } else {
        Object.assign(env, {
            ASSETS: bindings.assets(),
            ACCESS_TEAM_ORIGIN: bindings.text('https://wekit-ujhhgtg.cloudflareaccess.com'),
            ACCESS_AUDIENCE: bindings.text(required('WEKIT_ACCESS_AUDIENCE')),
            ADMIN_EMAILS_JSON: bindings.text(JSON.stringify(['feyxiexzf@gmail.com'])),
            ADMIN_ORIGIN: bindings.text('https://admin.wekit.ujhhgtg.dev'),
            CSRF_SECRET: bindings.secret(),
        });
    }
    return defineConfig({
        accountId: '5859b61376b8602974cf559be6f62fa0',
        worker: {
            name: `wekit-remote-${role}`,
            entrypoint: fileURLToPath(new URL(`../dist/${role}.js`, import.meta.url)),
            compatibilityDate: '2026-10-11',
            compatibilityFlags: ['nodejs_compat'],
            workersDev: false,
            previewUrls: false,
            domains: [`${role === 'api' ? 'auth' : role}.wekit.ujhhgtg.dev`],
            triggers: role === 'api' ? [triggers.scheduled({ schedule: '0 */6 * * *' })] : [],
            ...(role === 'admin' ? { assets: { runWorkerFirst: true, notFoundHandling: 'single-page-application' as const } } : {}),
            observability: {
                enabled: true,
                headSamplingRate: 1,
                redactQueryString: true,
                logs: { enabled: true, invocationLogs: false, persist: true },
            },
            env,
        },
    });
}
