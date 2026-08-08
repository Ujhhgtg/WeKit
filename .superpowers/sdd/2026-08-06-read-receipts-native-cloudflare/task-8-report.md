# Task 8 report: pinned cloudflared bridge proof of concept

## Outcome

The feasibility gate passes. WeKit now builds a small C ABI facade over Cloudflare's official Go
tunnel transport, proves a real Quick Tunnel can forward a public HTTPS request to a temporary
loopback HTTP origin, and cross-compiles the facade as Android shared libraries for `arm64-v8a`
and `armeabi-v7a`. No Task 9 Android service, controller, or JNI consumer was added.

## Upstream provenance and licenses

- Official repository: `https://github.com/cloudflare/cloudflared.git`
- Release: `2026.7.2`
- Annotated tag object: `736e2b51d838320c4b0e192c7ea58dbe1335fc9f`
- Peeled source commit: `8679787525edc8575b2948a7c4a50b6292c6d426`
- Checkout form: shallow Git submodule at `third_party/cloudflared`
- Local changes inside the submodule: none
- Upstream license: Apache-2.0 in `third_party/cloudflared/LICENSE`
- Upstream NOTICE: cloudflared does not ship a `NOTICE` file

The bridge repeats cloudflared's own replacements for its Cloudflare `quic-go` fork and `urfave/cli`
fork because dependency-module replacements are not inherited by Go consumers. Third-party LICENSE
and NOTICE files for modules linked into the Android binary are stored under
`third_party/cloudflared-licenses/`. A `go version -m` coverage check against the arm64 binary found
a license/notice directory for every recorded dependency. `go-licenses` exits non-zero only for the
local WeKit bridge package, which has no package-local license; the repository root license covers
WeKit itself and all third-party texts were copied.

## Facade and runtime boundary

`app/src/main/go/wekit-cloudflared` exports the six required C symbols. Each registered handle owns
a cancellable context and wait group. Status callbacks and JSON snapshots contain only status, a
URL bounded to 2048 bytes, and redacted error text bounded to 512 bytes. Quick credentials are not
returned. Origins are restricted to explicit HTTP(S) loopback URLs.

The adapter imports pinned upstream transport packages directly; it does not invoke a subprocess
or copy the wire protocol. The embedded path does not construct the cloudflared CLI, parse CLI
arguments, run an updater, install process signal handlers, initialize Sentry, open diagnostics,
readiness, or metrics listeners, or launch a browser.

Quick Tunnel is the only functioning connection mode in this milestone. Token start, browser login,
and existing-tunnel selection expose the stable ABI but return explicit `UNSUPPORTED` state/result;
they cannot report fake connector success and token input is never copied into Go memory.

## TDD and implementation evidence

- Bridge tests were first run with missing facade symbols and failed to compile, then passed after
  implementing the lifecycle, status, redaction, loopback validation, and unsupported auth facade.
- The upstream integration test was first run with missing Quick Tunnel request/transport functions
  and failed to compile, then passed after wiring official cloudflared packages.
- A C header compile check exposed a callback `const` mismatch in the cgo preamble; the facade now
  uses a compatible private callback typedef while retaining the exact public header contract.
- xtask parser/ABI mapping tests failed before the command and mapping existed, then passed after
  implementing `cloudflared-build`.
- A stop-during-credential-request test initially observed a false `FAILED` callback after
  cancellation. Cancellation is now classified as a normal stop and the regression test passes.

## Formal review hardening

The follow-up lifecycle/build review was implemented as a focused Task 8 fix:

- Every callback now runs through an owned per-handle queue. External stop first joins all event
  producers, then drains and joins callback dispatch before freeing the opaque handle, so callback
  `user` state is quiescent on return.
- C callback scope is tracked with thread-local state. A callback may call `wekit_tunnel_stop`
  without waiting on its own dispatcher; handle unregister/free runs only after that callback has
  returned. A bounded Go regression and a compiled C ABI harness both returned without deadlock.
- The package-init observer and global one-session guard were removed. Every session creates an
  upstream observer with a first owned sink that explicitly terminates its otherwise-infinite
  dispatcher after supervisor shutdown. Leak accounting begins before observer creation.
- cloudflared constructs QUIC v3 metrics through Prometheus's process default on every supervisor.
  Supervisor construction now runs under a short mutex with a private per-session registry and
  restores both process defaults immediately. This made two real sessions in one process pass.
- Normal APK build, run, and zygisk APK assembly now use the same ordered native-input plan:
  configure, cloudflared dual-ABI build, then WeKit Rust native build.
- The source-pin gate now requires both the exact commit and a clean `git status --porcelain` with
  all non-ignored untracked files included. Temp-repository tests cover clean, wrong revision,
  tracked modification, untracked Go source, and permitted ignored build artifacts.

RED evidence included the missing callback lifecycle APIs, inline login callback timeout, missing
xtask plan/pin helpers, and a real second Quick Tunnel panic from duplicate QUIC metric collector
registration. All corresponding focused tests are green after the changes.

## Verification evidence

Host unit gate:

```text
$ go test ./app/src/main/go/wekit-cloudflared
ok  dev.ujhhgtg.wekit/cloudflared-bridge
```

Real public integration gate against the pinned dependency graph:

```text
$ WEKIT_CLOUDFLARED_INTEGRATION=1 go test -v -count=1 -timeout 5m \
    -run TestRealQuickTunnelForwardsAndStopsWithoutLeaking \
    ./app/src/main/go/wekit-cloudflared
--- PASS: TestRealQuickTunnelForwardsAndStopsWithoutLeaking (37.12s)
    --- PASS: .../session-1 (17.70s)
    --- PASS: .../session-2 (19.42s)
PASS
ok  dev.ujhhgtg.wekit/cloudflared-bridge  37.124s
```

The test runs two sequential sessions in one process. Each starts a temporary loopback origin,
obtains a real random `trycloudflare.com` hostname, waits for the upstream connected event, sends a
public HTTPS request and verifies its exact origin response, stops the handle, and observes the
final stopped callback. One goroutine leak check spans observer creation and teardown for both.
The development host's system resolver filtered newly allocated Quick Tunnel hostnames, so the
verifier deliberately uses public DNS at `1.1.1.1`; the tunnel itself uses its normal upstream edge
discovery.

Android ABI gate:

```text
$ ./x cloudflared-build --abi arm64-v8a --abi armeabi-v7a
cloudflared-build: arm64-v8a (android/arm64)
cloudflared-build: armeabi-v7a (android/arm)

$ file app/src/main/jniLibs/arm64-v8a/libwekit_cloudflared.so \
       app/src/main/jniLibs/armeabi-v7a/libwekit_cloudflared.so
...arm64-v8a/...: ELF 64-bit LSB shared object, ARM aarch64, dynamically linked, for Android 28, stripped
...armeabi-v7a/...: ELF 32-bit LSB shared object, ARM, EABI5 version 1, dynamically linked, for Android 28, stripped
```

`readelf` confirmed `ET_DYN` for AArch64 and ARM and found all six required public C symbols in both
artifacts. A full `./x build` also passed (`147 actionable tasks`) and assembled standard and legacy
debug APKs with both bridge libraries. xtask's two new command/mapping tests pass.

Post-review verification additionally passed:

```text
$ go test -race -count=1 ./app/src/main/go/wekit-cloudflared
ok  dev.ujhhgtg.wekit/cloudflared-bridge  1.138s

$ cargo test -p xtask
22 passed; 0 failed

$ timeout 5s /tmp/wekit-task8-reentrant/reentrant_stop
reentrant callback stop returned without deadlock

$ ./x zygisk build
cloudflared-build: arm64-v8a (android/arm64)
cloudflared-build: armeabi-v7a (android/arm)
BUILD SUCCESSFUL
zygisk(package): .../WeKit-1055-git+263f98da-release.zip

$ ./x build
BUILD SUCCESSFUL in 13s
147 actionable tasks: 23 executed, 1 from cache, 123 up-to-date
```

The C harness links a host `c-shared` build, receives the unsupported login callback, calls
`wekit_tunnel_stop` from inside that callback, and must exit within five seconds. The complete
zygisk packaging gate was run rather than a reduced proxy; its output proves cloudflared was rebuilt
for both ABIs before `assembleStandardDebug` and the module ZIP was produced.

## Files added or changed

- `.gitmodules` and `third_party/cloudflared` pinned source gitlink
- `app/src/main/go/wekit-cloudflared/` facade, header, module, and tests
  (including `lifecycle.go` callback ownership)
- `go.work` and `go.work.sum` for the required repository-root Go test command
- `xtask/src/main.rs` build command, pin check, ABI mapping, and tests
- `third_party/cloudflared-licenses/` transitive license/NOTICE archive
- `docs/features/chat/cloudflared-bridge.md` operator/developer documentation
- this report

Generated `.so` files remain build artifacts and are not committed.

## Remaining limitations and follow-up concerns

- Quick Tunnel is development/testing only: the hostname is random and temporary, there is no
  uptime guarantee, Cloudflare currently limits it to 200 in-flight requests, and it does not
  support Server-Sent Events.
- Authenticated token/browser login and existing-tunnel selection are intentionally unsupported in
  this milestone. Later work must connect only user-created tunnels and hostnames; WeKit must not
  create Cloudflare resources.
- The bridge libraries are approximately 22 MB per ABI before APK compression. Packaging impact
  should be evaluated before release.
- Pinned cloudflared hard-codes Prometheus's process default when it constructs supervisor QUIC
  metrics. The bridge swaps in a private registry only for the serialized constructor call and
  restores the original defaults immediately; this assumes no unrelated Go component registers a
  collector during that short embedded-only critical section.
- Device lifecycle and hook behavior still require manual Android/WeChat validation in Task 9; this
  task proves the native transport and cross-compilation boundary only.
