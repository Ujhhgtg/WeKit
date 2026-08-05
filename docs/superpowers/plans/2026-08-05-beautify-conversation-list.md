# BeautifyConversationList Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add configurable home conversation-list card styles, unread highlighting, row-local avatar rounding, and coordinated divider hiding across all supported legacy/MVVM adapters.

**Architecture:** `WeConversationListViewApi` is the always-on shared binding boundary. It resolves both proven `BaseAdapter.getView(int, View, ViewGroup): View` targets, dispatches listeners, retains weak adapter/ListView references, refreshes through virtual `notifyDataSetChanged`, and owns the OR-merged divider state. `BeautifyConversationList` is a `ClickableFeature` consumer that owns preferences, pure visual policy, row restoration, unread access, avatar discovery, and Compose settings. `HideConversationListDividers` becomes a coordinator owner without its own Dex hooks.

**Tech Stack:** Kotlin, Android Views/graphics, Jetpack Compose Material 3, DexKit DSL, `reflekt`, WePrefs/MMKV, JUnit Jupiter, xtask (`./x`).

## Global Constraints

- Target only WeChat 8.0.65, 8.0.67, 8.0.69, 8.0.69 Google Play, 8.0.74, and 8.0.76.
- Run only in the WeChat main process through existing `ApiFeature`/`ClickableFeature` defaults.
- Legacy conversation adapter is expected only in 8.0.74/8.0.76; only that delegate may use `allowFailure` and must set an explicit versioned expected-failure placeholder through `DexResolutionContext.host`.
- MVVM conversation adapter is mandatory on every supported host; it must not use `allowFailure`.
- Resolver matcher dependencies must use Dex delegate `.data` metadata, never JVM reflection or host class loading during resolution.
- Conversation hosts are `ListView + BaseAdapter`; do not add a RecyclerView branch.
- `refresh()` calls the live adapter’s virtual `notifyDataSetChanged()` on the main thread and performs no data query or reconstruction.
- Runtime host reflection must use `reflekt`; unread accessor failures log once per runtime model class and mean “read”.
- Row styling must restore only module-owned values; do not alter event listeners, hierarchy, margins, LayoutParams, elevation, foreground, global `RoundAvatars`, or private `Themes` palette state.
- JVM tests may cover only Android/WeChat-independent value objects and pure functions. Do not fake Views, adapters, DexKit, reflection, Activities, or Compose.
- Build through `./x build`; do not use direct Gradle assembly as final build evidence.
- Do not modify `AntiStatusDeletion` or `WeTextStatusApi`.
- Produce one implementation commit: `feat: beautify conversation list`.

---

## File Map

- Create `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/ConversationListVisualPolicy.kt` — Android-free presets, palette, unread predicate, divider OR rule, avatar candidate scoring, and dp conversion.
- Create `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/ConversationListVisualPolicyTest.kt` — pure policy tests only.
- Create `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt` — dual adapter resolver, listener dispatch, weak refresh state, divider coordinator.
- Modify `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/HideConversationListDividers.kt` — remove its Dex hooks and delegate to API ownership.
- Create `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt` — feature lifecycle, preferences, row visuals, unread access, avatar outline, and Compose dialog.

## Stable Interfaces

```kotlin
internal enum class ConversationListPreset(
    val rowRadiusDp: Int,
    val horizontalInsetDp: Int,
    val verticalInsetDp: Int,
    val avatarRadiusDp: Int,
    val lightBackgroundColor: Int,
    val darkBackgroundColor: Int,
)

internal data class ConversationListPalette(
    val backgroundColor: Int,
    val strokeColor: Int,
    val unreadBackgroundColor: Int,
    val rippleColor: Int,
)

internal data class AvatarCandidateMetrics(
    val widthPx: Int,
    val heightPx: Int,
    val depth: Int,
)

internal fun conversationListPalette(
    preset: ConversationListPreset,
    isDark: Boolean,
): ConversationListPalette

internal fun isUnreadConversation(unreadCount: Int): Boolean
internal fun shouldHideConversationDivider(
    hideConversationListDividersEnabled: Boolean,
    beautifyConversationListEnabled: Boolean,
    beautifyHideDividersEnabled: Boolean,
): Boolean
internal fun avatarCandidateScore(
    candidate: AvatarCandidateMetrics,
    density: Float,
): Float?
internal fun dpToPx(dp: Int, density: Float): Int
```

```kotlin
object WeConversationListViewApi : ApiFeature(), IResolveDex {
    fun interface IBindViewListener {
        fun onBind(param: HookParam, row: View, conversation: Any)
    }
    fun addListener(listener: IBindViewListener)
    fun removeListener(listener: IBindViewListener)
    fun refresh()
    fun setDividerHidden(owner: Any, hidden: Boolean)
    fun removeDividerOwner(owner: Any)
}
```

---

### Task 1: Add and Test the Pure Conversation Visual Policy

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/ConversationListVisualPolicy.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/ConversationListVisualPolicyTest.kt`

**Interfaces:**
- Produces: all Android-free interfaces listed above.

- [ ] **Step 1: Write the failing tests**

Create tests for all three presets, palette constants, unread/divider semantics, avatar candidate bounds/scoring, and truncating dp conversion:

```kotlin
package dev.ujhhgtg.wekit.features.items.beautify

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationListVisualPolicyTest {
    @Test
    fun presetsMatchTheSpecification() {
        assertEquals(ConversationListPreset.COMFORT_CARD, ConversationListPreset.entries[0])
        assertEquals(14, ConversationListPreset.COMFORT_CARD.rowRadiusDp)
        assertEquals(10, ConversationListPreset.COMFORT_CARD.horizontalInsetDp)
        assertEquals(4, ConversationListPreset.COMFORT_CARD.verticalInsetDp)
        assertEquals(12, ConversationListPreset.COMFORT_CARD.avatarRadiusDp)
        assertEquals(0xFFF7FAF9.toInt(), ConversationListPreset.COMFORT_CARD.lightBackgroundColor)
        assertEquals(0xFF252827.toInt(), ConversationListPreset.COMFORT_CARD.darkBackgroundColor)

        assertEquals(10, ConversationListPreset.COMPACT_ROUNDED.rowRadiusDp)
        assertEquals(6, ConversationListPreset.COMPACT_ROUNDED.horizontalInsetDp)
        assertEquals(2, ConversationListPreset.COMPACT_ROUNDED.verticalInsetDp)
        assertEquals(10, ConversationListPreset.COMPACT_ROUNDED.avatarRadiusDp)
        assertEquals(0xFFF9FBFA.toInt(), ConversationListPreset.COMPACT_ROUNDED.lightBackgroundColor)
        assertEquals(0xFF272928.toInt(), ConversationListPreset.COMPACT_ROUNDED.darkBackgroundColor)

        assertEquals(6, ConversationListPreset.MINIMAL_LIST.rowRadiusDp)
        assertEquals(0, ConversationListPreset.MINIMAL_LIST.horizontalInsetDp)
        assertEquals(0, ConversationListPreset.MINIMAL_LIST.verticalInsetDp)
        assertEquals(8, ConversationListPreset.MINIMAL_LIST.avatarRadiusDp)
        assertEquals(0xFFFCFCFC.toInt(), ConversationListPreset.MINIMAL_LIST.lightBackgroundColor)
        assertEquals(0xFF232323.toInt(), ConversationListPreset.MINIMAL_LIST.darkBackgroundColor)
    }

    @Test
    fun paletteUsesFixedLightAndDarkColors() {
        assertEquals(
            ConversationListPalette(0xFFF7FAF9.toInt(), 0x16161D1C, 0xFFEAF8F2.toInt(), 0x18006A62),
            conversationListPalette(ConversationListPreset.COMFORT_CARD, false),
        )
        assertEquals(
            ConversationListPalette(0xFF232323.toInt(), 0x22FFFFFF, 0xFF253E37.toInt(), 0x2AFFFFFF),
            conversationListPalette(ConversationListPreset.MINIMAL_LIST, true),
        )
    }

    @Test
    fun unreadAndDividerRulesMatchTheOrSemantics() {
        assertTrue(isUnreadConversation(1))
        assertFalse(isUnreadConversation(0))
        assertFalse(isUnreadConversation(-1))
        assertFalse(shouldHideConversationDivider(false, false, true))
        assertFalse(shouldHideConversationDivider(false, true, false))
        assertTrue(shouldHideConversationDivider(true, false, false))
        assertTrue(shouldHideConversationDivider(false, true, true))
        assertTrue(shouldHideConversationDivider(true, true, false))
    }

    @Test
    fun avatarScoringRejectsInvalidGeometryAndRanksCandidates() {
        assertNull(avatarCandidateScore(AvatarCandidateMetrics(63, 63, 9), 1f))
        assertNull(avatarCandidateScore(AvatarCandidateMetrics(31, 31, 8), 1f))
        assertNull(avatarCandidateScore(AvatarCandidateMetrics(85, 85, 8), 1f))
        assertNull(avatarCandidateScore(AvatarCandidateMetrics(84, 64, 8), 1f))
        assertTrue(
            avatarCandidateScore(AvatarCandidateMetrics(64, 64, 8), 1f)!! >
                avatarCandidateScore(AvatarCandidateMetrics(64, 60, 8), 1f)!!,
        )
    }

    @Test
    fun dpConversionTruncates() {
        assertEquals(15, dpToPx(10, 1.5f))
        assertEquals(13, dpToPx(9, 1.5f))
        assertEquals(0, dpToPx(0, 3f))
    }
}
```

- [ ] **Step 2: Run the focused test and verify the red state**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.ConversationListVisualPolicyTest'
```

Expected: compilation fails because the policy source does not exist.

- [ ] **Step 3: Implement the Android-free policy**

Create the enum with exact values:

```kotlin
internal enum class ConversationListPreset(
    val rowRadiusDp: Int,
    val horizontalInsetDp: Int,
    val verticalInsetDp: Int,
    val avatarRadiusDp: Int,
    val lightBackgroundColor: Int,
    val darkBackgroundColor: Int,
) {
    COMFORT_CARD(14, 10, 4, 12, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
    COMPACT_ROUNDED(10, 6, 2, 10, 0xFFF9FBFA.toInt(), 0xFF272928.toInt()),
    MINIMAL_LIST(6, 0, 0, 8, 0xFFFCFCFC.toInt(), 0xFF232323.toInt()),
}
```

Implement the palette constants, `isUnreadConversation(count > 0)`, the explicit OR rule, and the Nuke-compatible avatar score: reject depth > 8, side bounds 32dp–84dp, max side zero, and shape deviation > 0.22; return area minus deviation. Keep the file free of Android, WeChat, DexKit, `reflekt`, and Compose imports.

- [ ] **Step 4: Run the focused test and verify green**

Run the same focused test command. Expected: `BUILD SUCCESSFUL` and all five tests pass.

---

### Task 2: Implement the Dual-Adapter Binding API and Divider Coordinator

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt`

**Interfaces:**
- Consumes: Task 1’s `shouldHideConversationDivider`, `ApiFeature`, `IResolveDex`, `HookParam`, `runOnUiThread`, and existing `View.findViewByChildIndexes`.
- Produces: `WeConversationListViewApi` stable interface, listener dispatch, refresh, and shared divider ownership.

- [ ] **Step 1: Declare delegates and resolver branches**

Use uninitialized delegates:

```kotlin
private val methodLegacyGetView by dexMethod()
private val methodMvvmGetView by dexMethod()
```

In `resolveDex`, read `DexResolutionContext.host.versionName`. For `8.0.74` and `8.0.76`, call:

```kotlin
methodLegacyGetView.setPlaceholderDescriptor(
    expectedFailure = true,
    reason = "ConversationWithCacheAdapter is absent in WeChat $hostVersion; MVVM adapter remains required",
)
```

For every other supported host, resolve legacy with:

```kotlin
methodLegacyGetView.find(dexKit) {
    searchPackages("com.tencent.mm.ui.conversation")
    matcher {
        name = "getView"
        paramTypes("int", "android.view.View", "android.view.ViewGroup")
        returnType = "android.view.View"
        usingEqStrings(
            "MicroMsg.ConversationWithCacheAdapter",
            "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
        )
    }
}
```

Resolve MVVM without `allowFailure`:

```kotlin
methodMvvmGetView.find(dexKit) {
    matcher {
        declaredClass {
            usingEqStrings(
                "MicroMsg.ConversationAdapter.MvvmConversationAdapter",
                "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
            )
        }
        name = "getView"
        paramTypes("int", "android.view.View", "android.view.ViewGroup")
        returnType = "android.view.View"
    }
}
```

- [ ] **Step 2: Add listener registration and weak refresh state**

Match `WeChatMessageViewApi`:

```kotlin
private const val TAG = "WeConversationListViewApi"
private val listeners = CopyOnWriteArrayList<IBindViewListener>()
private var latestAdapter: WeakReference<BaseAdapter>? = null
private var latestListView: WeakReference<ListView>? = null

fun addListener(listener: IBindViewListener) {
    if (!listeners.contains(listener)) listeners.add(listener)
}

fun removeListener(listener: IBindViewListener) {
    val removed = listeners.remove(listener)
    WeLogger.i(TAG, "listener remove ${if (removed) "succeeded" else "failed"}, current listener count: ${listeners.size}")
}
```

- [ ] **Step 3: Hook both targets and dispatch after binding**

For each non-placeholder delegate, install an after hook with the verified host contract:

```kotlin
private fun hookBinding(method: DexMethodDelegate) {
    if (method.isPlaceholder) return
    method.hookAfter {
        val row = result as View
        val adapter = thisObject as BaseAdapter
        val position = args[0] as Int
        val conversation = adapter.getItem(position)!!
        latestAdapter = WeakReference(adapter)
        (args[2] as? ListView)?.let { latestListView = WeakReference(it) }

        for (listener in listeners) {
            try {
                listener.onBind(this, row, conversation)
            } catch (error: Exception) {
                WeLogger.e(TAG, "listener ${listener.javaClass.name} threw", error)
            }
        }
        dividerCoordinator.apply(row, latestListView?.get())
    }
}
```

`onEnable()` calls `hookBinding(methodLegacyGetView)` and `hookBinding(methodMvvmGetView)`. Do not catch contract failures or silently skip a null row/conversation.

- [ ] **Step 4: Implement main-thread adapter refresh**

```kotlin
fun refresh() {
    runOnUiThread {
        val adapter = latestAdapter?.get() ?: return@runOnUiThread
        val listView = latestListView?.get()
        if (listView != null && listView.adapter !== adapter) return@runOnUiThread
        adapter.notifyDataSetChanged()
    }
}
```

The call remains virtual so 8.0.74/8.0.76 custom overrides resynchronize their MvvmList state.

- [ ] **Step 5: Implement explicit divider owner merging**

Store owner requests with identity semantics (`IdentityHashMap<Any, Boolean>`). Expose:

```kotlin
fun setDividerHidden(owner: Any, hidden: Boolean) {
    ownerRequests[owner] = hidden
    refresh()
}

fun removeDividerOwner(owner: Any) {
    ownerRequests.remove(owner)
    refresh()
}
```

The effective request is `ownerRequests.values.any { it }`. On every bind and refresh convergence:

1. Find row divider using `(0, 1, 1, 1)` then `(0, 1, 1)`.
2. Save original visibility in a `WeakHashMap<View, RowDividerState>` only on first module ownership.
3. Hide with `View.GONE` when requested; restore only if the current value is still the module-written `GONE` when no owner requests remain.
4. If a live `ListView` is known, save original divider and dividerHeight in a `WeakHashMap<ListView, ListDividerState>`, install one module-owned transparent `ColorDrawable` and height 0 when hidden, and restore only if the current divider is that module object.

Do not let a consumer mutate either divider directly.

- [ ] **Step 6: Compile the API**

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Run the affected Dex matrix**

```bash
./x dex-test \
  --apk ~/coding/wechat_8065.apk \
  --apk ~/coding/wechat_8067.apk \
  --apk ~/coding/wechat_8069.apk \
  --apk ~/coding/wechat_8069_3020_play.apk \
  --apk ~/coding/wechat_8074.apk \
  --apk ~/coding/wechat_8076.apk \
  --output-dir dex-test-results/beautify-conversation-list-api \
  --verbose
```

Expected: legacy success on 8065/67/69/69 Play; explicit expected failure only on 8074/76; MVVM success everywhere; no unexpected, blocked, or incomplete result.

---

### Task 3: Migrate HideConversationListDividers to the Shared Coordinator

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/HideConversationListDividers.kt`

**Interfaces:**
- Consumes: `WeConversationListViewApi.setDividerHidden` and `removeDividerOwner`.
- Produces: the same independent switch semantics without Dex declarations or direct View mutation.

- [ ] **Step 1: Replace the feature body**

```kotlin
package dev.ujhhgtg.wekit.features.items.beautify

import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(
    name = "隐藏对话列表分割线",
    categories = ["聊天", "界面美化"],
    description = "隐藏主页对话列表里对话间的分割线",
)
object HideConversationListDividers : SwitchFeature() {
    override fun onEnable() {
        WeConversationListViewApi.setDividerHidden(this, true)
    }

    override fun onDisable() {
        WeConversationListViewApi.removeDividerOwner(this)
    }
}
```

Remove `IResolveDex`, both old delegates, `ViewGroup`, `isGone`, `findViewByChildIndexes`, old hooks, and `handleViewGroup`. Do not add a unit test for host Views; API Dex and device acceptance cover this migration.

- [ ] **Step 2: Compile the migration**

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 4: Add BeautifyConversationList Preferences, Row Ownership, and Avatar/Unread Styling

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt`

**Interfaces:**
- Consumes: Task 1 policy functions and Task 2 API.
- Produces: enabled feature listener, row card/ripple visuals, unread tint, avatar outline, and divider request.

- [ ] **Step 1: Declare preferences and lifecycle**

```kotlin
@Feature(
    name = "美化对话列表",
    categories = ["聊天", "界面美化"],
    description = "为主页会话列表提供卡片布局、圆角头像、未读突出和分隔线设置",
)
object BeautifyConversationList : ClickableFeature() {
    private const val TAG = "BeautifyConversationList"

    private var presetName by prefOption(
        "beautify_conversation_list_preset",
        ConversationListPreset.COMFORT_CARD.name,
    )
    private var roundAvatarsEnabled by prefOption(
        "beautify_conversation_list_round_avatars",
        true,
    )
    private var highlightUnreadEnabled by prefOption(
        "beautify_conversation_list_highlight_unread",
        true,
    )
    private var hideDividersEnabled by prefOption(
        "beautify_conversation_list_hide_dividers",
        true,
    )

    private val selectedPreset: ConversationListPreset
        get() = ConversationListPreset.entries.firstOrNull { it.name == presetName }
            ?: ConversationListPreset.COMFORT_CARD

    private val bindListener = WeConversationListViewApi.IBindViewListener { _, row, conversation ->
        applyRowVisuals(row, conversation)
    }

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
    }
}
```

- [ ] **Step 2: Implement module-owned row restoration**

Use `WeakHashMap<View, RowVisualState>` with baseline background/padding, module background identity, and avatar state. On each bind:

1. If the row still carries the module background, restore baseline background and padding.
2. If another feature/WeChat replaced it, update baseline to the current value and do not overwrite it.
3. Restore an avatar’s original `outlineProvider` and `clipToOutline` only when the current provider is the module provider.
4. Clear the previous avatar state before searching/applying the current state.

Do not save or modify foreground, elevation, LayoutParams, margins, child hierarchy, or touch listeners; `InsetDrawable` is draw-area inset only and does not claim row measurement spacing.

- [ ] **Step 3: Build the row background**

Use `Context.isDarkMode`, the selected preset, and the pure palette:

```kotlin
private fun buildRowBackground(
    context: Context,
    preset: ConversationListPreset,
    palette: ConversationListPalette,
    unread: Boolean,
): Drawable {
    val card = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = preset.rowRadiusDp.dpToPx(context).toFloat()
        setColor(if (unread) palette.unreadBackgroundColor else palette.backgroundColor)
        setStroke(1.dpToPx(context).coerceAtLeast(1), palette.strokeColor)
    }
    val inset = InsetDrawable(
        card,
        preset.horizontalInsetDp.dpToPx(context),
        preset.verticalInsetDp.dpToPx(context),
        preset.horizontalInsetDp.dpToPx(context),
        preset.verticalInsetDp.dpToPx(context),
    )
    return RippleDrawable(ColorStateList.valueOf(palette.rippleColor), inset, null)
}
```

Assign only `row.background`, then restore baseline padding after assignment.

- [ ] **Step 4: Implement unread accessor lookup through reflekt**

Cache by runtime model class. Traverse inherited fields using `reflekt`, searching exact name `field_unReadCount`; cache either the accessor or a missing marker. On missing/read failure, log one warning per runtime class and return false. Convert only `Number` values and call `isUnreadConversation`; never query the database.

- [ ] **Step 5: Implement bounded avatar discovery and outline ownership**

Perform iterative or recursive DFS over visible nodes to depth 8. For each laid-out `ImageView`, call:

```kotlin
avatarCandidateScore(
    AvatarCandidateMetrics(view.width, view.height, depth),
    view.resources.displayMetrics.density,
)
```

Retain the highest score. If a candidate exists, save its original outline provider/clip flag, install a module-specific `ViewOutlineProvider` with the preset avatar radius, set `clipToOutline = true`, and call `invalidateOutline()`. Restore only if the provider remains module-owned. If the option is disabled or no candidate qualifies, do not alter any avatar.

- [ ] **Step 6: Submit the divider request**

```kotlin
private fun updateDividerRequest() {
    WeConversationListViewApi.setDividerHidden(
        owner = this,
        hidden = isEnabled && hideDividersEnabled,
    )
}
```

Do not read or toggle `HideConversationListDividers`; the API merges owners.

---

### Task 5: Add the Staged Compose Settings Dialog

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt`

**Interfaces:**
- Consumes: existing `showComposeDialog`, `AlertDialogContent`, `ListItem`, `Switch`, `Button`, `TextButton`, and preference properties.
- Produces: cancel-without-write behavior and confirm-all-at-once persistence/refresh.

- [ ] **Step 1: Stage local state inside `onClick`**

Use `remember` values initialized from persisted settings:

```kotlin
override fun onClick(context: ComponentActivity) {
    showComposeDialog(context) {
        var draftPreset by remember { mutableStateOf(selectedPreset) }
        var draftRoundAvatars by remember { mutableStateOf(roundAvatarsEnabled) }
        var draftHighlightUnread by remember { mutableStateOf(highlightUnreadEnabled) }
        var draftHideDividers by remember { mutableStateOf(hideDividersEnabled) }
        // AlertDialogContent below
    }
}
```

- [ ] **Step 2: Render the three preset choices and three switches**

Use existing project UI primitives. Labels must be exactly `舒适卡片`, `紧凑圆角`, `简洁列表`, `圆角头像`, `突出未读会话`, and `隐藏分隔线`. Preset rows update only `draftPreset`; switch rows update only their draft boolean. The dialog must not write preferences during editing.

- [ ] **Step 3: Implement cancel and confirm actions**

Cancel invokes `onDismiss` only. Confirm performs exactly once, in order:

```kotlin
presetName = draftPreset.name
roundAvatarsEnabled = draftRoundAvatars
highlightUnreadEnabled = draftHighlightUnread
hideDividersEnabled = draftHideDividers
updateDividerRequest()
WeConversationListViewApi.refresh()
onDismiss()
```

- [ ] **Step 4: Compile the complete consumer**

```bash
./gradlew :app:compileStandardDebugKotlin
```

Expected: `BUILD SUCCESSFUL`.

---

### Task 6: Run Final Verification and Create the Single Commit

**Files:**
- Verify all five source/test files in this plan.

- [ ] **Step 1: Run the focused pure-policy tests**

```bash
./gradlew :app:testStandardDebugUnitTest --tests 'dev.ujhhgtg.wekit.features.items.beautify.ConversationListVisualPolicyTest'
```

Expected: all policy tests pass.

- [ ] **Step 2: Run the affected Dex matrix**

```bash
./x dex-test \
  --apk ~/coding/wechat_8065.apk \
  --apk ~/coding/wechat_8067.apk \
  --apk ~/coding/wechat_8069.apk \
  --apk ~/coding/wechat_8069_3020_play.apk \
  --apk ~/coding/wechat_8074.apk \
  --apk ~/coding/wechat_8076.apk \
  --output-dir dex-test-results/beautify-conversation-list-final \
  --verbose
```

Expected: MVVM success on every APK; legacy success on 8065/67/69/69 Play; explicit versioned `EXPECTED_FAILURE` on 8074/76; no `UNEXPECTED_FAILURE`, `BLOCKED`, or `INCOMPLETE`.

- [ ] **Step 3: Run the required build**

```bash
./x build
```

Expected: debug APK build succeeds with refreshed native libraries.

- [ ] **Step 4: Check scope and whitespace**

```bash
git diff --check
git diff --name-only
```

The implementation commit may include only the five paths in this plan. Do not stage the design/plan docs, `.claude/`, sticker files, status files, or unrelated changes.

- [ ] **Step 5: Record manual device acceptance separately**

On both a legacy-adapter host and an MVVM-only host, verify all three presets, light/dark colors, unread positive/zero/missing behavior, avatar toggle locality, divider OR ownership, row recycle restoration, staged cancel/confirm semantics, immediate refresh, untouched click/long-click/swipe behavior, and feature disable. Desktop tests do not establish UI behavior.

- [ ] **Step 6: Commit only this implementation**

```bash
git add \
  app/src/main/java/dev/ujhhgtg/wekit/features/api/ui/WeConversationListViewApi.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/ConversationListVisualPolicy.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/BeautifyConversationList.kt \
  app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/HideConversationListDividers.kt \
  app/src/test/java/dev/ujhhgtg/wekit/features/items/beautify/ConversationListVisualPolicyTest.kt
git commit -m "feat: beautify conversation list"
```

Do not include sticker-related changes, `AntiStatusDeletion`, `WeTextStatusApi`, docs, or `.claude/`.
