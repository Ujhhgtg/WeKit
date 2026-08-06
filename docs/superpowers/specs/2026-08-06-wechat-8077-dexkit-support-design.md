# WeChat 8.0.77 DexKit Support Repair Design

**Date:** 2026-08-06

## Goal

Expand WeKit's supported WeChat host range through 8.0.77. The current 8.0.77 desktop resolution run has six primary unexpected resolver failures and 105 delegates blocked by those failures. The repair must resolve the six changed targets while keeping the existing 8.0.65–8.0.76 behavior intact.

## Compatibility contract

- WeChat 8.0.65–8.0.76 remain compatibility gates and must continue to pass.
- WeChat 8.0.77 becomes a compatibility gate after the repair.
- Required targets remain strict: no `allowFailure` is added merely to turn a required target into a placeholder.
- Any remaining `UNEXPECTED_FAILURE`, `BLOCKED`, `INCOMPLETE`, initialization, worker, native-library, metadata, or report failure fails the desktop run.
- Resolver-side version selection reads `DexResolutionContext.host`, never `HostInfo`, and all matcher dependencies use DexKit metadata rather than JVM reflection.

## Affected resolver domains

1. **VoIPMP and MultiTalk**
   - `PipVoip:methodVoipMpLaunchPage` is the primary failure for the VoIP PIP feature.
   - `SplitGroupCall:classSubCoreMultiTalk` is the primary failure for split group calls.
   - Related delegates are expected to unblock once their root class/method is resolved.
   - Existing runtime hook logic is preserved; only resolver declarations/selection change.

2. **Contacts and avatars**
   - `CustomLocalFriendAvatars:methodFeatureAvatarSimple1` must resolve the 8.0.77 avatar loader method.
   - `HideContacts:methodVoipMpInsertMsg` must resolve the 8.0.77 VoIPMP call-record insertion method.
   - Existing hook behavior is preserved.

3. **Conversation list API**
   - `WeConversationListViewApi:methodLegacyGetView` must account for the 8.0.77 conversation adapter transition while retaining the existing MVVM path and prior version rules.
   - The API must not hook a placeholder and must continue to bind rows through the existing `hookBinding` path.

4. **Moments API**
   - `WeMomentsApi:methodAddSightObjectByPath` must resolve the 8.0.77 `UploadPackHelper` method using its semantic class, return type, parameter shape, and stable upload evidence.
   - Existing media upload/repost behavior is unchanged.

## Resolver strategy

Prefer explicit 8.0.77 branches where the host structure differs, retaining the proven 8.0.65–8.0.76 matchers unchanged. A generalized matcher is acceptable only when desktop evidence demonstrates that it remains unique and preserves the intended descriptor and structural predicates. Every resolver rewrite is intentionally a cache-key change; the generated method hash must be regenerated and old device cache entries must be allowed to re-resolve once.

For each affected target:

1. Inspect 8.0.77 DEX evidence and identify the exact class/method descriptor and stable strings/structure.
2. Compare the 8.0.76 descriptor and matcher.
3. Add the narrowest host-aware branch or metadata-based matcher that resolves both versions.
4. Ensure downstream delegates use `.data` metadata while resolving.
5. Run the affected feature against 8.0.77 before broad validation.

## Error handling and scope

The repair addresses root resolver mismatches only. It must not alter diagnostic classification, convert unexpected failures to expected failures, or weaken the desktop runner's failure policy. Blocked delegates are resolved by fixing their root dependency, not by changing blocked-state handling.

## Validation gates

- Affected-feature desktop resolution against 8.0.77: zero unexpected, blocked, or incomplete delegates.
- Full desktop matrix for every APK from 8.0.65 through 8.0.77, including normal and Google Play variants where available.
- Relevant existing Gradle tests.
- `./x build` so the native library and APK are built through the repository's orchestration.
- `git diff --check`.
- Physical-device/manual WeChat validation remains required for runtime hook behavior; desktop resolution alone does not prove hook-time behavior.

## Out of scope

- Supporting versions after 8.0.77.
- Reworking DexKit diagnostics or the worker orchestration.
- Broad resolver refactoring unrelated to the six failures.
- Adding low-value unit tests for simple matcher declarations or host hook glue.
