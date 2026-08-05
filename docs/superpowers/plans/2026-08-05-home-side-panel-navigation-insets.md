# HomeSidePanel 原生底栏系统导航区适配实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `HomeSidePanel` 启用 edge-to-edge 时的微信原生 `LauncherUIBottomTabView` 始终避开三键导航栏和手势指示条，并与 `ReplaceNavigationBar` 共存。

**Architecture:** 在 `HomeSidePanelSession` 已有的 pre-draw 中定位稳定完整类名的原生底栏，读取 `WindowInsetsCompat` 的 `navigationBars` 与 `tappableElement`，将最大底部值绝对写入外层底栏 `paddingBottom`。当原生底栏隐藏或其子树包含 `ComposeView` 时清除旧的原生 padding 并跳过，让 `ReplaceNavigationBar` 自己消费 Insets；不安装新的 Insets listener，也不改变聊天功能。

**Tech Stack:** Kotlin, Android View hierarchy, AndroidX `ViewCompat`/`WindowInsetsCompat`, Jetpack Compose `ComposeView`, WeKit Xposed hooks。

## Global Constraints

- 只修改 `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`。
- 目标微信版本为 8.0.65–8.0.76；底栏定位使用 `com.tencent.mm.ui.LauncherUIBottomTabView` 完整类名，不使用资源 ID、混淆字段或新 DexKit 声明。
- 不覆盖微信已有 `OnApplyWindowInsetsListener`，不修改 `ReplaceNavigationBar.kt` 或三个聊天功能。
- Padding 必须绝对赋值且幂等；目标值不变时不得调用 `setPadding`、`requestLayout` 或逐帧日志。
- 本改动依赖真实微信宿主 UI，不新增 JVM/TDD 测试；验证使用 `git diff --check`、`./x build`，设备行为仍需实机矩阵确认。

---

### Task 1: 添加原生底栏 Insets 同步辅助逻辑

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt:1-40,274-340`

**Interfaces:**
- Consumes: `HomeSidePanelSession.parent`, `View.allViews`, `View.findViewWhich`, `ViewCompat.getRootWindowInsets`。
- Produces: `HomeSidePanelSession.syncNativeBottomTabInsets()`，供现有 session pre-draw 调用；内部常量保存 `LauncherUIBottomTabView` 的完整类名。

- [x] **Step 1: Extend imports and session state**

Add `LinearLayout`, `ViewCompat`, and `WindowInsetsCompat` imports. Add a private constant
`LAUNCHER_BOTTOM_TAB_VIEW_CLASS = "com.tencent.mm.ui.LauncherUIBottomTabView"` beside `TAG`.
Add a nullable cached `View` field in `HomeSidePanelSession` for the located native bottom bar.

- [x] **Step 2: Implement exact bottom inset calculation and native padding writes**

Add the following behavior as private methods on `HomeSidePanelSession`:

```kotlin
private fun syncNativeBottomTabInsets() {
    val bottomBar = cachedNativeBottomTabView
        ?.takeIf { it.isAttachedToWindow }
        ?: parent.findViewWhich { it.javaClass.name == LAUNCHER_BOTTOM_TAB_VIEW_CLASS }
            ?.also { cachedNativeBottomTabView = it }
        ?: return

    val nativeContainer = bottomBar as ViewGroup
    val legacyLinear = (0 until nativeContainer.childCount)
        .asSequence()
        .map(nativeContainer::getChildAt)
        .filterIsInstance<LinearLayout>()
        .firstOrNull()

    val isComposeReplacement = bottomBar.findViewWhich { it is ComposeView } != null
    if (bottomBar.visibility != View.VISIBLE || isComposeReplacement) {
        clearNativeBottomTabPadding(bottomBar, legacyLinear)
        return
    }

    val insets = ViewCompat.getRootWindowInsets(bottomBar) ?: return
    val systemBottom = insets.getInsets(
        WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.tappableElement(),
    ).bottom
    if (bottomBar.paddingBottom != systemBottom) {
        bottomBar.setPadding(
            bottomBar.paddingLeft,
            bottomBar.paddingTop,
            bottomBar.paddingRight,
            systemBottom,
        )
    }
    if (legacyLinear != null && legacyLinear.paddingBottom != 0) {
        legacyLinear.setPadding(
            legacyLinear.paddingLeft,
            legacyLinear.paddingTop,
            legacyLinear.paddingRight,
            0,
        )
    }
}
```

`clearNativeBottomTabPadding` must set the outer bottom bar and the located direct legacy `LinearLayout`
bottom padding to zero, preserving their other three padding sides. It must use the same “only write when
changed” rule. Do not call `requestApplyInsets` or install a listener from these helpers; the existing window
edge-to-edge path already requests Insets and the pre-draw retry handles timing.

- [x] **Step 3: Self-review the helper against the host behavior**

Confirm that three-button navigation uses `navigationBars`, gesture-indicator safe space is covered by
`tappableElement`, old 8.0.65–8.0.69 inner padding cannot double-count the outer value, and a
`ComposeView` replacement cannot receive a second outer padding.

### Task 2: Wire synchronization into the existing session frame

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt:307-315`

**Interfaces:**
- Consumes: `syncNativeBottomTabInsets()` from Task 1.
- Produces: one idempotent native bottom-bar update on every existing `HomeSidePanelSession` pre-draw.

- [x] **Step 1: Call the helper after existing host-tree synchronization**

Append `syncNativeBottomTabInsets()` to the existing `preDrawListener` after
`syncToolbarProfileBindings()` and `applyActionBarProgress(renderedProgress)`. Keep the listener’s final
return value `true` and do not add another observer.

- [x] **Step 2: Inspect the focused diff**

Run:

```bash
git diff -- app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt
git diff --check
```

Expected: only the new imports, constant/cache field, two helper methods, and one pre-draw call are present;
no unrelated source or i18n files are changed by this task.

### Task 3: Build and hand off device verification

**Files:**
- Verify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`

**Interfaces:**
- Consumes: completed Tasks 1–2.
- Produces: build evidence and a device-validation checklist; no additional source changes unless the build
  reports a concrete compile error in the focused change.

- [x] **Step 1: Run repository formatting/whitespace validation**

Run `git diff --check` from `/home/ujhhgtg/coding/WeKit`; expected exit code 0.

- [x] **Step 2: Build through xtask**

Run `./x build`; expected exit code 0 and a freshly rebuilt native library plus APK artifacts. Do not use
`./gradlew assemble*` as a substitute.

- [x] **Step 3: Recheck the final scoped diff and worktree**

Run:

```bash
git diff --check
git status --short
git diff --stat
```

Confirm the implementation diff contains only `HomeSidePanel.kt`; preserve unrelated existing i18n changes.

- [ ] **Step 4: Report the physical-device matrix without overstating results**

On a real WeChat device, check HomeSidePanel alone under three-button navigation, gesture navigation with
the indicator visible, and gesture navigation with the indicator hidden; then repeat with ReplaceNavigationBar
in normal and floating modes. Also enter/leave chat and rotate or switch navigation mode. Until those checks
are run, report build success separately from device behavior.

### Commit Boundary

After Tasks 1–3 pass, commit only `HomeSidePanel.kt` with:

```bash
git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt
git commit -m "fix: inset native launcher navigation bar"
```
