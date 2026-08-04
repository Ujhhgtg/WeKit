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
