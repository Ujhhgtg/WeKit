# Cloud Dex Resolution Design

## Goal

Build a cloud-assisted Dex resolution flow on top of the existing Linux DexKit runner. CI will
resolve every WeChat APK listed in `docs/getting-started.md`, publish successful per-host JSON
reports to a dedicated mutable GitHub Release, and keep full run diagnostics visible. The Android
resolver dialog will be able to import matching descriptors from that Release before offering the
existing local DexKit resolution path.

This work also standardizes Dex-related user-facing terminology on "resolution" (`解析`). Existing
Dex UI text that says "adaptation", "readaptation", or compatibility data will be changed to the
equivalent resolution wording. The broader product concept of adapting or supporting host
versions, and unrelated uses of "adapted", remain unchanged.

## Scope

The feature covers:

- CI acquisition and caching of the supported domestic and Google Play WeChat APKs;
- desktop Dex resolution on master pushes, dev pushes, pull requests, and manual workflow runs;
- partial publication of successful host reports to a dedicated `Dex-Test` tag / `Dex Test`
  prerelease on master only;
- cloud report download, validation, selective import, and pending-item recalculation on Android;
- a focused refactor that separates the resolver UI, local resolution runner, cloud report
  selection, and cache persistence;
- Dex-related terminology cleanup in English, Simplified Chinese, and Traditional Chinese;
- automated validation for pure cache/report logic and the existing build/i18n gates.

The feature does not automatically start skipped hooks in the current WeChat process after a
cloud import. A restart is still required. It also does not change any Feature resolver matcher,
supported host range, or general host-version support wording.

## Source APK Set

`docs/getting-started.md` remains the source of truth for the APK set. The CI downloader extracts:

- domestic official download links labelled `8.0.xx 官方` from the domestic support table;
- APKMirror release links labelled `8.0.xx APKMirror` from the Google Play support table.

At the time of this design, that is eleven domestic APKs (8.0.65, 8.0.66, 8.0.67, 8.0.68,
8.0.69, 8.0.70, 8.0.71, 8.0.72, 8.0.74, 8.0.76, and 8.0.77) plus Google Play 8.0.68 and
8.0.69. CI must fail visibly if the document contains no matching links, a download cannot be
validated as an APK, or two extracted sources would produce the same host identity.

Domestic APKs are downloaded directly from their official URLs. The APKMirror implementation
follows the approach used by `j-hc/revanced-magisk-module`: resolve the requested release page,
select a compatible arm64/universal APK or bundle, follow the download pages, and download the
final asset. If only a bundle is available, merge it with a pinned APKEditor release before the
Dex test. The downloader records the source URL and computes SHA-256 for diagnostics.

The fixed APK directory is restored through Actions Cache. Its key includes a downloader schema
version and hashes of `docs/getting-started.md` and the downloader implementation, so changing a
listed URL or download logic invalidates the cache. The DexKit source/native cache and Gradle cache
remain separate from the host APK cache.

## CI Architecture

### Dex test job

A new `dex-test` job in `.github/workflows/ci.yml` runs independently of the Android build jobs on:

- pushes to `master`;
- pushes to `dev`;
- pull requests targeting `master` or `dev`;
- `workflow_dispatch`.

The job checks out submodules, installs the JDK and desktop dependencies, restores the APK,
DexKit, and Gradle caches, downloads any missing APKs, and invokes one `./x dex-test` command with
all extracted APK paths. One invocation is important: the runner already attempts every APK after
an individual failure and produces a single summary plus one report per APK.

The command's non-zero status is captured instead of immediately ending the job. The job first
uploads the complete run directory as a GitHub Actions artifact, exposes the run directory and
status to later steps, and only then applies a final failure gate. Consequently, pull requests and
dev pushes fail when any supported version fails, while still preserving all reports for review.

### Release job

A separate `release-dex-test` job runs only for `master` (including a manual run whose ref is
`master`). It uses `if: always()` and downloads the report artifact even when `dex-test` will be
reported as failed. This is the only job with `contents: write`; dev and pull-request jobs never
receive a publishing path.

The dedicated Release uses tag `Dex-Test`, name `Dex Test`, and is marked as a prerelease. It is
created if absent and updated in place without deleting the tag or Release. Deleting the Release is
forbidden because a partial run must retain the last successful report for failed hosts.

For each report whose top-level outcome is `PASS`, the publisher derives a canonical asset name
from report metadata and uploads it with clobber semantics:

```text
wechat-<versionName>-<versionCode>-domestic.json
wechat-<versionName>-<versionCode>-google-play.json
```

For example, Google Play 8.0.69 build 3020 is
`wechat-8.0.69-3020-google-play.json`. Including both version name and code avoids the collision
between domestic 8.0.76 and 8.0.77, which may share a version code. The channel suffix separates
domestic and Google Play builds.

Publication is transactional at the per-host asset level:

- if no host passed, the Release, its assets, its notes, and its summary remain untouched;
- if some hosts passed, only their canonical reports are replaced; canonical reports for failed
  hosts remain at their previous successful versions;
- if every host passed, all canonical reports are replaced;
- whenever at least one host passed, `summary.json` and the Release notes are updated with the
  current commit, timestamps, each host outcome, and resolver counts.

The published summary may describe a failed current attempt whose canonical report was retained
from an older successful run. Clients do not consume `summary.json`; they consume only the
canonical host report and validate every imported entry against the installed WeKit build.

After artifact upload and any eligible master publication, the original Dex test result remains
authoritative for the job status. Partial publication therefore never turns a failing compatibility
run green.

## Cloud Report Contract

The client directly consumes the existing per-APK desktop report instead of introducing a second
cache schema. It decodes only required fields and ignores additional diagnostic fields:

- top-level `schemaVersion`, `outcome`, `versionName`, `versionCode`, and `isGooglePlay`;
- feature `className`, `methodHash`, and `outcome`;
- delegate `key`, `status`, `descriptor`, and `isPlaceholder`.

Only schema version 1 and a top-level `PASS` report are eligible. The report host identity must
exactly equal `HostInfo.versionName`, `HostInfo.versionCode`, and `HostInfo.isHostGooglePlay`.

The selector associates a report feature with an `IResolveDex` item by exact runtime class name.
Each item is independently eligible only when:

1. its report feature outcome is `PASS` or `PASS_WITH_EXPECTED_FAILURES`;
2. its report `methodHash` equals the current generated method hash;
3. every current delegate key occurs exactly once in the report;
4. every selected delegate has status `SUCCESS` or `EXPECTED_FAILURE`;
5. every selected descriptor is non-null and non-empty;
6. the report contains no duplicate key that could make selection ambiguous.

Extra report features and extra delegate diagnostics are ignored. A missing, stale, or incomplete
feature does not invalidate other eligible features. This allows the last successful report to
populate unchanged resolvers after a newer CI run fails, while changed resolvers remain queued for
local resolution.

The report URL is the existing anonymous GitHub Release download URL under
`https://github.com/Ujhhgtg/WeKit/releases/download/Dex-Test/`. The request uses the project's
existing OkHttp stack with bounded connect, read, and call timeouts and normal redirect handling.
HTTP failures, timeouts, malformed JSON, identity mismatch, and an ineligible report are recoverable
cloud-resolution outcomes, not fatal resolver states.

## Cache Import

Cloud JSON parsing and selection are pure Kotlin and have no Android or WeChat runtime dependency.
They produce an import plan containing the target technical ID, current method hash, and a complete
delegate descriptor map. This boundary makes the public report validation independently testable.

`DexCacheManager` remains responsible for persistence. It exposes the current generated hash to
the selector through a narrowly scoped internal API and gains a bulk import operation. The bulk
operation serializes every selected item using the existing local cache shape:

```json
{
  "methodHash": "...",
  "timestamp": 0,
  "Delegate:key": "descriptor"
}
```

The timestamp is the actual import time. Files are first written beside the destination as
temporary files. Only after every selected JSON object has serialized successfully are the
temporary files moved over their destinations with replace semantics. A failure before the commit
phase leaves existing caches untouched; a move failure is reported and leaves the item invalid or
at its prior valid state rather than treating it as imported. Temporary files are cleaned up on a
best-effort basis.

The importer only writes items in the dialog's current pending set. It does not delete valid local
caches or clear the entire cache directory. After import, the UI calls
`DexCacheManager.getOutdatedItems` again on the previous pending set. This existing validation is
the final authority for whether an item was successfully resolved from the cloud.

## Android Resolver Architecture

`DexResolver.kt` becomes the Compose presentation and state coordinator. Two implementation units
sit behind it:

- a local resolution runner that owns DexKit lifetime, parallelism, progress events, per-item
  persistence, and failure collection;
- a cloud resolver that builds the canonical asset name, downloads the report, invokes the pure
  selector, imports eligible caches, and returns imported and remaining counts plus a recoverable
  notice when appropriate.

The local runner preserves the current concurrency of eight, current per-item exception isolation,
error logging, partial-failure details, and progress reporting. Refactoring must not change any
Feature resolver body or matcher and therefore must not alter generated resolver hashes.

The dialog state is explicit:

- `Idle`: shows the current pending count and optional result notice;
- `DownloadingCloud`: disables actions and shows download/validation activity;
- `ResolvingLocal`: shows item progress and the two progress indicators;
- `Done`: shows cloud-complete, local-complete, or partial local failure copy;
- `Error`: represents an unrecoverable local runner/DexKit failure.

The pending list is mutable state initialized from the argument passed by `FeaturesLoader`. After a
cloud attempt it is replaced with the recalculated outdated list. Local resolution always consumes
the current pending snapshot, never the original argument.

## User Experience

The initial button row uses negative / neutral / positive visual ordering:

```text
关闭 | 云端解析 | 开始本地解析
```

The exact Chinese cloud and local action labels are:

- `云端解析`;
- `开始本地解析`;
- `继续本地解析`.

While a cloud request is active, resolution actions are disabled and the dialog shows the current
download or validation status. The dialog is not directly dismissible at the outer container level,
matching current behavior.

If every pending item becomes valid after import, the dialog reports cloud resolution complete and
offers close and restart actions. If only some items become valid, it reports how many were resolved
from the cloud and how many remain, then offers `继续本地解析`. If no item is imported because the
asset is missing, stale, malformed, or unreachable, the notice explains the recoverable reason and
still offers `继续本地解析`.

Local completion retains the existing all-success and partial-failure messages, expandable error
details, copy action, and restart action. A local DexKit initialization or orchestration failure
uses the `Error` phase and is logged. Per-item failures remain `Done` with partial-failure details.

Cloud-imported descriptors are not used to start skipped Features in the current process. Both
cloud-complete and local-complete paths require restarting WeChat before those Features load. This
avoids installing hooks after the normal startup sequence or leaving partially initialized
Features active.

All network, JSON parsing, cache serialization, and local DexKit work run off the main thread.
Compose state mutation returns to the main dispatcher.

## Terminology Migration

Within Dex cache/resolution UI and settings, English, Simplified Chinese, and Traditional Chinese
copy changes from adaptation terminology to resolution terminology. Resource identifiers containing
Dex-specific `adaptation` are renamed to `resolution` or a precise local/cloud action name. Examples
include:

- `正在适配` / `Adapting` to `正在解析` / `Resolving`;
- `开始适配` / `Start adaptation` to `开始本地解析` / `Start local resolution`;
- `禁用版本适配` to `禁用解析`;
- `重置适配信息` and compatibility/adaptation data to `重置解析缓存` or `解析缓存`;
- hot-update readaptation wording to rerun/reset resolution wording.

The title may continue to identify the object as a DEX cache update, while verbs and process names
use resolution. This requirement does not replace every phrase with the literal string "Dex
resolution". It standardizes the two competing process terms on "resolution".

The migration is limited to Dex-related resources and callers. Host compatibility documentation,
Feature descriptions that use adaptation in another domain, and unrelated strings such as a
message menu's adapted section are out of scope.

## Error Handling and Security

The GitHub Release is trusted as the same-repository distribution channel, but mutable assets are
still treated as untrusted input. The client never constructs a cache path from report data. It
maps a validated class name to an existing in-memory item and derives the destination using that
item's technical ID through `DexCacheManager`.

Strict schema, host identity, method hash, delegate key, status, and descriptor checks prevent an
old or mismatched report from being accepted as current. Duplicate features or delegate keys for a
target item are rejected for that item. JSON size is bounded at download time to prevent loading an
unreasonably large response into memory. HTTP response bodies are always closed.

Cloud failure never deletes caches and never prevents local resolution. CI validates downloaded
files as Android APKs before invoking DexKit and records their SHA-256. A failed or malformed host
download makes the Dex test job fail and produces no replacement report for that host.

## Testing and Verification

Pure Kotlin tests cover:

- canonical domestic and Google Play asset names;
- exact host identity acceptance and rejection;
- full current-report selection;
- partial selection from an older report when only some method hashes match;
- missing feature, method-hash mismatch, duplicate key, missing key, empty descriptor, disallowed
  status, and failed feature rejection;
- extra report fields/features/delegates being ignored;
- a malformed or non-PASS report producing no import plan.

These tests are appropriate under the repository testing strategy because report parsing and cache
selection are WeKit-owned deterministic core logic with no WeChat runtime dependency. Compose,
host startup, and hook behavior are not forced behind artificial test seams.

CI/downloader verification covers:

- extracting the expected domestic and Google Play sources from the checked-in document;
- producing unique deterministic APK names;
- rejecting an empty source set and invalid APK;
- preserving a cached fixed APK without downloading it again;
- generating canonical report asset names from actual report metadata.

Repository verification is:

```bash
cargo test -p xtask
./x i18n-check
./gradlew :app:testStandardDebugUnitTest \
  --tests dev.ujhhgtg.wekit.dexkit.cache.CloudDexReportTest
./x build
git diff --check
```

No Feature Dex matcher changes are in scope, so a full supported-version desktop rerun is not
required solely for the Android refactor. The publishing/renaming path is smoke-tested locally with
one existing supported APK and its generated report. The workflow itself validates all documented
hosts once merged or run in GitHub Actions.

Real-device verification remains explicit:

1. With a matching Release report, cloud resolution imports all current items and requests restart.
2. With a deliberately stale report, unchanged items import and the dialog offers to continue local
   resolution for the remainder.
3. With no network or a missing asset, existing caches remain intact and local resolution remains
   available.
4. After restart, imported caches load and the corresponding Features follow the normal startup
   path.

Desktop tests and `./x build` do not claim these device behaviors are proven until exercised in a
supported WeChat installation.
