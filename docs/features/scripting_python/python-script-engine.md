# Python plugin engine

WeKit's Python engine is an independent CPython plugin subsystem. It does not adapt the Java/BeanShell engine and has no JavaEngine callback compatibility layer.

## Distribution

The base APK contains only the loader-neutral SPI, plugin manager, host services and UI. CPython 3.13, Chaquopy, native libraries and Python standard-library assets are built by `extensions/python-runtime` and published by:

```bash
./x extensions pack --only python-runtime
```

This produces `dist/extensions/python-runtime-<content-hash>.apk` and `wekit-python-sdk.zip`. The runtime APK is an unsigned APK-shaped container stored under WeChat's private `filesDir/wekit-extensions/python-runtime`; PackageManager never installs it.

`RuntimeEntrypoint` first loads the manifest-ordered native libraries by absolute path. It then creates `WeKitAndroidPlatform`, the integration replacement for Chaquopy 17.0 revision `e01057c72fdd737f202bd1be1de85af51e06cad0`'s `AndroidPlatform`: native loading is skipped because it has already completed, assets come from the injected runtime APK, bootstrap assets retain Chaquopy's original layout, and `java.android.importer.nativeLibraryDir` is set to the mounted version's extracted native directory. The documented Chaquopy patch changes the native `FindClass` cache and `dynamic_proxy` loader to the supplied `ClassLoaders.HYBRID`; callback and Python-created threads also set and restore that loader as TCCL. Runtime DEX still uses `ClassLoaders.MODULE` as its parent.

## Plugins

Plugins live in `WeKit/scripts_python/<reverse-dns-id>/` and contain `plugin.json` plus an entry module. New plugins are disabled by default. Enabling one starts the process-local runtime on demand and calls `setup(ctx)` in a private `_wekit_plugins.<encoded-id>` namespace. `ctx.defer`, hook tokens and task handles are released in LIFO order during disable or reload.

`ctx.dex` exposes class, method, constructor and field queries with package scopes, descriptor/name/type/modifier constraints, contains/equals/prefix/suffix/regex strings, class field/method structure, field reader/writer methods, method shorty/opcode/field/callee/caller constraints, all/any/none composition, plural results and strict single-result helpers. Every facade value is translated to DexKit 2.2.0's real matcher classes. Queries use WeKit's shared DexKit lease off the UI thread and return descriptors tagged with the current host version/build rather than process-stale reflection objects.

The manager remains available when the runtime pack is absent. Missing runtime state is shown as `RUNTIME_MISSING`; WeKit does not download it or show an installation dialog during WeChat startup.

## Trust boundary

Python plugins are trusted arbitrary in-process code. There is no sandbox, permission or capability boundary. A plugin can read or change WeChat/WeKit state and files, use the process network access, install hooks, and crash or deadlock the host. Disabling a plugin is not a security response and cannot undo arbitrary side effects. Runtime SHA-256 verification protects download integrity only.

## Verification boundary

Desktop compilation and `./x extensions pack --only python-runtime` verify the base/runtime build and artifact hand-off. They do not prove JNI ownership, arbitrary-APK assets, direct Android/WeKit/WeChat imports, normal vs Zygisk loader behavior, or 16 KB-page behavior. Those P0 claims require an arm64 device report recording runtime hash, WeChat build, process, loader, ABI and page size.

The published desktop artifact deliberately contains `classes.dex` and a unique
`classes2.dex` probe, the patched Chaquopy bridge, `chaquopy/build.json`, and only
arm64 native libraries. Archive and scope unit tests cover DEX sequencing and
LIFO/error-aggregating cleanup. P0-B/D/E/F/G/I/J remain unverified until the
required device runs are recorded, and the diagnostics screen reports that
boundary instead of inferring support from these desktop checks.

Manual release checks cover both normal and Zygisk loaders, a 16 KB-page device, Android/WeKit/WeChat imports, one pure-Python plugin, light/dark settings themes, predictive back, empty/missing-runtime/error states, plugin detail/edit/diagnostics navigation, activation rollback and runtime-update restart messaging. An unrun item remains unsupported rather than being inferred from a desktop build.
