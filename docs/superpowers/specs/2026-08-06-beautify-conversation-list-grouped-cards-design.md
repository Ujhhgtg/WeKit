# Telegram-style grouped conversation cards design

## Goal

Add a Telegram-style conversation-list preset to `BeautifyConversationList`: all consecutive pinned conversations render as one rounded card, all consecutive non-pinned conversations render as a second rounded card, and the two cards have a gap between them. Conversations inside either group retain the native row-to-row compactness without vertical gaps.

## User-visible behavior

- Add a preset labeled `Telegram 分组卡片` (or the project’s final localized equivalent).
- The pinned group and non-pinned group each have one outer rounded container.
- The first row of each non-empty group has top corners; the last row has bottom corners; middle rows have no rounded outer corners.
- Rows within one group do not receive vertical inset spacing.
- The two groups have vertical spacing between them.
- Existing unread highlighting continues to apply to the relevant row without changing group membership or boundary calculations.
- The existing `隐藏分隔线` switch remains independent. In grouped mode, the divider directly between the last pinned row and first non-pinned row is hidden unconditionally. If the switch is enabled, all other host row dividers are hidden too.
- If there are no pinned conversations, only the non-pinned card is shown; if there are no non-pinned conversations, only the pinned card is shown.
- Existing presets and `不修改卡片布局` retain their current behavior.

## Architecture

### Shared binding API

Extend `WeConversationListViewApi.IBindViewListener` to provide enough adapter context for boundary decisions: current position, item count, and adjacent conversation items (or an equivalent immutable binding context). The existing adapter hooks remain the source of truth and continue supporting both legacy and MVVM adapters.

Add an owner-scoped row-divider override API. The override must be keyed by the row view and owner identity, use weak row state, and be applied by the existing divider coordinator after binding. The override is cleared when the owner releases it or the row is rebound as a non-boundary row. The global divider owner remains OR-merged and unchanged.

### BeautifyConversationList

Add a grouped-card preset and retain the current `RowBackgroundKey` cache, extending its key with group position/state and any dimensions needed by the new drawable. Resolve `field_username` using `reflekt` and cache the accessor by conversation class, then use `WeConversationApi.isPinned(talker)` to classify rows.

For each binding:

1. Restore the feature’s baseline row state.
2. Return immediately for `NO_LAYOUT`.
3. Classify the current conversation and adjacent items as pinned/non-pinned.
4. For grouped mode, derive `SINGLE`, `FIRST`, `MIDDLE`, or `LAST` group position.
5. Build/reuse a background with corners only on the appropriate outer edges. Apply horizontal insets to both groups and vertical inset only at group boundaries, producing the gap between groups while keeping rows within a group contiguous.
6. Request the boundary divider hidden only when the current row is the last pinned row; clear the row override for every other row.
7. Apply the row background and preserve the host row’s original content padding.

The grouped background should use a `GradientDrawable` wrapped in `InsetDrawable` and `RippleDrawable`, matching the current card color, dark-mode color, stroke, ripple, and unread variants. A per-row corner mask or equivalent `GradientDrawable` corner-radius array is preferred over changing the host view hierarchy.

## Divider lifecycle and error handling

- `BeautifyConversationList.onEnable()` registers the bind listener, updates the global divider owner, and refreshes.
- `onDisable()` removes the listener, removes the global divider owner, clears row-scoped divider requests, and clears visual/accessor caches.
- Grouped mode’s unconditional boundary rule must be active even when `hideDividersEnabled` is false.
- Missing `field_username` or pin lookup failure should fail classification closed (treat the row as non-pinned) and log once per model class using the existing logger pattern. This must not break row rendering.
- RecyclerView/ListView recycling or adapter refresh must not leave a former boundary’s divider hidden on a row that is reused elsewhere.

## Validation

- Run `git diff --check`.
- Build with `./x build`; do not substitute direct Gradle because native packaging can become stale.
- Inspect source for correct API compatibility across both list adapters, owner cleanup, recycling cleanup, and independent divider semantics.
- Manually validate in supported WeChat hosts with: no pinned chats, one pinned chat, multiple pinned chats, pinned/unpinned changes, unread highlighting on/off, global divider hiding on/off, and row recycling after scrolling.

## Scope

Only the shared conversation-list API and `BeautifyConversationList` should change unless a narrowly scoped supporting source update is required by compilation. No Dex declarations or resolution matchers change, so no DexKit desktop run is required.
