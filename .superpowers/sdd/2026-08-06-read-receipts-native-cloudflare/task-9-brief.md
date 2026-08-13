# Task 9 brief: Android foreground tunnel service and Quick/token modes

## Approved source

Implement Task 9 from `docs/superpowers/plans/2026-08-06-read-receipts-native-cloudflare.md`.
The user already approved that design and its 12-task plan. This brief refines implementation details
discovered after Tasks 7–8; it does not reopen scope.

Base commit: `20d09f6845c7da1326148454e48048916d184838`.

## Required product behavior

- Add the specified tunnel mode/state/status types, foreground service, controller, manifest entries,
  and built-in-mode UI for Quick and token modes.
- The embedded origin starts first; only after its loopback `/health` succeeds may the tunnel start.
- Shutdown reverses the order: stop and await tunnel teardown, then stop the origin.
- A public URL becomes usable only after the bridge reports connected **and** an HTTPS request to the
  configured/public `/health` route returns the exact expected health response. Invalidate it on
  disconnect/reconnect/failure.
- Quick mode publishes the temporary `trycloudflare.com` URL after public verification.
- Token mode decodes and runs a user-provided remotely-managed tunnel token through pinned official
  cloudflared library packages. Require a normalized HTTPS hostname and verify it publicly. WeKit must
  never create or mutate tunnels, DNS records, hostnames, or ingress. The user must configure the
  dashboard route to the selected fixed loopback origin. Token mode therefore requires a fixed built-in
  port; reject automatic/ephemeral port mode with an actionable UI explanation.
- Browser-login mode remains an explicit `NEEDS_USER_ACTION`/not-yet-implemented state for Task 10; do
  not fake it.
- Add bounded reconnect/backoff and Android default-network callback handling. Generation IDs must
  suppress all callbacks/status from prior sessions.

## Process and IPC architecture

The feature UI executes inside `com.tencent.mm`; the manifest service belongs to the installed WeKit
package. Therefore:

- Run `ReadReceiptsTunnelService` in WeKit's normal app process. Declare it exported because the
  injected WeChat process must reach it.
- Start it only from an explicit visible user action (`startForegroundService`) and promote it within
  five seconds. Automatic/background attempts must catch platform rejection and publish
  `NEEDS_USER_ACTION`; never pretend automatic startup succeeded.
- Use foreground service type `specialUse`, declare both `FOREGROUND_SERVICE` and
  `FOREGROUND_SERVICE_SPECIAL_USE`, and include a precise
  `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explanation. Do not use `dataSync` because target 37
  applies Android 15's six-hour quota and this connector is long-lived.
- Use framework Messenger/Binder IPC (or an equivalently narrow Binder protocol) for commands and live
  status. Validate every command's calling UID as either WeKit itself or a UID whose packages include
  `com.tencent.mm`. Do not put tokens in start-service Intent extras, broadcasts, notifications, logs,
  exception text, saved instance state, or clipboard. The no-secret start action may be callable by
  other apps, so stop the service if no authorized command/binding arrives within a short bounded window.
- Keep status listeners over Binder/Messenger rather than spoofable exported broadcasts. Handle binder
  death and controller rebind. Extend the command protocol cleanly for Task 10.

## Native bridge boundary

- Preserve Task 8's exact six C ABI symbols and lifecycle guarantees.
- Prefer direct JNI entry points exported by `libwekit_cloudflared.so` and a small
  `ReadReceiptsTunnelNative.kt`; do not route through Rust unless direct JNI proves infeasible.
- Kotlin may use start/stop/status polling rather than native callbacks. Polling avoids JNI callback
  ownership. Never query a freed handle after stop; the Kotlin owner must atomically clear it.
- Implement real `wekit_tunnel_start_token`: strict bounded base64/JSON token decoding into
  `connection.TunnelToken`, validate account tag/UUID/secret/optional endpoint, run the same owned
  supervisor/observer path with remote configuration support, and redact credentials from every error.
  Do not import or execute cloudflared CLI, updater, Sentry, OS/process signal handling, metrics
  listener, diagnostics, or browser code. The pinned public `supervisor.Supervisor.Run` API requires
  `cloudflared/signal.safe_signal`; that package is a `sync.Once`-protected in-memory channel close,
  imports no `os/signal`, installs no process handler, and is an approved narrow exception. Keep its
  construction isolated and covered by a static direct-import/handler-registration gate.
- Tests must cover malformed/oversize token rejection, successful synthetic token execution through an
  injected runner, cancellation, redaction, repeated start/stop, and JNI/C symbol/header correctness.
  A real named-tunnel test is not possible without user credentials; say so explicitly rather than
  embedding credentials.

## Credential storage

- Store retained run tokens only in a module-process private encrypted file/value store backed by a
  dedicated Android Keystore AES-GCM key (API 28 compatible, no per-use user authentication because
  unattended reconnect is required). Store IV/version/ciphertext, write atomically, and bound sizes.
- Exclude the credential file from both legacy backup and data-extraction/cloud-backup rules. If key or
  ciphertext is invalid, delete the unusable ciphertext and report `NEEDS_USER_ACTION` without exposing
  details.
- `WePrefs` contains only non-secret metadata. The token input must use ordinary Compose `remember`, not
  `rememberSaveable`; clear it after successful handoff. Provide masked/reveal/delete controls. Never
  offer token copy.

## Controller, service, and UI details

- Keep service status as the source of truth across processes. Controller exposes a process-local
  observable status and credential-exists metadata, reconnects/binds as needed, and stamps commands
  with monotonic configuration generations.
- Notification channel is low importance; notification is ongoing, shows only bounded state/hostname,
  and has a stop action. It must never show tokens or raw native errors.
- Health checks and hostname building use `HttpUrl` path construction, not string concatenation. Require
  root HTTPS hostnames with no userinfo/query/fragment. Use bounded timeouts and cancellation-aware
  OkHttp calls.
- UI: Quick/Token radio controls; Quick state and verified URL; token masked/reveal/delete; HTTPS
  hostname; validate/connect and disconnect; copy/share only the verified public URL. Do not persist
  token in configuration.
- Replace `verifiedTunnelEndpoint() = null` with the currently verified CONNECTED endpoint only. Built-in
  message registration remains loopback; pixels use the verified public endpoint.
- Manual and automatic lifecycle must serialize origin/tunnel operations. On configuration change,
  old-generation tunnel callbacks cannot overwrite current state. Preserve Task 7's origin reconciliation.

## Testing constraints and gates

- Follow repository testing strategy: TDD pure Go token/lifecycle logic; do not add low-value JVM tests
  for Android Service/Compose/platform glue. Manual device testing remains required.
- Run and report at minimum:
  - focused Go tests and `go test -race ./app/src/main/go/wekit-cloudflared`
  - any meaningful Kotlin/JVM tests added
  - `cargo test --workspace`
  - `./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a` and symbol checks
  - `./x build`
  - `git diff --check`
- Verify both APKs contain both native libraries. Do not run Gradle alone as the final build gate.
- Provide a precise manual device checklist for notification/FGS start restrictions, Quick forwarding,
  token validation, network loss/recovery, process death/rebind, token deletion, and reverse shutdown.

## Files

Required plan files plus documented exceptions needed by the architecture:

- Create `ReadReceiptsTunnelService.kt`, `ReadReceiptsTunnelController.kt`,
  `ReadReceiptsTunnelStatus.kt`, and `ReadReceiptsTunnelNative.kt`.
- Modify `ReadReceipts.kt`, `ReadReceiptsConfiguration.kt` if typed tunnel metadata is warranted,
  `AndroidManifest.xml`, backup/data-extraction XML, and direct Go bridge files/tests.
- Modify Rust only if direct Go JNI is demonstrably infeasible.
- Update `docs/features/chat/cloudflared-bridge.md` and the Task 9 report under the SDD workspace.

Commit a passing Task 9 milestone, preferably `feat: run read receipts cloudflare tunnel service`.
