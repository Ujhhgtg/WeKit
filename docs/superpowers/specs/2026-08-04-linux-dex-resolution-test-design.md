# Linux Dex Resolution Test Tool Design

## Purpose

WeKit currently validates its DexKit resolution logic only while running inside WeChat on an
Android device. This makes compatibility testing across the supported WeChat versions slow: each
APK must be installed, started, allowed to rebuild its cache, and inspected separately.

Add a Linux desktop test tool that runs WeKit's existing DexKit resolution implementations directly
against multiple `wechat_*.apk` files. The tool must execute the same resolver code used by the
Android cache-repair flow and produce a deterministic report that distinguishes successful matches,
allowed misses, real failures, and delegates that never ran because an earlier lookup failed.

The tool is a source-compatibility test. It does not install an APK, start Android, execute hooks, or
prove that a resolved member behaves correctly at runtime.

## Goals

- Run the real `resolveInlineDex(dexKit)` and `resolveDex(dexKit)` implementations from the app.
- Test every discovered `~/coding/wechat_*.apk` independently by default.
- Accept one or more explicit APK paths when a narrower run is wanted.
- Use the DexKit version pinned in `gradle/libs.versions.toml` and a matching Linux native library.
- Report outcomes per APK, Feature, and Dex delegate.
- Treat a no-result lookup with `allowFailure = true` that installs a placeholder as an expected
  failure.
- Treat thrown lookup/query exceptions and incomplete resolution as unexpected failures.
- Preserve the distinction between the delegate that failed and later delegates that were blocked.
- Produce both a readable terminal report and complete JSON artifacts.
- Keep every APK run isolated from Kotlin object initialization and delegate state created by other
  APK runs.
- Leave the Android Dex cache, preferences, APKs, and device state untouched.

## Non-goals

- Do not validate reflection against live WeChat classes or install hooks.
- Do not read, write, or synthesize the module's on-device `dex_cache` files.
- Do not invoke `DexCacheManager.saveItemCache()` from the desktop tool.
- Do not establish behavioral compatibility on a physical device.
- Do not extract all resolvers into a new platform-neutral module as part of this work.
- Do not maintain a separate per-version allowlist. Expected failure comes from the existing
  `allowFailure = true` call semantics.

## Chosen Architecture

The tool will live in the Android app module's local JVM test environment, where it can reuse the
compiled app classes, internal resolver APIs, generated method hashes, Android compile stubs, and the
existing dependency graph. `xtask` will provide the user-facing command and orchestrate the native
DexKit build, app/test compilation, worker JVMs, and aggregate report.

This was selected over two alternatives:

1. Moving every resolver into a new JVM module would provide the cleanest platform separation, but
   it would require a large migration across the Feature tree and create unnecessary runtime risk.
2. Generating copied desktop matchers with KSP would not reliably reproduce custom imperative
   `resolveDex()` implementations and could silently drift away from production behavior.

The chosen design changes only the discovery metadata, test observability, test runner, and build
orchestration. Resolver implementations remain in their current Feature objects.

## User-facing Command

Add an `xtask` subcommand exposed through the existing `./x` wrapper:

```bash
./x dex-test
./x dex-test --apk ~/coding/wechat_8065.apk
./x dex-test \
  --apk ~/coding/wechat_8065.apk \
  --apk ~/coding/wechat_8076.apk \
  --output-dir ./my-dex-results
./x dex-test --verbose
```

Arguments:

- `--apk <PATH>` is repeatable. When present, APKs run in command-line order. Paths are
  canonicalized and an identical APK path is tested only at its first occurrence.
- With no `--apk`, discover regular files matching `~/coding/wechat_*.apk` and natural-sort them by
  filename so version-like numeric components are ordered numerically.
- `--output-dir <PATH>` changes the report root. It defaults to the repository-root
  `dex-test-results` directory.
- `--verbose` prints every successful delegate and descriptor. The default terminal output prints a
  Feature summary and expands expected failures, unexpected failures, blocked delegates, and
  infrastructure errors.

The command fails before running workers if no APK input is found. An invalid individual APK is
reported as an infrastructure failure for that input while the remaining APKs continue.

## Generated Resolver Registry

Extend `FeaturesScanner` to generate a second provider alongside `FeaturesProvider`. The desktop
provider contains metadata only and must not reference Feature object instances or class literals.
Each entry contains:

- fully qualified Feature class name;
- annotation name;
- annotation categories;
- annotation description;
- whether the symbol implements `IResolveDex` through any supertype path.

Only `IResolveDex` entries are emitted into the resolver-test item list. The generated source stores
class names as strings so reading the registry does not eagerly initialize all Feature objects.

The worker loads one class name at a time, obtains the Kotlin object's `INSTANCE`, verifies that it is
a `BaseFeature` and `IResolveDex`, and applies the generated name/category/description metadata just
as the production `FeaturesProvider` does. Class initialization failure becomes a Feature-level
unexpected failure and does not prevent other Feature classes from loading.

The worker must not access `FeaturesProvider.ALL_HOOK_ITEMS`, because that property eagerly
initializes the complete Feature list and would turn one bad initializer into a global failure.

## Process Isolation

`xtask` compiles the runner once, then launches one worker JVM per APK. APK workers run sequentially
in the first version of the tool.

Process-per-APK isolation is required because:

- Feature implementations are Kotlin `object` singletons;
- delegates retain descriptors and cached reflection objects;
- a JVM class whose static initialization failed cannot be initialized successfully later in the
  same class loader;
- Feature or dependency static state may be changed during initialization;
- JNI libraries and `DexKitBridge` lifecycle are easiest to make deterministic at process scope.

Each worker receives one APK path, the absolute Linux `libdexkit.so` path, its JSON output path, and
the verbosity setting. It loads the native library with `System.load(absolutePath)`, opens a fresh
`DexKitBridge`, runs all resolver Features, closes the bridge, writes the APK report atomically, and
exits.

The parent process aggregates all worker reports after every worker has exited. A worker crash or
missing/malformed report becomes an infrastructure failure for that APK rather than aborting the
remaining test set.

## Linux DexKit Native Build

The Maven `org.luckypray:dexkit` dependency supplies the JVM API and Android native libraries. The
desktop tool needs a Linux native library built from the matching upstream DexKit source.

`xtask` will:

1. Read the DexKit version from `gradle/libs.versions.toml` rather than duplicating it in Rust or
   Gradle code.
2. Resolve the pinned upstream release tag/commit for that exact version.
3. Fetch the source on first use into `.wekit/dex-test/source/`.
4. Verify the cached checkout still points at the expected revision before reuse.
5. Build the current Linux architecture with CMake/Ninja into
   `.wekit/dex-test/native/<version>/<architecture>/`.
6. Rebuild only when the expected version/revision, CMake inputs, architecture, or output library
   changes.

The first implementation targets the current Linux desktop architecture. Unsupported operating
systems or architectures fail with a clear infrastructure error. The source/native working area is
ignored by Git.

## Worker Runtime Classpath

The worker uses the app's compiled standard-debug classes and generated sources. Its runtime
classpath must additionally contain dependencies that are compile-only in the Android app but may
be referenced while the JVM verifies or initializes Feature classes, including:

- the Android platform stub jar selected by the app compile SDK;
- WeChat/hidden-class stubs from `libs/common/stubs`;
- LibXposed/legacy Xposed API stubs where applicable;
- the compiled DexKit JVM classes from the pinned AAR;
- normal app runtime/test dependencies required to load Feature classes.

The worker never calls `startup()`, `onEnable()`, reflection accessors such as `.method`/`.clazz`, or
hook installation APIs. Android stub methods are therefore not expected to participate in Dex
resolution. A Feature initializer that nevertheless performs Android/Xposed/native runtime work is
reported as an initialization failure instead of being silently skipped.

## Resolution Execution

For each successfully initialized Feature, execute:

```text
reset delegate descriptors, reflection caches, and test outcomes
resolveInlineDex(dexKit)
resolveDex(dexKit)
finalize unresolved delegate outcomes
collect descriptors, outcomes, and elapsed time
```

The raw scan always runs. The worker does not consult cache validity, current host version,
preferences, `KnownPaths`, or `DexCacheManager` persistence. This keeps the test focused on current
source-to-APK resolution compatibility.

Feature resolution may use the same bounded concurrency of eight Features as the existing
`DexResolver` flow. Report entries are sorted back into generated registry order before printing or
serialization, so concurrency cannot make reports nondeterministic. Each individual Feature's
`resolveInlineDex()` and `resolveDex()` calls remain sequential.

## Delegate Observation

Add transient, non-serialized test observation state to each `BaseDexDelegate` implementation. It
must not affect descriptor strings, cache serialization, hook behavior, or Android resolution
control flow.

Each delegate supports:

- resetting its descriptor and cached reflection object before a desktop scan;
- recording a successful result and descriptor;
- recording an allowed no-result that installed a placeholder;
- recording an exception immediately before it is rethrown;
- exposing its final test outcome to the worker.

Each `find()` implementation wraps the complete query operation, including matcher construction,
DexKit/JNI invocation, result cardinality checks, result-index selection, and descriptor assignment.
An unexpected exception is recorded and then rethrown unchanged. Existing loud-failure semantics are
preserved.

When a delegate is resolved through direct `setDescriptor()` calls rather than `find()`, finalization
uses the resulting descriptor:

- a real non-placeholder descriptor is successful;
- a placeholder is expected only when the current scan recorded an allowed no-result;
- a placeholder without an allowed-failure record is unexpected;
- no descriptor after normal Feature completion is incomplete and therefore unexpected.

If one delegate throws, the worker records that delegate as unexpected and marks every still-pending
delegate in the Feature as blocked by that failure. If custom Feature code throws outside a delegate
query, the Feature receives an unexpected `resolveDex` error and all pending delegates are blocked by
that Feature-level error.

## Outcome Model

Delegate outcomes:

| Outcome | Meaning |
| --- | --- |
| `SUCCESS` | A real descriptor was produced. |
| `EXPECTED_FAILURE` | A lookup with `allowFailure = true` found no result and installed its placeholder. |
| `UNEXPECTED_FAILURE` | A disallowed miss, multiple-result violation, invalid result index, matcher/JNI exception, or other thrown query failure occurred. |
| `BLOCKED` | The delegate never ran because an earlier delegate or Feature-level resolver operation threw. |
| `INCOMPLETE` | The Feature returned normally but the delegate was never resolved or did not produce a descriptor. This counts as unexpected. |

Feature outcomes:

- `PASS`: all delegates succeeded.
- `PASS_WITH_EXPECTED_FAILURES`: at least one delegate has an expected failure and no unexpected,
  blocked, incomplete, or initialization outcome exists.
- `FAIL`: at least one unexpected, blocked, incomplete, or Feature-level resolver error exists.
- `INITIALIZATION_FAILURE`: the Feature object could not be initialized or did not satisfy the
  generated registry contract. This is a failing outcome.

APK outcomes:

- `PASS`: all Features pass or pass with expected failures.
- `FAIL`: any Feature fails or has an initialization failure.
- `INFRASTRUCTURE_FAILURE`: the APK cannot be opened, the worker crashes, the native library cannot
  load, or the worker cannot produce a valid report.

## Terminal Report

Default output prints one section per APK and a final cross-version summary. Successful Features get
one compact line. Expected/failing Features expand their relevant delegates.

```text
=== wechat_8065.apk ===
DEX files: 31    elapsed: 42.7s

[PASS] 聊天/禁止消息折叠
[EXPECTED] 聊天/禁止上传正在输入状态
  DisableTypingStatusUploading:classMmTypingSendReq
  no result; allowFailure=true; placeholder installed

[FAIL] 朋友圈/朋友圈评论防撤回
  AntiMomentCommentsDelete:methodSnsCommentStorageDeleteComment
  DexKit: No method found ...

  BLOCKED:
    methodSnsCommentStorageDeleteCommentBySnsId
    methodSnsCommentSetCommentDelFlag

Summary:
  success             548
  expected failure     17
  unexpected failure    1
  blocked               3
```

The cross-version summary keeps variants such as `wechat_8069.apk` and
`wechat_8069_3020_play.apk` separate:

```text
wechat_8049             PASS   552 success   14 expected
wechat_8065             FAIL   548 success   17 expected   1 unexpected   3 blocked
wechat_8069_3020_play   PASS   557 success    9 expected
wechat_8076             PASS   559 success    7 expected
```

## Report Files

Reports are not Gradle build outputs. The default root is the explicit, repository-level
`dex-test-results/` directory:

```text
dex-test-results/
└── 2026-08-04T15-30-12Z/
    ├── summary.json
    ├── wechat_8049.json
    ├── wechat_8065.json
    └── wechat_8076.json
```

Each run creates a UTC timestamp directory. If it already exists, append a numeric suffix rather
than overwriting an earlier report. `dex-test-results/` is ignored by Git.

Each APK JSON contains:

- schema version and tool/source revision;
- APK absolute path, filename, size, SHA-256, and test label;
- DexKit version, native revision, Linux architecture, JVM version, and DEX count;
- start/end timestamps and elapsed time;
- ordered Feature results, including the generated `resolveDex` method hash for each Feature;
- ordered delegate keys, outcome, descriptor, placeholder state, message, exception type, and full
  stack trace where applicable;
- aggregate counts and APK outcome.

Aggregate success/expected/unexpected/blocked/incomplete counts refer to delegates. Feature-level
outcome counts are stored separately. If two explicit APK paths have the same filename, their report
filenames receive a short SHA-256 suffix so neither report overwrites the other.

`summary.json` contains the run metadata, ordered APK summaries, global counts, and paths to the
individual reports. Reports are written atomically through a temporary sibling file followed by a
rename.

## Exit Status

- Exit `0` only when every APK is `PASS`. Expected failures do not fail the command.
- Exit non-zero when any APK contains an unexpected, blocked, incomplete, or initialization failure.
- Exit non-zero for discovery, build, worker, native-loading, or report infrastructure failures.
- Continue testing remaining APKs after an individual APK/worker infrastructure failure whenever the
  native toolchain itself is still usable.

## Error Handling

- Preserve original resolver exceptions and stack traces in JSON.
- Do not catch exceptions inside production hook callbacks; the tool only wraps resolver execution
  and delegate query calls.
- Do not downgrade class initialization or environment errors into expected Dex misses.
- Validate APK readability before starting its worker.
- Always close `DexKitBridge` with structured cleanup.
- Keep partial reports from appearing as completed results by writing atomically.
- Print the exact native build or worker command on infrastructure failure so it can be reproduced.

## Verification Strategy

### Focused tests

- `xtask` CLI parsing for repeated `--apk`, `--output-dir`, defaults, and `--verbose`.
- Default APK discovery, natural filename ordering, explicit input ordering, missing inputs, and
  duplicate paths.
- KSP registry generation includes `IResolveDex` objects, excludes non-resolver Features, preserves
  metadata, and emits strings rather than eager object references.
- Delegate state transitions for successful lookup, allowed no-result placeholder, disallowed miss,
  multiple results, result-index failure, direct descriptor assignment, incomplete completion, and
  reset between scans.
- Feature finalization marks pending delegates blocked after an exception and incomplete after normal
  completion.
- JSON schema serialization, atomic output, summary aggregation, and exit-code calculation.

### Linux integration tests

- Build and load the pinned Linux DexKit native library.
- Run one real `wechat_*.apk` through a worker and verify DexKit reports at least one DEX file.
- Verify the report contains successful descriptors.
- Where the selected APK/source combination has an allowed missing matcher, verify it is emitted as
  an expected placeholder rather than a failure.
- Run two APKs and verify they have distinct process IDs and no delegate descriptor leakage.
- Force a controlled resolver failure in test-only fixtures and verify unexpected/blocked reporting.

### Repository checks

- Run the focused Gradle and Rust tests for the new tooling.
- Run `git diff --check`.
- Run `./x build` so the Android app and native WeKit library are rebuilt through the repository's
  canonical build path.

Passing these checks establishes that the Linux tool builds, runs, reports correctly, and does not
break the Android build. It does not establish physical-device hook behavior.

## Implementation Boundaries

- Preserve unrelated worktree changes and scope the spec/implementation commits to this tool.
- Keep the DexKit version single-sourced from the Gradle version catalog.
- Do not add `allowFailure` to existing resolvers merely to make the desktop report green.
- Do not swallow unexpected resolver failures or reinterpret them as blocked/expected.
- Do not use JADX-renamed runtime identifiers in new test fixtures or resolver code.
- Do not write test results under Gradle's `build/reports` hierarchy.
- Add `.wekit/dex-test/` and `dex-test-results/` to Git ignore rules during implementation.

## Acceptance Criteria

The design is complete when an implementation can satisfy all of the following:

1. `./x dex-test` discovers every `~/coding/wechat_*.apk` and runs each in a separate JVM.
2. Explicit repeated `--apk` arguments test only those APKs, independently and in the requested
   order.
3. The Linux worker executes the existing Feature `resolveInlineDex()` and `resolveDex()` source.
4. Allowed no-result placeholders are expected failures and do not fail the command.
5. A thrown delegate is unexpected; later unresolved delegates are reported as blocked, not as if
   they had run.
6. A normally completed Feature with an unresolved delegate is an incomplete unexpected failure.
7. One Feature or APK failure does not prevent unrelated Features or remaining APKs from running.
8. Default reports are written under `dex-test-results/<run-id>/`, never `build/reports`.
9. Native/source caches are kept under `.wekit/dex-test/` and do not enter Git.
10. The command returns zero only when every APK has no unexpected, blocked, incomplete,
    initialization, or infrastructure failures.
11. Focused tests, `git diff --check`, and `./x build` pass before the implementation is considered
    complete.
