# Linux DexKit resolver testing

The repository contains a desktop compatibility runner for the same DexKit resolver code used by
the Android cache flow. It does not install WeChat or execute hooks; it only checks whether the
current source matchers resolve against each APK's DEX files.

```bash
./x dex-test
./x dex-test --apk ~/coding/wechat_8069.apk --apk ~/coding/wechat_8069_3020_play.apk
./x dex-test --output-dir /absolute/report/root --verbose
```

With no `--apk`, regular files matching `~/coding/wechat_*.apk` are natural-sorted and tested.
Each APK is run in a separate JVM worker. Reports are written under
`dex-test-results/<run-id>/` by default (or the supplied output directory), with one JSON file per
APK and a `summary.json` aggregate. DexKit source/native build cache is kept under
`.wekit/dex-test/`.

Delegate statuses are:

- `SUCCESS`: a real descriptor was resolved;
- `EXPECTED_FAILURE`: `allowFailure = true` installed its placeholder;
- `UNEXPECTED_FAILURE`: the lookup or resolver threw, or an unclassified placeholder was used;
- `BLOCKED`: a later delegate did not run after an earlier failure;
- `INCOMPLETE`: the resolver returned while the delegate remained unresolved.

Expected failures alone return exit code 0. Any unexpected, blocked, incomplete, feature
initialization, worker, APK, native-build, or report failure returns non-zero after remaining APKs
have been attempted. A passing source-resolution report does not prove hook-time behavior on a
physical device.

The first run requires a JDK 21 environment, Android SDK `apkanalyzer`, CMake, Ninja, Git, and
network access to fetch the pinned DexKit source. The pinned Linux native library is built from
the DexKit version in `gradle/libs.versions.toml`; the tool verifies the cached checkout revision
before reusing it.

## CI and cloud reports

The `dex-test` CI job runs independently from the Android build on pushes to `master` and `dev`,
pull requests targeting those branches, and manual workflow runs. Its APK matrix comes from the
download links in [`docs/getting-started.md`](../getting-started.md): domestic builds use the listed
official WeChat URLs, while Google Play builds use the listed APKMirror release pages. Keep those
links unique by version and channel.

CI turns that document into a manifest with:

```bash
./x dex-test-ci sources \
  --doc docs/getting-started.md \
  --output /tmp/wekit-dex-test-sources.json
```

Downloaded APKs are validated as ZIPs containing `AndroidManifest.xml` and at least one DEX, then
cached using a key derived from the document, downloader, and manifest implementation. A matching
cached APK and SHA-256 sidecar are reused. APKMirror bundles are merged with the pinned APKEditor
version before testing.

CI caches only the verified DexKit source checkout under `.wekit/dex-test/source`; the native
library is rebuilt on every run so CMake never reuses stale absolute JDK include paths from an
older runner image.

Every CI event uploads the complete run directory as the `wekit-dex-test-reports` Actions artifact,
including failed per-APK reports and the aggregate `summary.json`. Any failed, blocked, incomplete,
worker, APK, or infrastructure result still fails the `dex-test` job after the available reports
have been preserved.

The downloader attempts every source even when an earlier one fails. CI passes `--failures-out` so
per-source failures are recorded as warnings and the successfully downloaded APKs are still
resolved and reported; a partial download failure does not fail the run, and the APK cache is saved
for the hosts that did download so a retry only re-fetches the missing ones. Without
`--failures-out` the script keeps its original fail-fast behavior for standalone use. Only when no
APK at all could be downloaded is the resolution step skipped and the job failed.

On `master` and `dev`, a second job updates the prerelease named `Dex Test` at tag `Dex-Test`. Successful
per-host reports use canonical asset names:

```text
wechat-<versionName>-<versionCode>-domestic.json
wechat-<versionName>-<versionCode>-google-play.json
```

The publisher replaces only hosts whose current report is `PASS`. If another host fails, its last
successful canonical asset remains available. If no host passes, the Release is not modified at
all. When at least one host passes, the current aggregate `summary.json` and Release notes are also
updated, so they may describe a failed attempt while a failed host's canonical asset remains from
an older successful run.

The Android cloud-resolution client consumes only its matching canonical PASS report. It does not
use `summary.json`, and it revalidates the host identity, generated resolver hash, delegate keys,
statuses, and descriptors before writing any local cache.
