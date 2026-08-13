# Embedded cloudflared bridge and Android tunnel service

## Scope

WeKit embeds Cloudflare's official Go tunnel transport as a separate Android shared library. It
supports repeated Quick Tunnel sessions, remotely-managed named tunnels started with a run token,
and browser-assisted read-only selection of an existing remotely-managed tunnel, forwarding only
to an HTTP(S) loopback origin. The Android runtime consumer is an exported `specialUse` foreground
service in WeKit's own process, controlled from the injected WeChat process through a narrow
Messenger protocol with calling-UID validation.

Android browser authentication uses an independent JNI auth handle and never stops or replaces the
active connector until a selected tunnel has passed connector and public-health verification. The
six-symbol C compatibility adapter can attach authentication to an existing connector facade, but
selection only advances that auth session's non-secret state; its fixed signature cannot safely
return a token or switch the connector.

WeKit does not create tunnels, DNS records, hostnames, ingress routes, or public-hostname
configuration, and it does not mutate those resources during login, selection, startup, recovery,
or logout. Authenticated modes connect only an existing remotely-managed tunnel and an existing
hostname configured by the user in Cloudflare. Cloudflare documents that remotely-managed
configuration is stored in Cloudflare and managed through the dashboard or API.

## Source pin and licensing

`third_party/cloudflared` is a shallow Git submodule of the official
`https://github.com/cloudflare/cloudflared.git` repository:

- release: `2026.7.2`
- annotated tag object: `736e2b51d838320c4b0e192c7ea58dbe1335fc9f`
- peeled source commit: `8679787525edc8575b2948a7c4a50b6292c6d426`
- local modifications inside the submodule: none

The bridge module repeats cloudflared's pinned `quic-go` and `urfave/cli` replacements because Go
does not inherit replacements from dependency modules. xtask refuses to build if the submodule
HEAD differs from the full pinned commit or the checkout contains tracked or non-ignored untracked
changes. Ignored generated artifacts do not invalidate the pin.

cloudflared's Apache-2.0 `LICENSE` is in the submodule. Upstream does not ship a `NOTICE` file.
The license and NOTICE files for packages linked into the bridge are retained under
`third_party/cloudflared-licenses/`; its README records generation and replacement details.

## Embedded runtime boundary

The adapter imports cloudflared's `client`, `connection`, `ingress`, `orchestration`, `supervisor`,
and TLS packages directly. It does not execute cloudflared as a subprocess and does not reproduce
the tunnel wire transport.

The CLI application entrypoint is never called or constructed. Consequently the embedded path
does not parse CLI arguments, start the updater, install OS signal handlers, initialize Sentry,
start diagnostic/readiness/metrics listeners, or launch a desktop browser. Browser login only
returns a bounded authorization URL; Android launches it through an explicit `ACTION_VIEW` user
action. Upstream internal Prometheus collectors remain linked because the transport uses them, but
no listener exposes them.

The pinned public Supervisor API requires `cloudflared/signal.safe_signal`. Its isolated adapter is
not a process signal handler: the package only closes an in-memory channel once with `sync.Once`,
does not import `os/signal`, and installs no handler. A static bridge test rejects any direct
`os/signal`, `Notify`, or `NotifyContext` use and confines this safe one-shot import to that adapter.

Each public handle owns its cancellation context, worker wait group, and single-consumer callback
queue. Producers never call foreign callbacks directly. An external `wekit_tunnel_stop` cancels
the context, joins every producer, drains and joins callback dispatch, unregisters the handle, and
then frees its opaque C allocation. If a callback calls stop reentrantly, callback-scope TLS avoids
self-deadlock and handle release is deferred until that callback returns. Callbacks contain only a
numeric status, the bounded public URL (maximum 2048 bytes), and a bounded/redacted error (maximum
512 bytes). Quick credential fields are never included.

cloudflared's `connection.Observer` has no public stop API. The bridge registers a first per-session
sink that owns the dispatcher and terminates it after the supervisor exits. cloudflared also creates
QUIC v3 collectors through Prometheus's process default during supervisor construction; the bridge
temporarily installs a private session registry under a construction mutex and immediately restores
the original process defaults. No metrics listener is created.

Token strings are strictly bounded and decoded directly into the pinned upstream
`connection.TunnelToken` representation. Account tag, 32-byte secret, tunnel UUID, and optional
endpoint are validated. Parse failures are generic; transport failures redact the raw token and all
decoded credential forms. Token mode enables cloudflared's normal remotely-managed configuration
feature, so dashboard ingress is applied by Cloudflare after registration.

The pinned ingress pipeline installs a connector-specific authenticated reader-IP channel before
the request reaches the loopback origin. Its local handler removes caller-supplied internal and
forwarding headers, parses the edge-provided `CF-Connecting-IP` as one canonical IP address, and
overwrites two WeKit-only origin headers. The authenticator reuses the controller's existing
24-byte random Binder START nonce (32 ASCII characters after `Base64.NO_WRAP`); no second secret is
created, persisted, displayed, or carried in a new Binder field or exported native entry point. It
is removed from the origin URL before
remote configuration is marshalled, and the local rule is retained across remotely-managed config
updates. The Rust origin compares it in constant time before accepting the reader IP.

Direct loopback callers and requests with missing, malformed, or forged metadata still use Axum's
actual TCP peer. `Forwarded`, `X-Forwarded-For`, `X-Real-IP`, `True-Client-IP`, and raw
`CF-Connecting-IP` never become trusted merely by reaching Rust. Cloudflare documents the edge
header and the different same-zone/cross-zone Worker subrequest behavior in
[HTTP request headers](https://developers.cloudflare.com/fundamentals/reference/http-request-headers/);
placing a Worker in front of the tunnel changes the identity semantics accordingly.

## C ABI

The exact symbols are declared in `app/src/main/go/wekit-cloudflared/bridge.h`:

```c
wekit_tunnel_handle wekit_tunnel_start_quick(const char *origin, wekit_callback callback, void *user);
wekit_tunnel_handle wekit_tunnel_start_token(const char *token, const char *origin, wekit_callback callback, void *user);
int wekit_tunnel_begin_login(wekit_tunnel_handle handle, wekit_callback callback, void *user);
int wekit_tunnel_select_existing(wekit_tunnel_handle handle, const char *tunnel_id, const char *hostname);
int wekit_tunnel_stop(wekit_tunnel_handle handle);
int wekit_tunnel_status(wekit_tunnel_handle handle, char *buffer, size_t buffer_len);
```

Status codes are `STOPPED=0`, `STARTING=1`, `CONNECTED=2`, `RECONNECTING=3`, `FAILED=4`,
`STOPPING=5`, and the retained compatibility value `UNSUPPORTED=6`. Function results are `0` for
success, `-1` for invalid input or handle, the retained compatibility value `-2` for unsupported,
and `-3` for a status buffer that is too small. `wekit_tunnel_status` writes a NUL-terminated JSON
object containing only `status`, `url`, and `error`.

The same Go library also exports nine direct JNI entry points used by
`ReadReceiptsTunnelNative`: four connector operations and five independent auth operations. Kotlin
owns connector and auth handles separately and atomically clears each before stop/cancel; a blocked
list or selection call can be cancelled and joined from another service IO coroutine.

## Android lifecycle and credential boundary

The module process owns `ReadReceiptsTunnelService`, its notification, the Go handle, and retained
run token. The injected WeChat process never loads the Go library. It starts the loopback origin
first, verifies local `/health`, then starts the foreground service only from a visible settings
action. Android background-start rejection is reported as `NEEDS_USER_ACTION`. Shutdown reverses
the order: the tunnel receives a bounded teardown window before the origin is stopped.

Every service command is accepted only from WeKit's UID or a UID containing `com.tencent.mm`.
Tokens travel only in Binder command data, never Intents, broadcasts, notifications, logs,
saved-instance state, clipboard, or MMKV. Status uses Binder replies plus a per-controller random
nonce. Configuration generations derive from the boot-monotonic clock, so stale callbacks and a
surviving module service cannot overwrite a newer WeChat-process session.

Browser login is stricter: the origin certificate remains inside the Go auth session and never
crosses JNI. A selected run token crosses JNI only into a module-service local value and never
enters Messenger, status JSON, notification, UI, logs, or clipboard. After connector and exact
public-health verification, the service atomically commits one encrypted versioned payload
containing the token, source, account/tunnel identity, canonical hostname, and fixed origin port.
Messenger exposes only bounded non-secret auth state and committed metadata. The authorization URL
is transient dialog/process state; Android offers an explicit copy fallback for that URL only.

Auth generation/request IDs are independent from connector generations. BEGIN, LIST, CANCEL, and
LOGOUT do not advance connector authority. SELECT reserves replacement authority, but publishes it
only after service verification and the encrypted commit; failure preserves the prior payload and
restores the prior stack.

START uses a generation-bound service ACK. The controller retains the transient token only until an
authorized service has copied the command and removed the token from its Binder Bundle; rejection,
supersession, Binder failure, or a ten-second timeout clears the pending command and never replays it.
Native start/stop and network invalidation share a serialized generation lease, while STOP completion
uses one generation drain so Binder death, status, and timeout can stop the origin at most once.

Retained tokens are stored below `noBackupFilesDir` in an atomically-written private file encrypted with a dedicated
Android Keystore AES-256-GCM key (API 28, no per-use authentication so unattended reconnect works).
A newly supplied token remains transient until cloudflared reports connected and the configured
public HTTPS `/health` returns exactly `204` with an empty body. Only then does it replace the last
working ciphertext. Invalid keys/ciphertext are deleted and surfaced as `NEEDS_USER_ACTION`. Backup,
cloud-backup, and device-transfer rules additionally exclude the entire legacy `files/read_receipts/`
directory, including every `AtomicFile` `.new`/`.bak` sidecar.

Quick mode publishes its random URL only after that same public verification. Token mode requires a
root HTTPS DNS hostname and a fixed loopback port matching the dashboard Public Hostname service;
automatic/ephemeral port selection is rejected. The service rechecks public health periodically,
invalidates the URL on loss/reconnect, follows bounded reconnect backoff, and reacts to Android
default-network changes.

Android 13+ notification permission is declared. Because the feature UI runs inside WeChat and cannot
request another package's runtime permission, a disabled WeKit notification channel/permission rejects
START as `NEEDS_USER_ACTION`; the UI provides an explicit button to open WeKit's app notification
settings. It never runs a connected tunnel with an invisible ongoing notification/stop action.

## Build

Go 1.26 and the NDK version pinned in `gradle/libs.versions.toml` are required. xtask uses the NDK
API 28 Clang drivers and writes build intermediates below `target/cloudflared/`. Only the shared
objects are copied into the APK input directories:

```bash
./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a
```

Outputs:

```text
app/src/main/jniLibs/arm64-v8a/libwekit_cloudflared.so
app/src/main/jniLibs/armeabi-v7a/libwekit_cloudflared.so
```

A normal `./x build` and `./x run` refresh these bridge artifacts before the Rust native library
and Gradle step. `./x build --native-only` retains its existing meaning and rebuilds only the Rust
library.

## Quick Tunnel limitations

Quick Tunnels are Cloudflare testing/development facilities, not production infrastructure. They
produce a random `trycloudflare.com` hostname, have no uptime guarantee, currently cap a tunnel at
200 in-flight requests, and do not support Server-Sent Events. WeKit must not promise SSE behavior
through this mode. The URL is valid only while the current tunnel session is connected and changes
when a new Quick Tunnel is allocated.

These statements are checked against Cloudflare's current
[Quick Tunnel documentation](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/do-more-with-tunnels/trycloudflare/).
Cloudflare also documents that
[remotely-managed configuration is stored in Cloudflare](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/get-started/tunnel-useful-terms/)
and that [anyone holding a tunnel token can run that tunnel](https://developers.cloudflare.com/tunnel/advanced/tunnel-tokens/).

The real integration test is opt-in because it uses Cloudflare's public service:

```bash
WEKIT_CLOUDFLARED_INTEGRATION=1 go test -v -count=1 -timeout 5m \
  -run TestRealQuickTunnelForwardsAndStopsWithoutLeaking \
  ./app/src/main/go/wekit-cloudflared
```

It runs two sessions sequentially in one process. Each session creates a temporary loopback HTTP
origin, obtains a real `trycloudflare.com` hostname, waits for the connected callback, verifies a
public HTTPS request reaches that origin, stops the handle, and checks the stopped callback. Leak
accounting begins before the first observer is created and covers both sessions. The verifier uses
public DNS directly because the development host's configured system resolver filters newly
allocated Quick Tunnel hostnames.

## Manual Android checks

Automated host tests cannot prove Android/WeChat lifecycle behavior. Before release, verify on API
28 and a current target-SDK device:

1. A visible **验证并连接** action starts the low-importance ongoing notification; a background or
   automatic attempt reports `NEEDS_USER_ACTION` instead of claiming success. Disable WeKit
   notifications on Android 13+, confirm START is rejected, and use the UI button to open the correct
   WeKit notification settings page before retrying.
2. Quick mode forwards public `/health` and pixel requests, publishes only the verified
   `trycloudflare.com` URL, and invalidates it after network loss.
3. Token mode rejects automatic ports, malformed tokens, and non-root/non-HTTPS hostnames; with a
   user-created tunnel and dashboard ingress pointing at the fixed loopback port it reconnects and
   preserves the last working token when a replacement fails validation.
4. Airplane mode and network switching show reconnecting state, suppress stale-generation URLs,
   and recover with bounded backoff.
5. Killing/restarting WeChat rebinds to a surviving service without accepting stale status; killing
   the module service requires an explicit visible restart and never leaves a fake connected URL.
6. Deleting the saved token removes the ciphertext and stops an active token session. No token is
   visible in notification, recents, logs, clipboard, saved UI state, backups, or Intents.
7. UI disconnect and notification stop both tear down the tunnel before the loopback origin; a dead
   service or missing reply triggers the bounded origin-stop fallback. Race Binder death against the
   STOPPED reply/timeout and confirm origin shutdown runs once.
8. While replacing a connected fixed-port configuration, switch ports and immediately exercise a
   rejected handoff. Confirm the candidate origin uses the requested port, the previous configuration
   and stack are restored on failure, and pressing **确定** after a successful ACK does not tear down
   the just-started connector.
