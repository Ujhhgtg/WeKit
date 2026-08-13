# Cloud Dex Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish successful Linux DexKit reports per supported WeChat build and let the Android resolver dialog selectively import them before local resolution.

**Architecture:** The existing desktop report remains the only cloud protocol. Pure Kotlin maps a report plus current resolver metadata into validated cache import entries; `DexCacheManager` persists them, a cloud service performs bounded HTTP retrieval, and a local runner owns DexKit orchestration outside Compose. A focused xtask module extracts the documented APK matrix and stages canonical PASS reports, while CI scripts acquire fixed APKs and update only successful assets in a dedicated mutable Release.

**Tech Stack:** Kotlin/JVM, kotlinx.serialization JSON, OkHttp 5, Java NIO, Jetpack Compose Material 3, DexKit, Rust 2024 xtask, Bash, GitHub Actions, GitHub CLI.

## Global Constraints

- Work directly on `dev`; do not create an isolated worktree.
- Preserve unrelated user changes and stage only files belonging to this feature.
- Supported host versions are exactly those linked in `docs/getting-started.md`; do not add version-gated resolver code.
- Do not modify `dexMethod`, `dexClass`, `dexField`, inline matcher, `resolveDex`, or `resolveInlineDex` bodies.
- Directly consume desktop report schema version 1; do not introduce a second cloud cache schema.
- Match cloud assets by exact `versionName + versionCode + isGooglePlay`.
- Accept only complete per-item matches with the current method hash and every current delegate descriptor.
- Cloud failure must preserve local caches and keep local resolution available.
- The exact Simplified Chinese buttons are `云端解析`, `开始本地解析`, and `继续本地解析`.
- In Dex cache/resolution UI only, replace adaptation terminology with resolution terminology; do not rewrite unrelated host-support or feature copy.
- master, dev, PR, and manual CI runs perform the full Dex test; only master updates the `Dex-Test` Release.
- A partial master run replaces PASS host assets only and retains the last successful assets for failed hosts.
- A run with zero PASS hosts must not mutate the Release.
- Do not claim desktop tests prove real-device hook startup, dialog lifecycle, network behavior, or restart behavior.

---

### Task 1: Pure cloud report contract and selector

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/dexkit/cache/CloudDexReport.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dexkit/cache/CloudDexReportTest.kt`

**Interfaces:**
- Produces: `CloudDexHost(versionName: String, versionCode: Long, isGooglePlay: Boolean)`.
- Produces: `CurrentDexItem(className: String, technicalId: String, methodHash: String, delegateKeys: Set<String>)`.
- Produces: `CloudDexCacheEntry(technicalId: String, methodHash: String, descriptors: Map<String, String>)`.
- Produces: `CloudDexSelection(entries: List<CloudDexCacheEntry>, rejectedCount: Int)`.
- Produces: `CloudDexReport.assetName(host: CloudDexHost): String`.
- Produces: `CloudDexReport.select(jsonText: String, host: CloudDexHost, items: List<CurrentDexItem>): CloudDexSelection`.
- Consumes: no Android, WeChat, filesystem, network, `BaseFeature`, or generated-hash state.

- [ ] **Step 1: Write selector and asset-name tests before production code**

  Add `CloudDexReportTest` fixtures using literal schema-v1 JSON and test these exact behaviors:

  ```kotlin
  assertEquals(
      "wechat-8.0.69-3040-domestic.json",
      CloudDexReport.assetName(CloudDexHost("8.0.69", 3040, false)),
  )
  assertEquals(
      "wechat-8.0.69-3020-google-play.json",
      CloudDexReport.assetName(CloudDexHost("8.0.69", 3020, true)),
  )
  ```

  Build two `CurrentDexItem` values and assert that a PASS report selects both. Then mutate one
  report feature at a time and assert only that item is rejected for: stale `methodHash`, missing
  delegate, duplicate delegate, empty descriptor, `UNEXPECTED_FAILURE`, non-PASS feature, and
  duplicate target feature. Add whole-report rejection tests for schema != 1, top-level outcome !=
  `PASS`, and each host identity mismatch. Assert unknown fields, extra features, and extra
  delegates do not reject a valid current item.

- [ ] **Step 2: Run the focused test and verify RED**

  Run:

  ```bash
  ./gradlew :app:testStandardDebugUnitTest \
    --tests dev.ujhhgtg.wekit.dexkit.cache.CloudDexReportTest
  ```

  Expected: compilation fails because `CloudDexReport`, `CloudDexHost`, and related types do not
  exist. Fix only test syntax or fixture errors until the failure is caused by the missing
  production API.

- [ ] **Step 3: Implement the serializable DTOs and pure selector**

  In `CloudDexReport.kt`, declare private `@Serializable` report DTOs that decode only:

  ```kotlin
  private data class Report(
      val schemaVersion: Int,
      val outcome: String,
      val versionCode: Long,
      val versionName: String,
      val isGooglePlay: Boolean,
      val features: List<Feature>,
  )
  ```

  Configure `Json { ignoreUnknownKeys = true }`. Reject a duplicate report feature for a requested
  class instead of picking first or last. For each current item, require exact method hash, a PASS
  feature outcome (`PASS` or `PASS_WITH_EXPECTED_FAILURES` is eligible because the top-level APK
  remains PASS), unique delegate keys, every current key present, statuses limited to `SUCCESS` and
  `EXPECTED_FAILURE`, and non-empty descriptors. Ignore additional delegate keys only after checking
  that all delegate keys in the report are unique. Return entries in input-item order.

- [ ] **Step 4: Run the focused test and verify GREEN**

  Run the command from Step 2. Expected: all `CloudDexReportTest` cases pass.

- [ ] **Step 5: Refactor fixtures without changing behavior**

  Extract a test-only `reportJson(...)` builder if it removes repeated literal JSON. Re-run the
  focused test and keep it green.

- [ ] **Step 6: Commit the contract**

  ```bash
  git add \
    app/src/main/java/dev/ujhhgtg/wekit/dexkit/cache/CloudDexReport.kt \
    app/src/test/java/dev/ujhhgtg/wekit/dexkit/cache/CloudDexReportTest.kt
  git commit -m "feat: validate cloud dex reports"
  ```

### Task 2: Cache import and cloud retrieval service

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/dexkit/cache/DexCacheManager.kt`
- Create: `app/src/main/java/dev/ujhhgtg/wekit/dexkit/cache/CloudDexResolver.kt`
- Create: `app/src/test/java/dev/ujhhgtg/wekit/dexkit/cache/CloudDexCacheWriterTest.kt`

**Interfaces:**
- Consumes: Task 1 types and `CloudDexReport.select`.
- Produces: `DexCacheManager.methodHash(item: IResolveDex): String` as an internal read-only API.
- Produces: `DexCacheManager.importCloudCaches(entries: List<CloudDexCacheEntry>)`.
- Produces: `CloudDexResolutionResult(importedCount: Int, remainingItems: List<IResolveDex>, notice: CloudDexNotice?)`.
- Produces: `CloudDexNotice` categories for not found, network failure, invalid report, no match, and partial match.
- Produces: `CloudDexResolver.resolve(items: List<IResolveDex>): CloudDexResolutionResult`.

- [ ] **Step 1: Write cache-file transaction tests before production code**

  Add `CloudDexCacheWriterTest` with `@TempDir`. Call the wished-for internal
  `writeCloudCacheFiles(cacheDir, entries, timestamp)` using two complete entries. Assert both JSON
  files use `DexCacheManager.cacheFileName(technicalId)`, contain the supplied method hash,
  timestamp, and descriptors, and leave no `.tmp` or `.bak` siblings. Pre-create one destination,
  import a replacement, and assert the new complete JSON replaces it.

- [ ] **Step 2: Run the focused test and verify RED for the new expectation**

  Run:

  ```bash
  ./gradlew :app:testStandardDebugUnitTest \
    --tests dev.ujhhgtg.wekit.dexkit.cache.CloudDexCacheWriterTest
  ```

  Expected: compilation fails because `writeCloudCacheFiles` does not exist.

- [ ] **Step 3: Add narrow cache-manager APIs and atomic bulk import**

  Rename private `calculateMethodHash` to internal `methodHash` and use it from existing validity and
  save paths. Implement the internal `writeCloudCacheFiles(cacheDir, entries, timestamp)` used by
  `importCloudCaches`. It serializes all entries to sibling temp files first. Before replacing
  destinations, move existing files to sibling backup names. If any commit move fails, restore every
  backup and delete any newly created destination without a backup. Always clean temp and backup
  paths best-effort. Never derive a path from report data other than the already-validated
  `technicalId` matched to a live item.

- [ ] **Step 4: Implement bounded cloud retrieval**

  `CloudDexResolver` owns a private OkHttp client with 10-second connect/read/call timeouts, follows
  redirects, and downloads:

  ```text
  https://github.com/Ujhhgtg/WeKit/releases/download/Dex-Test/<canonical asset name>
  ```

  Reject a body larger than 8 MiB using both `Content-Length` and a bounded byte read. Close every
  response. Map 404 separately; map other HTTP errors and I/O failures to recoverable notices.
  Construct `CurrentDexItem` from each pending `BaseFeature` using runtime class name, technical ID,
  `DexCacheManager.methodHash`, and current delegate keys. Parse/select, import selected entries, and
  call `DexCacheManager.getOutdatedItems(items)` as the final authority. Return the actual imported
  count as `items.size - remainingItems.size` rather than trusting the selection count.

- [ ] **Step 5: Run focused tests and compile production sources**

  ```bash
  ./gradlew :app:testStandardDebugUnitTest \
    --tests dev.ujhhgtg.wekit.dexkit.cache.CloudDexReportTest \
    --tests dev.ujhhgtg.wekit.dexkit.cache.CloudDexCacheWriterTest
  ./gradlew :app:compileStandardDebugKotlin
  ```

  Expected: both commands exit 0.

- [ ] **Step 6: Commit cache import and cloud retrieval**

  ```bash
  git add \
    app/src/main/java/dev/ujhhgtg/wekit/dexkit/cache/DexCacheManager.kt \
    app/src/main/java/dev/ujhhgtg/wekit/dexkit/cache/CloudDexResolver.kt \
    app/src/test/java/dev/ujhhgtg/wekit/dexkit/cache/CloudDexCacheWriterTest.kt
  git commit -m "feat: import cloud dex caches"
  ```

### Task 3: Extract local resolver and rebuild dialog state flow

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/ui/content/LocalDexResolver.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/content/DexResolver.kt`

**Interfaces:**
- Consumes: `CloudDexResolver.resolve` and `CloudDexResolutionResult`.
- Produces: `LocalDexProgress.Start/Complete/Failed`.
- Produces: `LocalDexFailure(displayName: String, error: Exception)`.
- Produces: `LocalDexResolutionResult(failures: List<LocalDexFailure>)`.
- Produces: `LocalDexResolver.resolve(items, onProgress)` as a suspending operation.

- [ ] **Step 1: Extract the local resolution runner without changing Feature resolver bodies**

  Move DexKit lifetime, `.asFlow().map { async(Dispatchers.IO) ... }.buffer(8)`, per-item
  `resolveAllDex`, `DexCacheManager.saveItemCache`, logging, and result collection into
  `LocalDexResolver.kt`. Keep direct `BaseFeature.technicalPath` use and the current per-item catch.
  Do not wrap any hook registration and do not touch resolver declarations.

- [ ] **Step 2: Replace nested Compose operations with explicit dialog state**

  In `DexResolver.kt`, use a sealed phase equivalent to:

  ```kotlin
  Idle(notice: CloudDexNotice? = null, cloudAttempted: Boolean = false)
  DownloadingCloud
  ResolvingLocal
  Done(source: CompletionSource, failures: List<LocalDexFailure>)
  Error(message: String)
  ```

  Store the current pending items as Compose state initialized from `outdatedItems`. Cloud action:
  set `DownloadingCloud`, invoke the service from the provided scope, replace pending items from the
  result, then enter cloud `Done` when empty or `Idle(notice, cloudAttempted = true)` when items
  remain. Local action must snapshot the current pending list, reset progress maps/counts, run the
  extracted resolver, and enter local `Done` or `Error`.

- [ ] **Step 3: Implement exact button and completion behavior**

  Render the initial row as close, cloud, local. Use neutral styling for cloud and positive styling
  for local. Exact Chinese resources are supplied in Task 4, but callers must reference IDs whose
  English meanings are cloud resolution, start local resolution, and continue local resolution.
  During cloud/local activity, disable or hide actions. Cloud complete offers close and restart.
  Recoverable cloud notice offers `继续本地解析`. Local success/partial failure retains error detail,
  clipboard report, and restart. Do not start skipped Features in-process.

- [ ] **Step 4: Compile and statically verify resolver hash scope**

  ```bash
  ./gradlew :app:compileStandardDebugKotlin
  git diff -- app/src/main/java/dev/ujhhgtg/wekit/features \
    app/src/main/java/dev/ujhhgtg/wekit/dexkit/dsl
  ```

  Expected: Kotlin compilation exits 0 and the resolver/Feature diff is empty.

- [ ] **Step 5: Commit the UI refactor**

  ```bash
  git add \
    app/src/main/java/dev/ujhhgtg/wekit/ui/content/DexResolver.kt \
    app/src/main/java/dev/ujhhgtg/wekit/ui/content/LocalDexResolver.kt
  git commit -m "feat: add cloud dex resolution flow"
  ```

### Task 4: Dex resolution terminology and localized UI

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values-zh-rCN/strings.xml`
- Modify: `app/src/main/res/values-zh-rTW/strings.xml`
- Modify: Dex-setting callers found by the scoped resource-ID search.

**Interfaces:**
- Consumes: Task 3's new resource IDs.
- Produces: complete English, Simplified Chinese, and Traditional Chinese cloud/local/notice copy.

- [ ] **Step 1: Inventory only Dex-related adaptation resources**

  Run:

  ```bash
  rg -n "dex_cache|settings_.*adaptation|reset_dex_cache|适配|適配|adaptation|readapt|compatibility data" \
    app/src/main/res/values*/strings.xml \
    app/src/main/java/dev/ujhhgtg/wekit
  ```

  Record the resource IDs used by the Dex dialog, disable-resolution setting, reset-cache setting,
  hot-update setting, and ResetDexCache feature. Explicitly exclude host support tables and
  `noncompose_message_menu_adapted_section`.

- [ ] **Step 2: Rename Dex-specific resource IDs and translate exact states**

  Add/rename catalog entries for: downloading cloud report, validating cloud report, cloud complete,
  partial cloud result, not found, network error, invalid report, no matching entries, resolving,
  local all-success, local partial failure, cloud resolution, start local resolution, and continue
  local resolution. Replace existing Dex-specific adaptation wording with resolution wording in all
  three locales. Preserve placeholder indexes and formatting exactly across locales.

- [ ] **Step 3: Update callers and prove no Dex-specific old IDs remain**

  Update Kotlin references atomically with resource renames. Re-run the scoped `rg`; the only
  remaining adaptation hits must be explicitly out-of-scope meanings.

- [ ] **Step 4: Run i18n and compile gates**

  ```bash
  ./x i18n-check
  ./gradlew :app:compileStandardDebugKotlin
  ```

  Expected: both exit 0.

- [ ] **Step 5: Commit terminology and catalogs**

  ```bash
  git add app/src/main/res app/src/main/java/dev/ujhhgtg/wekit
  git commit -m "refactor: standardize dex resolution wording"
  ```

### Task 5: CI source matrix and Release staging in xtask

**Files:**
- Create: `xtask/src/dex_test_ci.rs`
- Modify: `xtask/src/main.rs`
- Modify: `xtask/Cargo.toml` only if a parser dependency is demonstrably required.

**Interfaces:**
- Produces CLI: `./x dex-test-ci sources --doc <path> --output <manifest.json>`.
- Produces CLI: `./x dex-test-ci stage-release --run-dir <dir> --output-dir <dir> --sha <sha>`.
- Produces manifest entries: version name, channel, source URL, deterministic APK filename.
- Produces staged PASS assets using the same canonical name contract as Task 1.
- Produces `summary.json`, `release-notes.md`, and `assets.txt`; exits 0 with an empty `assets.txt`
  when zero reports passed so the caller can skip Release mutation.

- [ ] **Step 1: Write Rust tests for document extraction and canonical staging**

  In `dex_test_ci.rs`'s test module, use an inline Markdown table with two official and two APKMirror
  links. Assert ordered, unique entries and filenames such as `wechat_8069_domestic.apk` and
  `wechat_8069_google_play.apk`. Add failures for an empty source set and duplicate
  version/channel.

  Build a temporary run directory with one PASS and one FAIL report. Assert `stage_release` copies
  only the PASS report to `wechat-8.0.69-3040-domestic.json`, lists only it in `assets.txt`, and
  includes both PASS and FAIL in release notes. Add a zero-PASS test that leaves the staged output
  asset list empty and does not invent a canonical host report.

- [ ] **Step 2: Run Rust tests and verify RED**

  ```bash
  cargo test -p xtask dex_test_ci
  ```

  Expected: compilation fails because the module and functions do not exist.

- [ ] **Step 3: Implement source extraction and stage-release logic**

  Parse only links whose labels match `<version> 官方` or `<version> APKMirror`. Preserve document
  order, normalize the channel to `domestic` or `google-play`, and serialize a camelCase manifest.
  Reuse/deserialise the existing schema-v1 report fields for staging. Require unique canonical asset
  names and copy bytes without rewriting the report. Generate notes with commit SHA, report outcome,
  and all resolver counts. Copy the current full `summary.json` only when at least one PASS exists.

- [ ] **Step 4: Wire the CLI and verify GREEN**

  Add `DexTestCi` to `Cmd`, dispatch it beside `DexTest`, and update top-level usage comments. Run:

  ```bash
  cargo test -p xtask dex_test_ci
  ./x dex-test-ci sources \
    --doc docs/getting-started.md \
    --output /tmp/wekit-dex-test-sources.json
  jq '.sources | length' /tmp/wekit-dex-test-sources.json
  ```

  Expected: tests pass and the checked-in document yields 13 sources.

- [ ] **Step 5: Commit xtask CI support**

  ```bash
  git add xtask/src/dex_test_ci.rs xtask/src/main.rs xtask/Cargo.toml Cargo.lock
  git commit -m "feat: stage dex test release assets"
  ```

### Task 6: Fixed APK acquisition and GitHub Actions jobs

**Files:**
- Create: `.github/scripts/download-dex-test-apks.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `docs/development/linux-dex-test.md`

**Interfaces:**
- Consumes: Task 5 source manifest.
- Produces: each manifest entry's deterministic APK under the passed cache directory.
- Consumes: `./x dex-test --apk ... --output-dir ...`.
- Consumes: Task 5 staged asset list and notes.

- [ ] **Step 1: Implement strict fixed-APK acquisition script**

  The script accepts `MANIFEST CACHE_DIR`. Use `set -euo pipefail`, explicit paths, `curl --fail
  --location --retry 3`, and temporary files inside `CACHE_DIR`. For domestic sources, download the
  direct official URL. For APKMirror, follow the focused flow from `j-hc/revanced-magisk-module`:
  fetch the fixed release page, select arm64-v8a/universal APK before bundle, follow the variant and
  download pages, and download the final nofollow URL. If only a bundle exists, download it and use
  pinned APKEditor `V1.4.7` to merge it to one APK. Do not redownload an existing validated cache
  entry.

  Validate every result with `unzip -t`, require `AndroidManifest.xml` and at least one `classes*.dex`,
  and write a sidecar SHA-256. A cached APK is reused only when validation succeeds and its sidecar
  matches. Print the final ordered APK paths, one per line, to stdout; send status text to stderr so
  command substitution remains safe.

- [ ] **Step 2: Add independent `dex-test` job**

  Configure checkout with submodules, JDK 21, Gradle cache, Rust, `gcc-multilib`, `jq`, `curl`, and
  `unzip`. Restore:

  - fixed APK cache keyed by downloader schema plus hashes of the doc, script, and xtask source;
  - `.wekit/dex-test` keyed by OS, architecture, DexKit version/revision inputs;
  - Gradle caches using the repository's existing pattern.

  Generate the manifest, acquire APKs, run one `./x dex-test` with all 13 explicit `--apk` arguments,
  and capture its exit status. Upload the complete run directory with `if: always()`. Expose the run
  directory artifact name and status, then end with the captured non-zero result so master/dev/PR
  validation stays strict.

- [ ] **Step 3: Add master-only partial Release publisher**

  `release-dex-test` uses `needs: dex-test`, `if: always() && github.ref == 'refs/heads/master' &&
  github.event_name != 'pull_request'`, and `contents: write`. Download the report artifact, run
  `stage-release`, and if `assets.txt` is empty, print a skip message without creating or editing the
  Release. Otherwise create `Dex-Test` as a prerelease if absent, edit its notes, and upload
  `summary.json` plus every listed PASS asset with `gh release upload Dex-Test --clobber`. Never
  delete the Release or tag.

- [ ] **Step 4: Document CI and cloud artifacts**

  Extend `docs/development/linux-dex-test.md` with the documented APK source-of-truth, CI triggers,
  cache behavior, canonical asset naming, partial-publish semantics, and the distinction between
  diagnostic `summary.json` and client-consumed per-host PASS reports.

- [ ] **Step 5: Validate YAML/script syntax and local source acquisition behavior**

  ```bash
  bash -n .github/scripts/download-dex-test-apks.sh
  ./x dex-test-ci sources \
    --doc docs/getting-started.md \
    --output /tmp/wekit-dex-test-sources.json
  jq -e '.sources | length == 13' /tmp/wekit-dex-test-sources.json
  ruby -e 'require "yaml"; YAML.load_file(".github/workflows/ci.yml", aliases: true)'
  ```

  Do not download all 13 multi-hundred-megabyte APKs locally merely to validate YAML. Smoke the
  script against one already-cached domestic APK by copying it into a temporary cache with the
  expected sidecar, then verify the script reuses it without network access. GitHub Actions remains
  the all-source integration gate.

- [ ] **Step 6: Commit CI integration and documentation**

  ```bash
  git add \
    .github/scripts/download-dex-test-apks.sh \
    .github/workflows/ci.yml \
    docs/development/linux-dex-test.md
  git commit -m "ci: publish cloud dex reports"
  ```

### Task 7: Smoke report staging and full repository verification

**Files:**
- Modify only files required to fix failures introduced by Tasks 1-6.

**Interfaces:**
- Consumes: all preceding tasks.
- Produces: fresh verification evidence and an explicit real-device pending list.

- [ ] **Step 1: Run focused Kotlin and Rust suites**

  ```bash
  ./gradlew :app:testStandardDebugUnitTest \
    --tests dev.ujhhgtg.wekit.dexkit.cache.CloudDexReportTest \
    --tests dev.ujhhgtg.wekit.dexkit.cache.CloudDexCacheWriterTest \
    --tests dev.ujhhgtg.wekit.dexkit.cache.DexCacheCompatibilityTest
  cargo test -p xtask
  ```

- [ ] **Step 2: Smoke the canonical staging path with one existing supported APK report**

  Reuse the newest existing run containing a PASS report under `dex-test-results/` or run
  `/home/ujhhgtg/coding/wechat_8069.apk` only if no PASS report exists. Stage it with:

  ```bash
  PASS_RUN=$(for report in $(find dex-test-results -mindepth 2 -maxdepth 2 -name '*.json' ! -name summary.json -print); do
    jq -e '.outcome == "PASS"' "$report" >/dev/null && dirname "$report" && break
  done)
  test -n "$PASS_RUN"
  ./x dex-test-ci stage-release \
    --run-dir "$PASS_RUN" \
    --output-dir /tmp/wekit-dex-test-release-smoke \
    --sha "$(git rev-parse HEAD)"
  jq -e '.schemaVersion == 1' /tmp/wekit-dex-test-release-smoke/summary.json
  ```

  Inspect `assets.txt` and verify each listed filename equals its report's metadata-derived name.

- [ ] **Step 3: Run catalogs, diff, and full build gates**

  ```bash
  ./x i18n-check
  git diff --check 4b4b92bf..HEAD
  ./x build
  ```

  Read complete output and require exit code 0 for each command.

- [ ] **Step 4: Review requirements and current diff**

  Re-read `docs/superpowers/specs/2026-08-13-cloud-dex-resolution-design.md`, inspect
  `git diff 4b4b92bf..HEAD`, and check every Global Constraint. Confirm there is no Feature resolver
  or matcher diff, no Release deletion, no dev/PR write permission, no cloud path derived from raw
  report data, and no Dex-specific adaptation wording left in scope.

- [ ] **Step 5: Record real-device pending verification in the handoff**

  State that these are not desktop-proven: matching cloud import and restart, stale-report partial
  import, offline fallback, real WeChat cache load after restart, and normal hook startup.

- [ ] **Step 6: Commit any verification-only fixes**

  If verification required source fixes, stage only those files and commit with a precise message.
  If no fixes were needed, do not create an empty commit.
