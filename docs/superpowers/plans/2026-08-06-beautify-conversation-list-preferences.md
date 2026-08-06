# Beautify conversation list preferences Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `BeautifyConversationList` the single owner of conversation-list layout, unread, and divider preferences, with a non-invasive default layout and independent divider control.

**Architecture:** Extend the existing `ConversationListPreset` with a `NO_LAYOUT` sentinel that bypasses module row-background/padding changes. Keep unread highlighting as a layout-only option in the dialog and force it off for `NO_LAYOUT`; keep divider ownership in the same feature through `WeConversationListViewApi`. Remove the standalone divider feature and all duplicate avatar-rounding logic from the beautifier.

**Tech Stack:** Kotlin, Android Views, Jetpack Compose Material 3, MMKV preference delegates, WeKit `WeConversationListViewApi`, `./x` build orchestration.

## Global Constraints

- Keep package namespace `dev.ujhhgtg.wekit` and existing feature/UI conventions.
- Do not add tests for host UI/reflection glue; manual WeChat validation remains the behavioral test for this feature.
- Always use `./x` rather than Gradle directly because Gradle can package a stale native library.
- Do not change Dex declarations or resolution logic; no DexKit desktop test is required.
- Keep divider teardown best-effort and use the existing `WeConversationListViewApi` owner API.
- Remove duplicate feature registration rather than retaining two controls for the same divider behavior.

---

### Task 1: Update the combined conversation-list feature

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt`

**Interfaces:**
- Consumes: `WeConversationListViewApi.addListener`, `setDividerHidden`, `removeDividerOwner`, and `refresh`.
- Produces: the same `BeautifyConversationList` feature with `NO_LAYOUT` as the default preset, unread/divider defaults off, no avatar preference, and divider control retained.

- [ ] **Step 1: Extend the preset and preference defaults**

  Change the enum so `NO_LAYOUT` is the first entry. It must not require meaningful card dimensions because row application will bypass it; retain the existing metadata fields for the other presets. Set these declarations:

  ```kotlin
  private enum class ConversationListPreset(
      val rowRadiusDp: Int,
      val horizontalInsetDp: Int,
      val verticalInsetDp: Int,
      val avatarRadiusDp: Int,
      val lightBackgroundColor: Int,
      val darkBackgroundColor: Int,
  ) {
      NO_LAYOUT(0, 0, 0, 0, 0, 0),
      COMFORT_CARD(14, 10, 4, 12, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
      COMPACT_ROUNDED(10, 6, 2, 10, 0xFFF9FBFA.toInt(), 0xFF272928.toInt()),
      MINIMAL_LIST(6, 0, 0, 8, 0xFFFCFCFC.toInt(), 0xFF232323.toInt()),
  }
  ```

  Set the preference defaults to `NO_LAYOUT.name`, `false` for `highlightUnreadEnabled`, and `false` for `hideDividersEnabled`. Delete `roundAvatarsEnabled` entirely.

- [ ] **Step 2: Remove avatar-specific state and imports**

  Delete the imports for `Outline`, `ImageView`, `ViewGroup`, `ViewOutlineProvider`, `WeakReference`, and `abs`, `max`, `min` if no remaining code uses them. Remove `AvatarVisualState`, the `avatar` property from `RowVisualState`, and the functions `clearAvatarState`, `installAvatarOutline`, `isVisibleDescendant`, and `findAvatarCandidate`.

  Update the feature description to no longer claim that it controls rounded avatars; describe card layouts, unread emphasis, and divider settings only.

- [ ] **Step 3: Add the no-layout dialog row and hide unread conditionally**

  In `onClick`, remove `draftRoundAvatars` and its switch row. Add a label mapping for `NO_LAYOUT`:

  ```kotlin
  ConversationListPreset.NO_LAYOUT -> "不修改卡片布局"
  ```

  Render the unread `ListItem` only when `draftPreset != ConversationListPreset.NO_LAYOUT`. When the draft preset changes to `NO_LAYOUT`, set `draftHighlightUnread = false` in the preset row click handler. Keep the divider switch always rendered.

  The preset row should have this behavior:

  ```kotlin
  modifier = Modifier.clickable {
      draftPreset = preset
      if (preset == ConversationListPreset.NO_LAYOUT) {
          draftHighlightUnread = false
      }
  }
  ```

  Keep the radio button’s `onClick` consistent with the row click, for example by assigning the same small local action or by setting the draft preset and disabling unread there as well. Do not leave a path where clicking only the radio button selects `NO_LAYOUT` while retaining unread highlighting.

- [ ] **Step 4: Save only the active preferences and keep divider ownership**

  In the confirm callback, remove the `roundAvatarsEnabled` assignment. Persist `highlightUnreadEnabled` as `false` whenever `draftPreset == NO_LAYOUT`, otherwise persist the draft value. Keep `hideDividersEnabled = draftHideDividers`, then call `updateDividerRequest()` and `WeConversationListViewApi.refresh()` as currently done.

  Retain:

  ```kotlin
  override fun onEnable() {
      WeConversationListViewApi.addListener(bindListener)
      updateDividerRequest()
      WeConversationListViewApi.refresh()
  }

  override fun onDisable() {
      WeConversationListViewApi.removeListener(bindListener)
      WeConversationListViewApi.removeDividerOwner(this)
      rowStates.clear()
      unreadAccessorCache.clear()
      unreadFailuresLogged.clear()
  }
  ```

- [ ] **Step 5: Bypass row modifications for `NO_LAYOUT`**

  At the start of `applyRowVisuals`, after baseline restoration, branch on the selected preset. For `NO_LAYOUT`, return without installing a module background or changing row padding. For all other presets, retain the existing background-key caching and baseline-padding behavior, but remove avatar handling entirely.

  The resulting shape should be:

  ```kotlin
  private fun applyRowVisuals(row: View, conversation: Any) {
      val state = rowStates.getOrPut(row) {
          RowVisualState(
              baselineBackground = row.background,
              baselinePaddingLeft = row.paddingLeft,
              baselinePaddingTop = row.paddingTop,
              baselinePaddingRight = row.paddingRight,
              baselinePaddingBottom = row.paddingBottom,
          )
      }
      restoreRowBaseline(row, state)

      val preset = selectedPreset
      if (preset == ConversationListPreset.NO_LAYOUT) return

      val unread = highlightUnreadEnabled && isUnread(conversation)
      val backgroundKey = RowBackgroundKey(
          preset = preset,
          unread = unread,
          isDark = row.context.isDarkMode,
          density = row.resources.displayMetrics.density,
      )
      val background = if (state.backgroundKey == backgroundKey) {
          state.moduleBackground!!
      } else {
          buildRowBackground(row.context, preset, unread).also {
              state.backgroundKey = backgroundKey
              state.moduleBackground = it
          }
      }
      row.background = background
  }
  ```

  Preserve the existing `restoreRowBaseline` behavior so switching back to `NO_LAYOUT` restores any card drawable previously installed by the feature. Since no avatar state remains, no avatar restoration path is needed.

- [ ] **Step 6: Remove the duplicate feature source**

  Delete `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/HideConversationListDividers.kt`. No replacement feature file is needed because `BeautifyConversationList` already owns the divider preference and lifecycle.

- [ ] **Step 7: Inspect the diff for source-level correctness**

  Run:

  ```bash
  git diff --check
  git diff -- app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/HideConversationListDividers.kt
  ```

  Expected: no whitespace errors; the diff contains the new no-layout radio, no round-avatar switch/state/logic, unread/divider defaults set to `false`, conditional unread UI, and deletion of the standalone divider feature.

- [ ] **Step 8: Commit the implementation**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/HideConversationListDividers.kt
  git commit -m "refactor: merge conversation list divider settings"
  ```

---

### Task 2: Build and final verification

**Files:**
- Read-only verification of the implementation from Task 1.

**Interfaces:**
- Consumes: the committed `BeautifyConversationList` implementation and project `./x` build entry point.
- Produces: verified build/check results and a concise report of any environment limitation.

- [ ] **Step 1: Run the project build**

  ```bash
  ./x build
  ```

  Expected: the debug build completes successfully. If the local environment lacks the Android SDK/NDK or another required toolchain component, record the exact failure rather than substituting a direct Gradle build.

- [ ] **Step 2: Re-run whitespace and status checks**

  ```bash
  git diff --check
  git status --short
  ```

  Expected: no diff-check errors and only intentional repository state (the implementation commit should leave no modified implementation files).

- [ ] **Step 3: Verify duplicate feature/options are gone**

  ```bash
  rg -n "HideConversationListDividers|roundAvatarsEnabled|圆角头像|不修改卡片布局|highlightUnreadEnabled|hideDividersEnabled" app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify
  ```

  Expected: `HideConversationListDividers` and `roundAvatarsEnabled` do not appear; the no-layout label and the two retained preference names appear only in the combined feature as expected. The global `RoundAvatars` feature remains untouched.

- [ ] **Step 4: Report manual validation requirement**

  Report that the following must be checked in a supported WeChat host: the default feature opens with `不修改卡片布局` selected, unread highlighting is absent in that mode, divider hiding remains visible and works, selecting a card preset reveals unread highlighting, and `RoundAvatars` remains the only avatar-rounding control.
