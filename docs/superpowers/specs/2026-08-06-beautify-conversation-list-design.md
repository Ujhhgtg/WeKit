# Beautify conversation list preferences design

## Goal

Make `BeautifyConversationList` the single owner of conversation-list visual preferences and divider hiding, while making the default behavior non-invasive.

## Behavior

- Add a `NO_LAYOUT` conversation-list preset labeled `不修改卡片布局` and make it the default preference value.
- When `NO_LAYOUT` is selected, the feature restores the host row background and padding and does not apply card backgrounds or row layout changes.
- The unread highlighting switch is shown only for layout presets. Selecting `NO_LAYOUT` hides that switch and forces unread highlighting off for the saved configuration and current draft.
- The divider hiding switch remains visible for all presets and remains independently effective.
- New preference defaults are unread highlighting off and divider hiding off.
- Existing valid stored preset/toggle values remain honored unless changed by the user; the new defaults apply when no value has been stored.
- Avatar rounding is removed from this feature and remains exclusively implemented by `RoundAvatars`.

## Implementation

- Extend `ConversationListPreset` with `NO_LAYOUT`.
- Remove the conversation-list feature's avatar preference, avatar state, outline installation, candidate scanning, and associated imports.
- Branch row visual application for `NO_LAYOUT`; retain baseline restoration and normal card rendering for the other presets.
- Keep divider ownership calls in `BeautifyConversationList`, using its own owner identity and existing refresh lifecycle.
- Delete `HideConversationListDividers.kt` to avoid duplicate feature registration and competing divider controls.

## Validation

- Confirm the source contains no duplicate avatar option or standalone divider feature.
- Run `git diff --check`.
- Run the project's `./x build` verification if the local toolchain permits it.
- Do not add tests for host UI/reflection glue; validate those behaviors manually in WeChat according to project policy.
