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
   `TunnelStopCompletion` atomically matches one stop generation, keeps at most one origin callback,
   and lets only the first terminal path drain it.
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
