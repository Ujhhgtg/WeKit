# 加入群聊自动免打扰 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Detect when the current user joins a WeChat group through the shared room-member synchronization path, then submit one native group Do Not Disturb operation without affecting the initial self-created-group operation or ordinary member updates.

**Architecture:** Add a feature-local DexKit hook for WeChat’s `ChatroomMembersLogic.N(...)` synchronization method. The hook snapshots a lightweight WeKit-owned chatroom state before and after the original method, accepts only a confirmed `self absent -> self present` transition, and dispatches one asynchronous `WeConversationApi.setDnd(roomId, true)` call. Initial self-created-group persistence bypasses this generic sync method; a later leave-and-rejoin intentionally enters the join rule. Keep database access in `WeDatabaseApi`; do not use `WeDatabaseListenerApi` as the trigger and do not persist processed room IDs.

**Tech Stack:** Kotlin, LibXposed hook APIs, DexKit delegates, WeKit `SwitchFeature`, MMKV-backed feature enablement, WeDatabaseApi/WeConversationApi, coroutines, desktop DexKit validation through `./x dex-test`, Android build through `./x build`.

## Global Constraints

- Target WeChat versions are 8.0.65, 8.0.67, 8.0.69, 8.0.74, and 8.0.76.
- Support both ordinary `@chatroom` and OpenIM `@im.chatroom`; do not act on `@groupcard` or `@talkroom`.
- Only mute groups the current user joins; never mute the initial operation that creates or pulls up a group. If the user later leaves and rejoins that group, treat the rejoin as a new join and mute it.
- Do not trigger from `chatroom` table insert/update listeners.
- Reuse `WeConversationApi.setDnd(roomId, true)` and `WeConversationApi.isDnd(roomId)`; do not duplicate room-oplog resolvers.
- Do not add retry behavior, configurable delays, whitelist/blacklist settings, or persistent processed-room history.
- `hookBefore` and `hookAfter` must not be wrapped in `try-catch` or `runCatching`.
- Resolver code must use DexKit metadata rather than JVM reflection or resolved delegate reflection.
- Do not add low-value tests coupled to WeChat host classes; use focused pure tests only if the transition predicate is extracted into WeKit-owned low-coupling logic.
- Always validate resolver changes with the affected supported APKs, then run `./x build` and `git diff --check`.

---

## File Map

### Create

- `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/models/WeChatroomSyncState.kt` — immutable WeKit-owned snapshot containing room ID, normalized member IDs, and optional member version.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroup.kt` — switch feature, DexKit resolver, before/after hook, async DND dispatch, bounded in-memory deduplication.
- `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroupLogic.kt` — pure transition/dedup-key decision logic, separated from host/database code so it can be tested without WeChat classes.
- `app/src/test/...` — focused tests for the pure transition and key logic.

### Modify

- `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeDatabaseApi.kt` — add a bound-SQL helper that reads one chatroom’s raw member list and version into `WeChatroomSyncState?` without resolving contacts.

### Do not modify unless validation proves necessary

- `WeConversationApi.kt` — existing group DND operation is the implementation of record.
- `WeDatabaseListenerApi.kt` — not a trigger for this feature.
- KSP/buildSrc generated feature registration — `@Feature` discovery is automatic.

---

## Task 1: Verify cross-version sync signatures and creation-path bypass

**Files:**
- Read: `/home/ujhhgtg/coding/wechat_8065/**`
- Read: `/home/ujhhgtg/coding/wechat_8067/**`
- Read: `/home/ujhhgtg/coding/wechat_8069/**`
- Read: `/home/ujhhgtg/coding/wechat_8074/**`
- Read: `/home/ujhhgtg/coding/wechat_8076/**`
- Modify: none

**Interfaces:**
- Produces the exact per-version method descriptors/argument positions and owner/creation-path evidence required by Tasks 2–4.

- [ ] **Step 1: Locate the semantic sync method in every supported source tree.**

  Search each tree with `rg` for `SyncAddChatroomMember`, `ChatroomMembersLogic`, and `MicroMsg.ChatroomMembersLogic`. Record the class, method signature, return type, and all argument positions for room ID, owner, member payload, versions, and incremental flags.

- [ ] **Step 2: Trace the create-room and self-join paths.**

  Follow the callers and inspect the room-service/create code and contact-sync assembler. Confirm that the initial create/pull-up persistence bypasses the generic synchronization hook in each supported version. A later leave-and-rejoin is intentionally eligible and does not require historical provenance tracking.

- [ ] **Step 3: Record stable DexKit constraints without JVM reflection.**

  For each version, document the strings, parameter descriptors, and method-body structure that can be expressed through DexKit metadata. Do not use `.clazz`, `.method`, `.asClass`, or reflection-derived types inside resolver/matcher code.

- [ ] **Step 4: Decide whether one resolver covers all versions or explicit branches are required.**

  If a method is genuinely absent on a supported version, identify its source-confirmed equivalent before using a branch. Do not mark an expected resolver as `allowFailure` merely to make reports green.

- [ ] **Step 5: Finish the source verification record.**

  Capture the confirmed per-version method signatures, creation-path bypass evidence, and resolver constraints in the implementation task notes or code-review description. Do not add decompiled-source copies to the repository.

---

## Task 2: Add the lightweight chatroom state model and database helper

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/models/WeChatroomSyncState.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeDatabaseApi.kt`
- Test: `app/src/test/...` only for any extracted pure normalization function that has no Android/WeChat coupling

**Interfaces:**
- Produces `WeChatroomSyncState(roomId: String, memberIds: Set<String>, memberVersion: Int?)` (use the repository’s established nullable/version type if source inspection proves a different type).
- Produces `WeDatabaseApi.getChatroomSyncState(roomId: String): WeChatroomSyncState?`.

- [ ] **Step 1: Inspect existing SQL helper and model conventions.**

  Read `WeDatabaseApi.kt`, its `SqlStatements`, and existing group helpers. Match existing database readiness checks, cursor lifecycle, bound arguments, logging, package imports, and model visibility.

- [ ] **Step 2: Add the immutable state model.**

  Define only the fields required by the feature: room ID, normalized member-ID set, and nullable member version. Do not retain owner/provenance guesses, host model objects, contacts, display names, or full member records.

- [ ] **Step 3: Add raw chatroom-state SQL.**

  Query the `chatroom` row by `chatroomname`, selecting `memberlist` and the version column confirmed in Task 1. Return `null` for no row or unavailable database; preserve an empty member set when the row exists with an empty list.

- [ ] **Step 4: Normalize member IDs deterministically.**

  Split the semicolon-delimited member list, trim entries, drop blank entries, and return a set. If a pure helper is extracted, sort the IDs only when constructing a hash/dedup key; do not change the semantic set comparison.

- [ ] **Step 5: Add focused tests only for pure normalization/state logic if eligible.**

  Cover: empty string -> empty set; repeated IDs -> one ID; whitespace/blank entries are ignored; sorted hashing is stable regardless of source order. Do not instantiate WeChat database or host classes in tests.

- [ ] **Step 6: Run the focused test or compile check.**

  Run the repository’s applicable Gradle test task for the exact test class if one was added. Expected: PASS. If no eligible test exists, run the smallest available Kotlin/Android compilation check and record why no unit test was added.

- [ ] **Step 7: Commit the state API.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/api/core/models/WeChatroomSyncState.kt app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeDatabaseApi.kt app/src/test
  git commit -m "feat: expose lightweight chatroom sync state"
  ```

---

## Task 3: Implement pure join decision and bounded deduplication

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroupLogic.kt`
- Create: focused pure test file under `app/src/test/...`

**Interfaces:**
- Produces a decision function with this exact signature, used unchanged by Task 4:
  `shouldMuteJoinedGroup(oldState: WeChatroomSyncState?, newState: WeChatroomSyncState, selfWxId: String): Boolean`.
- Produces this exact deterministic key function, used unchanged by Task 4:
  `dedupKey(state: WeChatroomSyncState): String`.

- [ ] **Step 1: Write failing pure tests for accepted transitions.**

  Test these exact cases:
  - no old row + new complete member set contains self -> `true`; this is a first-time invitation/QR join;
  - old empty member set + new complete member set contains self -> `true`;
  - old member set excludes self + new set contains self -> `true`;
  - old member set already contains self -> `false`;
  - another member is added but self remains present/absent -> `false`;
  - new state does not contain self -> `false`;
  - do not encode the initial self-created/pulled-up operation in this pure predicate: it bypasses the generic hook through direct create-response persistence;
  - a later self-created-group rejoin with the same persisted transition -> `true`;
  - unsupported room suffix -> `false` if suffix validation belongs in the pure predicate.

- [ ] **Step 2: Run the tests and verify they fail for the expected missing decision function.**

  Run the exact test class with the project’s applicable test command. Expected: compilation failure or test failure because the new decision function does not yet exist.

- [ ] **Step 3: Implement the minimal pure predicate.**

  Implement exactly:

  ```kotlin
  fun shouldMuteJoinedGroup(
      oldState: WeChatroomSyncState?,
      newState: WeChatroomSyncState,
      selfWxId: String,
  ): Boolean
  ```

  Treat a missing old row as self absent: when the new member set is complete and contains self, it is a first-time invitation/QR join. An existing empty old member set follows the same self-absent rule after a complete post-sync state. The initial self-created/pulled-up operation remains excluded because direct create-response persistence bypasses this predicate. Fail closed only when the new state is unavailable, empty, or does not contain self; keep ordinary existing-room updates from triggering unless they establish the exact self transition.

- [ ] **Step 4: Implement deterministic deduplication key generation.**

  Implement exactly:

  ```kotlin
  fun dedupKey(state: WeChatroomSyncState): String
  ```

  Use `(roomId, memberVersion)` when version is present. Otherwise use `(roomId, SHA-256 or repository-standard stable hash of sorted normalized member IDs)`. Do not include an unstable collection iteration order.

- [ ] **Step 5: Run the tests and verify they pass.**

  Run the exact test class. Expected: all transition and key-stability tests PASS.

- [ ] **Step 6: Commit the pure decision unit.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroupLogic.kt app/src/test
  git commit -m "feat: identify newly joined external groups"
  ```

  If the repository testing strategy rejects this test because the implementation remains coupled to WeChat/Android, remove the test and keep the predicate inline with a documented reason; do not add a fake host test.

---

## Task 4: Add the DexKit hook feature

**Files:**
- Create: `app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroup.kt`
- Modify: the new resolver file only; do not duplicate `WeConversationApi` resolvers

**Interfaces:**
- Consumes `WeDatabaseApi.getChatroomSyncState(roomId)`, `WeApi.selfWxId`, the Task 3 decision/key functions, `WeConversationApi.isDnd`, and `WeConversationApi.setDnd`.
- Produces an enabled `SwitchFeature` registered as `加入群聊自动免打扰` in `联系人与群组`.

- [ ] **Step 1: Add the feature declaration and lifecycle-owned state.**

  Declare the feature with `@Feature`, extend `SwitchFeature`, create a feature-owned `CoroutineScope(Dispatchers.IO + SupervisorJob())`, and create synchronized bounded maps for invocation snapshots and dedup keys. Ensure `onDisable()` cancels the scope and clears all state.

- [ ] **Step 2: Add and validate the semantic resolver.**

  Declare the `ChatroomMembersLogic.N` resolver using the stable log anchor and Task 1’s version-safe structural constraints. Keep resolver code desktop-safe: use only `delegate.data`/DexKit metadata for matcher construction, and do not use host reflection.

- [ ] **Step 3: Implement the before-hook snapshot.**

  Extract the verified room ID from the arguments, reject unsupported suffixes, and read the old state before the original method. Associate the snapshot with the current hook invocation using the hook parameter identity/lifecycle confirmed from the project hook API; never use one global mutable snapshot.

- [ ] **Step 4: Implement the after-hook transition.**

  Retrieve and remove the matching old snapshot, read the post-sync state, use the Task 3 predicate, and return without action only for unavailable/empty post-sync state, missing self identity, existing self membership, or unsupported room IDs. Missing or empty old state must reach the predicate.

- [ ] **Step 5: Implement one-shot asynchronous DND submission.**

  Generate the dedup key, atomically suppress an already pending/seen key, then dispatch to the feature-owned IO scope. In the job, call `WeConversationApi.isDnd(roomId)`; if already true, log and stop; otherwise call `WeConversationApi.setDnd(roomId, true)` once. Catch/log only expected failure around this asynchronous operation, never retry, and include room ID plus dedup/version context in logs.

- [ ] **Step 6: Handle post-sync timing without introducing retries.**

  Verify the original method writes the state before returning for each supported version. If a version defers persistence, use one bounded post-hook scheduling step supported by existing project utilities and document it as synchronization completion handling, not a retry loop. If state remains unavailable, log and stop.

- [ ] **Step 7: Compile the feature.**

  Run the smallest applicable compile/build check after the feature code is in place. Expected: Kotlin compilation succeeds and the feature scanner sees exactly one new `@Feature` declaration.

- [ ] **Step 8: Commit the feature hook.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroup.kt
  git commit -m "feat: auto mute groups joined by the user"
  ```

---

## Task 5: Validate supported Dex resolutions

**Files:**
- Modify: none unless a resolver constraint is corrected
- Reports: `dex-test-results/<run-id>/`

**Interfaces:**
- Consumes the committed resolver from Task 4.
- Produces per-APK reports and aggregate summary with no unexpected, blocked, or incomplete results.

- [ ] **Step 1: Run affected supported-version desktop tests.**

  Run `./x dex-test` with each supported APK/version metadata supplied separately, including separate normal and Google Play workers where APKs are available.

- [ ] **Step 2: Inspect all reports.**

  Confirm initialization, native library, worker, resolver, report, unexpected, blocked, and incomplete failures are visible and absent where success is claimed. Confirm any expected failure is source-justified and explicitly marked.

- [ ] **Step 3: Correct only evidence-based resolver issues.**

  If a resolver fails, use the decompiled source and DexKit metadata to fix structural constraints. Do not loosen signatures or replace a required resolver with `allowFailure`.

- [ ] **Step 4: Rerun only the affected APKs after resolver edits.**

  Verify all affected versions pass before proceeding. Record that the resolver source changed the method hash and requires one device-side re-resolution.

- [ ] **Step 5: Commit any resolver correction separately.**

  ```bash
  git add app/src/main/java/dev/ujhhgtg/wekit/features/items/contacts/AutoDndAfterJoinGroup.kt
  git commit -m "fix: resolve group member synchronization across WeChat versions"
  ```

---

## Task 6: Build and run final checks

**Files:**
- Modify: none unless a verified build issue requires a focused fix

- [ ] **Step 1: Build through xtask, not Gradle directly.**

  ```bash
  ./x build
  ```

  Expected: native library compilation and APK packaging complete successfully; do not use direct Gradle assembly because it can package a stale native library.

- [ ] **Step 2: Check the final diff.**

  ```bash
  git diff --check master...HEAD
  git status --short
  ```

  Expected: no whitespace errors and only intended feature/spec files or committed changes remain.

- [ ] **Step 3: Confirm generated feature discovery.**

  Inspect the build output or generated feature list to verify the new annotation is included once under `联系人与群组` and no generated source was manually edited.

- [ ] **Step 4: Document manual device validation.**

  Execute the spec’s scenarios on real WeChat: external invitation/QR join, initial self-created-group bypass, leave-and-rejoin of a self-created group, empty-then-complete member sync, other-member changes, both room suffixes, disabled state, restart/replay, already-muted state, and one failed DND submission. Record outcomes and logs; desktop tests do not replace this validation.

- [ ] **Step 5: Commit only after verification evidence is recorded.**

  ```bash
  git status --short
  git log -3 --oneline
  ```

  Do not claim completion unless `./x dex-test` for affected versions, `./x build`, and `git diff --check` have all produced successful evidence.

---

## Spec Coverage Self-Review

- Trigger semantics and database-insert rejection: Tasks 1, 2, and 4.
- Self-join versus self-created exclusion: Tasks 1, 3, and 4.
- Full/empty/incremental member synchronization: Tasks 1, 2, and 4.
- Native group DND reuse and no retry: Task 4.
- In-process deduplication and lifecycle cleanup: Tasks 3 and 4.
- Cross-version resolver validation: Tasks 1 and 5.
- No resolver-time reflection: Tasks 1 and 4.
- DexKit, build, diff, and manual validation: Tasks 5 and 6.
- No new preferences, persistent history, whitelist/blacklist, or database listener trigger: Global Constraints and Tasks 2–4.

The plan contains no unresolved `TBD`/`TODO` implementation placeholders. All source paths, interfaces, decision cases, commands, and expected validation outcomes are specified above.
