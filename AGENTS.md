# WeKit — Agent Guide

## Build

```bash
./x build           # debug (uses same signing as release)
./x build --release # release (with optimization on)
./x zygisk build    # standard universal APK + arm64/arm32 Zygisk module ZIP
# (./x is alias to `cargo xtask` which orchestrates the build process)
```

- **When working in a Git worktree, initialize submodules before starting any work:**
  `git submodule update --init --recursive`. Worktrees do not automatically populate submodule
  contents, and builds will fail when `libs/common/bsh` and `libs/common/reflekt` are empty.
- **When working in a Git worktree, work directly on `dev` unless the user explicitly requests
  another branch or isolated history.** This is because commits made on a detached worktree are not automatically
  transferred by Codex's “local checkout” action and can appear to be lost.
- JDK 21
- **Gradle does NOT build the Rust native lib.** `./gradlew assemble*` only packages whatever
  prebuilt `libwekit_native.so` already sits in `app/src/main/jniLibs/<abi>/`. Compiling
  `app/src/main/rust/wekit-native` and refreshing those `.so` files is xtask's job
  (`task_build_native`), so **always go through `./x`** — running Gradle directly will silently ship
  a stale native lib. Requires a Rust toolchain + the Android NDK and its Rust targets;
  `./x configure` regenerates `wekit-native/.cargo/config.toml` from the local NDK and is invoked
  automatically by the build tasks.
- `./x build --native-only` rebuilds just the native lib into `jniLibs/`
- AGP 9, Gradle version catalog in `gradle/libs.versions.toml`

## Project Structure

- `app/` — main Android module, entrypoints, hooks, UI, native Rust lib
- `libs/common/annotation-scanner/` — KSP annotation processor (`@Feature` scanner)
- `libs/common/libxposed-api/` — compileOnly LibXposed API interface stubs (compileOnly since they are provided by user's Xposed framework)
- `libs/common/bsh/` — submodule: forked BeanShell interpreter with snapshot serialization (`BshSnapshot`, `BshSnapshotHelper`); snapshots are encrypted AST byte representations used by the WAuxiliary Xposed module; `app/src/main/java/dev/ujhhgtg/wekit/utils/BshSnapshotDecompiler.kt` — decompiles encrypted BeanShell snapshot files back into Java-like source code; the AES key was recovered from WAuxiliary's decompiled source
- `libs/common/reflekt/` — submodule: reflection utility library (`dev.ujhhgtg.reflekt`)
- `libs/common/stubs/` — compileOnly stubs for WeChat and Android hidden classes
- `buildSrc/` — custom Gradle tasks: `GenerateMethodHashesTask` (`IResolveDex` `resolveDex` method MD5 cache), `GenerateNewFeaturesTask` (features whose source file was added within 30 days of the HEAD commit → `NewFeatures.ADDED_AT_BY_NAME`, backing the 新功能 pseudo-category)
- `xtask/` — build orchestration behind `./x`: native-lib compilation + NDK linker config, APK
  assembly via Gradle, and Zygisk module packaging/flashing

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

## Desktop DexKit Validation

- Use `./x dex-test` to run the same `IResolveDex`/DexKit resolution steps used by
  `DexCacheManager.kt` against WeChat APKs on the Linux desktop. Test only the supported host
  range **8.0.65–8.0.76**; APKs outside that range are useful for investigation but must not be
  treated as compatibility gates for the project.
- Test each supported APK version separately, including separate normal and Google Play APKs
  when both are available. Each APK runs in its own JVM worker and must carry its own version code,
  version name, build tag, and Google Play metadata.
- Reports belong under `dex-test-results/<run-id>/` (or an explicitly supplied output directory),
  never under Gradle's `build/reports/`. Preserve the per-APK JSON reports and aggregate summary.
- Resolution classification is strict: an `allowFailure = true` delegate that receives its
  placeholder is `EXPECTED_FAILURE`; an unhandled resolver exception is `UNEXPECTED_FAILURE`;
  delegates that remain pending after that exception are `BLOCKED` and must record the triggering
  delegate; a resolver returning with pending delegates is `INCOMPLETE`.
- A desktop resolution pass does not prove hook-time behavior on a physical device. Initialization,
  worker, native-library, APK metadata, report, unexpected, blocked, or incomplete failures must
  remain visible and make the command fail.
- DexKit desktop testing is intentionally expensive. After a supported-version run has passed,
  do not rerun it for unrelated changes when no Dex declarations or resolution steps changed.
  Rerun the affected supported APK versions after changing `dexMethod`, `dexClass`, `dexField`,
  inline matchers, or the corresponding `resolveDex`/`resolveInlineDex` logic.
- Before reporting a Dex resolver change as complete, run the affected desktop tests plus any
  relevant existing or qualifying Gradle tests (as defined under Testing Strategy), `./x build`,
  and `git diff --check`.

### Desktop-safe Dex resolver rules

- `resolveDex`, `resolveInlineDex`, and inline matcher blocks run in the same
  `DexResolutionContext`. When a matcher needs information from an already-resolved delegate,
  use its DexKit metadata (`delegate.data.name`, `.declaredClassName`, `.returnTypeName`,
  `.paramTypeNames`, `.superClass`, `.interfaces`, etc.), not JVM reflection. In particular, do
  not use another delegate's `.clazz`, `.method`, `.constructor`, `.field`, `asClass`, or
  reflection-derived `Class`/type information to construct a later Dex query: desktop workers
  cannot reliably load WeChat/Android classes.
- Do not hide that reflection behind a `lazy` property or object initialization. A resolver-side
  lazy such as `by lazy { target.method.declaringClass }` is still invalid for desktop testing;
  derive the required descriptor from `target.data` while resolving instead. Reflection properties
  remain valid after resolution for actual hook-time Android behavior; this rule applies only to
  declaration and resolution paths.
- Version, build-tag, and Google Play branches inside resolution must read
  `DexResolutionContext.host`, rather than `HostInfo`, so `./x dex-test` uses metadata belonging
  to the APK under test. Android resolution receives equivalent current-host metadata through the
  same context.
- A metadata migration must preserve the intended descriptor/matcher constraints. Do not loosen
  strings, signatures, or structural predicates merely to make a desktop test pass; use stable
  DexKit evidence as normal.
- For an intentional supported-version absence, use `allowFailure = true` only as documented
  below and record the placeholder explicitly with `expectedFailure = true` plus a version reason.
  Do not convert exceptions or uncertain matches into placeholders just to obtain a green report.
- Resolver source is part of the device cache key: even a mechanically equivalent rewrite from
  reflection to `.data` changes the generated `methodHash` and invalidates that feature's old
  cache. Expect one device re-resolution after such a change; never retain or hand-edit an old
  hash to suppress it. Avoid unrelated formatting/refactors in resolver and inline matcher bodies
  when a cache invalidation is not intended.

## Testing Strategy

- TDD and new automated tests are allowed only when all core logic under test lives in WeKit,
  has low coupling to WeChat, and does not depend on WeChat host classes, runtime state, UI, or
  behavior.
- Do not add tests for simple logic that is easy to verify by static review, such as constants,
  direct mappings, boolean expressions, identity functions, or straightforward arithmetic. Do not
  add tests merely to satisfy a workflow or a skill such as Superpowers.
- Do not increase production-code complexity to create a test seam. In particular, do not split a
  simple object singleton into an interface plus implementation, introduce unnecessary wrappers or
  dependency injection, or extract simple one-use logic into a standalone function solely so it can
  be unit-tested.
- Keep simple logic inline when it has only one use and does not form a meaningful reusable domain
  boundary. Extract a helper only when it improves readability, is reused, or isolates genuinely
  complex behavior; testability alone is not sufficient justification.
- If work does not meet all of those conditions, do not use TDD and do not add low-value tests
  merely to satisfy a testing workflow. Host hooks, reflection/DexKit glue, and host UI behavior
  are normally in this category.
- Use `./x dex-test` for automated Dex resolution validation as documented above. Apart from Dex
  resolution, manual testing in the real WeChat host is the primary behavioral test method;
  desktop JVM or Gradle tests do not replace it.

## Key Conventions

- Package namespace: `dev.ujhhgtg.wekit`
- Min SDK 28, target SDK 37, compile SDK 37
- Target: WeChat `com.tencent.mm`, versions 8.0.65–8.0.76. Current host info in `HostInfo`
- Process targeting via `TargetProcesses`: override `startup()` to check
  `TargetProcesses.isInMain` / `TargetProcesses.currentType`. Default: main process only.
- Device behavior still requires manual testing on real WeChat; desktop JVM tests cover Dex
  resolution only and do not replace device validation.
- NEVER wrap `hookBefore` and `hookAfter` in a `try-catch`/`runCatching` block. They should NOT fail. If they fail, then it's the module developer's problem.
- Use `WePrefs.Companion.prefOption` delegates to declare & use preference items easily.
- Teardown/revert on `onDisable` is **best-effort by design**, not a requirement. Many features
  irreversibly modify the host view tree; fully reverting them would need complex state management
  and syncing for little gain, so having the user restart WeChat is the accepted approach. Do NOT
  report "feature does not undo its changes in `onDisable`" as a bug.
- `allowFailure` on `dexMethod`/`dexClass`/`dexField` is ONLY for structures whose existence
  differs across supported WeChat versions (present in old, absent in new, or vice versa). If a
  declared Dex resolution is expected to succeed on every supported version (8.0.65–8.0.76), do
  NOT set `allowFailure`: a resolution failure must fail that feature loudly instead of silently
  degrading to a no-op.
- JVM reflection over host classes should go through `reflekt` (`libs/common/reflekt/`) by
  default, e.g. `thisObject.reflekt().firstField { ... }` or `.getField(name, true)` — not
  hand-rolled `getDeclaredField`/`getMethod` traversal.
- No excessive defensiveness. When e.g. the hooked method and its argument types are
  known to hold, use direct casts: `thisObject as Activity`, `args[0] as View`, `!!`. Do NOT use `as?`
  safe casts, `args.getOrNull(0)`, `?:`, `?.someFun()` or similar guards for values that should always be present/non-null/etc.
  Code that is correct does not need the defense; code that is wrong must throw loudly and get caught by either `HookUtils`' or code's own exception catcher, and these
  guards only swallow the exception and hide the real error. Defenses and guards that are reasonable should still exist.

## Naming Conventions

- 群聊: WeChat: chatroom; WeKit: group/群组
- 朋友圈: WeChat: sns; WeKit: moment

## Context you need

- WeChat decompiled sources: ~/coding/wechat_80{65,67,69,74,76}
- Decrypted WeChat main database: ./decrypted_wechat.db

## CI

- GitHub Actions: builds on push/PR to `master`/`dev` (skips non-code changes)
- Artifacts automatically published to a release named "CI" + Telegram channel
