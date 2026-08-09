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

No Kotlin/JVM tests were added: the new Kotlin code is Android Service, Binder, Keystore, network,
notification, and Compose glue, which the repository testing strategy assigns to real-host manual
validation rather than low-value desktop seams. No Dex resolver changed, so no supported-version
DexKit rerun was required.

## Required manual device checklist

The following gates still require a device and, where noted, user-owned Cloudflare configuration:

1. On API 28 and a current target-37 device, open the WeChat-hosted feature UI and connect visibly.
   Confirm the low-importance ongoing `specialUse` notification appears promptly. Exercise a platform
   background-start rejection and confirm the UI reports `NEEDS_USER_ACTION` without claiming success.
2. Start Quick mode. Confirm local `/health` succeeds before connector startup, the public URL is hidden
   until exact public `/health` verification, a generated tracking pixel forwards through the verified
   URL, and copy/share expose only that URL.
3. With a user-created remotely-managed Cloudflare Tunnel, configure its public-hostname route to the
   exact fixed `127.0.0.1:<port>` selected in WeKit. Confirm automatic-port mode is rejected, malformed
   tokens/hostnames are actionable, the real token connects, and the token is retained only after public
   health succeeds. Then submit a bad replacement and confirm the prior saved credential still works.
4. Disable and restore the default network. Confirm the verified URL disappears immediately, reconnect
   uses bounded backoff, and the URL returns only after a fresh public-health pass. Leave it connected
   long enough to observe periodic public-health revalidation.
5. Kill and restart the WeChat process while the module service survives, then kill the module service.
   Confirm status rebinds to the authoritative generation, old callbacks cannot overwrite it, and a
   lost service yields actionable state while the loopback origin is shut down.
6. Delete the saved token while token mode is active and while stopped. Confirm the active token tunnel
   stops, later unattended reconnect requests a new token, and no token appears in logs, notification,
   clipboard, saved UI state, backup, or transfer data.
7. Disconnect from the UI and separately use the notification stop action. Confirm connector teardown
   finishes before the loopback origin stops; also confirm the bounded fallback stops the origin if the
   module process/Binder disappears during teardown.

A live named-tunnel run cannot be automated or truthfully claimed without the user's Cloudflare token,
hostname, and dashboard route; no credentials were embedded in tests or source. Device-level FGS,
cross-process, network, and forwarding behavior likewise remains an explicit manual acceptance gate.
