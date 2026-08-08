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
they cannot report fake connector success and token input is never copied into Go memory. The proof
permits one Quick Tunnel session per process because upstream transport metrics use process-global
collectors. Repeatable lifecycle and authenticated flow are Task 9 work.

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
--- PASS: TestRealQuickTunnelForwardsAndStopsWithoutLeaking (15.12s)
PASS
ok  dev.ujhhgtg.wekit/cloudflared-bridge  15.119s
```

The test starts a temporary loopback origin, obtains a real random `trycloudflare.com` hostname,
waits for the upstream connected event, sends a public HTTPS request and verifies its exact origin
response, stops the handle, observes the final stopped callback, and runs a goroutine leak check.
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

## Files added or changed

- `.gitmodules` and `third_party/cloudflared` pinned source gitlink
- `app/src/main/go/wekit-cloudflared/` facade, header, module, and tests
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
- Repeatable start/stop within one Android process remains Task 9 work.
- The bridge libraries are approximately 22 MB per ABI before APK compression. Packaging impact
  should be evaluated before release.
- Device lifecycle and hook behavior still require manual Android/WeChat validation in Task 9; this
  task proves the native transport and cross-compilation boundary only.
