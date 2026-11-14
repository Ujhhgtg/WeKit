import { build } from "esbuild";
await build({
  entryPoints: ["src/api.ts", "src/bot.ts", "src/admin.ts"],
  bundle: true,
  outdir: "dist",
  format: "esm",
  platform: "browser",
  target: "es2022",
  sourcemap: true,
});
