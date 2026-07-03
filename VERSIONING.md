# Versioning

WeKit does not use semantic versioning. The module is distributed as CI-built "nightly" artifacts
with a deterministic, git-derived version scheme. There are no manual version bumps or release
branches.

## Module Version

Both values are computed at build time in `app/build.gradle.kts`.

| Field         | Source                                                                      | Example       |
|---------------|-----------------------------------------------------------------------------|---------------|
| `versionCode` | `git rev-list --count HEAD` — total number of commits in the current branch | `592`         |
| `versionName` | `"git+"` + `git rev-parse --short HEAD` — short commit hash                 | `git+8920253` |

- `versionCode` monotonically increases with every commit.
- `versionName` uniquely identifies the exact build commit.
- Neither is manually edited; they are fully automated.

The APK also embeds these in `BuildConfig`:

- `BuildConfig.COMMIT_HASH` — short commit hash
- `BuildConfig.TAG` — always `"WeKit"`
- `BuildConfig.BUILD_TIMESTAMP` — `System.currentTimeMillis()` at build time

## Release Model

There are **no stable releases**. The project uses a continuous delivery approach:

- **Every push to `master`** triggers CI, which builds signed release APKs and publishes them.
- GitHub Releases contains a **single rolling "CI" prerelease** — overwritten each build.
- `stable-ci-N` tags (e.g., `stable-ci-6`) are occasional manual checkpoints, not regularly
  maintained.

| Artifact                  | Channel                  | Update Frequency       |
|---------------------------|--------------------------|------------------------|
| APK (per-ABI + universal) | GitHub Actions, Telegram | Every push to `master` |
| `update.json`             | GitHub CI Release        | Every push to `master` |

### update.json

Generated in CI:

```json
{
  "versionCode": 592,
  "versionName": "git+8920253",
  "commit": "8920253"
}
```

Consumed by the module's built-in update checker. Fields mirror the build-time version
identifiers.
