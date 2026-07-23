# WeKit Zygisk Module

WeKit can be loaded through Zygisk on a per-Android-user, per-package basis.
The module is disabled for every process immediately after installation.

## KernelSU WebUI

Open the WeKit module page in KernelSU to manage injection targets.

- The first page open scans every Android user and adds every installed package
  matching `PackageNames.isWeChat` (`com.tencent.mm*`) as a disabled target.
- Enabling one instance injects its main process and every process named
  `<package>:...` for that same Android user at the next process launch.
- Refresh scans all Android users again, replaces the package membership with
  the current result, preserves switches for surviving rows, and disables newly
  discovered rows. The WebUI intentionally has no manual add or delete action.

The persisted target list is `/data/adb/wekit/injection-targets.tsv`. Module
updates retain it; uninstall removes it without touching app data.

## Hot Update

The installer exports `MODULE_HOT_INSTALL_REQUEST=true`, the hot-install
request used by compatible KernelSU-family root managers. On such a manager an
updated module is activated without a device reboot. Stop and restart WeChat
after the update: the Zygisk companion opens the active ABI-specific payload
for every newly specialized WeChat process, so it receives the updated APK.

This does not replace code in an already running WeChat process. Root managers
that do not implement the hot-install protocol retain their normal reboot or
restart requirements.

## Build

```bash
# Build standard APK splits, both Zygisk loader ABIs, and the installable debug ZIP.
./x zygisk build

# Build a release ZIP.
./x zygisk build --release

# Only configure or compile the Zygisk native loader(s).
./x zygisk config --abi arm64-v8a
./x zygisk native --abi arm64-v8a

# Reuse existing APK outputs, or explicitly select one APK per ABI.
./x zygisk build --skip-apk-build
./x zygisk build --skip-apk-build --apk path/to/arm64.apk --apk path/to/arm32.apk

# Build and install with adb; omit --root to let install_module.sh detect it.
./x zygisk flash --device SERIAL --root ksu --reboot

# Install the newest ZIP for the requested profile without rebuilding.
./x zygisk flash --skip-build
```

`./x zygisk build` defaults to debug, standard APK payloads, and both supported
Zygisk ABIs (`arm64-v8a` and `armeabi-v7a`). The ZIP is output to `release/`.
Run `./x zygisk --help` or `./x zygisk <subcommand> --help` for every option.

## Development environment

- LLVM clangd
- VSCode + Clangd Plugin
- Android NDK
- CMake

## See also

https://github.com/topjohnwu/zygisk-module-sample
