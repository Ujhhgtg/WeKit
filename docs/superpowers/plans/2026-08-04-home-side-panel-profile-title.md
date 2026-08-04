# HomeSidePanel Profile Title Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add safe profile/status navigation, a panel settings page, and a compact QQ-style profile title to every LauncherUI Toolbar while preserving HomeSidePanel animation and tab behavior.

**Architecture:** Extend the existing controller/navigator contract for Activity navigation and the persisted title option. Keep all host View ownership inside `HomeSidePanelSession`: structurally discover each Toolbar, attach one lifecycle-aware ComposeView per Toolbar, synchronize tab/title visibility during pre-draw, and dispose everything on replacement or detach.

**Tech Stack:** Kotlin, Android Views, Jetpack Compose, Material 3, Kotlin Flow, MMKV through `WePrefs.prefOption`, existing Xposed hooks.

## Global Constraints

- Do not use `getIdentifier`.
- Do not add a `PhoneWindow` hook.
- Do not reparent or replace `ActionBarContainer`/`Toolbar`, and do not assign parent LayoutParams to either host View.
- Status Activity order is `TextStatusDoWhatActivityV2`, then `TextStatusDoWhatActivity`; resolve before launch and pass `KEY_IS_ENTER=true`.
- The profile Activity is `SettingsPersonalInfoUI`; resolve before launch.
- The title component is visible only on settled home Tab index `0`.
- `隐藏微信字样` defaults to `false` and is the only new panel customization.
- Host UI behavior is verified on device; do not add low-value JVM tests that merely restate Toolbar, Intent, or Compose wiring.
- Preserve the existing uncommitted HomeSidePanel changes and do not create a separate worktree.

---

### Task 1: Navigation, Preference, And Controller State

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelNavigator.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelPreferences.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelModels.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelController.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`

**Interfaces:**
- Produces: `HomeSidePanelNavigator.openPersonalProfile()` and `openStatusEditor()`.
- Produces: `HomeSidePanelPreferences.hideWeChatTitle: Boolean` backed by `prefOption("home_side_panel_hide_wechat_title", false)`.
- Produces: `HomeSidePanelCardMode.PANEL_SETTINGS` and `HomeSidePanelUiState.hideWeChatTitle`.
- Produces: controller methods `openPersonalProfile()`, `openStatusEditor()`, `openStatusEditorFromToolbar()`, `openPanelSettings()`, and `setHideWeChatTitle(Boolean)`.

- [ ] **Step 1: Extend the navigator contract and safe host implementation**

Add the two navigation operations. In `HomeSidePanelHostNavigator`, resolve explicit Activities before launch, use the V2/legacy status fallback order, include `KEY_IS_ENTER`, and Toast on complete failure:

```kotlin
interface HomeSidePanelNavigator {
    fun closePanel(afterClosed: (() -> Unit)? = null)
    fun openShortcut(shortcut: HomeSidePanelShortcut)
    fun openPersonalProfile()
    fun openStatusEditor()
}

override fun openStatusEditor() {
    val opened = STATUS_EDITOR_CLASSES.any { className ->
        startExplicit(className) { putExtra("KEY_IS_ENTER", true) }
    }
    if (!opened) showToast(activity, "无法打开状态编辑页")
}
```

- [ ] **Step 2: Persist and expose the panel option**

Use the required delegate in `HomeSidePanelPreferences`:

```kotlin
var hideWeChatTitle by WePrefs.prefOption(
    HomeSidePanelPreferenceKeys.HIDE_WECHAT_TITLE,
    false,
)
```

- [ ] **Step 3: Add settings state and controller commands**

Initialize `hideWeChatTitle` from preferences, make `PANEL_SETTINGS` part of the existing non-content back hierarchy, persist switch changes immediately, and preserve the one-shot close-before-navigation behavior:

```kotlin
fun openPersonalProfile() = navigator.closePanel(navigator::openPersonalProfile)
fun openStatusEditor() = navigator.closePanel(navigator::openStatusEditor)
fun openStatusEditorFromToolbar() = navigator.openStatusEditor()
fun openPanelSettings() {
    _uiState.update { it.copy(cardMode = HomeSidePanelCardMode.PANEL_SETTINGS) }
}
fun setHideWeChatTitle(hide: Boolean) {
    HomeSidePanelPreferences.hideWeChatTitle = hide
    _uiState.update { it.copy(hideWeChatTitle = hide) }
}
```

- [ ] **Step 4: Compile-check the contract**

Run the existing Kotlin unit-test compilation path after Tasks 1-3 are integrated; no new Android-host mock test is added.

---

### Task 2: Side Panel Profile Interactions And Settings Page

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelContent.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelSettingsContent.kt`

**Interfaces:**
- Consumes: Task 1 controller navigation/settings methods and `HomeSidePanelUiState.hideWeChatTitle`.
- Produces: independently clickable avatar, nickname/status area, status chevron, settings IconButton, and panel settings screen.

- [ ] **Step 1: Split the profile header into three hit targets**

Clip the avatar ripple to `CircleShape`, clip the nickname/status ripple to a small rounded rectangle, keep the error refresh IconButton independent, and add Outlined `Chevron_right` plus Outlined `Settings`:

```kotlin
Row(verticalAlignment = Alignment.CenterVertically) {
    ProfileAvatar(
        profile = profile,
        modifier = Modifier.clip(CircleShape).clickable(controller::openPersonalProfile),
    )
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(controller::openStatusEditor)
            .padding(8.dp),
    ) {
        Text(homeSidePanelProfileDisplayName(profile), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            HomeSidePanelStatus(profile.status, controller, Modifier.weight(1f))
            Icon(MaterialSymbols.Outlined.Chevron_right, contentDescription = null)
        }
    }
    IconButton(onClick = controller::openPanelSettings) {
        Icon(MaterialSymbols.Outlined.Settings, contentDescription = "侧栏设置")
    }
}
```

- [ ] **Step 2: Add the Material 3 panel settings page**

Route `PANEL_SETTINGS` to a page with the existing `SettingsHeader` and one full-width `ListItem`/`Switch` row labeled `隐藏微信字样`. The switch calls `controller.setHideWeChatTitle` and the header calls `controller.closeCardSettings`.

- [ ] **Step 3: Check all card-mode branches**

Ensure `HomeSidePanelSettingsContent` exhaustively handles `CONTENT`, weather settings, Hitokoto settings, and panel settings. The existing `consumeSettingsBack()` must remain the only system-back hierarchy implementation.

---

### Task 3: LauncherUI Toolbar Profile Component

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelToolbarContent.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`

**Interfaces:**
- Consumes: shared `HomeSidePanelController.uiState`, `openStatusEditorFromToolbar()`, and Session `open()`.
- Produces: `HomeSidePanelToolbarContent(profile, onAvatarClick, onStatusClick)`.
- Produces: Session-owned Toolbar bindings and native-title visibility snapshots.

- [ ] **Step 1: Build the compact two-line Compose title**

Create a Material 3 composable constrained to the ActionBar height: a 32 dp circular avatar and a bounded two-line nickname/status column. The avatar and text use separate clipped ripples; the status uses the same loading/online/error semantics as the panel and an Outlined chevron.

```kotlin
@Composable
internal fun HomeSidePanelToolbarContent(
    profile: HomeSidePanelProfile,
    onAvatarClick: () -> Unit,
    onStatusClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HomeSidePanelToolbarAvatar(profile, onAvatarClick)
        HomeSidePanelToolbarStatus(profile, onStatusClick)
    }
}
```

- [ ] **Step 2: Track every Toolbar structurally**

During the existing pre-draw callback, recursively collect all `ViewGroup`s whose class name passes `homeSidePanelIsToolbarClass`. Install exactly one lifecycle-aware `ComposeView` per Toolbar, store the binding in the Session, and collect the same controller state:

```kotlin
composeView.setLifecycleOwner(LifecycleOwnerProvider.getOrCreate(activity))
composeView.setContent {
    InjectedUiTheme {
        val state by controller.uiState.collectAsStateWithLifecycle()
        HomeSidePanelToolbarContent(
            profile = state.profile,
            onAvatarClick = ::open,
            onStatusClick = controller::openStatusEditorFromToolbar,
        )
    }
}
```

Use a Toolbar-owned child LayoutParams type or let Toolbar generate one; never reuse a parent/FrameLayout LayoutParams.

- [ ] **Step 3: Synchronize settled-tab visibility and opening**

Keep a Session `selectedTabIndex`, set bindings `VISIBLE` only for index `0`, and hide them immediately during unsettled swipes via the existing `onPageScrolled` path. Add `open()` to cancel a current animator and animate from `renderedProgress` to `1f` only on the home tab.

- [ ] **Step 4: Hide and restore only native title TextViews**

Within each discovered Toolbar, find `android.R.id.text1`. When home Tab is settled and `uiState.value.hideWeChatTitle` is true, snapshot its current visibility once and set it to `GONE`. Restore snapshots when the option turns off, the tab changes, the Toolbar is removed, or Session detaches.

- [ ] **Step 5: Clean up replaced Toolbars and refresh profile lifecycle**

Remove/dispose bindings no longer present in the decor tree. On Session detach, remove every injected ComposeView, dispose every composition, restore all native title visibilities, and clear maps. On LauncherUI resume, refresh the shared profile so status-editor changes appear in the panel and Toolbar.

---

### Task 4: Verification And Review

**Files:**
- Verify all modified files above.

**Interfaces:**
- Consumes: completed Tasks 1-3.
- Produces: build evidence and a device acceptance checklist.

- [ ] **Step 1: Run existing full JVM tests**

Run:

```bash
./gradlew :app:testStandardDebugUnitTest
```

Expected: all existing tests pass. Do not add synthetic Toolbar/Intent tests.

- [ ] **Step 2: Run the authoritative project build**

Run:

```bash
./x build
```

Expected: Standard and Legacy debug APK builds succeed, including fresh native packaging through xtask.

- [ ] **Step 3: Check diff hygiene**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only the intended HomeSidePanel files and plan remain modified/untracked.

- [ ] **Step 4: Report device-only acceptance items explicitly**

Report that device verification remains required for double Toolbar visual placement, settled-tab visibility, four click targets, status Activity fallback, immediate title switch behavior, and panel animation/coverage.
