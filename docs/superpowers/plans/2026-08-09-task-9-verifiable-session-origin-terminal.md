# Task 9 Verifiable Session and Origin Terminal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent post-network-invalidation verification capture and guarantee one typed terminal for every transaction-owned origin request.

**Architecture:** `TunnelNativeLease` gains an explicit verifiable native-session marker that network invalidation and stop clear synchronously and only native start sets. Origin execution returns `Completed(Result<T>)` or `Superseded` once, and exhaustive `when` expressions propagate that terminal through the complete stack/UI transaction chain without stale rollback side effects.

**Tech Stack:** Kotlin/JVM, Android Service/Binder, Kotlin coroutines, JUnit 5.

## Global Constraints

- Do not begin Task 10.
- `Superseded` contains and logs no token or request data.
- Every transaction-owned origin request delivers exactly one terminal on Main.
- `Superseded` releases ownership but performs no save, rollback, start, stop, or token clear.
- Preserve all previously closed Task 9 lifecycle, ACK, notification, signal, backup, and canonical-hostname behavior.

---

### Task 1: Verifiable native-session marker

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordination.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelService.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordinationTest.kt`

**Interfaces:**
- `activateRequest(generation: Long, preserveNativeSession: Boolean = false): Boolean`
- `invalidateNetwork(): Long?`
- `captureVerification(generation: Long): TunnelVerificationTicket?`
- `commitVerification(...): TunnelVerificationCommit`

- [x] Add a test that starts generation 30, captures a ticket, invalidates the network, and asserts immediate capture is null plus the old ticket commit is `STALE` with zero side effects.
- [x] Extend it through owner stop and assert capture stays null, then start a new native session and assert capture/commit succeeds.
- [x] Table-drive available, lost, and replacement event labels through `invalidateNetwork` and assert identical unavailable behavior.
- [x] Add administrative-preservation tests: preserving activation retains an already-valid session, but after invalidation it cannot make capture non-null.
- [x] Run the focused test class and record RED because capture currently binds a new epoch ticket to the old owner.
- [x] Add `verifiableNativeSessionEpoch`; clear it on default activation, invalidation, and native stop; set it only after successful native start; require it in capture/commit matching.
- [x] Use preservation only in the quick-mode credential-delete generation transfer.
- [x] Run the focused test class and record GREEN.

### Task 2: Typed origin execution terminal

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordination.kt`
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordinationTest.kt`

**Interfaces:**
- `sealed interface OriginRequestTerminal<out T>` with `Completed(Result<T>)` and `Superseded`.
- `OriginTerminalDelivery<T>.deliver(terminal): Boolean` for exactly-once owner delivery.
- `submitOriginRequest(request, onTerminal)` computes one terminal and delivers it once on Main.

- [x] Add a parameterized checkpoint test for pre-queue, pre-reconcile, post-reconcile, pre-snapshot, pre-publish, and pre-Main-delivery staleness; old start and stop owners each observe exactly one `Superseded`.
- [x] Assert old transaction rollback/save/start/stop counters stay zero and a new request's `Completed(Result.success)` is unaffected.
- [x] Add a delivery-race test proving repeated Completed/Superseded attempts invoke the owner only once.
- [x] Run the focused test class and record RED because the sealed terminal/delivery types do not exist.
- [x] Refactor origin execution to return a terminal at every stale/current exit and perform one final Main generation check before one delivery.
- [x] Run the focused test class and record GREEN.

### Task 3: Exhaustive real-call-chain propagation

**Files:**
- Modify: `app/src/main/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceipts.kt`
- Test: `app/src/test/java/dev/ujhhgtg/wekit/features/items/chat/ReadReceiptsTunnelCoordinationTest.kt`

**Interfaces:**
- `startOrigin`, `stopOrigin`, `startBuiltInStack`, `stopBuiltInStack`, and `applyAndStartBuiltInStack` use `OriginRequestTerminal` callbacks.
- Production call sites use exhaustive `when` with distinct Completed and Superseded branches.

- [x] Add transaction handling tests: Completed success performs current success effects; Completed failure performs current failure effects; Superseded releases ownership once with all rollback/save/start/stop/token-clear counters zero.
- [x] Add notification-rejection restore coverage showing a superseded stop does not save/restart but still completes the old UI transaction once.
- [x] Run focused tests and record RED against generic Result-based handling.
- [x] Propagate the sealed terminal through every start/stop/stack/apply/UI call site with exhaustive `when` branches.
- [x] Ensure UI always clears `connectionTransactionActive`, clears token only on current Completed success, and treats Superseded as a generic replacement without request data.
- [x] Grep every production call to confirm no transaction owner drops `Superseded` and no stale branch enters restore.
- [x] Run focused and full Android tests.

### Task 4: Review, verification, report, and commit

**Files:**
- Modify: `.superpowers/sdd/2026-08-06-read-receipts-native-cloudflare/task-9-report.md`

- [x] Dispatch an internal read-only reviewer to trace the real production call chain and specifically inspect the lease lock boundary, exactly-once Main terminal delivery, exhaustive sealed handling, and token secrecy.
- [x] Fix every Critical/Important finding with another focused RED/GREEN cycle.
- [x] Run `./gradlew testStandardDebugUnitTest`, `go test -race -count=1 ./app/src/main/go/wekit-cloudflared`, `cargo test --workspace`, and `./x build`.
- [x] Inspect both APKs and both ABIs, move generated `jniLibs` out of the worktree, and run `git diff --check`.
- [x] Update the Task 9 report with roots, terminal semantics, RED/GREEN evidence, review verdict, gates, and remaining device tests.
- [x] Commit the scoped implementation and confirm a clean worktree.
