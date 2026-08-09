# Task 9 report: Android foreground tunnel service and Quick/token modes

## Outcome

Task 9 is implemented as a passing source milestone. The read-receipts built-in server can now be
paired with an embedded Cloudflare connector in Quick or remotely-managed token mode. Browser login
remains an explicit `NEEDS_USER_ACTION` path for Task 10.

The Android side uses an exported module-process `specialUse` foreground service and a narrow
Messenger protocol. The injected WeChat process starts the service only from a visible user action,
then sends the secret over Binder after per-message UID validation. Status remains service-owned and
is returned only to nonce-registered Binder listeners. Binder death, service rebind, monotonically
stamped configuration generations, and bounded reconnect/backoff prevent stale sessions from
publishing usable endpoints.

Startup is ordered origin health first, connector second. A connector URL is published only after an
exact public HTTPS `/health` response (`204` with an empty body), and is invalidated while reconnecting
or after a later public-health failure. Shutdown reverses the order: the controller waits for connector
teardown before stopping the loopback origin, with a bounded fallback when the module process is gone.

## Security and credential handling

- The service accepts commands only from WeKit's UID or a UID mapped to `com.tencent.mm`.
- Tokens never use service Intent extras, broadcasts, notifications, logs, saved Compose state, or the
  clipboard. The UI uses ordinary `remember`, clears the input after Binder handoff, and offers no
  token-copy action.
- A retained token is encrypted by a dedicated Android Keystore AES-256-GCM key with no per-use user
  authentication, then stored in a bounded version/IV/ciphertext `AtomicFile` under private app files.
- New token material is persisted only after the native connector is connected and the configured
  public route passes exact health verification. A bad replacement therefore leaves the last verified
  credential intact.
- Invalid key/ciphertext state deletes the unusable private ciphertext and reports only an actionable
  status. Both legacy backup and cloud/device-transfer extraction rules exclude the credential file.
- The notification stop action carries a process-random 192-bit nonce. An untrusted app that merely
  starts the exported no-secret service cannot stop an authorized session, and an unauthenticated
  service start self-terminates after ten seconds.
- Token parsing is strict and bounded before constructing official `connection.TunnelToken`
  credentials. Account tag, UUID, 32-byte secret, optional endpoint, trailing JSON, and encoded size
  are validated. Raw and decoded credential forms are redacted from native errors.

## Native boundary

The six Task 8 C ABI symbols remain unchanged:

- `wekit_tunnel_start_quick`
- `wekit_tunnel_start_token`
- `wekit_tunnel_begin_login`
- `wekit_tunnel_select_existing`
- `wekit_tunnel_stop`
- `wekit_tunnel_status`

`wekit_tunnel_start_token` now runs a real remotely-managed tunnel through the same owned
supervisor/observer path as Quick mode, including remote-configuration support. Four direct JNI
entry points back `ReadReceiptsTunnelNative`; its synchronized owner atomically clears a native handle
before freeing it and never polls a freed handle. Browser login and existing-tunnel selection remain
explicitly unsupported.

## TDD and automated verification

The Go work began with failing tests for missing strict token parsing and execution. A separate invalid
account-tag case initially failed because the parser accepted it, then passed after adding the bounded
account validation. The resulting focused suite covers malformed and oversized input, unknown/trailing
JSON, UUID/secret/account/endpoint validation, synthetic credential execution, cancellation, raw and
decoded credential redaction, repeated start/stop, and the still-unsupported Task 10 paths.

Commands and results:

```text
go test -race -count=1 ./app/src/main/go/wekit-cloudflared
ok dev.ujhhgtg.wekit/cloudflared-bridge 1.140s

./gradlew compileStandardDebugKotlin
BUILD SUCCESSFUL in 3s

cargo test --workspace
PASS: wekit-native 9; service library 15; pixel logging 1; zygisk 10; xtask 22

./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a
PASS

./x build
BUILD SUCCESSFUL in 21s (147 actionable tasks: 39 executed, 2 from cache, 106 up-to-date)

git diff --check
PASS
```

`file` identified both generated connector libraries as stripped Android 28 shared objects built by
NDK r30-beta1: AArch64 for `arm64-v8a` and ARM EABI5 for `armeabi-v7a`. `llvm-readelf -Ws` confirmed
all six exact C symbols and all four direct JNI symbols in both ABIs.

Both final debug APKs contain all four required ABI/library pairs:

```text
app/build/outputs/apk/legacy/debug/app-legacy-debug.apk
app/build/outputs/apk/standard/debug/app-standard-debug.apk

lib/arm64-v8a/libwekit_cloudflared.so
lib/arm64-v8a/libwekit_native.so
lib/armeabi-v7a/libwekit_cloudflared.so
lib/armeabi-v7a/libwekit_native.so
```

Generated `app/src/main/jniLibs` artifacts were not committed. The exact verified copies were moved to
`/tmp/wekit-task9-jni.pb63f1/jniLibs` after APK inspection.

The formal-review follow-up added a pure Kotlin concurrency test around the production generation
lease, handoff gate, and stop-completion drain. Android Service, Binder, Keystore, network,
notification, and Compose behavior remains a real-host manual gate. No Dex resolver changed, so no
supported-version DexKit rerun was required.

## Formal review follow-up

The first Task 9 review found six important and two minor defects. Their roots and fixes are:

1. `handleStart` created an intermediate cleanup job and then captured that job rather than the real
   predecessor. An old `runTunnel` could consequently call the process-global native stop after a new
   start. A single replacement job now captures each predecessor once, while `TunnelNativeLease`
   serializes every native transition and rejects cleanup that no longer owns the generation.
2. Network callbacks launched an unconditional native stop and treated non-null `activeNetwork` as
   proof that a published URL remained valid. Default-network loss/replacement now publishes
   `RECONNECTING` immediately and performs a generation/owner recheck inside the same native lease;
   even make-before-break switching forces a fresh public-health verification.
3. `startVisible` treated FGS dispatch/queueing as successful secret handoff, so UI state cleared too
   early and a never-bound pending message could retain/replay the token. START now has an authorized,
   nonce- and generation-bound service ACK. Rejection, supersession, Binder failure, and a bounded
   timeout clear the Bundle token and pending message; UI clears only after accepted ACK.
4. The connect button used an unsaved candidate independently of the persisted configuration and
   origin port. Runtime candidate replacement now stops the previous stack when necessary, reconciles
   the exact fixed port, waits for ACK before committing the candidate, rolls back/restarts the prior
   working configuration on immediate failure, and disables **确定** during the transaction.
5. Binder death, STOPPED status, and timeout drained a shared callback list on different threads.
   `TunnelStopCompletion` atomically matches one stop generation, retains every caller callback, and
   lets only the first terminal path drain them; duplicate terminals match without returning callbacks.
6. Android 13 notification permission was undeclared and a hidden FGS could connect without a visible
   stop action. `POST_NOTIFICATIONS` is declared; the module service rejects START when its own
   notifications are disabled and the WeChat-hosted UI opens WeKit's app-notification settings.
7. The pinned Supervisor's public API requires `cloudflared/signal.safe_signal`. The approved boundary
   was clarified to forbid OS/process signal handling: the required safe helper only performs a
   `sync.Once` channel close. Its construction is isolated, and a Go AST gate rejects `os/signal`,
   `Notify`, `NotifyContext`, or use outside that adapter.
8. A file-only backup exclusion did not cover `AtomicFile` sidecars. Credentials now live below
   `noBackupFilesDir`; legacy backup/cloud/device-transfer rules exclude the full `read_receipts/`
   directory so `.new` and `.bak` files are also covered.

Focused RED/GREEN evidence: the new Kotlin tests first failed to compile because the lease, completion,
and handoff state did not exist. They now deterministically delay an old cleanup until after a new
native lease starts, apply an old network event to a replacement lease, race sixteen STOP terminal
paths, and deliver late ACK/timeout events after a replacement. The Go boundary test was RED while
`upstream.go` imported the safe signal package directly, then GREEN after isolation.

Post-review verification:

```text
go test -race -count=1 ./app/src/main/go/wekit-cloudflared
ok dev.ujhhgtg.wekit/cloudflared-bridge 1.154s

./gradlew testStandardDebugUnitTest
BUILD SUCCESSFUL in 6s

cargo test --workspace
PASS: wekit-native 9; service library 15; pixel logging 1; zygisk 10; xtask 22

./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a
PASS

./x build
BUILD SUCCESSFUL in 7s (144 actionable tasks: 12 executed, 1 from cache, 131 up-to-date)

git diff --check
PASS
```

After the final build, `llvm-readelf -Ws` again found all six C ABI and four direct JNI symbols in
both connector libraries. Both Standard and Legacy APKs again contained both `libwekit_cloudflared.so`
and `libwekit_native.so` for arm64-v8a and armeabi-v7a. `aapt2 dump xmltree` confirmed the packaged
manifest contains `POST_NOTIFICATIONS`, both special-use FGS permissions, the exported service, its
special-use type, and subtype explanation. The final generated JNI inputs were moved out of the
worktree to `/tmp/wekit-task9-review-verified-jni.q6Gxm0/jniLibs` after APK/symbol inspection.

## Required manual device checklist

The following gates still require a device and, where noted, user-owned Cloudflare configuration:

1. On API 28 and a current target-37 device, open the WeChat-hosted feature UI and connect visibly.
   Confirm the low-importance ongoing `specialUse` notification appears promptly. Exercise a platform
   background-start rejection and confirm the UI reports `NEEDS_USER_ACTION` without claiming success.
   On Android 13+, disable WeKit notifications, confirm START is rejected without clearing the token,
   use the UI action to open WeKit's notification settings, enable notifications, and retry.
2. Start Quick mode. Confirm local `/health` succeeds before connector startup, the public URL is hidden
   until exact public `/health` verification, a generated tracking pixel forwards through the verified
   URL, and copy/share expose only that URL.
3. With a user-created remotely-managed Cloudflare Tunnel, configure its public-hostname route to the
   exact fixed `127.0.0.1:<port>` selected in WeKit. Confirm automatic-port mode is rejected, malformed
   tokens/hostnames are actionable, the real token connects, and the token is retained only after public
   health succeeds. Then submit a bad replacement and confirm the prior saved credential still works.
4. Disable and restore the default network. Confirm the verified URL disappears immediately, reconnect
   uses bounded backoff, and the URL returns only after a fresh public-health pass. Leave it connected
   long enough to observe periodic public-health revalidation. Also switch between two live default
   networks (make-before-break) and confirm the old verified URL is still invalidated.
5. Kill and restart the WeChat process while the module service survives, then kill the module service.
   Confirm status rebinds to the authoritative generation, old callbacks cannot overwrite it, and a
   lost service yields actionable state while the loopback origin is shut down.
6. Delete the saved token while token mode is active and while stopped. Confirm the active token tunnel
   stops, later unattended reconnect requests a new token, and no token appears in logs, notification,
   clipboard, saved UI state, backup, or transfer data.
7. Disconnect from the UI and separately use the notification stop action. Confirm connector teardown
   finishes before the loopback origin stops; also confirm the bounded fallback stops the origin if the
   module process/Binder disappears during teardown. Race Binder death with STOPPED/timeout and confirm
   the origin-stop callback executes once.
8. Replace a connected fixed-port candidate with another port and force an ACK rejection/timeout.
   Confirm the exact candidate port was reconciled, the old configuration/stack is restored on failure,
   a late ACK cannot clear a replacement token, and pressing **确定** after accepted ACK does not stop
   the just-started stack.

A live named-tunnel run cannot be automated or truthfully claimed without the user's Cloudflare token,
hostname, and dashboard route; no credentials were embedded in tests or source. Device-level FGS,
cross-process, network, and forwarding behavior likewise remains an explicit manual acceptance gate.

## Scoped re-review follow-up

The scoped re-review found three important defects and one minor ordering defect in the first formal
review fix. All four remain within Task 9:

1. Configuration generation alone did not invalidate an in-flight public-health result. A default
   network event could publish `RECONNECTING` and queue native teardown while the same-generation
   coroutine subsequently wrote the credential, cleared `pendingToken`, and republished `CONNECTED`.
   `TunnelNativeLease` now owns an independently monotonic network epoch, active-request generation,
   and native-session epoch. Every default-network `onAvailable`/`onLost` synchronously increments the
   epoch before asynchronous reconnect work. A verification ticket captures all four identities, and
   one synchronized commit rechecks the ticket immediately before credential write, pending-token
   clear, and CONNECTED publication. The same monitor excludes a network invalidation from
   interleaving those three steps. Cached public health is also tagged with the network epoch, so the
   no-health-needed branch cannot reuse an earlier network's verification.
2. `TunnelStopCompletion.register` retained only the first non-null callback. It now collects every
   callback for the pending STOP generation; the first terminal drains all of them exactly once and
   repeated terminals return no callbacks. Because those controller callbacks represent multiple
   callers of the same stack shutdown, `ReadReceipts.stopBuiltInStack` uses a second synchronized
   completion group: one tunnel STOP leads to one origin shutdown, whose single `Result<Unit>` is
   delivered once to every caller. If a newer origin generation supersedes that shutdown, stale
   success handling remains suppressed while a dedicated Main-thread terminal path releases the
   completion group and delivers `Result.failure("内置服务器停止请求已被新请求取代")` once to every
   caller; later STOP requests can therefore start a fresh shutdown.
3. The connection button validated a token-mode hostname but constructed its transaction candidate
   from the original text, while **确定** canonicalized it. Uppercase/trailing-slash spelling could
   therefore look like a runtime change and tear down the new stack. Both actions now call the same
   `canonicalPublicRoot`; the connection candidate is canonical before transaction construction,
   ACK persistence saves only that canonical value, and `TunnelRuntimeIdentity` compares canonical
   mode/hostname identities for replacement decisions. A legacy valid spelling such as
   `HTTPS://RECEIPTS.EXAMPLE.COM/` compares equal to `https://receipts.example.com`.
4. `startVisible` allocated its replacement generation before synchronously failing the old pending
   handoff. The old completion could start rollback/STOP and allocate a higher generation, making the
   replacement START stale before send. A completion could also call `startVisible` recursively and
   have its pending callback silently overwritten by the outer request. `TunnelHandoffGate`
   now repeatedly drains every pending handoff created during synchronous completion, then calls the
   generation factory and installs the outer replacement. In the regression, generation 100 creates
   nested generation 101, both callbacks terminate once, and the outer generation 102 is the final
   winner. Old ACK/timeout events still cannot clear generation 102.

Focused RED/GREEN evidence:

- The network tests were RED with unresolved epoch/ticket APIs. They now block health, invalidate the
  network, release a successful result, and observe `STALE` with zero credential writes, zero pending
  clears, and zero CONNECTED publications. A separate cached/no-health-needed test observes the same
  zero-side-effect result; a current ticket commits all three effects once.
- After adding the higher-layer coalescer scaffold, the corrected 16-caller STOP race was RED with
  `expected: <16> but was: <1>`. It now races 16 registrations and 16 terminal paths, observing one
  STOP send/generation allocation, every caller callback exactly once, and no callbacks from duplicate
  terminals. The origin coalescing test separately observes one origin side effect and 16 results.
- Canonical identity and reentrant START tests were RED with unresolved production APIs. They now
  verify that both runtime-change consumers share uppercase/trailing-slash equivalence, and that an
  old generation-100 completion can create nested generation 101, which is completed before the
  outer replacement installs generation 102; late old ACK/timeout cannot clear generation 102.
- An independent read-only diff review then found one remaining raw Confirm comparison, a permanently
  occupied higher-layer callback group when origin stop was superseded, and the nested-START overwrite
  case. The shared predicate, dedicated superseded terminal path, and drain-all handoff gate above
  closed those paths; their focused regressions are included in the final 11-test suite.

Scoped re-review verification:

```text
./gradlew testStandardDebugUnitTest \
  --tests dev.ujhhgtg.wekit.features.items.chat.ReadReceiptsTunnelCoordinationTest
BUILD SUCCESSFUL; 11 focused tests

./gradlew testStandardDebugUnitTest
BUILD SUCCESSFUL in 7s (64 actionable tasks: 8 executed, 1 from cache, 55 up-to-date)

go test -race -count=1 ./app/src/main/go/wekit-cloudflared
ok dev.ujhhgtg.wekit/cloudflared-bridge 1.157s

cargo test --workspace
PASS: wekit-native 9; service library 15; pixel logging 1; zygisk 10; xtask 22

./x build
BUILD SUCCESSFUL in 14s (144 actionable tasks: 15 executed, 1 from cache, 128 up-to-date)
```

Both final debug APKs again contain `libwekit_cloudflared.so` and `libwekit_native.so` for arm64-v8a
and armeabi-v7a. Symbol inspection found the exact six C ABI and four direct JNI exports in each
connector library. Generated JNI inputs were moved out of the worktree to
`/tmp/wekit-task9-rereview-final-jni.3AVv3V/jniLibs` after inspection. A second independent
read-only review reported no Critical, Important, or Minor findings and a Ready verdict.

The existing device checklist remains required. In particular, exercise a network event while public
health is in flight and shortly after cached verification, issue multiple concurrent disconnect
requests and confirm each caller completes while origin shutdown occurs once, reconnect with an
uppercase/trailing-slash spelling without teardown on **确定**, and force a superseded START completion
to allocate rollback work before the replacement command.

## Final real-call-chain review follow-up

A later review of the complete callback chain found three additional ownership races after
`abc2105f`:

1. The higher-layer stack-stop coalescer copied every owner and broadcast the same `Completed`
   terminal. It now drains owners newest-first, gives the original terminal only to the newest owner
   when the captured origin generation is still current, and gives every older owner a fieldless
   `Superseded`. Each owner still completes once, while the underlying tunnel and origin stop execute
   once. This remains true when the newest callback does not create another origin generation; if it
   does reenter and start a replacement, the generation check also prevents any remaining stale work.
2. `ReadReceiptsTunnelController.stop()` previously superseded only the pending START visible on entry.
   Its synchronous completion could install a replacement START before STOP generation allocation.
   STOP now repeatedly drains every callback-created pending START, then allocates and publishes the
   final STOP generation, so callback reentry cannot survive and be stopped by an older command.
3. Compose represented connection ownership with one Boolean. A stale completion could clear it while
   a newer transaction still owned the UI and could run stale success effects. A monotonic owner ID now
   atomically releases only the current owner; a stale owner's terminal is treated as `Superseded`, so
   it cannot clear the token. Both **验证并连接** and **确定** use the same active-owner gate.

Focused TDD evidence:

- The supplied three regressions were initially RED at compilation because the current-aware
  coalescer, connection ownership, and STOP drain APIs did not exist.
- A separate no-reentry regression was RED with one assertion failure because an older coalesced
  owner still received `Completed` while the generation stayed unchanged.
- After the production call sites were connected, the focused class passed 29 tests with zero
  failures or errors.

Fresh verification after the fixes:

```text
./gradlew testStandardDebugUnitTest \
  --tests dev.ujhhgtg.wekit.features.items.chat.ReadReceiptsTunnelCoordinationTest
BUILD SUCCESSFUL; 29 focused tests

./gradlew testStandardDebugUnitTest
BUILD SUCCESSFUL in 9s

go test -race -count=1 ./app/src/main/go/wekit-cloudflared
ok dev.ujhhgtg.wekit/cloudflared-bridge 1.155s

cargo test --workspace
PASS: wekit-native 9; service library 15; pixel logging 1; zygisk 10; xtask 22

./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a
PASS

./x build
BUILD SUCCESSFUL in 8s (144 actionable tasks: 18 executed, 1 from cache, 125 up-to-date)
```

Both Standard and Legacy APKs contain `libwekit_cloudflared.so` and `libwekit_native.so` for
arm64-v8a and armeabi-v7a. Each connector library exports the exact six C ABI and four direct JNI
symbols. Generated JNI inputs were moved out of the worktree to
`/tmp/wekit-task9-finalfix-final-jni.0qUyLW/jniLibs` after inspection. `git diff --check` passed.

The existing real-device checklist remains mandatory. The final callback-ownership cases to exercise
are a coalesced disconnect followed by immediate reconnect, a STOP whose superseded callback starts a
replacement, and an old connection completion arriving while a newer connection transaction owns the
dialog. External scoped review of this follow-up is still pending; this report does not claim Task 9
acceptance by itself.

## Fix round 5 external final-review follow-up

The external final review found two more Task 9 ownership gaps in `97d36487`:

1. Network teardown was queued with a configuration generation. If Quick credential deletion
   transferred the live native owner from generation G to H before that coroutine ran, the G check
   suppressed teardown and left the invalidated native session running. Network invalidation now
   returns an immutable ticket containing the native-session epoch. Queued teardown matches that
   session epoch, stops whichever request generation currently owns the same session, and returns
   that current generation for `RECONNECTING` publication. A fresh replacement native start advances
   the epoch, so an old ticket cannot stop it. `onAvailable` and `onLost` both enter this same path.
2. A coalesced STOP retained its original generation even after another administrative command
   allocated a newer one. A second stop caller now upgrades the pending STOP to a generation strictly
   newer than the latest issued command while retaining every callback. Old STOPPED and timeout paths
   cannot drain the upgraded transaction. A START attempted while STOP remains pending is instead
   rejected synchronously with `Completed(failure)` before FGS startup, generation allocation,
   command construction, or token consumption; the original STOP remains authoritative and can
   complete normally.

Focused TDD evidence:

- The native-session-ticket and STOP-upgrade tests first failed to compile with unresolved
  `stopInvalidatedSession` and `latestIssuedGeneration`. After those production APIs were connected,
  a timeout-authority regression first failed to compile with unresolved `completeTimeout`.
- The no-second-stop START case then failed to compile with unresolved `hasPendingStop`. The final
  production guard and split regression suite pass 32 focused tests with zero failures or errors.
- The native regression starts session S/G, invalidates it, transfers the request to H, then proves
  queued teardown stops S/H, verification stays unavailable until fresh S2/H, and the old ticket
  cannot stop S2. The STOP regression proves G -> administrative H -> second STOP J, with late G/H
  terminal paths ignored and the first J terminal completing both callers exactly once.

Fresh verification after round 5:

```text
./gradlew :app:testStandardDebugUnitTest \
  --tests dev.ujhhgtg.wekit.features.items.chat.ReadReceiptsTunnelCoordinationTest
BUILD SUCCESSFUL; 32 focused tests

./gradlew testStandardDebugUnitTest
BUILD SUCCESSFUL in 6s

go test -race -count=1 ./app/src/main/go/wekit-cloudflared
ok dev.ujhhgtg.wekit/cloudflared-bridge 1.147s

cargo test --workspace
PASS: wekit-native 9; service library 15; pixel logging 1; zygisk 10; xtask 22

./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a
PASS

./x build
BUILD SUCCESSFUL in 14s (147 actionable tasks: 27 executed, 1 from cache, 119 up-to-date)

git diff --check
PASS
```

Each ABI's connector library exports exactly the six Task 8 C symbols and four direct tunnel JNI
symbols. Both Standard and Legacy APKs contain both `libwekit_cloudflared.so` and
`libwekit_native.so` for arm64-v8a and armeabi-v7a. The exact generated JNI inputs were moved to
`/tmp/wekit-task9-fix5-final-jni.1l0wbN/jniLibs` after inspection.

No Dex declaration or resolution logic changed, so a supported-version DexKit rerun is not required.
The existing device checklist remains mandatory, with two added race cases: trigger network
invalidation immediately before Quick credential deletion and confirm the transferred session
reconnects only after a fresh native start/public-health pass; then issue disconnect, an intervening
credential-delete command, and disconnect again, confirming the origin stops once only after the
upgraded STOP terminal. External final re-review remains pending, so this report still does not claim
Task 9 acceptance by itself.

### Round 5 external re-review corrections

The external re-review found that the first round-5 fix still allowed credential deletion to create
an intervening administrative generation and left native stop/status publication as two separately
observable operations. The earlier G -> administrative H scenario in this section is therefore
superseded by the following narrower production contract:

1. Credential deletion allocates no generation. The controller sends the current authoritative
   generation and atomically refuses the administrative command while either START or STOP is
   pending, so the single command slot cannot replace a secret START or the authoritative STOP.
   A STOP G followed by a delete attempt has no H allocation and no DELETE send; G's first terminal
   or authoritative timeout drains its callbacks exactly once.
2. The service accepts deletion only at the lease's current generation. Quick deletion clears the
   credential without transferring the request or native owner. In the same lease boundary it reads
   owner-active and verifiable-session state: only both true may preserve CONNECTED; an invalidated,
   stopped, or inactive session is synchronously published as `RECONNECTING` with no public URL even
   when administrative deletion runs before queued native teardown.
   Token deletion clears the credential and enters `stopTunnel(G)`; equal-generation `advance` does
   not transfer ownership, while `clearRequest(G)` invalidates verification before asynchronous
   teardown. A stale process/rebind command cannot advance the service generation and is a no-op.
3. `stopInvalidatedSession` performs native stop and the matching generation's `RECONNECTING` status
   commit inside one lease monitor. The status callback does not acquire the controller/UI or native
   lease: it updates service state/notification and queues Messenger status only. The callback runs
   in `finally`, so a native-stop error still invalidates the published URL before the monitor is
   released. Verification CONNECTED commits and same-generation administrative state republishes use
   the same lease boundary, preventing either from entering between stop and `RECONNECTING`.

Focused RED/GREEN evidence:

- The two new regressions were RED at compilation with unresolved `withCurrentGeneration`,
  `publishReconnecting`, and `runAdministrativeCommandIfIdle` APIs.
- A follow-up synchronous-invalidation regression was RED because `withCurrentGeneration` did not
  expose its locked session state and `forAdministrativePublish` did not exist. It now proves
  invalidation -> same-G Quick delete -> queued teardown publishes only RECONNECTING/null, never the
  stale CONNECTED URL.
- The final 34-test focused suite is GREEN. Its deterministic interleaving holds native teardown in
  the lease, proves the same-generation administrative command cannot finish, then observes the exact
  order `native-stop`, `reconnecting`, `credential-delete`. The old verification ticket remains
  `STALE`, and the authoritative status remains `RECONNECTING` rather than reverting to CONNECTED.
- Separate coverage proves G -> delete attempt allocates/sends nothing, then the first G timeout
  completes its callback once and a duplicate G terminal returns no callback. A pending START also
  rejects deletion.

Fresh gates for the corrected implementation:

```text
./gradlew :app:testStandardDebugUnitTest \
  --tests dev.ujhhgtg.wekit.features.items.chat.ReadReceiptsTunnelCoordinationTest
BUILD SUCCESSFUL; 34 focused tests

./gradlew testStandardDebugUnitTest
BUILD SUCCESSFUL in 6s

go test -race -count=1 ./app/src/main/go/wekit-cloudflared
ok dev.ujhhgtg.wekit/cloudflared-bridge 1.147s

cargo test --workspace
PASS: wekit-native 9; service library 15; pixel logging 1; zygisk 10; xtask 22

./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a
PASS

./x build
BUILD SUCCESSFUL in 12s (144 actionable tasks: 18 executed, 1 from cache, 125 up-to-date)

git diff --check
PASS
```

Each ABI again exports exactly six Task 8 C symbols and four direct tunnel JNI symbols. Standard and
Legacy APKs each contain the four required native library entries. Generated JNI inputs were moved to
`/tmp/wekit-task9-fix5-sync-final-jni.ksrh2S/jniLibs`. No Dex resolver changed. External final review
and the existing device checklist remain pending; this report does not claim Task 9 acceptance.

### Atomic authoritative-generation transition follow-up

The next external review found one remaining gap shared by the START and STOP service paths. Lease
`advance(H)` transferred a native owner before the separate service `generation = H` assignment. An
old invalidation ticket could enter that interval, stop owner H, and attempt an H `RECONNECTING`
publication while the service filter still held G, dropping the update and retaining the old
CONNECTED URL.

`TunnelNativeLease.advance` now requires a transition callback and executes it under the same monitor
as current-generation and owner transfer. The complete production-call audit found exactly two call
sites:

- `handleStart` atomically sets service generation H, clears the old service request, and replaces
  authoritative local status with STARTING/no URL.
- `stopTunnel` atomically sets service generation H, clears the old service request, and replaces
  authoritative local status with STOPPING/no URL.

Those callbacks mutate local service metadata only. They do not publish, send Binder messages,
invoke UI/controller code, or reenter the lease. Parsing, failure publication, ACK delivery,
lifecycle cancellation, and native stop remain outside the transition callback.

Service generation and status are stored as one immutable value in an `AtomicReference`. Transition
sets H plus STARTING/STOPPING in one write; publication uses compare-and-set, so a G publication that
passed an earlier check cannot overwrite an H transition; REGISTER/status delivery reads the pair in
one snapshot. This closes the visibility tear that two independent volatile fields would otherwise
leave between H and the old CONNECTED status.

Accepted advance also clears the lease's active request and increments its network epoch before the
metadata callback runs. This makes equal-generation STOP/token-delete transitions invalidate an
in-flight verification under the same monitor instead of relying on a later `clearRequest` call. The
obsolete native-session-preservation activation option was removed; every newly activated request
requires its own successful native start before verification.

The deterministic regression covers both STARTING and STOPPING transition metadata in both lock
orders. It was RED at compilation because `advance(Long)` accepted no callback. It is now GREEN:

- Transition-first: H holds the lease while teardown waits; service generation/status become H and
  non-CONNECTED before release; the old ticket then stops transferred owner H and its H RECONNECTING
  commit passes the H filter.
- Teardown-first: the ticket stops owner G and commits G RECONNECTING; the later H transition records
  STARTING or STOPPING with no URL. A REGISTER snapshot therefore cannot observe H with G's old
  CONNECTED URL in either order.

Final fresh gates for this follow-up:

```text
./gradlew :app:testStandardDebugUnitTest \
  --tests dev.ujhhgtg.wekit.features.items.chat.ReadReceiptsTunnelCoordinationTest
BUILD SUCCESSFUL; 35 focused tests

./gradlew testStandardDebugUnitTest
BUILD SUCCESSFUL in 6s

go test -race -count=1 ./app/src/main/go/wekit-cloudflared
ok dev.ujhhgtg.wekit/cloudflared-bridge 1.148s

cargo test --workspace
PASS: wekit-native 9; service library 15; pixel logging 1; zygisk 10; xtask 22

./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a
PASS

./x build
BUILD SUCCESSFUL in 12s (144 actionable tasks: 12 executed, 1 from cache, 131 up-to-date)

git diff --check
PASS
```

Both ABIs again export exactly six Task 8 C symbols and four direct tunnel JNI symbols, and Standard
and Legacy APKs each contain all four required native entries. Generated JNI inputs were moved to
`/tmp/wekit-task9-fix5-authoritative-final-jni.s9WkdL/jniLibs`. No Dex resolver changed. External final review
and the device checklist remain pending; Task 10 has not started.
