# WeKit Zygisk Module

WeKit can be loaded through Zygisk on a per-Android-user, per-package basis.
The module is disabled for every process immediately after installation.

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

Every WeKit APK is also a Zygisk module ZIP. Rename `.apk` to `.zip`, install it
from your root manager, select the target instances in the WebUI, and restart as
required by the manager. APK installation and module installation update their
respective deployments separately. Do not enable both injection modes for the
same WeChat instance.

The installer stores the original signed package as `$MODPATH/module.apk` and
extracts its loader into `zygisk/arm64-v8a.so`. DEX stays inside the APK. The native
loader copies that APK into the host's private directory under a content hash,
then reads its DEX into memory. Resources, native libraries and child processes
use the same APK version.

The installer retains `MODULE_HOT_INSTALL_REQUEST=true` for compatible root
managers. This is not a guarantee that native loader updates can take effect
without rebooting, and it never replaces code in an already running process.
The APK and loader follow the manager's module activation lifecycle together.

## Build

```bash
# Both standard and legacy dual-format APKs (arm64-v8a).
./x build
./x build --release

# Prepare application and Zygisk native libraries without building an APK.
./x build --native-only

# Also export the unstripped Zygisk loader symbols.
./x build --save-symbols

# Normal Android installation.
./x run

# Build and install as a module; omit --root for manager auto-detection.
./x run --zygisk --device SERIAL --root ksu --reboot
./x run --zygisk --flavor legacy --release
```

APKs are in `app/build/outputs/apk/<flavor>/<type>/`. No separate module ZIP is
built or published. The device-side `.zip` used by `run --zygisk` has exactly the
APK's bytes. Symbols are in `target/zygisk-symbols/`.
Run `./x build --help` or `./x run --help` for options.

## Development environment

- Rust toolchain with the Android targets
- rust-analyzer
- Android NDK

## See also

<https://github.com/topjohnwu/zygisk-module-sample>
