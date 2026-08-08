# Embedded cloudflared bridge proof of concept

## Scope

Task 8 proves that WeKit can embed Cloudflare's official Go tunnel transport as a separate Android
shared library. The feasibility proof supports repeated Quick Tunnel sessions, forwarding to an
HTTP or HTTPS loopback origin. It owns and terminates each upstream observer dispatcher and gives
each supervisor session an isolated metrics registry. This task does not add the Android foreground
service, controller, or JNI runtime consumer.

The token, browser-login, and existing-tunnel C symbols are present so consumers can compile
against a stable ABI, but they deliberately return `WEKIT_TUNNEL_UNSUPPORTED` / `-2` in this
milestone. They never return fake connector success, copy a supplied run token into Go memory, or
create Cloudflare resources. Authenticated execution and Android browser transfer are deferred.

WeKit does not create tunnels, DNS records, hostnames, ingress routes, or public-hostname
configuration. Later authenticated modes must connect only an existing tunnel and an existing
hostname configured by the user in Cloudflare.

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
start diagnostic/readiness/metrics listeners, or launch a desktop browser. Upstream internal
Prometheus collectors remain linked because the transport uses them, but no listener exposes them.

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
`STOPPING=5`, and `UNSUPPORTED=6`. Function results are `0` for success, `-1` for invalid input or
handle, `-2` for an intentionally unsupported operation, and `-3` for a status buffer that is too
small. `wekit_tunnel_status` writes a NUL-terminated JSON object containing only `status`, `url`,
and `error`.

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
