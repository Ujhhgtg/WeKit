# WeKit — Agent Guide

## Build

```bash
./gradlew :app:assembleDebug   # debug (uses same signing as release)
./gradlew :app:assembleRelease # release (with optimization on)
```

- JDK 21
- Rust native lib auto-compiles during build (targets: `app/src/main/rust/wekit-native`). Requires:
  Rust toolchain + Android NDK targets + NDK. `configureCargo` task auto-generates `.cargo/config.toml`
  from NDK.
- AGP 9, Gradle version catalog in `gradle/libs.versions.toml`

## Project Structure

- `app/` — main Android module, entrypoints, hooks, UI, native Rust lib
- `libs/common/annotation-scanner/` — KSP annotation processor (`@Feature` scanner)
- `libs/common/libxposed-api/` — compileOnly LibXposed API interface stubs (compileOnly since they are provided by user's Xposed framework)
- `libs/common/bsh/` — submodule: forked BeanShell interpreter with snapshot serialization (`BshSnapshot`, `BshSnapshotHelper`); snapshots are encrypted AST byte representations used by the WAuxiliary Xposed module; `app/src/main/java/dev/ujhhgtg/wekit/utils/BshSnapshotDecompiler.kt` — decompiles encrypted BeanShell snapshot files back into Java-like source code; the AES key was recovered from WAuxiliary's decompiled source
- `libs/common/reflekt/` — submodule: reflection utility library (`dev.ujhhgtg.reflekt`)
- `libs/common/stubs/` — compileOnly stubs for WeChat and Android hidden classes
- `libs/external/comptime-kt/` — submodule: compile-time reflection utility
- `buildSrc/` — custom Gradle tasks: `GenerateMethodHashesTask` (`IResolveDex` `resolveDex` method MD5 cache), `ConfigureCargoTask` (Rust NDK linker config)

## Entry Points & Architecture

- Xposed entry: `dev.ujhhgtg.wekit.loader.entry.lsp10x.Lsp10xUnifiedHookEntry` (libxposed 101 & 100) and legacy Xposed API (51+) entry: `dev.ujhhgtg.wekit.loader.entry.xp51.Xp51HookEntry`
- Unified flow: `UnifiedEntryPoint.entry()` → `StartupAgent.startup()` → `WeLauncher.init()`
- Hook items annotated with `@Feature(path, description)`, auto-discovered by KSP annotation scanner at compile time
- Base classes: `SwitchFeature` (toggle on/off), `ClickableFeature` (toggle on/off with onClick event), `ApiFeature` (always-on), `BaseFeature` (abstract base, do not use directly)
- DEX analysis via DexKit with `IResolveDex` interface; method resolve body MD5-hashed for cache (
  `GenerateMethodHashesTask`)
- DEX-resolved targets DSL: `val methodTarget by dexMethod()` `val classTarget by dexClass()` delegate → `methodTarget.hookBefore { ... }`, `val method: Method = methodTarget.method`, `val clazz = classTarget.clazz`
- UI: Jetpack Compose + Material 3, dialogs written using `showComposeDialog` and `AlertDialogContent`
- Config: MMKV via `WePrefs`
- Logging: via `WeLogger`

## Key Conventions

- Package namespace: `dev.ujhhgtg.wekit`
- Min SDK 29, target SDK 37, compile SDK 37
- Target: WeChat `com.tencent.mm`, versions 8.0.65–8.0.71. Version info in `HostInfo`
- Process targeting via `TargetProcesses`: override `startup()` to check
  `TargetProcesses.isInMain` / `TargetProcesses.currentType`. Default: main process only.
- No unit tests — manual testing on real WeChat only
- Use the library `KavaRef` for Java reflection if possible. Use `KavaRefUtils` as an entry point to it for clearer semantics. KavaRef
- If `JsApiExposer` (`hooks/items/scripting_js/JsApiExposer.kt`) is modified, keep `globals.d.ts` in
  the same directory in sync — it's the TypeScript type declaration for the JS scripting API
- NEVER wrap `hookBefore` and `hookAfter` in a `try-catch`/`runCatching` block. They should NOT fail. If they fail, then it's the module developer's problem.
- Use `WePrefs.Companion.prefOption` delegates to declare & use preference items easily.

## Naming Conventions

- 群聊: WeChat: chatroom; WeKit: group/群组
- 朋友圈: WeChat: sns; WeKit: moment

## Context you need

- WeChat decompiled sources: ~/coding/wechat_8074
- Decrypted WeChat main database: ./decrypted_wechat.db

## CI

- GitHub Actions: builds on push/PR to `master` (skips non-code changes)
- Artifacts automatically published to a release named "CI" + Telegram channel
