# Python 插件引擎

入口：WeKit 设置 → 功能 → 脚本 (Python) → Python 插件引擎。

## 首次使用

1. 点击功能打开管理页，先检查顶部 Python 运行时扩展包状态；未安装时按扩展包管理流程安装。
2. 选择新建插件或导入插件，检查名称、ID、版本和入口模块。插件目录为 `<模块数据>/scripts_python/<插件 ID>/`，包含 `plugin.json` 和入口 Python 文件，默认入口是 `main.py`。
3. 启用引擎，再在插件列表中启用需要运行的插件。新插件默认关闭；仅安装运行时不会自动启用全部插件。
4. 出错时打开插件详情和诊断页查看状态。缺少运行时显示 `RUNTIME_MISSING`，不会在微信启动时静默下载。

插件在微信进程中拥有直接访问 JVM、文件与网络的能力，没有沙箱隔离。禁用会回收管理器跟踪的资源，但不能撤销插件任意副作用。

## 运行时与开发参考

WeKit's Python engine is an independent CPython plugin subsystem. It does not adapt the Java/BeanShell engine and has no JavaEngine callback compatibility layer.

## Distribution

The base APK contains only the loader-neutral SPI, plugin manager, host services and UI. CPython 3.13, Chaquopy, native libraries and Python standard-library assets are built by `extension-packs/python-runtime` and published by:

```bash
./x extensions pack --only python-runtime
```

This produces `dist/extensions/python-runtime-<content-hash>.apk` and `wekit-python-sdk.zip`. The runtime APK is an unsigned APK-shaped container stored under WeChat's private `filesDir/wekit-extensions/python-runtime`; PackageManager never installs it.

`RuntimeEntrypoint` first loads the manifest-ordered native libraries by absolute path. It then creates `WeKitAndroidPlatform`, the integration replacement for Chaquopy 17.0 revision `e01057c72fdd737f202bd1be1de85af51e06cad0`'s `AndroidPlatform`: native loading is skipped because it has already completed, assets come from the injected runtime APK, bootstrap assets retain Chaquopy's original layout, and `java.android.importer.nativeLibraryDir` is set to the mounted version's extracted native directory. The documented Chaquopy patch changes the native `FindClass` cache and `dynamic_proxy` loader to the supplied `ClassLoaders.HYBRID`; callback and Python-created threads also set and restore that loader as TCCL. Runtime DEX still uses `ClassLoaders.MODULE` as its parent.

## Plugins

Plugins live in `WeKit/scripts_python/<reverse-dns-id>/` and contain `plugin.json` plus an entry module. New plugins are disabled by default. Enabling one starts the process-local runtime on demand and calls `setup(ctx)` in a private `_wekit_plugins.<encoded-id>` namespace. `ctx.defer`, hook tokens and task handles are released in LIFO order during disable or reload.

`wekit.dexkit` is generated from DexKit 2.2.0's AAR. It exposes every public
matcher, matcher collection and query enum as a typed Python class, with
snake-case methods, typed overloads and the canonical JVM method aliases. A
binding recursively unwraps nested Python bindings before calling DexKit and
wraps matcher results again; the underlying JVM object is not the public API.
Constructor keywords are generated from DexKit's fluent setters, so
`MethodMatcher(return_type="void", param_count=1, using_strings=[eq("foo")])`
and the equivalent fluent chain are the same binding.

`ctx.dex` accepts these real bindings for class, method, constructor and field
queries. Queries use WeKit's shared DexKit lease off the UI thread and return
descriptors tagged with the current host version/build rather than
process-stale reflection objects. The generated `.pyi` files ship in
`wekit-python-sdk.zip` with the runtime and examples.

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
