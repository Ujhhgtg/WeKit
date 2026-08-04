# HomeSidePanel Interaction Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix HomeSidePanel settings navigation, one-shot close sequencing, weather settings layout/errors, ripple clipping, Hitokoto attribution, account-card copy, and unified shortcut icons without regressing the existing drawer shell.

**Architecture:** Keep host view and animation ownership in the nested `HomeSidePanelSession`; expose close completion callbacks from the session-backed navigator so only navigation shortcuts wait for animation completion. Keep transient weather failures in a Controller `SharedFlow` consumed by the Compose session for Toasts, while retaining persistent weather state only for cache and selection. Centralize the shared shortcut icon mapping and the `Mark_chat_read` drawable so FAB, popup menu, and HomeSidePanel use the same semantic icons.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, LibXposed hooks, Kotlin Coroutines/StateFlow/SharedFlow, existing `VectorPathDrawable`, JUnit 5 JVM tests.

## Global Constraints

- Preserve the existing HomeSidePanel gesture, edge-to-edge, FAB, ActionBarContainer, and return-key behavior outside the requested changes.
- Do not use `getIdentifier` or add a `PhoneWindow` hook.
- Keep `AddMainScreenFab.kt` behavior unchanged except the required shared `Mark_chat_read` icon synchronization.
- Weather errors are Toast-only; no weather error text remains in the weather card or weather settings content.
- Clear-all-read executes immediately; navigation shortcuts execute only after the one-shot close animation completes.
- One-shot close uses a shorter full-close duration of approximately 240ms; normal close duration remains unchanged.
- Supported host range remains WeChat 8.0.65–8.0.76; no Dex resolver declarations change in this plan.

### Task 1: Add pure interaction rules and regression tests

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelController.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelContent.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelNavigator.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelGestureStateTest.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelControllerRulesTest.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelShortcutMappingTest.kt`

**Interfaces:**
- Produce `homeSidePanelOneShotCloseDuration(from: Float, target: Float): Long` with 240ms at a full close.
- Produce `homeSidePanelAttribution(author: String?, source: String?): String?` with exact formats `—— 作者「出处」`, `—— 作者`, and `——「出处」`.
- Produce `homeSidePanelShortcutWaitsForClose(shortcut: HomeSidePanelShortcut): Boolean`, false only for `MARK_ALL_READ`.
- Add a Controller weather-message stream API that tests can collect without Compose.

- [ ] **Step 1: Write failing tests**

  Add tests for one-shot duration, four attribution combinations, shortcut wait policy, settings-back consumption, and a weather failure producing one transient message even when repeated.

- [ ] **Step 2: Run the focused tests and verify RED**

  Run:

  ```bash
  ./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.*'
  ```

  Expected: compilation or assertion failures for the new helpers and message stream.

- [ ] **Step 3: Implement the minimal pure helpers and Controller stream surface**

  Use a `MutableSharedFlow<String>` with buffered emission for weather errors. Preserve cancellation by rethrowing `CancellationException`; emit only mapped weather/location/profile failures.

- [ ] **Step 4: Re-run the focused tests and verify GREEN**

  Run the same focused command and confirm all HomeSidePanel tests pass.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel
  git commit -m "test: define HomeSidePanel interaction rules"
  ```

### Task 2: Implement settings-page back hierarchy and close-completion navigation

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelController.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelNavigator.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelControllerRulesTest.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelGestureStateTest.kt`

**Interfaces:**
- `HomeSidePanelController.consumeSettingsBack(): Boolean` changes `WEATHER_SETTINGS` or `HITOKOTO_SETTINGS` to `CONTENT` and returns true; it returns false in content mode.
- `HomeSidePanelNavigator.closePanel(afterClosed: (() -> Unit)? = null)` supports completion callbacks.
- Session close accepts `oneShot: Boolean` and `afterClosed: (() -> Unit)?`.

- [ ] **Step 1: Extend tests for settings back and callback policy**

  Verify that settings back does not request panel close, content back does, six navigation shortcuts wait for completion, and `MARK_ALL_READ` invokes its action immediately.

- [ ] **Step 2: Run the new tests and verify RED**

  Run the focused HomeSidePanel suite; expected failures should identify the missing controller/navigator behavior.

- [ ] **Step 3: Implement the Controller and navigator policy**

  In `runShortcut`, call `closePanel { openShortcut(shortcut) }` only when `homeSidePanelShortcutWaitsForClose` is true. For clear-all-read, call `closePanel()` and immediately call `openShortcut`. In `Session.consumeBack`, check `controller.consumeSettingsBack()` before checking drawer visibility.

- [ ] **Step 4: Run focused tests and verify GREEN**

  Confirm all existing and new controller/gesture tests pass.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel
  git commit -m "fix: sequence HomeSidePanel navigation after close"
  ```

### Task 3: Add one-shot close animation timing and remove UI debug logs

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelGestureStateTest.kt`

- [ ] **Step 1: Add a failing duration test**

  Assert that a full one-shot close is shorter than the current normal 360ms and equals the planned approximately 240ms, while normal close remains 360ms.

- [ ] **Step 2: Verify RED**

  Run the focused gesture suite and confirm the duration assertion fails against the current single formula.

- [ ] **Step 3: Implement the animation callback and timing**

  Add a `oneShot` parameter to `animateTo`. Use the one-shot duration helper only when `target == 0f`; preserve normal duration for back/tab transitions. Invoke `afterClosed` only from uncanceled `onAnimationEnd`, and invoke immediately when the panel is already closed. Change dim clicks and gesture settle-to-zero paths to `oneShot = true`; pass `oneShot = true` for deferred shortcut navigation.

  Remove all `WeLogger.i` calls in `HomeSidePanel.kt` used for initialization, touch, drag, lookup, transforms, and edge-to-edge observation. Keep error logging and non-debug diagnostics elsewhere.

- [ ] **Step 4: Run focused tests and verify GREEN**

  Run the focused HomeSidePanel suite and confirm duration, callback, and existing gesture tests pass.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelGestureStateTest.kt
  git commit -m "fix: tune HomeSidePanel one-shot close animation"
  ```

### Task 4: Rework weather settings UI and Toast error delivery

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelController.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelSettingsContent.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelContent.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelControllerRulesTest.kt`

- [ ] **Step 1: Add failing tests for repeated weather failure messages**

  Trigger two identical weather/profile/location failures and assert two transient events are available; assert cached weather state remains available without an embedded error string.

- [ ] **Step 2: Verify RED**

  Run the focused suite and confirm no transient weather event exists in the current implementation.

- [ ] **Step 3: Implement the message stream and Compose collector**

  Emit weather refresh errors, profile-city errors, permission denial, location failures, and city-match failures from the Controller. Collect the stream in the existing `ComposeView.setContent` `LaunchedEffect` using `showToast`. Remove weather error Text and retry row from both the weather card and weather settings page; retain card cache/neutral loading presentation. Keep `WeatherSettingsUiState.message` for deterministic controller state and existing tests, but never render it in Compose.

- [ ] **Step 4: Reorder and restyle the weather settings controls**

  Place current city, then two equal-height `OutlinedButton`s with vertical icon/text content and `maxLines = 1`, then the search field. Replace each search result's separate “选择” button with a full-row clickable `ListItem` inside a rounded Card, with a selected indicator and indented dividers.

- [ ] **Step 5: Run focused tests and verify GREEN**

  Confirm controller and UI-state tests pass and no HomeSidePanel weather error text remains in the Compose source.

- [ ] **Step 6: Commit**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel
  git add app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel
  git commit -m "fix: polish HomeSidePanel weather settings"
  ```

### Task 5: Fix ripple clipping, Hitokoto attribution, and account card copy

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelContent.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelShortcutMappingTest.kt`

- [ ] **Step 1: Add failing attribution and copy tests**

  Assert all four attribution combinations and the absence of wxId rendering data in the account header mapping.

- [ ] **Step 2: Verify RED**

  Run the focused suite and observe the old `·` attribution and wxId assumptions.

- [ ] **Step 3: Implement the content changes**

  Define one shape per card/Tile, apply `.clip(shape)` before `.combinedClickable`, remove the Hitokoto header refresh `IconButton`, render the formatted attribution with `fillMaxWidth()` and `textAlign = TextAlign.End`, and remove the profile wxId `Text` from the header.

- [ ] **Step 4: Run focused tests and verify GREEN**

  Confirm attribution and mapping tests pass.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelContent.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelShortcutMappingTest.kt
  git commit -m "fix: refine HomeSidePanel cards and attribution"
  ```

### Task 6: Synchronize shortcut icons across FAB, popup menu, and HomeSidePanel

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelContent.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/AddMainScreenFab.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/home_screen_menu/MarkAllAsRead.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/ui/utils/DrawableIcons.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelShortcutMappingTest.kt`

- [ ] **Step 1: Add failing icon mapping tests**

  Assert the six non-clear-all shortcuts map to `Qr_code_scanner`, `Wallet`, `Bookmark`, `Camera`, `Movie`, and `Extension`, and clear-all maps to `Mark_chat_read`.

- [ ] **Step 2: Verify RED**

  Run the focused shortcut suite and confirm the current outlined icons and `Check_circle` mapping fail.

- [ ] **Step 3: Implement the shared icon mapping**

  Import the same `OutlinedFilled` Material Symbols used by `AddMainScreenFab`. Update HomeSidePanel mapping. Replace the FAB default clear-all icon name and icon-pool entry with `Mark_chat_read`. Read the supplied `/home/ujhhgtg/Downloads/mark_chat_read_24px.xml`, copy its exact vector path data into a `MarkChatReadIcon` beside `CheckCircleIcon`, and update `MarkAllAsRead` to use it. Keep the old `Check_circle` icon-pool entry so previously saved FAB configurations remain valid.

- [ ] **Step 4: Run focused tests and verify GREEN**

  Confirm icon mapping tests pass and inspect the three call sites for consistent naming.

- [ ] **Step 5: Commit**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/AddMainScreenFab.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/home_screen_menu/MarkAllAsRead.kt app/src/main/java/dev/ujhhgtg/wekit/ui/utils/DrawableIcons.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelContent.kt app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanelShortcutMappingTest.kt
  git commit -m "fix: unify HomeSidePanel shortcut icons"
  ```

### Task 7: Full verification and handoff

**Files:**
- Modify only files required by failing verification output.

- [ ] **Step 1: Run focused HomeSidePanel tests**

  ```bash
  ./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel.*'
  ```

- [ ] **Step 2: Run the complete standard JVM suite**

  ```bash
  ./gradlew :app:testStandardDebugUnitTest
  ```

- [ ] **Step 3: Check scope and formatting**

  ```bash
  git diff --check
  git status --short
  git diff --stat db034e7148bebb876fc766618449ee255033774d..HEAD
  ```

  Confirm the user-owned `AddMainScreenFab.kt` changes are intentionally included only for the required icon synchronization, and no unrelated files are staged.

- [ ] **Step 4: Run canonical build**

  ```bash
  ./x build
  ```

- [ ] **Step 5: Perform device acceptance**

  Verify settings return hierarchy, shortcut jump timing, one-shot close speed, ripple clipping, weather Toasts, balanced city-source buttons, search result selection, Hitokoto attribution, no wxId text, and icon consistency across the supported WeChat builds.

- [ ] **Step 6: Commit verification-only fixes separately and report evidence**

  Do not rerun DexKit unless a Dex declaration or resolver changes. Report JVM tests, build, scope check, and remaining device-only validation separately.
