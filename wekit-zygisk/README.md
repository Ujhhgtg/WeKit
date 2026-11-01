# WeKit Zygisk Module

WeKit can be loaded through Zygisk on a per-Android-user, per-package basis.
On a fresh installation, injection is disabled for every target until enabled in
the WebUI. Updates retain existing target switches.

## KernelSU WebUI

Open the WeKit module page in KernelSU to manage injection targets.

- The first page open scans every Android user and adds every installed package
  matching `PackageNames.isWeChat` (`com.tencent.mm*`) as a disabled target.
- Package discovery uses KernelSU's root-shell `exec` API to run
  `/system/bin/pm list users` and `pm list packages --user <id>`; it does not
  use KernelSU's `listPackages` or `getPackagesInfo` APIs.
- Enabling one instance injects its main process and every process named
  `<package>:...` for that same Android user at the next process launch.
- Refresh scans all Android users again, replaces the package membership with
  the current result, preserves switches for surviving rows, and disables newly
  discovered rows. The WebUI intentionally has no manual add or delete action.

The persisted target list is `/data/adb/wekit_zygisk/injection-targets.tsv`. Module
updates retain it; uninstall removes it without touching app data.

## Installation and updates

Every debug/release bootstrap APK is also an ARM64 Zygisk module ZIP.
Rename `.apk` to `.zip`, install it
from your root manager, select the target instances in the WebUI, and restart as
required by the manager. APK installation and module installation update their
respective deployments separately. Do not enable both injection modes for the
same WeChat instance.

Only change the extension; the file contents must stay identical. When downloading
the `wekit-apk` GitHub Actions artifact, extract its outer archive first and select
an APK. Do not flash that outer archive or unpack/repack the APK. Installation
must run from a root manager app; recovery installation is not supported.

The installer stores the original signed package as `$MODPATH/module.apk` and
extracts its loader into `zygisk/arm64-v8a.so`. DEX stays inside the APK. The native
loader copies that APK into the host's private directory under a content hash,
then reads its DEX into memory. Resources, native libraries and child processes
use the same APK version.

`preAppSpecialize` reads `module.apk` from the module directory while
`getModuleDir()` is guaranteed to be usable, and `postAppSpecialize` publishes
those bytes into the host's private directory and prepares the in-memory DEX.
The lifecycle does not require `exemptFd`, and it never relies on the
module-directory fd surviving the UID/SELinux switch.

The installer compares the new loader byte-for-byte with the active module's
`zygisk/arm64-v8a.so`, not a pending update. Native changes require a device reboot;
an unchanged loader allows APK updates without a device reboot once activated.
Only an enabled module with an unchanged loader requests
`MODULE_HOT_INSTALL_REQUEST=true`. First installs, disabled/removed modules and
comparison errors also advise a reboot. Hot installation requires manager support;
if the manager keeps the update pending, follow its reboot requirement. Fully stop
and restart all WeChat processes after activation: running code is never replaced.
The APK and loader follow the manager's module activation lifecycle together.

## Build

```bash
# Bootstrap dual-format APKs (arm64-v8a).
./x bootstrap build

# Release bootstrap with app-release.apk embedded for offline internal testing.
./x bootstrap build --local

# Private payload APKs (built separately from the bootstrap).
./x payload build
./x payload build --release

# Prepare Zygisk native libraries without building an APK.
./x bootstrap build --native-only

# Also export the unstripped Zygisk loader symbols.
./x bootstrap build --save-symbols

# Normal Android installation.
./x bootstrap install
./x bootstrap install --local

# Build and install as a module; omit --root for manager auto-detection.
./x bootstrap install --zygisk --device SERIAL --root ksu --reboot
./x bootstrap install --zygisk
./x bootstrap install --zygisk --local
```

Bootstrap APKs are in `bootstrap/build/outputs/apk/<type>/`. The offline variant is
`bootstrap/build/outputs/apk/local/bootstrap-local.apk`. Payload APKs are in
`app/build/outputs/apk/<type>/`. No separate module ZIP is built or published.
The device-side `.zip` used by `bootstrap install --zygisk` has exactly the APK's
bytes. Symbols are in `target/zygisk-symbols/`.
Module resources are added by `GenerateZygiskResourcesTask` before AGP signs each
APK; never append files to or repack the signed output.
`bootstrap-local` always builds the release payload, embeds that exact APK as
`assets/wekit/payload.apk`, and bypasses the remote authentication and download paths.
The embedded payload is plaintext and extractable; this variant is for internal testing only.
Pass `--local` to `bootstrap install` to install the same local APK through adb or a root manager.
Bootstrap builds always use the release profile. Run `./x bootstrap build --help` or `./x bootstrap install --help` for options.

## Development environment

- Rust toolchain with the Android targets
- rust-analyzer
- Android NDK pinned in `gradle/libs.versions.toml`
- CMake 3.28 or newer and Ninja (CI uses CMake 3.31.6 and Ninja 1.11.1.4)

`./x bootstrap build` initializes the pinned LSPlant and XZ Embedded sources.
LSPlant's unrelated test/documentation submodules are
not required. `./x configure` writes the selected NDK and API level into the
Cargo configuration; direct Android Cargo builds also need this configuration
and the initialized native dependencies. Desktop Rust tests build XZ Embedded,
not LSPlant; initialize it with `git submodule update --init third_party/xz-embedded`.

## ART hooks

The Zygisk lifecycle, companion IPC, payload loading, JNI registration and ART
symbol resolution remain in Rust. A small C ABI bridge statically links
[LSPlant](https://github.com/LSPosed/LSPlant), including the C++ runtime, into the
existing `libwekit_zygisk.so`. LSPlant calls Rust function pointers for native
inline hook installation and removal; Dobby is no longer a build dependency.
The LSPlant gitlink is pinned to `1dc6ef5c6c5c4eb4d5565fcf50791450c5ede5a9`;
its LGPL-3.0 license remains in the submodule.

The native build requires NDK `30.0.16248370`, CMake 3.31.6+ and Ninja 1.11+.
`native/cmake/cxx` supplies LSPlant's `cxx::cxx` package using the selected NDK's
libc++ headers and `std.cppm`. The module is compiled locally, and its archive
joins the NDK's static libc++/libc++abi archives in Cargo's final link; no Prefab
or shared C++ runtime is needed. A generated copy of `std.cppm` includes
LSPosed's module-only Bionic compatibility header, preserving FORTIFY checks
while allowing the C wrappers to be exported. Installed NDK files are not modified.

ART mini debug symbols use statically linked XZ Embedded (0BSD), with the existing
64 MiB output limit. No system `liblzma.so` is required.

The ARM64 backend is in `native/src/art/inline_hook/`. It provides executable
original-function backups, near entry veneers with an absolute-branch fallback,
PC-relative instruction relocation (ADR/ADRP, integer/SIMD literal loads, PRFM,
B/BL, conditional/compare/test branches), and internal branch destination mapping.
Branch relays preserve x17 when a relocated intra-function jump is out of range.
Literals overlapping the patched entry are copied before publication; other
literal loads still read live memory. Existing absolute veneers with embedded
address data are supported. PAC prologue instructions execute in the backup;
guarded entry points retain a BTI landing pad. Memory protection changes preserve
BTI/MTE mapping attributes and the device's page size, including 16 KiB pages.

Install/remove operations are serialized by a Rust mutex. They run in the existing
LSPlant initialization path, before module Java code. This is an initialization
hook backend, not an arbitrary concurrent hot-patching API: other host threads
are not suspended, a multi-instruction patch is not atomic, and an early callback
can race LSPlant's publication of its backup pointer. These are the same timing
constraints as the former Dobby integration. No signal handlers are installed.

All allocation, relocation and generated-code cache synchronization finish before
the target is changed. A stale/foreign entry patch is rejected. Failure before
publication leaves the target unchanged. If restoring page permissions fails
*after* publication, the error is logged and the installed hook retains its valid
backup; returning failure and freeing that code would be unsafe. Native unhook
restores the saved entry bytes, and retired backups/relays remain mapped until
process exit. This native unhook operation is separate from removing Java callbacks.

The emitter was developed with reference to
[Dobby's ARM64 relocation](https://github.com/LSPosed/Dobby/blob/c1da0315d7a2069bde2ab432e45d4ab6df91237a/source/InstructionRelocation/arm64/InstructionRelocationARM64.cc)
and [Arm's code synchronization guidance](https://developer.arm.com/community/arm-community-blogs/b/architectures-and-processors-blog/posts/caches-self-modifying-code-working-with-threads).

Desktop validation (from the repository root, with uv and Rust installed):

```sh
cargo test -p wekit-zygisk --lib
uv run --locked --project wekit-zygisk --group test python wekit-zygisk/native/tests/arm64_relocation.py
```

Python tooling dependencies are declared in `pyproject.toml` and pinned in
`uv.lock`: the `test` group provides Unicorn, and the `build` group provides
CMake and Ninja. uv creates and manages `wekit-zygisk/.venv` automatically.
To make the pinned build tools available to the native build, run:

```sh
uv run --locked --project wekit-zygisk --group build ./x bootstrap build --native-only
```

The Unicorn suite compiles the production Rust emitter, executes original and
relocated functions, and compares general/SIMD registers, SP and NZCV for near
and far allocations. It also tests poisoned original entries, embedded literals,
existing veneers, function calls and both branch outcomes. It does not establish
Android cache coherency, hardware PAC/BTI enforcement or real ART compatibility;
those require device validation.

LSPlant initializes during native `postAppSpecialize`, before entering module
Java code, and trusts the module's in-memory DEX files. Kotlin retains the
`IHookBridge` callback contract, including priorities, mutable arguments,
before/after callbacks, original invocation and constructor handling. Explicit
method deoptimization is provided by LSPlant.

Hook targets may be ordinary methods, JNI/native methods, methods declared by
generated `java.lang.reflect.Proxy` classes, concrete interface methods
(default, static or private), and ordinary constructors. Abstract declarations
remain rejected: for an abstract interface method, hook the concrete method on
the implementing/proxy class. Generated proxy constructors also remain rejected
because the pinned LSPlant's proxy signature path calls `Method.getReturnType()`
on the reflected target, which is invalid for a `Constructor`. Native method
hooks intercept ART method calls; they do not intercept direct calls to the
underlying C/C++ function. LSPlant returns false when deoptimizing native methods.

Unhooking a handle removes only its callback. The underlying LSPlant hook and
backup remain alive until process exit, and an empty callback list invokes the
original method directly. This preserves in-flight calls and allows later
registrations to reuse the hook; it retains generated code and some dispatch
overhead. `hookCounter` and `hookedMethods` describe members with active callbacks.
This follows LSPosed's logical-unhook lifecycle: LSPlant does not permit using a
backup after physical unhooking.

On a device, validate startup, static/instance/constructor hooks, native and
generated proxy methods, concrete interface methods, argument and result
replacement, original exceptions, callback ordering, concurrent calls,
logical unhook/re-registration and explicit deoptimization. Desktop tests and a
successful native build cannot establish ART or WeChat runtime compatibility.

## See also

<https://github.com/topjohnwu/zygisk-module-sample>
