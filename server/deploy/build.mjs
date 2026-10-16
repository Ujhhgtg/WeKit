import { mkdir, copyFile, access } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { loadAndParseConfig } from '@cloudflare/config';
import { cleanBuildOutputDir, getWorkerBundleDir, writeWorkerConfig, writeRootConfig,
    writeAssets, readBuildOutput } from '@cloudflare/build-output-utils';

const role = process.argv[2];
if (!['api', 'bot', 'admin'].includes(role)) throw new Error('Worker role must be api, bot or admin');
const root = resolve(dirname(fileURLToPath(import.meta.url)), role);
const context = { isPreview: false, mode: undefined };
const parsed = await loadAndParseConfig(resolve(root, 'cloudflare.config.ts'), context);
if (!parsed.result.success) throw new Error(`Invalid typed deployment config: ${parsed.result.error.message}`);
const config = parsed.result.data;
await access(config.worker.entrypoint);
await cleanBuildOutputDir(root);
const bundle = getWorkerBundleDir(root);
await mkdir(bundle, { recursive: true });
await copyFile(config.worker.entrypoint, resolve(bundle, 'index.js'));
// The backend uses source maps for private diagnostics. They are not a public asset.
try {
    await access(`${config.worker.entrypoint}.map`);
    await copyFile(`${config.worker.entrypoint}.map`, resolve(bundle, 'index.js.map'));
} catch (error) { if (error.code !== 'ENOENT') throw error; }
await writeRootConfig(root, { accountId: config.accountId }, context);
await writeWorkerConfig({root, config: config.worker, manifest: {
    type: 'complete', mainModule: 'index.js', modules: { 'index.js': { type: 'esm' } },
}});
if (role === 'admin') {
    await writeAssets({root, sourceDirectory: resolve(root, '../../admin-ui/dist')});
}
const verified = await readBuildOutput(root);
if (verified.workers.default.config.workersDev || verified.workers.default.config.previewUrls) {
    throw new Error('Public alternate Worker entrypoints must remain disabled');
}
if (role === 'admin' && verified.workers.default.config.assets?.runWorkerFirst !== true) {
    throw new Error('Admin assets must pass through Access JWT enforcement first');
}
console.log(`Prepared ${verified.workers.default.config.name} Build Output Specification`);
