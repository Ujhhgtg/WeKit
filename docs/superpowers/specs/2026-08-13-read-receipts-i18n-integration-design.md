# Read Receipts i18n Integration Design

## Goal

Merge the reviewed read-receipts native server and Cloudflare Tunnel branch into the current local
`dev`, remove its completed worktree, and migrate every Android user-facing read-receipts string to
WeKit's process-local internationalization system without changing protocol or lifecycle behavior.

## Scope

The migration covers Android text that a user can see:

- Compose settings, dialogs, buttons, labels, descriptions, validation messages, and status text;
- Toasts, native dialogs, notifications, clipboard/share feedback, and chooser titles;
- Browser-login, tunnel, origin-server, configuration, and connection status/error presentation;
- read-receipt message rendering, including known-zero and suffix/placeholder text.

The migration does not translate technical data:

- feature configuration keys, enum names, Binder fields, JSON keys, URLs, SQL, protocol strings,
  DexKit matchers, class/member names, or database content;
- internal logs and errors that are never shown directly to users;
- the standalone desktop read-receipts server, dashboard, REPL, and CLI.

When an internal error reaches the Android UI, the UI resolves a localized bounded category or
message at display time. It must not expose or translate arbitrary raw connector detail.

## Integration Strategy

Use a normal merge commit from `worktree-read-receipts-native-tunnel` into the current local `dev`.
Do not rebase or reconstruct the large reviewed branch with cherry-picks. Resolve the known overlap
in `Cargo.lock`, `WeChatMessageViewApi.kt`, `MessageTimeEnhancements.kt`, `ReadReceipts.kt`, and
`xtask/src/main.rs` by retaining both the reviewed read-receipts behavior and current `dev` i18n
architecture.

The merge must preserve:

- separate origin and connector generations;
- tunnel-first, origin-second shutdown and typed failure propagation;
- non-blocking send hooks and bounded polling;
- authenticated reader metadata and secret-handling boundaries;
- current `dev` locale providers, localized contexts, and resource validation;
- supported-host resolver semantics, without introducing version branches or weakening matchers.

After the merged tree passes its pre-i18n baseline verification, remove the completed linked
worktree and delete its merged branch. All subsequent i18n work occurs directly on local `dev`, as
required by the repository guide.

## Resource Architecture

English remains the source and fallback catalog:

- `app/src/main/res/values/strings.xml`: complete English source strings;
- `app/src/main/res/values-zh-rCN/strings.xml`: complete Simplified Chinese translations for this
  feature;
- `app/src/main/res/values-zh-rTW/strings.xml`: complete Traditional Chinese translations for this
  feature.

Keys use the `read_receipts_` prefix, except when an existing generic action or status resource is
semantically exact. Formatted strings use indexed Java Formatter placeholders. Quantity-dependent
messages use plurals only when grammatical selection is meaningful; protocol identifiers and
literal placeholders such as `$readReceipts` remain stable.

## Runtime Resolution

Compose code uses `stringResource` below the existing injected-host locale provider. It does not
add nested providers or cache resolved strings.

Non-Compose and asynchronous paths create an injected-host localized context when text is actually
displayed:

```kotlin
LocalizedContextFactory.create(
    base = context,
    locale = WeKitLocaleController.resolvedLocale,
    mode = LocaleResourceMode.InjectedHost,
).getString(resourceId, *formatArgs)
```

Callbacks and long-lived controller state carry resource IDs, semantic states, or bounded error
categories rather than resolved strings. Notifications are rebuilt from the current locale when
published. This preserves immediate language switching inside the WeChat host and does not add
cross-process synchronization.

## State and Error Presentation

Runtime enums remain language-neutral. Their Android presentation is mapped at the UI boundary:

- origin states: stopped, starting, running, stopping, failed;
- tunnel states: stopped, starting, connected, reconnecting, needs user action, failed, stopping;
- browser authorization and selection states;
- bounded network failure categories and transaction outcomes.

Existing fixed Chinese errors inside coordination or controller layers are migrated only when they
are part of an Android user-visible contract. Where a string is used both for internal control and
display, preserve a semantic/typed internal value and localize the display separately rather than
making control flow depend on translated text.

## Merge and Cleanup Safety

Before merging, verify the main checkout and feature worktree have no unrelated changes and record
their exact heads and submodule revisions. Perform the merge from `/home/ujhhgtg/coding/WeKit`.
Resolve conflicts without destructive reset or cleanup.

The read-receipts worktree is removed only after the merged baseline builds and tests pass. Because
the user explicitly requested cleanup, removal includes the linked worktree registration and the
fully merged `worktree-read-receipts-native-tunnel` branch. Other worktrees and branches are not
touched.

## Verification

The merged baseline must pass the focused Kotlin suite, full Standard unit suite,
`cargo test --workspace`, Go race test, `git diff --check`, and `./x build` before worktree removal.
Final i18n verification repeats those gates and additionally includes:

- `cargo test -p xtask i18n_check`;
- `./x i18n-check`;
- focused read-receipts Kotlin tests and the full Standard unit suite;
- `go test -race -count=1 ./app/src/main/go/wekit-cloudflared`;
- `cargo test --workspace`;
- `git diff --check`;
- `./x build` with Standard and Legacy APK/native inspection when applicable.

No Dex test rerun is required unless conflict resolution or i18n adaptation changes Dex delegates,
matchers, `resolveDex`, or `resolveInlineDex`. Existing unrelated WeChat 8.0.77 resolver failures
remain visible and must not be reclassified as read-receipts regressions.

Desktop validation does not prove injected-host recomposition, notification locale refresh,
Binder/foreground-service timing, or real tunnel behavior. Manual device validation must exercise
English, Simplified Chinese, Traditional Chinese, and system-following modes without a process
restart, plus the existing read-receipts lifecycle/tunnel matrix.

## Acceptance Criteria

- The reviewed read-receipts branch is merged into local `dev` with no lost lifecycle, security,
  packaging, or resolver behavior.
- Its linked worktree and merged branch are removed without affecting other worktrees.
- No Android user-facing read-receipts Chinese literal remains in Kotlin, except technical literals
  explicitly excluded by this design.
- English, Simplified Chinese, and Traditional Chinese catalogs pass repository i18n validation.
- Compose, asynchronous UI, Toast, notification, chooser, and message-rendering text resolves from
  the current process-local locale at display time.
- Automated verification passes, with device-only and unrelated 8.0.77 boundaries reported
  precisely rather than claimed as verified.
