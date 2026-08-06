# Conversation and Sticker Cache Design

**Date:** 2026-08-06

## Goal

Reduce repeated work in conversation-list row binding without introducing global mutable drawables or difficult invalidation rules. Preserve the existing sticker disk cache and remove redundant filesystem validation rather than adding a second in-memory cache.

## Scope

The implementation changes only:

- `BeautifyConversationList` row-local visual state.
- `WeConversationListViewApi` adapter/list tracking and list-divider assignments.
- `ViewStickerAsImage` validation after `decodeStickerToFile`.

Dex declarations, resolver matchers, cache retention limits, preference schema, and visual output do not change.

## Row Background Cache

Each `RowVisualState` stores a background style key alongside its module-created drawable. The key contains:

- selected preset;
- whether the row is highlighted as unread;
- current dark-mode state;
- current display density.

The drawable is row-local because `RippleDrawable` has mutable pressed/ripple state and must not be shared between rows.

During binding:

1. Preserve or update the host baseline using the existing identity check.
2. Compute the style key.
3. Reuse the row's module drawable when its key matches.
4. Otherwise create one drawable and replace the cached key/drawable.
5. Reapply baseline padding as before.

A preference change triggers `notifyDataSetChanged`; changed preset or highlight state produces a key miss. A theme or density change also produces a key miss. `onDisable` clears `rowStates` as it does today.

## Avatar Cache

`AvatarVisualState` continues to hold the avatar weakly and records:

- the host outline provider and original `clipToOutline` value;
- the module outline provider;
- the applied radius in pixels.

During binding, the cached avatar/provider is reused only when all conditions hold:

- the weak reference still resolves;
- the avatar remains a descendant of the bound row;
- the avatar and its ancestor path remain visible;
- the module outline provider is still installed;
- the configured radius in pixels is unchanged.

When any condition fails, restore the old avatar if the module provider still owns it, clear the state, perform the existing candidate search, and install a new provider. When rounded avatars are disabled, restore and clear any cached avatar state.

This avoids repeated hierarchy walks and provider allocation for stable recycled row structures while allowing WeChat to replace or restyle child views.

## Shared API Idempotence

`WeConversationListViewApi` keeps weak references to the latest adapter and list. A bind replaces each `WeakReference` only when its current referent is not the same object.

The divider coordinator remains defensive on every bind because WeChat may replace divider state. It skips property assignments when the list already has the module divider and a zero divider height. Restoration remains identity-guarded, preserving host changes made while the module did not own the divider.

No additional list-level state machine is introduced.

## Sticker Disk Cache

`WeMessageApi.decodeStickerToFile` already returns a path only after verifying that the final file exists, is regular, and is non-empty. `ViewStickerAsImage.resolveCachedGif` therefore returns that result directly instead of repeating the same `isRegularFile` and `fileSize` calls.

The pre-decode destination check remains because it controls whether directory pruning runs. The decoder remains the sole owner of cache-hit validation and last-modified-time updates. No in-memory path cache is added, so external file deletion cannot leave stale cached paths.

## Error Handling and Memory

- Row state remains in a `WeakHashMap`, so rows are not retained after the host releases them.
- Avatar references remain weak to avoid retaining a row through the child-to-parent chain.
- Cached drawables are retained only by their corresponding weak-keyed row state.
- Existing fallback behavior remains: invalid avatar state causes a new search; failed sticker decode falls through to snapshot generation.
- No new exception swallowing is added around hooks.

## Verification

Automated verification:

1. Run `./x build` for native refresh and both Android debug variants.
2. Run `git diff --check`.
3. Confirm no resolver declaration or matcher changed; therefore the expensive APK Dex matrix is not rerun.
4. Perform a focused static review of ownership and invalidation conditions.

Real-host acceptance checks:

- Scroll and repeatedly recycle conversation rows in all three presets.
- Verify unread changes update row colors after refresh.
- Toggle dark mode or recreate the host and verify colors update.
- Toggle rounded avatars and verify avatar providers restore/reapply correctly.
- Toggle either divider feature and verify list/row dividers hide and restore.
- Open the same sticker repeatedly and confirm cached GIF viewing and snapshot fallback still work.
