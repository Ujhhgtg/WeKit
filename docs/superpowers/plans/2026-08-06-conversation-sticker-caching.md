# Conversation and Sticker Caching Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce repeated conversation-row binding work through row-local caching and remove redundant sticker-cache filesystem validation.

**Architecture:** Extend the existing weak-keyed `RowVisualState` so each recycled row owns and reuses its mutable background and avatar outline provider. Make shared adapter/list tracking and divider writes idempotent without introducing a global state machine. Keep sticker caching disk-backed and make the decoder the sole owner of returned-file validation.

**Tech Stack:** Kotlin, Android Views and drawables, Xposed hooks, Java weak references/maps, xtask/Gradle.

## Global Constraints

- Do not share `RippleDrawable` instances between rows.
- Do not add an in-memory sticker-path cache.
- Preserve visual output, preference keys, cache retention limits, and Dex declarations.
- Do not add automated tests for this host-dependent UI caching logic.
- Keep row ownership weak: `WeakHashMap<View, RowVisualState>` and `WeakReference<ImageView>`.
- Do not commit unless explicitly requested.

---

### Task 1: Cache Conversation Row Visuals

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt`

**Interfaces:**
- Consumes: `ConversationListPreset`, `RowVisualState`, `AvatarVisualState`, `Int.dpToPx(Context)`.
- Produces: row-local background reuse keyed by preset/unread/dark-mode/density and avatar/provider reuse keyed by descendant validity and radius.

- [ ] **Step 1: Add explicit cache keys to row state**

Add a private immutable background key and store it in `RowVisualState`:

```kotlin
private data class RowBackgroundKey(
    val preset: ConversationListPreset,
    val unread: Boolean,
    val isDark: Boolean,
    val density: Float,
)

private data class RowVisualState(
    var baselineBackground: Drawable?,
    var baselinePaddingLeft: Int,
    var baselinePaddingTop: Int,
    var baselinePaddingRight: Int,
    var baselinePaddingBottom: Int,
    var moduleBackground: Drawable? = null,
    var backgroundKey: RowBackgroundKey? = null,
    var avatar: AvatarVisualState? = null,
)
```

Extend `AvatarVisualState` with `radiusPx: Float`.

- [ ] **Step 2: Reuse row-local backgrounds**

In `applyRowVisuals`, compute:

```kotlin
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
```

Keep the drawable row-local. Update `restoreRowBaseline` so an externally replaced background clears `backgroundKey` and `moduleBackground` after adopting the new host baseline.

- [ ] **Step 3: Reuse valid avatar state**

Add a descendant/visibility check that walks `avatar.parent` to `row`, rejecting any non-visible node. In `installAvatarOutline`:

1. Compute `radiusPx`.
2. If the cached avatar resolves, remains on a visible path under `row`, still owns the module provider, and has the same radius, return without searching or allocating.
3. Otherwise call `clearAvatarState`, find the current candidate, create one provider, and record `radiusPx`.

Move the unconditional `clearAvatarState(state)` out of the start of `applyRowVisuals`. Call it only when rounded avatars are disabled or cached state is invalid.

- [ ] **Step 4: Compile the row-cache implementation**

Run:

```bash
./x build
```

Expected: `BUILD SUCCESSFUL` for standard and legacy debug variants.

---

### Task 2: Make Shared Binding State Idempotent

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt`

**Interfaces:**
- Consumes: existing `latestAdapter`, `latestListView`, and `dividerCoordinator` state.
- Produces: the same weak-reference and divider behavior with no replacement/write when values are already current.

- [ ] **Step 1: Avoid duplicate weak-reference allocation**

Replace each weak reference only when its referent differs:

```kotlin
if (latestAdapter?.get() !== adapter) latestAdapter = WeakReference(adapter)
(args[2] as? ListView)?.let { listView ->
    if (latestListView?.get() !== listView) latestListView = WeakReference(listView)
}
```

- [ ] **Step 2: Avoid duplicate divider assignments**

In the hidden branch of `applyListView`, retain `getOrPut` but assign only when required:

```kotlin
if (listView.divider !== state.moduleDivider) listView.divider = state.moduleDivider
if (listView.dividerHeight != 0) listView.dividerHeight = 0
```

Do not add another hidden-state field; the identity and height checks are the cache.

- [ ] **Step 3: Compile the shared API implementation**

Run:

```bash
./x build
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 3: Remove Redundant Sticker Validation and Verify

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ViewStickerAsImage.kt`

**Interfaces:**
- Consumes: `WeMessageApi.decodeStickerToFile(md5: String, destination: Path): Path?`, whose non-null return is already verified as a non-empty regular file.
- Produces: unchanged disk-cache and fallback behavior with fewer duplicate filesystem metadata calls.

- [ ] **Step 1: Trust the decoder's return contract**

Replace:

```kotlin
return WeMessageApi.decodeStickerToFile(md5, destination)
    ?.takeIf { it.isRegularFile() && it.fileSize() > 0L }
```

with:

```kotlin
return WeMessageApi.decodeStickerToFile(md5, destination)
```

Keep the pre-decode destination check because it controls pruning.

- [ ] **Step 2: Run final verification**

Run:

```bash
./x build
git diff --check
git status --short
```

Expected:

- `BUILD SUCCESSFUL`.
- `git diff --check` exits successfully with no output.
- Only intended source, specification, plan, and pre-existing untracked project-tool directories are present.

- [ ] **Step 3: Perform focused static acceptance review**

Confirm from the final diff:

- no drawable is shared globally;
- row and avatar ownership remain weak;
- every cache has a complete invalidation condition;
- no Dex declaration changed;
- no test or test-only abstraction was added;
- no in-memory sticker path cache was introduced.

Device-only acceptance remains scrolling/recycling, unread/theme refresh, avatar toggle, divider toggle, and repeated sticker viewing in WeChat.
