# Read Receipts i18n Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the reviewed read-receipts native server/tunnel branch into current local `dev`, remove its completed worktree, and migrate all Android user-facing read-receipts text to WeKit's English/Simplified Chinese/Traditional Chinese catalogs.

**Architecture:** Preserve the reviewed lifecycle, security, native, Binder, and Dex behavior with a normal merge commit. Resolve Android text at its final presentation boundary: Compose uses `stringResource`, host callbacks use an injected-host localized context, the module service builds notifications from its module-process locale, and pure rendering receives already-localized display text.

**Tech Stack:** Kotlin, Jetpack Compose, Android resources, WeKit process-local i18n, Binder/foreground service, Rust, Go, Gradle/AGP, `cargo xtask`.

## Global Constraints

- Work directly on local branch `dev` in `/home/ujhhgtg/coding/WeKit`.
- Merge `worktree-read-receipts-native-tunnel` with a normal merge commit; do not rebase it or reconstruct it with cherry-picks.
- Remove only `/home/ujhhgtg/coding/WeKit/.claude/worktrees/read-receipts-native-tunnel` and its fully merged branch; leave every other worktree and branch untouched.
- Preserve separate origin/connector generations, typed stop failures, best-effort origin teardown, non-blocking send hooks, bounded polling, authenticated reader metadata, secret boundaries, and native packaging.
- Preserve process-local locale behavior: system-following by default, manual `en`/`zh-Hans`/`zh-Hant`, English fallback, and no IPC/broadcast/provider/polling synchronization of language selection.
- `values/strings.xml` is the English source/fallback catalog; this feature must also provide complete `values-zh-rCN` and `values-zh-rTW` translations.
- Translate Android user-facing Compose, Toast, notification, chooser, status/error, and message-rendering text. Do not translate protocol fields, configuration keys, enum wire names, JSON/SQL/URLs, DexKit evidence, logs, or standalone server/dashboard/REPL/CLI text.
- Do not add tests for trivial resource constants or direct mappings. Preserve meaningful pure rendering/coordination tests and use the repository i18n checker for catalog structure.
- Build with `./x build`, never Gradle `assemble*`, so native libraries are rebuilt.
- Do not rerun Dex tests unless conflict resolution or i18n work changes a Dex declaration, inline matcher, `resolveDex`, or `resolveInlineDex` body.
- Do not perform Cloudflare API, DNS, authorization, tunnel, or deployment operations.

---

## File and Responsibility Map

- `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeChatMessageViewApi.kt`: merged lifecycle listener and current `dev` resolver/i18n-compatible code.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/MessageTimeEnhancements.kt`: merged `$readReceipts` placeholder integration and localized message-time rendering.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRendering.kt`: pure placement of already-localized read-count text.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsLocalizedResources.kt`: read-receipts semantic state/error resource mappings; host callbacks reuse the existing chat-domain localization helper.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`: feature metadata, send flow, runtime presentation, Compose settings, Toast/chooser text.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelStatus.kt`: language-neutral tunnel status/error identifiers.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelController.kt`: language-neutral controller results and Binder status decoding.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt`: language-neutral Binder state plus module-locale notification rendering.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordination.kt`: typed coordination only; no resolved UI strings.
- `app/src/main/res/values*/strings.xml`: complete three-language read-receipts catalog.
- Existing `ReadReceiptRenderingTest.kt`, `ReadReceiptsConfigurationTest.kt`, and tunnel coordination/parser tests: behavior regression coverage for any amended pure contracts.

---

### Task 1: Merge the reviewed branch and remove its worktree

**Files:**
- Merge: `worktree-read-receipts-native-tunnel` into `dev`
- Resolve: `Cargo.lock`
- Resolve: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeChatMessageViewApi.kt`
- Resolve: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/MessageTimeEnhancements.kt`
- Resolve: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Resolve: `xtask/src/main.rs`

**Interfaces:**
- Consumes: feature head `b62aa806f208237ca9c0b91ee3174b945c33cd05`; local `dev` containing i18n foundations and design commit `1ef65ad3`.
- Produces: one merge commit on `dev`, a verified merged tree, and removal of only the completed read-receipts worktree/branch.

- [ ] **Step 1: Record exact pre-merge state**

Run:

```bash
git status --short
git rev-parse dev worktree-read-receipts-native-tunnel
git submodule status --recursive
git worktree list --porcelain
```

Expected: main checkout and feature worktree have no unrelated changes; feature head is
`b62aa806f208237ca9c0b91ee3174b945c33cd05`.

- [ ] **Step 2: Merge without rewriting feature history**

Run:

```bash
git merge --no-ff worktree-read-receipts-native-tunnel
```

Resolve conflicts with these exact rules:

- `WeChatMessageViewApi.kt`: keep current `dev` resolver metadata and imports while adding the
  reviewed attach/detach/recycle listener API and hooks;
- `MessageTimeEnhancements.kt`: keep current `dev` resource-based feature annotation, localized
  relative-time/date formatting, and Compose strings while adding `$readReceipts` replacement and
  forced native-time fallback;
- `ReadReceipts.kt`: retain the reviewed read-receipts implementation; replace only obsolete
  pre-i18n feature annotation fields with `id`, `nameRes`, `categoryIds`, and `descriptionRes`;
- `xtask/src/main.rs`: retain both current `dev` i18n-check commands/tests and the reviewed
  cloudflared-before-Rust native build plan;
- `Cargo.lock`: regenerate through Cargo after retaining both dependency graphs; never hand-delete
  unrelated packages.

- [ ] **Step 3: Prove conflict resolution contains both sides**

Run:

```bash
rg -n '^(<<<<<<<|=======|>>>>>>>)' . --glob '!third_party/cloudflared/**'
rg -n 'i18n-check|task_build_cloudflared|task_build_native' xtask/src/main.rs
rg -n 'IMessageViewLifecycleListener|onAttachView|onDetachView|recycle' \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeChatMessageViewApi.kt
rg -n 'READ_RECEIPTS_PLACEHOLDER|localizedChatString|nameRes|descriptionRes' \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/MessageTimeEnhancements.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt
git diff --check
```

Expected: no conflict markers; both i18n and read-receipts/native orchestration anchors are present.

- [ ] **Step 4: Complete the merge commit**

Run:

```bash
git add Cargo.lock \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeChatMessageViewApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/MessageTimeEnhancements.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt \
  xtask/src/main.rs
git add -u
git commit
```

Use the merge message generated by Git. Do not squash the reviewed branch.

- [ ] **Step 5: Verify the merged baseline before cleanup**

Run serially:

```bash
git submodule update --init --recursive
./gradlew testStandardDebugUnitTest \
  --tests '*ReadReceipt*Test*' \
  --tests '*ReadReceipts*Test*' \
  --rerun-tasks --console=plain
./gradlew testStandardDebugUnitTest --rerun-tasks --console=plain
go test -race -count=1 ./app/src/main/go/wekit-cloudflared
cargo test --workspace
git diff --check
./x build
```

If conflict resolution changed any Dex declaration/resolver body, run the affected supported APK
matrix before cleanup. Otherwise retain the existing Dex evidence and explicitly record that no Dex
rerun was required.

- [ ] **Step 6: Remove only the completed worktree and branch**

Run from `/home/ujhhgtg/coding/WeKit` after Step 5 passes:

```bash
git worktree remove /home/ujhhgtg/coding/WeKit/.claude/worktrees/read-receipts-native-tunnel
git worktree prune
git branch -d worktree-read-receipts-native-tunnel
git worktree list --porcelain
git status --short
```

Expected: the named worktree/branch is absent; all other worktrees are unchanged; `dev` is clean.

---

### Task 2: Localize feature metadata and pure message rendering

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRendering.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/MessageTimeEnhancements.kt`
- Modify: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRenderingTest.kt`
- Modify: all three `app/src/main/res/values*/strings.xml`

**Interfaces:**
- Consumes: existing `localizedChatString` and `FeatureCategoryIds.CHAT`.
- Produces:
  - `renderReadReceiptText(templateOrNativeText: String, localizedReadText: String?, enhancementActive: Boolean): String`.

- [ ] **Step 1: Update the meaningful pure rendering test first**

Change tests to pass already-localized text:

```kotlin
assertEquals(
    "12:00 | Read by 0",
    renderReadReceiptText("12:00", "Read by 0", enhancementActive = false),
)
assertEquals(
    "12:00 · 已讀 2 人",
    renderReadReceiptText(
        $$"12:00 · $readReceipts",
        "已讀 2 人",
        enhancementActive = true,
    ),
)
assertEquals(
    "12:00",
    renderReadReceiptText("12:00", null, enhancementActive = false),
)
```

Run the focused test and confirm it fails because the old API accepts a count:

```bash
./gradlew testStandardDebugUnitTest \
  --tests '*ReadReceiptRenderingTest*' --rerun-tasks --console=plain
```

- [ ] **Step 2: Make rendering locale-neutral**

Implement:

```kotlin
fun renderReadReceiptText(
    templateOrNativeText: String,
    localizedReadText: String?,
    enhancementActive: Boolean,
): String {
    val hasPlaceholder = templateOrNativeText.contains(READ_RECEIPTS_PLACEHOLDER)
    if (enhancementActive && hasPlaceholder) {
        return templateOrNativeText.replace(
            READ_RECEIPTS_PLACEHOLDER,
            localizedReadText.orEmpty(),
        )
    }
    return localizedReadText?.let { "$templateOrNativeText | $it" } ?: templateOrNativeText
}
```

Keep `READ_RECEIPTS_PLACEHOLDER` technical and remove the Chinese `READ_RECEIPTS_SUFFIX` constant.
At the call site, resolve `R.string.chat_read_receipts_count` with the existing
`localizedChatString` helper before calling the pure renderer.

- [ ] **Step 3: Reuse the injected-host chat resource helper**

Use the existing `localizedChatString` for host-process Toasts and callbacks. It already resolves
through the current injected-host locale. Do not add a duplicate read-receipts Context helper or
cache resolved contexts/strings.

- [ ] **Step 4: Migrate feature metadata and core send-flow text**

Use:

```kotlin
@Feature(
    id = "已读追踪",
    nameRes = "feature_read_receipts_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_read_receipts_description",
)
```

Replace core send-flow Toast and runtime presentation strings with resource IDs, including missing
backend, invalid sender/content bounds, registration failure category, send failure, sent success,
and read-count rendering. Preserve the annotation `id` and preference keys as stable technical IDs.

- [ ] **Step 5: Add exact source/translation resources for this boundary**

Reuse the existing `feature_read_receipts_*` and `chat_read_receipts_*` keys where their semantics are
exact. Add `read_receipts_` keys for new messages. Required English meanings include:

```xml
<string name="read_receipts_error_prefix">Error: %1$s</string>
<string name="read_receipts_sender_or_content_too_large">The sender identifier or message content is too large</string>
<string name="read_receipts_send_failed">Message sending failed</string>
<string name="read_receipts_recent_error">Latest error: %1$s</string>
```

Add semantically equivalent Simplified and Traditional Chinese entries with identical indexed
placeholder types.

- [ ] **Step 6: Verify Task 2**

Run:

```bash
./gradlew testStandardDebugUnitTest \
  --tests '*ReadReceiptRenderingTest*' --rerun-tasks --console=plain
./x i18n-check
git diff --check
```

Commit:

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRendering.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/MessageTimeEnhancements.kt \
  app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRenderingTest.kt \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat: localize read receipt rendering"
```

---

### Task 3: Localize the Compose configuration and Browser Login UI

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Modify: all three `app/src/main/res/values*/strings.xml`

**Interfaces:**
- Consumes: Compose `stringResource`, Task 2 resource naming, current injected-host provider from
  `showComposeDialog`/theme roots.
- Produces: a settings composition containing no user-facing hard-coded Chinese text.

- [ ] **Step 1: Inventory the full Compose region before editing**

Run:

```bash
rg -n 'Text\("|Text\(if|label = \{ Text\("|supportingContent = \{ Text\("|title = \{ Text\("' \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt
```

Classify every hit as user text or technical data. `Account ID`, `Tunnel ID`, URLs, database paths,
hostnames, and tunnel names need localized surrounding labels but retain their values verbatim.

- [ ] **Step 2: Migrate server mode and lifecycle controls**

Replace literals with `stringResource` for dialog title, third-party/built-in mode names and
descriptions, HTTPS endpoint, connection test, automatic lifecycle, Quick/Token/Browser mode names
and descriptions, automatic/fixed port controls, hostname/token fields, reveal/hide/delete actions,
and immutable Cloudflare ownership warnings.

State labels use resource IDs rather than pre-resolved strings:

```kotlin
val stateText = stringResource(originStatus.state.labelRes)
val tunnelStateText = stringResource(tunnelStatus.state.labelRes)
```

- [ ] **Step 3: Migrate Browser Login and tunnel-selection UI**

Add resources and replace literals for login/retry/cancel/logout, authorization-page open/copy,
refresh, lost session, authoritative metadata pending, account/tunnel/hostname labels, empty-list
guidance, manual hostname selection, selection verification, and reconnect feedback.

Use indexed placeholders for dynamic values:

```xml
<string name="read_receipts_cloudflare_login_status">Cloudflare login status: %1$s</string>
<string name="read_receipts_account_id">Account ID: %1$s</string>
<string name="read_receipts_tunnel_id">Tunnel ID: %1$s</string>
```

- [ ] **Step 4: Migrate diagnostics, actions, and save validation**

Replace literals for origin/tunnel state summaries, local address readiness, database/path/byte
display, notification settings, verified URL copy/share, connect/reconnect/disconnect actions,
prefix and polling fields, cancel/confirm, validation errors, empty-prefix warning, save failure,
and superseded requests.

Use generic existing action resources only when their meaning exactly matches; otherwise use
feature-prefixed resources.

- [ ] **Step 5: Verify the Compose migration**

Run:

```bash
rg -n 'Text\("|Text\(if|label = \{ Text\("|supportingContent = \{ Text\("|title = \{ Text\("' \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt
./x i18n-check
./gradlew compileStandardDebugKotlin --rerun-tasks --console=plain
git diff --check
```

Every remaining literal hit must be a documented technical value, not user-facing prose.

- [ ] **Step 6: Commit Task 3**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat: localize read receipts settings"
```

---

### Task 4: Make asynchronous errors and tunnel status language-neutral

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsLocalizedResources.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelStatus.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelController.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordination.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Modify: related tunnel coordination/parser/service tests
- Modify: all three `app/src/main/res/values*/strings.xml`

**Interfaces:**
- Consumes: existing generation-aware Binder protocol and Task 2 localization helpers.
- Produces:
  - `enum class ReadReceiptsTunnelErrorCode` with stable wire names;
  - `ReadReceiptsTunnelStatus.errorCode: ReadReceiptsTunnelErrorCode?` instead of cached localized
    prose for known service/controller failures;
  - `@StringRes val ReadReceiptsTunnelErrorCode.messageRes: Int` at the Android presentation edge.

- [ ] **Step 1: Add meaningful parser/coordination tests for the amended wire contract**

Cover these cases using existing real Bundle/parser coordination helpers:

```kotlin
assertEquals(
    ReadReceiptsTunnelErrorCode.NOTIFICATIONS_DISABLED,
    decodedStatus.errorCode,
)
assertNull(connectedStatus.errorCode)
assertNull(stoppedStatus.errorCode)
```

Also verify unknown wire error codes are rejected or mapped to `UNEXPECTED_FAILURE` according to the
existing strict parser policy. Run the focused tests and confirm they fail before production changes.

- [ ] **Step 2: Define stable semantic error codes**

Define exactly these initial wire-visible categories; add another category only when the audit finds
a distinct user-remediation action that cannot use one of them:

```kotlin
enum class ReadReceiptsTunnelErrorCode {
    VISIBLE_SETTINGS_REQUIRED,
    NOTIFICATIONS_DISABLED,
    TOKEN_REQUIRED,
    TOKEN_INVALID,
    BROWSER_CREDENTIAL_INVALID,
    CREDENTIAL_SAVE_FAILED,
    START_HANDOFF_TIMEOUT,
    STOP_TIMEOUT,
    SERVICE_UNAVAILABLE,
    HEALTH_CHECK_FAILED,
    UNEXPECTED_FAILURE,
}
```

Do not use translated text as enum/wire names. Preserve bounded native diagnostic detail only for
logs; Android UI and notifications resolve the enum to a resource.

- [ ] **Step 3: Carry codes through Binder and controller state**

Add a strict protocol key for the enum name. Publish `null` for healthy/stopped states. Decode with
`entries.firstOrNull { it.name == wireValue }` under the existing exact-key and generation checks.
Replace fixed Chinese controller exceptions/status strings with either a semantic error code or an
internal English diagnostic that is never rendered directly.

Only a matching protocol `STOPPED` remains a successful stop terminal; timeout, bind failure, and
Binder teardown retain the reviewed typed failure behavior.

- [ ] **Step 4: Resolve errors at the final UI boundary**

Add:

```kotlin
@get:StringRes
internal val ReadReceiptsTunnelErrorCode.messageRes: Int
    get() = when (this) {
        ReadReceiptsTunnelErrorCode.VISIBLE_SETTINGS_REQUIRED ->
            R.string.read_receipts_error_visible_settings_required
        ReadReceiptsTunnelErrorCode.NOTIFICATIONS_DISABLED ->
            R.string.read_receipts_error_notifications_disabled
        ReadReceiptsTunnelErrorCode.TOKEN_REQUIRED ->
            R.string.read_receipts_error_token_required
        ReadReceiptsTunnelErrorCode.TOKEN_INVALID ->
            R.string.read_receipts_error_token_invalid
        ReadReceiptsTunnelErrorCode.BROWSER_CREDENTIAL_INVALID ->
            R.string.read_receipts_error_browser_credential_invalid
        ReadReceiptsTunnelErrorCode.CREDENTIAL_SAVE_FAILED ->
            R.string.read_receipts_error_credential_save_failed
        ReadReceiptsTunnelErrorCode.START_HANDOFF_TIMEOUT ->
            R.string.read_receipts_error_start_handoff_timeout
        ReadReceiptsTunnelErrorCode.STOP_TIMEOUT ->
            R.string.read_receipts_error_stop_timeout
        ReadReceiptsTunnelErrorCode.SERVICE_UNAVAILABLE ->
            R.string.read_receipts_error_service_unavailable
        ReadReceiptsTunnelErrorCode.HEALTH_CHECK_FAILED ->
            R.string.read_receipts_error_health_check_failed
        ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE ->
            R.string.read_receipts_error_unexpected_failure
    }
```

Compose uses `stringResource(errorCode.messageRes)`. Host-process Toasts use
`localizedChatString(errorCode.messageRes)`. Do not store the resolved result in controller
singletons or remembered state.

- [ ] **Step 5: Rebuild notifications from the module-process locale**

At every `notification(value)` call, create `LocaleResourceMode.ModuleApp` context from the Service
and current module-process locale. Localize channel name, title, state detail, and Stop action.
Dynamic public hostnames remain verbatim. Do not read host-process `WePrefs` or add language IPC.

- [ ] **Step 6: Verify Task 4**

Run:

```bash
./gradlew testStandardDebugUnitTest \
  --tests '*ReadReceiptsTunnel*Test*' --rerun-tasks --console=plain
./x i18n-check
./gradlew compileStandardDebugKotlin --rerun-tasks --console=plain
git diff --check
```

- [ ] **Step 7: Commit Task 4**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelStatus.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsLocalizedResources.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelController.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordination.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt \
  app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnel*Test.kt \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat: localize read receipts tunnel status"
```

---

### Task 5: Audit remaining Android user text and complete the catalog

**Files:**
- Audit and modify only when a presentation literal remains:
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRecord.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptRendering.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsConfiguration.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsNative.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsServerController.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsStatus.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelController.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordination.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelNative.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt`
  - `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelStatus.kt`
- Modify: all three `app/src/main/res/values*/strings.xml`

**Interfaces:**
- Consumes: Tasks 2–4 localization boundaries.
- Produces: no remaining Android user-facing Chinese literal in read-receipts production Kotlin and
  complete English/zh-CN/zh-TW feature catalogs.

- [ ] **Step 1: Run literal and presentation-sink audits**

Run:

```bash
rg -nP '"[^"\\]*(?:[\x{3400}-\x{9fff}])' \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipt*.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts*.kt
rg -n 'showToast\(|Text\(|setContentTitle\(|setContentText\(|addAction\(|createChooser\(' \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipt*.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts*.kt
```

Allowed Chinese literals are limited to stable technical annotation `id` values or test fixtures
that must match existing persisted identities. Every presentation sink must receive a resource-based
value.

- [ ] **Step 2: Resolve all presentation-sink gaps found by Step 1**

For Compose, use `stringResource`. For host callbacks, resolve with
`localizedReadReceiptsString` at display time. For Service notifications, use the module-localized
context. For arbitrary native/server detail, show a localized bounded category and keep the raw
detail out of UI.

- [ ] **Step 3: Verify catalog parity and source-language policy**

Run:

```bash
cargo test -p xtask i18n_check
./x i18n-check
```

Expected: only the three supported directories exist; no target-only key, type mismatch, duplicate,
or placeholder mismatch; all new source entries are English and all feature entries exist in both
Chinese catalogs.

- [ ] **Step 4: Commit Task 5**

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/chat \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh-rCN/strings.xml \
  app/src/main/res/values-zh-rTW/strings.xml
git commit -m "feat: complete read receipts translations"
```

---

### Task 6: Final review and verification

**Files:**
- Review: merge commit through final `dev` head
- Verify: Standard and Legacy APKs and generated native libraries

**Interfaces:**
- Consumes: completed merged/i18n implementation.
- Produces: evidence-backed final status with device-only and unrelated Dex boundaries kept visible.

- [ ] **Step 1: Review the complete merged i18n diff**

Review against the design acceptance criteria. Check especially:

- no lifecycle/security/native behavior was lost in conflict resolution;
- no translated string controls logic or wire parsing;
- no process-global locale mutation or cross-process locale synchronization was added;
- resolved strings are not cached in singleton/controller/long-lived callback state;
- no standalone server/CLI scope creep occurred.

- [ ] **Step 2: Run fresh complete verification serially**

```bash
cargo test -p xtask i18n_check
./x i18n-check
./gradlew testStandardDebugUnitTest --rerun-tasks --console=plain
go test -race -count=1 ./app/src/main/go/wekit-cloudflared
cargo test --workspace
git diff --check
./x build
```

Inspect both APKs for version metadata, both `libwekit_cloudflared.so` and `libwekit_native.so` on
`arm64-v8a` and `armeabi-v7a`, and the established 6 C / 9 Go JNI / 3 Rust JNI exports. Confirm any
generated artifacts are ignored or otherwise leave the tracked tree unchanged.

- [ ] **Step 3: Apply the Dex decision rule**

Compare the final diff for resolver declarations/bodies. If none changed, do not rerun Dex and
retain the existing unrelated 8.0.77 result (6 unexpected, 105 blocked; branch-affected message-view
resolver previously passed). If a resolver changed, run affected supported APKs and report every
unexpected/blocked/incomplete result.

- [ ] **Step 4: Report manual verification boundary**

Mark as NOT RUN unless performed on a real supported device:

- immediate English/zh-CN/zh-TW/system-following switching in WeChat;
- Compose dialog, Toast, chooser, and message suffix refresh without process restart;
- module-process notification language under system locale;
- send/poll/recycle persistence behavior;
- origin STOP timeout, Binder teardown, rollback restart;
- Quick, Token, and Browser tunnel flows plus network transitions.

- [ ] **Step 5: Confirm final repository state**

```bash
git status --short
git diff --check
git submodule status --recursive
git worktree list --porcelain
git log --oneline --decorate -12
```

Expected: clean `dev`; read-receipts worktree and branch absent; unrelated worktrees untouched.
