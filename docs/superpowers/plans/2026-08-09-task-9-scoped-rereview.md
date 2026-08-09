# Task 9 Scoped Re-review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four remaining Task 9 lifecycle review findings without starting Task 10.

**Architecture:** Extend the existing synchronized native lease with a monotonically increasing network epoch and guarded verification tickets, so network callbacks synchronously invalidate health results before asynchronous native teardown. Preserve every STOP caller at the controller boundary, coalesce the resulting origin shutdown in `ReadReceipts`, canonicalize token-mode hostnames before transaction comparison, and order START supersession before generation allocation.

**Tech Stack:** Kotlin/JVM, Android Service/Binder, coroutines, JUnit 5, OkHttp `HttpUrl`.

## Global Constraints

- Keep the existing native lease, cross-generation network stop, secret cleanup, rollback, notification, signal-boundary, and no-backup behavior intact.
- Keep `ReadReceiptsTunnelController.stop` and `stopBuiltInStack` callback APIs source-compatible.
- Do not start Task 10.
- Run Android tests through the existing Gradle test task and production packaging through `./x`.

---

### Task 1: Network verification epoch

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordination.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordinationTest.kt`

**Interfaces:**
- Produces: `TunnelVerificationTicket`, `TunnelNativeLease.activateRequest`, `invalidateNetwork`, `captureVerification`, and `runIfVerificationCurrent`.
- Consumes: the service request generation and native owner already managed by `TunnelNativeLease`.

- [x] Write blocked-health and no-health-needed tests that capture a verification ticket, invalidate the network epoch, then assert guarded credential write, pending-token clear, and CONNECTED publish counts all remain zero.
- [x] Run the focused JUnit class and confirm both tests fail because epoch APIs do not exist.
- [x] Add a monotonic epoch and active-request generation to the synchronized lease; every verification guard must compare epoch, active request, current generation, and native owner.
- [x] Make every default-network available/lost callback invalidate the epoch synchronously before queuing reconnect/native-stop work.
- [x] Capture a ticket before deciding whether public health is needed, associate cached health with its ticket epoch, and commit credential write, pending-token clear, and CONNECTED publication under one synchronized guard with a recheck before each boundary.
- [x] Run the focused JUnit class and confirm it passes.

### Task 2: STOP caller completion and origin coalescing

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordination.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordinationTest.kt`

**Interfaces:**
- Produces: all non-null callbacks retained by `TunnelStopCompletion`; `CoalescedResultCallbacks<Unit>` for the higher-layer origin stop.
- Consumes: unchanged `ReadReceiptsTunnelController.stop(onStopped)` and `stopOrigin(onFinished)` APIs.

- [x] Replace the incorrect STOP test with 16 concurrent registrations followed by 16 terminal attempts; assert one STOP send/generation allocation, every caller once, and duplicate terminals with no callbacks.
- [x] Add a higher-layer coalescing test asserting 16 callers cause one origin side effect and each receives the same result once.
- [x] Run the focused JUnit class and confirm callback-count tests fail.
- [x] Retain every non-null STOP callback and add synchronized result-callback aggregation for `stopBuiltInStack`.
- [x] Run the focused JUnit class and confirm it passes.

### Task 3: Canonical hostname transaction identity

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordinationTest.kt`

**Interfaces:**
- Produces: `canonicalPublicRoot` and canonical runtime hostname comparison.
- Consumes: `normalizePublicRoot` and the existing configuration transaction.

- [x] Add a behavior test asserting an uppercase/trailing-slash hostname and its lowercase/rootless form have the same runtime identity and require no replacement.
- [x] Run the focused JUnit class and confirm the test fails because canonical comparison is absent.
- [x] Canonicalize before constructing the connection candidate, use the same helper in Confirm, pass/save only canonical token-mode hostnames after ACK, and compare canonical identities during replacement detection.
- [x] Run the focused JUnit class and confirm it passes.

### Task 4: Reentrant START allocation order and verification

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelController.kt`
- Modify: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordinationTest.kt`
- Modify: `.superpowers/sdd/2026-08-06-read-receipts-native-cloudflare/task-9-report.md`

**Interfaces:**
- Preserves: `startVisible` public behavior and nonce/generation ACK matching.
- Enforces: previous pending request is cleared and completed before the replacement calls `nextGeneration()`.

- [x] Rewrite the handoff regression to model a synchronous superseded completion that allocates a generation, then assert the outer replacement allocates a strictly newer generation and late old terminals cannot clear it.
- [x] Run the focused JUnit class and record RED against the current allocation-before-supersession ordering contract.
- [x] Reorder `startVisible`: fail/isolate old pending handoff first, allocate the replacement generation second, then begin/store the new pending request.
- [x] Run focused tests, the full Android unit-test task, relevant native tests/builds, `./x build`, and `git diff --check`.
- [x] Update the Task 9 report with root causes, RED/GREEN evidence, gates, and remaining device checks; commit one scoped fix commit.

### External final-review fix round 5

- [x] Replace generation-bound queued network teardown with an immutable native-session ticket, while
  publishing `RECONNECTING` against the request generation that owns that session at teardown time.
- [x] Prove administrative generation transfer cannot shield an invalidated session, capture remains
  unavailable until a fresh native start, and an old ticket cannot stop that replacement session.
- [x] Upgrade a pending STOP past an intervening administrative generation when another stop caller
  arrives, preserving all callbacks and rejecting late older terminal/timeout paths.
- [x] Reject START synchronously while STOP is pending without allocating a generation, starting the
  foreground service, sending a command, or consuming the caller's token.
- [x] Run the 32-test focused suite, full Android unit tests, Go race tests, Rust workspace tests,
  double-ABI connector/native builds, APK entry/export inspection, and `git diff --check`.
- [ ] Obtain the external final re-review verdict; do not begin Task 10 before acceptance.

#### External re-review corrections

- [x] Remove credential-delete generation allocation/transfer; send and execute only at the current
  authoritative generation.
- [x] Reject credential delete while START or STOP is pending so it cannot replace either queued
  command, and prove STOP G remains authoritative with exactly-once terminal/timeout completion.
- [x] Linearize invalidated native stop and `RECONNECTING` publication under the native lease, with
  no reverse Controller/UI/native-lease acquisition from the publication callback.
- [x] Add deterministic teardown-versus-same-generation-delete ordering coverage and prove stale
  verification cannot restore CONNECTED/publicUrl after native stop.
- [x] Read owner-active/verifiable state only inside the lease and sanitize a Quick-delete status to
  RECONNECTING/null immediately after invalidation, including before queued teardown runs.
- [x] Re-run 34 focused tests and every Android, Go race, Rust, dual-ABI, APK, symbol, and diff gate.
- [ ] Obtain a passing external final re-review verdict.
