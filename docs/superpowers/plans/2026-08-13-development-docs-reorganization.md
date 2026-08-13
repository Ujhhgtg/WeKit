# Development Documentation Reorganization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the development guide into `docs/development/`, organize it as the section landing page, and add an accurate contributor guide for WeKit i18n.

**Architecture:** Keep build and environment guidance together in `docs/development/README.md`, with focused DexKit and i18n documents alongside it. Update both documentation navigation files so the directory move does not leave stale links.

**Tech Stack:** Markdown, Android resource catalogs, Kotlin i18n APIs, Rust `xtask`

## Global Constraints

- Modify documentation only; do not change runtime or build implementation.
- Preserve the existing environment, `./x`, APK, and Zygisk instructions while improving navigation and consistency.
- Keep locale state process-local; the standalone module process must not read WeChat-host MMKV state.
- Document default system-following behavior, explicit English/Simplified Chinese/Traditional Chinese selection, and English fallback.
- Resolve localized non-Compose strings at display time; do not cache localized `String` values.
- Preserve unrelated worktree changes and the existing stash.

---

### Task 1: Move and organize the development landing page

**Files:**
- Move: `docs/development.md` -> `docs/development/README.md`
- Modify: `docs/README.md`
- Modify: `docs/SUMMARY.md`

**Interfaces:**
- Consumes: Existing build instructions and `docs/development/linux-dex-test.md`.
- Produces: A stable development landing page at `docs/development/README.md` and valid site navigation.

- [ ] **Step 1: Move the existing guide without losing content**

Use `apply_patch` to add `docs/development/README.md` with the complete existing guide content and
delete `docs/development.md` in the same patch. Preserve every build instruction before organizing
the headings in Step 2.

Expected: `docs/development.md` no longer exists and the original guide content is present at the new path.

- [ ] **Step 2: Organize the landing page**

Edit `docs/development/README.md` to:

- add links to `linux-dex-test.md` and `i18n.md` near the top;
- remove numeric prefixes from headings;
- keep environment, Android SDK, `./x`, APK, and Zygisk instructions in their current order;
- normalize Chinese punctuation and use consistent `flavor`, ABI, APK, Rust, and Zygisk terminology.

- [ ] **Step 3: Update navigation paths**

In `docs/README.md`, replace:

```markdown
[🛠 开发指南](development.md)
```

with:

```markdown
[🛠 开发指南](development/README.md)
```

In `docs/SUMMARY.md`, replace the development entry with:

```markdown
* [🛠️ 开发指南](development/README.md)
  * [DexKit 解析器测试](development/linux-dex-test.md)
  * [国际化开发指南](development/i18n.md)
```

- [ ] **Step 4: Check the moved guide and links**

Run:

```bash
test ! -e docs/development.md
test -f docs/development/README.md
rg -n 'development\.md' docs/README.md docs/SUMMARY.md
```

Expected: the two `test` commands succeed and `rg` returns no stale `development.md` link.

### Task 2: Add the i18n contributor guide

**Files:**
- Create: `docs/development/i18n.md`

**Interfaces:**
- Consumes: `WeKitLocaleController`, `LocalizedContextFactory`, `LocaleResourceMode`, `WeKitLocaleProvider`, `WeKitWindowDialog`, Android resource catalogs, and `./x i18n-check`.
- Produces: Contributor rules and copyable examples for adding or changing localized UI.

- [ ] **Step 1: Document catalogs and language behavior**

Create `docs/development/i18n.md` with a catalog table for:

```text
app/src/main/res/values/strings.xml
app/src/main/res/values-zh-rCN/strings.xml
app/src/main/res/values-zh-rTW/strings.xml
```

State that the default catalog is English, supported selections are system, English, Simplified Chinese, and Traditional Chinese, and unsupported system locales fall back to English.

- [ ] **Step 2: Document process and UI integration rules**

Include these concrete rules and examples:

```kotlin
WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
    Text(stringResource(R.string.example_title))
}
```

```kotlin
val localizedContext = LocalizedContextFactory.create(
    base = context,
    locale = WeKitLocaleController.resolvedLocale,
    mode = LocaleResourceMode.InjectedHost,
)
showToast(localizedContext.getString(R.string.example_saved))
```

Explain that `ModuleApp` is for the standalone module application, `InjectedHost` is for UI/resources hosted in WeChat, `WeKitWindowDialog` must wrap separate Miuix window compositions, and resolved strings must not be stored in singleton/provider caches.

- [ ] **Step 3: Document resource authoring and validation**

Cover resource-key naming, indexed Java Formatter placeholders, plurals, technical/non-translatable strings, and use-time number/date formatting. Include:

```bash
cargo test -p xtask i18n_check
./x i18n-check
./x build
git diff --check
```

State that real WeChat testing is required for runtime language switching in settings, dialogs, panels, overlays, menus, toasts, and notifications.

- [ ] **Step 4: Validate the completed documentation**

Run:

```bash
./x i18n-check
git diff --check
git diff --name-only HEAD
```

Expected: catalog validation succeeds, whitespace validation is clean, and only planned documentation paths are listed.

- [ ] **Step 5: Commit the documentation changes**

Run:

```bash
git add docs/development.md docs/development/README.md docs/development/i18n.md docs/README.md docs/SUMMARY.md
git diff --cached --check
git commit -m "docs: organize development and i18n guides"
```

Expected: the commit records the move, new guide, and both navigation updates as one coherent documentation change.
