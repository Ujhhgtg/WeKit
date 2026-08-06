# Telegram-style grouped conversation cards Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Telegram-style preset that renders pinned and non-pinned conversations as two contiguous rounded cards with a guaranteed hidden divider at their boundary.

**Architecture:** Enrich `WeConversationListViewApi` binding callbacks with adapter position/count and adjacent conversation models, and add an owner-scoped row-divider hide request to its existing divider coordinator. Keep classification, grouped-card geometry, preference UI, and lifecycle ownership inside `BeautifyConversationList`; preserve existing per-row presets and the non-invasive `NO_LAYOUT` mode.

**Tech Stack:** Kotlin, Android `ListView`/`BaseAdapter`, Jetpack Compose Material 3, MMKV preference delegates, WeKit reflection utilities, `./x` build orchestration.

## Global Constraints

- Keep package namespace `dev.ujhhgtg.wekit` and existing feature/UI conventions.
- Do not change Dex declarations or resolution logic; no DexKit desktop test is required.
- Use `reflekt` for host-class reflection, including `field_username` lookup.
- Do not add tests for host UI/reflection glue; manual WeChat validation remains the behavioral test.
- Always use `./x` rather than Gradle directly because Gradle can package a stale native library.
- Divider teardown remains best-effort and must remove both global and row-scoped requests owned by the feature.
- Preserve the existing OR-merged global divider behavior and do not affect unrelated divider owners.
- Treat missing conversation usernames as non-pinned and log the failure once per model class without breaking row rendering.
- Ensure recycled rows cannot retain a former boundary divider override.

---

### Task 1: Extend the conversation-list binding and divider APIs

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt:28-131` (binding context and dispatch)
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt:134-192` (row-scoped divider coordinator)

**Interfaces:**
- Consumes: existing `BaseAdapter.getView(position, convertView, parent)` hooks, `setDividerHidden(owner, hidden)`, `removeDividerOwner(owner)`.
- Produces: `IBindViewListener.onBind(param, row, conversation, context)` where `context` contains `position`, `itemCount`, `previousConversation`, and `nextConversation`; produces `setRowDividerHidden(owner, row, hidden)` and owner cleanup through `removeDividerOwner(owner)`.

- [ ] **Step 1: Define an immutable binding context beside `IBindViewListener`**

  Add a public/internal data class used by the API callback:

  ```kotlin
  data class BindContext(
      val position: Int,
      val itemCount: Int,
      val previousConversation: Any?,
      val nextConversation: Any?,
  )
  ```

  Change the listener signature to:

  ```kotlin
  fun interface IBindViewListener {
      fun onBind(param: HookParam, row: View, conversation: Any, context: BindContext)
  }
  ```

  Keep the class in `WeConversationListViewApi` so callers do not need a new API file.

- [ ] **Step 2: Populate context in both existing adapter hooks**

  In `hookBinding`, after obtaining `position` and `conversation`, construct the context from the same adapter:

  ```kotlin
  val context = BindContext(
      position = position,
      itemCount = adapter.count,
      previousConversation = if (position > 0) adapter.getItem(position - 1) else null,
      nextConversation = if (position + 1 < adapter.count) adapter.getItem(position + 1) else null,
  )
  ```

  Dispatch `listener.onBind(this, row, conversation, context)`. Keep the existing listener exception logging and the final divider-coordinator application.

- [ ] **Step 3: Add owner-scoped row divider state**

  Add a weak row map in `dividerCoordinator`:

  ```kotlin
  private val rowHiddenOwners = WeakHashMap<View, MutableSet<Any>>()
  ```

  Add:

  ```kotlin
  fun setRowHidden(owner: Any, row: View, hidden: Boolean) {
      val owners = rowHiddenOwners[row]
      if (hidden) {
          (owners ?: mutableSetOf<Any>().also { rowHiddenOwners[row] = it }).add(owner)
      } else {
          owners?.remove(owner)
          if (owners != null && owners.isEmpty()) rowHiddenOwners.remove(row)
      }
  }
  ```

  Expose it at the API level:

  ```kotlin
  fun setRowDividerHidden(owner: Any, row: View, hidden: Boolean) {
      dividerCoordinator.setRowHidden(owner, row, hidden)
      dividerCoordinator.apply(row, latestListView?.get())
  }
  ```

  Update `removeOwner(owner)` to remove the owner from every row set before applying/refreshing. Because binding and these state changes run on the host UI thread, retain the coordinator’s existing UI-thread model rather than adding broad synchronization.

- [ ] **Step 4: Apply the row-specific and global divider predicates together**

  Change `applyRowDivider` to accept the row and hide the divider when either the global owner set is non-empty or that row’s owner set is non-empty. Preserve each divider’s original visibility when first hidden and restore it only when neither predicate is active:

  ```kotlin
  private fun applyRowDivider(row: View) {
      val divider = row.findViewByChildIndexes(0, 1, 1, 1)
          ?: row.findViewByChildIndexes(0, 1, 1)
          ?: return
      if (isHidden(row)) {
          rowStates.getOrPut(divider) { RowDividerState(divider.visibility) }
          if (divider.visibility != View.GONE) divider.visibility = View.GONE
      } else {
          val state = rowStates.remove(divider) ?: return
          if (divider.visibility == View.GONE) divider.visibility = state.originalVisibility
      }
  }

  private fun isHidden(row: View): Boolean = hiddenOwners.isNotEmpty() || rowHiddenOwners[row]?.isNotEmpty() == true
  ```

  Keep `applyListView` based only on global hidden owners; a row-specific boundary must not hide the entire `ListView` divider.

- [ ] **Step 5: Commit the API change**

  Run:

  ```bash
  git diff --check
  git add app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt
  git commit -m "feat: expose conversation row binding context"
  ```

  Expected: the API file compiles conceptually with no whitespace errors; the commit contains only the shared API changes.

---

### Task 2: Add grouped-card preferences and row geometry

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt:1-95` (imports, preset, state/accessor caches)
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt:97-253` (listener, dialog, row application)
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt:255-315` (backgrounds and reflection helpers)

**Interfaces:**
- Consumes: `WeConversationListViewApi.BindContext`, `WeConversationListViewApi.setRowDividerHidden`, `WeConversationApi.isPinned`, and the existing unread accessor/cache.
- Produces: a persisted `PINNED_GROUPED_CARD` preset and row visuals whose background shape and insets reflect group position.

- [ ] **Step 1: Add the preset and state dimensions**

  Add a preset entry after `COMFORT_CARD` (or use the project’s final ordering) with comfort-card colors and dimensions:

  ```kotlin
  PINNED_GROUPED_CARD(14, 10, 4, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
  ```

  Extend `RowBackgroundKey` with grouped geometry:

  ```kotlin
  val groupPosition: GroupPosition,
  ```

  Add:

  ```kotlin
  private enum class GroupPosition { SINGLE, FIRST, MIDDLE, LAST }
  ```

  Add a cached `field_username` accessor using the same `UnreadAccessor` pattern (or a small equivalent accessor), plus a once-per-class failure set. The accessor must search with `conversation.reflekt().firstFieldOrNull { name = "field_username"; superclass() }` and return the value as `String`.

- [ ] **Step 2: Add the preset label and dialog behavior**

  Extend the label `when` with:

  ```kotlin
  ConversationListPreset.PINNED_GROUPED_CARD -> "置顶分组卡片"
  ```

  Keep unread highlighting available for the new preset. Selecting `NO_LAYOUT` must continue disabling and persisting unread highlighting as already implemented. Keep the divider switch visible for every preset.

- [ ] **Step 3: Update the bind listener to pass context into row application**

  Change the listener construction to:

  ```kotlin
  private val bindListener = WeConversationListViewApi.IBindViewListener { _, row, conversation, context ->
      applyRowVisuals(row, conversation, context)
  }
  ```

  Change the method signature to accept `WeConversationListViewApi.BindContext`.

- [ ] **Step 4: Classify rows and calculate group positions**

  Add helpers with these exact semantics:

  ```kotlin
  private fun isPinnedConversation(conversation: Any): Boolean {
      val talker = usernameAccessorCache[conversation.javaClass]
          ?.let { (it as UnreadAccessor.Field).get(conversation) as? String }
          ?: return false
      return WeConversationApi.isPinned(talker)
  }

  private fun groupPosition(conversation: Any, context: WeConversationListViewApi.BindContext): GroupPosition {
      val pinned = isPinnedConversation(conversation)
      val previousPinned = context.previousConversation?.let(::isPinnedConversation)
      val nextPinned = context.nextConversation?.let(::isPinnedConversation)
      return when {
          previousPinned != pinned && nextPinned != pinned -> GroupPosition.SINGLE
          previousPinned != pinned -> GroupPosition.FIRST
          nextPinned != pinned -> GroupPosition.LAST
          else -> GroupPosition.MIDDLE
      }
  }
  ```

  Treat a missing adjacent item as a group boundary, so the first and last adapter rows are shaped correctly. Do not infer grouping from position alone.

- [ ] **Step 5: Install grouped row background and boundary divider request**

  In `applyRowVisuals`, after restoring baseline and returning for `NO_LAYOUT`, derive `groupPosition` only for `PINNED_GROUPED_CARD`. For the grouped preset, call:

  ```kotlin
  WeConversationListViewApi.setRowDividerHidden(
      owner = this,
      row = row,
      hidden = isPinnedConversation(conversation) &&
          !context.nextConversation.isNullOrPinned(),
  )
  ```

  Implement the equivalent directly without introducing a nullable extension if clearer: hide only when the current row is pinned and the next item exists and is non-pinned. For all non-grouped presets, explicitly call `setRowDividerHidden(owner = this, row = row, hidden = false)` so recycled rows cannot retain the boundary state.

  Use `backgroundKey` to cache the drawable. Preserve host content padding, and keep existing row restoration behavior.

- [ ] **Step 6: Build corner radii and inset geometry**

  Update `buildRowBackground` to accept `groupPosition` and pass a corner-radii array to `GradientDrawable.setCornerRadii` for the grouped preset:

  ```kotlin
  private fun cornerRadii(context: Context, radiusDp: Int, position: GroupPosition): FloatArray {
      val radius = radiusDp.dpToPx(context).toFloat()
      val zero = 0f
      return when (position) {
          GroupPosition.SINGLE -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
          GroupPosition.FIRST -> floatArrayOf(radius, radius, radius, radius, zero, zero, zero, zero)
          GroupPosition.MIDDLE -> floatArrayOf(zero, zero, zero, zero, zero, zero, zero, zero)
          GroupPosition.LAST -> floatArrayOf(zero, zero, zero, zero, radius, radius, radius, radius)
      }
  }
  ```

  For grouped rows, apply horizontal insets on every row; apply the existing vertical inset only to the first and last edges of each group. A straightforward implementation can use `verticalInset = when (groupPosition) { SINGLE -> preset.verticalInsetDp; FIRST, LAST -> preset.verticalInsetDp; MIDDLE -> 0 }`, preserving the visible gap between the two cards while keeping middle rows flush. Keep the existing colors, stroke, dark-mode handling, ripple, and unread color branches.

- [ ] **Step 7: Clear grouped state during lifecycle teardown**

  In `onDisable`, call the API’s owner cleanup (which removes global and row-scoped divider ownership), then clear the new username accessor/failure caches alongside existing caches. When switching presets, the next refresh must clear row-specific requests on every rebound row and global cleanup must not affect other owners.

- [ ] **Step 8: Commit the feature implementation**

  Run:

  ```bash
  git diff --check
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt
  git commit -m "feat: add grouped conversation cards"
  ```

  Expected: the commit includes the new preset, dialog label, grouped geometry, username/pin classification, boundary divider override, and cleanup only.

---

### Task 3: Compile and verify behavior-facing source contracts

**Files:**
- Read-only verification of `WeConversationListViewApi.kt` and `BeautifyConversationList.kt`.

**Interfaces:**
- Consumes: the two implementation commits and existing `./x` orchestration.
- Produces: a successful build/check result or an exact toolchain limitation, plus a final source audit.

- [ ] **Step 1: Run whitespace and source checks**

  Run:

  ```bash
  git diff --check
  rg -n "PINNED_GROUPED_CARD|setRowDividerHidden|BindContext|field_username|置顶分组卡片" app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt
  ```

  Expected: all new symbols appear in the intended files and `git diff --check` emits no errors.

- [ ] **Step 2: Run the project build**

  Run:

  ```bash
  ./x build
  ```

  Expected: the debug build completes successfully. If the environment lacks the Android SDK/NDK or another required component, preserve the exact failure and do not replace this with direct Gradle assembly.

- [ ] **Step 3: Audit divider and recycling semantics**

  Inspect the final diff and verify all of the following:

  - `setRowDividerHidden` is called for every bound row, with `false` for non-boundaries.
  - `removeDividerOwner(this)` clears both global and row-specific owner state.
  - `applyRowDivider` restores original visibility only when neither global nor row-specific hiding is active.
  - The `ListView` divider remains controlled only by global divider owners.
  - Group classification uses `field_username` plus `WeConversationApi.isPinned`, not a fragile visual or adapter-position heuristic.
  - `NO_LAYOUT`, existing presets, unread highlighting, and the always-on boundary rule remain independent.

- [ ] **Step 4: Final status check**

  Run:

  ```bash
  git status --short
  git log -3 --oneline
  ```

  Expected: only intentional untracked/generated files remain, no modified implementation files are left after the commits, and both implementation commits are visible in recent history.

- [ ] **Step 5: Record manual validation requirements**

  Report that a supported WeChat host must be checked for zero/one/multiple pinned rows, pin/unpin refreshes, scrolling/recycling, unread highlighting, global divider hiding on/off, and the boundary divider hidden regardless of the switch.
