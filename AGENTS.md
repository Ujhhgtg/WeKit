# AGENTS.md

## Artifacts

- All development artifacts (plans, specs, designs) are written, edited, and committed **only** in
  `~/coding/wekit_dev/artifacts`, never inside this repo (those paths are gitignored here).

## Build

```bash
./x bootstrap build                     # release bootstrap APK
./x bootstrap build --local             # release bootstrap APK with the debug payload embedded
./x bootstrap build --local --release   # release bootstrap APK with the release payload embedded
./x bootstrap build --native-only       # only prepare Zygisk native inputs
./x payload build                       # debug payload APK
./x payload publish                     # build, encrypt, sign, and publish the release payload
./x bootstrap install --zygisk          # build the bootstrap APK and install it as a Zygisk module
./x bootstrap install --local           # install bootstrap-local with the debug payload embedded
./x bootstrap install --local --release # install bootstrap-local with the release payload embedded
./x fmt                                 # formats Kotlin/Java with spotless and Rust with cargo
# (./x is alias to `cargo xtask` which orchestrates the build process)
```

- In a Git worktree, run `git submodule update --init --recursive` before any work; otherwise
  `libs/common/bsh` and `libs/common/reflekt` are empty and builds fail.
- Work directly on `dev` unless the user explicitly requests another branch or isolated history.
- Bootstrap debug/release APKs are dual-format: install as an APK, or rename to `.zip`
  and install through a root manager. Module files enter the bootstrap APK before AGP signs it;
  never modify or repack the signed output. Payload APKs are private runtime payloads and are not
  installed or flashed directly. `bootstrap-local` itself is always a release build; it embeds the
  debug app payload by default and the release app payload with `--release`. It skips all bootstrap
  authentication, binding, cache, and download paths; because the payload is present in plaintext,
  never publish this variant outside internal testing. There is no separate `zygisk build` command.
  The legacy server-side `local` release channel and `./x payload install` command have been removed.
- JDK 21, AGP 9, Gradle version catalog in `gradle/libs.versions.toml`
- CI builds one release APK (`app-release.apk`) and publishes it to the CI GitHub release and
  Xposed Modules Repository.

## Project Structure

- `app/` — main Android module, entrypoints, hooks and UI
- `crates/wekit-native/` — payload JNI library, Flutter native hooks and desktop Dex native support
- `crates/wekit-zygisk/` — Zygisk loader and LSPlant ART hook integration
- `crates/wekit-native-common/` — shared `memory`, `aarch64`, and `logging` modules for executable
  memory, ARM64 instruction relocation, and platform logging, used by both native libraries
  (the platform logger is configured by thin per-library forwarding modules)
- `wekit-zygisk/` — uv tooling project and Zygisk documentation; the `build` dependency group
  supplies CMake/Ninja and the `test` group supplies Unicorn
- `libs/common/annotation-scanner/` — KSP processors: source-subtype discovery for
  `BaseFeature`/`ExtensionPack` objects, the Feature → Feature reference scan
  (`GeneratedFeatureDependencies.DIRECT`), plus the `@AgentTool` scanner
- `libs/common/bsh/` — submodule: forked BeanShell interpreter with snapshot serialization
  (`BshSnapshot`, `BshSnapshotHelper`); snapshots are encrypted AST bytes used by the WAuxiliary
  Xposed module. `app/src/main/java/dev/ujhhgtg/wekit/utils/BshSnapshotDecompiler.kt` decompiles
  them back into Java-like source (AES key recovered from WAuxiliary's decompiled source)
- `libs/common/reflekt/` — submodule: reflection utility library (`dev.ujhhgtg.reflekt`)
- `libs/common/stubs/` — compileOnly stubs for WeChat and Android hidden classes
- `buildSrc/` — custom Gradle tasks: `GenerateMethodHashesTask` (`BaseFeature` delegate-source
  MD5 cache), `GenerateNewFeaturesTask` (Kotlin sources added within 30 days of HEAD →
  `NewFeatures.ADDED_AT_BY_SOURCE_KEY`; KSP joins them to features for the 新功能 pseudo-category)
- `xtask/` — build orchestration behind `./x`: native-lib compilation + NDK linker config, APK
  assembly/signing via Gradle, and Zygisk installation of that same APK

## Entry Points & Architecture

- Xposed entry: `dev.ujhhgtg.wekit.loader.entry.lxp.LxpHookEntry` (libxposed 101 ~ 102)
- Unified flow: entry point → `ModuleLoader.init()` → (after `Application.onCreate`) `ModuleLoader.startup()` → `launch()` → `FeaturesLoader.loadFeatures()`
- Feature objects inherit `BaseFeature` and declare `technicalId`/resource/category metadata as
  override properties; extension pack objects implement `ExtensionPack` with a required
  `displayOrder`. Both are auto-discovered by KSP from their source subtype at compile time.
- Base classes: `SwitchFeature` (toggle), `ClickableFeature` (toggle + onClick), `ApiFeature`
  (always-on), `BaseFeature` (abstract base, do not use directly)
- DEX analysis uses DexSQL queries plus the WeKit query matcher; DexKit has been removed, so
  every target lives behind a `BaseFeature` Dex delegate. Matcher and computed delegate blocks are
  MD5-hashed as the cache key (`GenerateMethodHashesTask`). The `dev.ujhhgtg:dexsql` library's
  Rust engine only executes read-only SQL — row selection and resolver-state classification are
  WeKit responsibilities.
- If any started feature needs Dex resolution, no feature starts (in any process); the dialog
  resolves and restarts WeChat. Failures are persisted per resolver hash. A feature with
  `BaseFeature.errors` (own/transitive-dependency Dex state or `declaredErrors`) never starts
  and can't be enabled — declare host restrictions via `declaredErrors`, not toasts in `onEnable`
- DEX-resolved targets DSL: `val methodTarget by dexMethod { ... }` / `val classTarget by dexClass { ... }` →
  `methodTarget.hookBefore { ... }`, `methodTarget.method`, `classTarget.clazz`
- Extension scripts resolve DEX through the same query matcher: Python plugins use the
  query-backed `wekit.dex` module (spec builders plus `find_methods`/`find_classes`/`find_fields`),
  and BeanShell exposes `findClassList`/`findMemberList` as matcher-backed string searches. The old
  DexKit-backed `wekit.dexkit` module was removed without a compatibility shim.
- SQL query delegates use the matcher-shaped builder instead of a raw SQL string:
  `by dexMethod(sqlMethod { declaredClass = "..."; returnType = "void"; usingEqStrings("...") })`
  (also `sqlClass`/`sqlField`/`sqlConstructor`). The builder emits canonical DexSQL text; a
  raw `dexMethod("""SELECT ...""")` remains only as an escape hatch for queries the DSL cannot
  express. Each SQL query must return exactly one descriptor row; multi-row results are
  ambiguity bugs, and empty results are expected failures only when `allowFailure = true`.
  Write the query so it selects a single member (use `limit`/`offset` for deterministic
  candidate selection). There is no `allowMultiple`/`resultIndex` delegate option.
  A class query with several nested method matchers emits pairwise distinctness
  conditions because the compatibility semantics bind nested methods injectively (each nested matcher
  must bind a different method); do not remove them. Class-literal constraints
  (method/constructor declaredClass { } and class superClass { }) are existential
  over the constraint class's methods with one unit per literal value. Access-flag
  constraints use modifiers(...) or modifiers = ... and compile to
  (alias.access_flags & mask) = mask (Contains semantics: every requested bit must be set). Literal LIKE
  matchers are case-sensitive: contains compiles to instr(col, lit) > 0,
  prefix to instr(col, lit) = 1, suffix to substr(col, -N) = lit, and class packages to
  instr(descriptor, 'Lpkg/') = 1; equality stays plain and an empty literal with a
  non-equals mode falls back to equals. The builder block is LAZY: it executes when the
  delegate is resolved, inside the DexResolutionContext, so matcher arguments may read
  another delegate's metadata directly, e.g. declaredClass = classFoo.data.name or
  paramTypes(methodX.data.returnTypeName, "boolean"); no wrapper is needed and dependencies
  resolve on demand.
- UI: Jetpack Compose + Material 3; dialogs via `showComposeDialog` and `AlertDialogContent`;
  settings screens follow Material 3 UI Standards below
- Storage: everything lives in the host's private `filesDir/wekit/`; the Room database
  `WeKitDatabase` (`wekit.db`) holds preferences (`KvStore`), structured feature configs, and the
  Dex cache. Only Room schema migrations exist; pre-Room data (MMKV, JSON files, old
  external/private roots) is not imported.
- Logging: via `WeLogger`

## Desktop Dex validation

- `./x dex-test` runs the same `BaseFeature`/DexSQL resolution as `DexCacheManager.kt` against
  WeChat APKs on the desktop. Only the supported range **8.0.65–8.0.80** is a compatibility gate;
  other APKs are for investigation only.
- `--workers 4` exercises the shared batch scheduler used by Android resolution; `--workers 1` is
  its serial comparison; omitting it keeps per-feature runs. Batch runs initialize/reset all
  selected roots before starting workers and report after joining.
- Test each supported APK separately (normal and Google Play APKs separately when both exist). Each
  runs in its own JVM worker with its own version code, version name, build tag, and Google Play
  metadata.
- Reports go under `dex-test-results/<run-id>/` (or an explicit output dir), never Gradle's
  `build/reports/`. Keep the per-APK JSON reports and the aggregate summary.
- `./x dex-report-diff` compares member signatures across existing per-APK reports.
- Classification is strict and comes from each delegate's `DexResolutionState`: an
  `allowFailure = true` lookup with no result (or an explicit `markExpectedFailure()`) is
  `EXPECTED_FAILURE`; an unhandled resolver exception is `UNEXPECTED_FAILURE`; delegates still
  pending after that exception are `BLOCKED` and record the triggering delegate; a resolver
  returning with pending delegates is `INCOMPLETE`. Initialization, worker, native-library, APK
  metadata, report, unexpected, blocked, or incomplete failures must stay visible and fail the command.
- Desktop testing is expensive. Rerun only the affected supported APKs after changing `dexMethod`,
  `dexClass`, `dexField`, SQL queries/DSL expressions, or computed delegate logic; don't rerun a
  passed version for unrelated changes.
- The desktop worker only loads DexSQL when `./x dex-test` can build the host `libwekit_native.so`;
  the task always runs the incremental host build before starting workers, so the library cannot
  be silently stale.
- Before reporting a Dex resolver change complete, run the affected desktop tests, any relevant
  qualifying Gradle tests (see Testing Strategy), `./x payload build`, and `git diff --check`.

### Desktop-safe Dex resolver rules

- All matcher and computed delegate bodies share one `DexResolutionContext`.
  To use an already-resolved delegate in a later query, read its Dex metadata
  (`delegate.data.name`, `.declaredClassName`, `.returnTypeName`, `.paramTypeNames`,
  `.superClass`, `.interfaces`, etc.), never JVM reflection (`.clazz`, `.method`, `.constructor`,
  `.field`, `asClass`, reflection-derived types): desktop workers cannot reliably load
  WeChat/Android classes. Hiding reflection behind `lazy`/object init (e.g.
  `by lazy { target.method.declaringClass }`) is equally invalid. Reflection remains fine at hook time.
- Any user-approved host-version branch, or build-tag/Google Play branch, inside resolution must
  read `DexResolutionContext.host`, not `HostInfo`, so dex-test uses the APK-under-test's metadata
  (Android resolution receives equivalent current-host metadata).
- Preserve intended descriptor/matcher constraints when migrating; never loosen strings,
  signatures, or structural predicates just to pass a desktop test.
- Resolver source is the device cache key: any rewrite (even reflection → `.data`) changes
  `methodHash` and forces one re-resolution. Never retain or hand-edit an old hash; avoid unrelated
  formatting/refactors in resolver and matcher bodies.

### `allowFailure` and host compatibility paths

- `allowFailure` on `dexMethod`/`dexClass`/`dexField` is ONLY for structures
  whose existence differs across supported versions. If a resolution should succeed on every
  supported version, don't set it — failure must be loud, not a silent no-op. Never turn
  exceptions or uncertain matches into expected failures to get a green report. Expected
  failures carry no reason. The dexsql engine itself has no resolver-failure policy; WeKit
  maps an empty SQL result to an expected failure only through this delegate-level flag.
- Prefer structure-based compatibility over host-version checks. In resolution, probe one stable
  member that exists only on the newer path; if it yields zero results, it is an expected failure;
  mark the newer path's other delegates with `markExpectedFailure()` and fall back to the older path, otherwise keep every other target on that path strict. Multiple
  results, matcher errors, and failures after the probe are failures, not fallback conditions.
- At hook/invocation time, choose the path from the resolved structure (the probe's
  `isFound`, the resolved member's reflection signature, or another directly relevant runtime
  property), not a repeated version comparison.
- If the same semantic member differs only in signature, accept confirmed signatures structurally
  (e.g. `paramCount(10, 11)`); when arguments differ, build them from the actual
  `Method.parameterCount`/`Constructor.parameterCount`.
- Avoid branching on the host's `versionCode`, `versionName`, hard-coded version strings, or
  equivalent constants. If genuinely unavoidable, get explicit user confirmation first.
  `isHostGooglePlay`/`isGooglePlay` checks are not host-version checks and need no confirmation.

## Testing Strategy

- Automated tests (and TDD) are allowed only for core logic that lives entirely in WeKit and does
  not depend on WeChat host classes, runtime state, UI, or behavior. Host hooks, reflection/matcher
  glue, and host UI are normally excluded.
- Don't test logic that is easy to verify statically (constants, direct mappings, boolean
  expressions, identity functions, simple arithmetic), and don't add tests merely to satisfy a
  workflow or skill.
- Don't add production complexity for testability (interface + impl splits of simple singletons,
  unnecessary wrappers/DI, extracting one-use logic). Extract a helper only when it improves
  readability, is reused, or isolates genuinely complex behavior.
- Dex resolution is validated by `./x dex-test`; everything else is validated by manual testing in
  real WeChat on a device — desktop/Gradle tests never replace it.
- Shared native core tests run with `cargo test -p wekit-native-common --lib`; Zygisk host tests
  run with `cargo test -p wekit-zygisk --lib`. The ARM64 relocation suite runs from the repository
  root with `uv run --locked --project wekit-zygisk --group test python crates/wekit-native-common/tests/arm64_relocation.py`.
  It builds the `wekit-relocation-driver` Cargo binary once with the explicit `relocation-driver`
  feature before executing the generated code in Unicorn. These tests cover core logic, not
  Android cache coherency or real ART/WeChat compatibility.

## Key Conventions

- Package namespace: `dev.ujhhgtg.wekit`
- `app` is an application module; don't use Kotlin `internal` in `app/src/main` (it provides no
  boundary) — use the default `public`.
- Min SDK 29, target SDK 37, compile SDK 37
- Target: WeChat `com.tencent.mm`, versions 8.0.65–8.0.80. Current host info in `HostInfo`
- Process targeting via `TargetProcesses`: override `startup()` to check
  `TargetProcesses.isInMain` / `TargetProcesses.currentType`. Default: main process only.
- NEVER wrap `hookBefore`/`hookAfter` in a catch-all `try-catch`/`runCatching`. They should not fail; if they
  do, it's the module developer's bug.
- Use `KvStore.prefOption` delegates to declare & use preference items.
- `onDisable` teardown is **best-effort by design**: many features irreversibly modify the host
  view tree, and restarting WeChat is the accepted revert. Do NOT report missing `onDisable`
  reverts as bugs.
- Host-class reflection goes through `reflekt` (`libs/common/reflekt/`), e.g.
  `thisObject.reflekt().firstField { ... }` or `.getField(name, true)`, not hand-rolled
  `getDeclaredField`/`getMethod` traversal.
- **NEVER use `Path.of` or `Files.writeString`** (unavailable on older supported API levels).
  Use `dev.ujhhgtg.wekit.utils.fs.asPath` from `utils/fs/PathUtils.kt` (e.g. `pathString.asPath`,
  `base.asPath.resolve(child)`) and `kotlin.io.path.writeText`.
- No excessive defensiveness. When types/presence are known to hold, use direct casts and `!!`
  (`thisObject as Activity`, `args[0] as View`), not `as?`, `args.getOrNull(0)`, `?:`, `?.` guards:
  wrong code must throw loudly into `HookUtils`' or the code's own exception handler instead of
  being silently swallowed. Genuinely reasonable guards stay.
- You are NOT familiar with `reflekt`, the DexSQL schema/DSL, or the WeKit query matcher. Read
  their code (`dex/dsl/` here, the `dev.ujhhgtg:dexsql` sources/matcher/sql, and the dexsql-rs
  repo's schema/tools) before using them; don't hallucinate APIs.
- In Compose, `LocalContext` is always the platform context and is never localized by WeKit. Use
  standard Compose resource APIs for composable text and `LocalLocalizedContext` only for
  imperative WeKit resource reads; mixed platform/resource operations read both. Use
  `LocalActivity.current` for Activity-only APIs, and never add AndroidX owner forwarding to
  `LocaleProvider`.
- Outside Compose, read WeKit resources only through `i18n/LocalizedResources.kt`:
  `localizedString` / `Context.localizedString` for strings and `localizedQuantity` /
  `Context.localizedQuantity` for plurals. Don't add per-feature wrappers such as
  `localizedChatString` and don't call `LocalizedContextFactory` directly (`LocaleProvider` is the
  only exception). Resolve at display time; never cache localized strings in long-lived state.

## Material 3 UI Standards

Design reference: `~/coding/InstallerX-Revived` — when unsure how a settings page should look or
behave, read its `app/src/main/java/com/rosan/installer/ui/page/main/widget/setting/`. WeKit's
ported widget family lives in `app/src/main/java/dev/ujhhgtg/wekit/ui/content/m3/`.

### Layout

- Settings screens are a `LazyColumn` of `SegmentedColumn` groups (inset rounded cards, one group
  per concern, short `title` above each). No hand-rolled cards or flat lists with dividers.
- Use the shared scaffolds — `M3ListScaffold` (`activity/settings/SettingsActivity.kt`) or
  `SettingsScaffold` (`ui/content/m3/SettingsComponents.kt`): collapsing `LargeFlexibleTopAppBar`
  + blur + back button. No per-screen scaffolds.
- Multi-screen settings follow the miuix-nav `NavDisplay` pattern of `WeAgentSettingsActivity` /
  `ReadReceiptsSettingsActivity` (sealed `@Serializable` routes, predictive-back drill-down).

### Widget choice

Prefer these over raw Compose controls:

- Plain / status / navigation row → `BaseWidget` (chevron or action in `trailingContent`).
- Boolean setting → `SwitchWidget`.
- Exclusive choice → `RadioButtonWidget`; supports dual click areas like the WeAgent "Memory" row:
  `onClick` (main area, e.g. opens the detail screen) + `onSelect` (the radio) + `trailingDivider`.
- String or number input → `TextFieldDialogWidget` (row showing the current value, edited in a
  cancel/confirm dialog). For draft & save semantics, put a bare `TextField`/`OutlinedTextField`
  in a `BaseSupportingWidget`.
- Value with a natural range and step (counts, seconds, delays) → `IntNumberPickerWidget` (slider
  row with drag tooltip) in a `BaseItemContainer` inside the group. Free-form identifiers (ports,
  hostnames, tokens, URLs) use the dialog row instead.
- Compact choice from a fixed set → `DropDownMenuWidget`.

## Naming Conventions

- 群聊: WeChat: chatroom; WeKit: group/群组
- 朋友圈: WeChat: sns; WeKit: moment

## Context you need

- WeChat decompiled sources: ~/coding/wechat_80*/
- Decrypted WeChat main database: ./decrypted_wechat.db

## CI

- GitHub Actions: builds on push/PR to `master`/`dev` (skips non-code changes)
- Artifacts automatically published to a release named "CI" and the Xposed Modules Repository
