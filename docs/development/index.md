# 开发指南

本页介绍 WeKit 的开发环境、构建命令和产物。专题说明请参阅：

- [DexKit 解析器测试](linux-dex-test.md)
- [国际化开发指南](i18n.md)
- [文档站维护](documentation-site.md)
- [翻译贡献](../translations/index.md)

## 克隆仓库

```bash
git clone https://github.com/Ujhhgtg/WeKit.git --recursive
cd WeKit
```

## 环境要求

当前项目使用：

| 依赖 | 版本或要求 |
|------|------------|
| JDK | 21 |
| Android SDK | compile SDK 37、target SDK 37 |
| Android NDK | `30.0.14904198` |
| Rust | 支持 Rust 2024 edition 的 stable 工具链 |
| CMake / Ninja | 构建 Zygisk 的 LSPlant 依赖; CMake >= 3.28，版本锁定方式见下文 |
| uv | 使用仓库锁定的 CMake / Ninja，以及运行 Python 原生测试时需要 |
| adb | 安装 APK 或将同一 APK 作为 Zygisk 模块刷入时需要 |

Zygisk 的 Python 工具依赖由 `wekit-zygisk/pyproject.toml` 和 `uv.lock` 管理。
`build` 组固定 CMake 3.31.6、Ninja 1.11.1.4，`test` 组提供 Unicorn。在仓库根目录
执行以下命令，可使用锁定的构建工具完成 APK 构建：

```bash
uv run --locked --project wekit-zygisk --group build ./x build
```

uv 自动管理 `wekit-zygisk/.venv`。也可将已安装的 CMake 和 Ninja 放入 `PATH` 后
直接使用 `./x build`。构建会初始化固定版本的 LSPlant 及其运行时子模块，无须拉取
LSPlant 的测试和文档子模块。

### Arch Linux

```bash
yay -Syu jdk21-openjdk rustup
rustup toolchain install stable
rustup default stable
rustup target add aarch64-linux-android
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$(sed -n 's/^ndk = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
```

### Debian 系

JDK 21 和 `rustup` 的包名可能随发行版而异。安装 JDK 21 后，还需要 Rust Android
targets：

```bash
sudo apt update
sudo apt install rustup
rustup toolchain install stable
rustup default stable
rustup target add aarch64-linux-android
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" "ndk;$(sed -n 's/^ndk = "\(.*\)"/\1/p' gradle/libs.versions.toml)"
```

### Windows

建议全文背诵[《停止用 Windows 工作！》](https://zhuanlan.fxzhihu.com/p/2024527609388627701)。

### Android SDK 路径

`./x` 按以下顺序查找 Android SDK：

1. `ANDROID_HOME`
2. `ANDROID_SDK_ROOT`
3. 仓库根目录 `local.properties` 中的 `sdk.dir`

## `./x`

仓库根目录的 `./x` 等价于 `cargo xtask`，以下文档统一使用 `./x`：

```sh
#!/usr/bin/env sh
exec cargo xtask "$@"
```

可用的一级命令：

| 命令 | 用途 |
|------|------|
| `./x configure` | 生成 Rust Android linker 配置 |
| `./x build` | 构建二合一 APK，或仅准备应用与 Zygisk native 库 |
| `./x run` | 构建并安装 APK；添加 `--zygisk` 则作为模块刷入 |
| `./x check` | 对 Rust native 库执行 `cargo check` |
| `./x clippy` | 对 Rust native 库执行 `cargo clippy -- -D warnings` |
| `./x i18n-check` | 校验 Android 国际化资源目录 |
| `./x dex-test` | 在桌面测试 DexKit 解析器 |
| `./x dex-report-diff <report...>` | 离线比较版本报告中的方法和构造函数签名 |
| `./x extensions` | 构建和管理按需下载的扩展包 |

使用 `./x --help` 或 `./x <命令> --help` 查看当前支持的参数。

### Rust Android 配置

```bash
./x configure
```

该命令使用版本目录配置的 NDK，为 ARM64 生成 linker 配置，包括：

```text
app/src/main/rust/wekit-native/.cargo/config.toml
wekit-zygisk/native/.cargo/config.toml
```

完整 APK 模式的 `./x build` 和 `./x run` 会自动执行该步骤。直接运行
`./x check` 或 `./x clippy` 前应执行一次 `./x configure`；`./x build --native-only` 自动准备配置和应用/Zygisk native 输入。

## APK

### 变体

模块通过 `entrypoint` flavor 提供两个变体：

- **standard**：包含现代 libxposed API 入口
  （`entry/lxp/*` 与 `META-INF/xposed/*`）。大多数用户应使用此变体。
- **legacy**：不包含 libxposed 入口和相关元数据，使框架回退到传统
  `de.robv.android.xposed` API（`Xp51HookEntry` 与 `assets/xposed_init`）。

两个变体使用同一个 `applicationId`，不能同时安装。

### 构建

```bash
# 两个 flavor 的 debug APK
./x build

# 两个 flavor 的 release APK
./x build --release

# 只构建一个 flavor
./x build --flavor standard
./x build --flavor legacy --release

# 只准备应用与 Zygisk native 库，跳过 Gradle
./x build --native-only
./x build --native-only --abi arm64-v8a
```

完整 APK 构建依次执行：

1. `./x configure`
2. 为所选 ABI 准备应用与 Zygisk native 库及所需输入
3. 将 native 库复制到 `app/src/main/jniLibs/<abi>/`
4. 执行对应的 Gradle `assemble` 任务，在签名前放入模块文件

Rust native 仅支持 `arm64-v8a`。
`--native-only` 会忽略 `--flavor` 和 `--release`，native 库始终使用 Cargo release
profile。

Gradle 为每个 flavor 输出一个仅包含 ARM64 native 库的 APK：

```text
app/build/outputs/apk/standard/debug/app-standard-debug.apk
app/build/outputs/apk/legacy/debug/app-legacy-debug.apk
```

release 产物位于对应的 `standard/release/` 和 `legacy/release/` 目录。

### 安装

连接 adb 设备后执行：

```bash
# 默认安装 standard debug
./x run
./x run --debug

./x run --flavor standard --release
./x run --flavor legacy
```

当前 `run` 命令执行 `installStandardDebug`、`installStandardRelease` 或对应的 legacy
Gradle 任务。它会先重新构建 ARM64 native 库。

存在多个 adb 设备时，可通过 `ANDROID_SERIAL` 选择设备：

```bash
ANDROID_SERIAL=SERIAL ./x run
```

可选：应用基准配置（Baseline Profile）：

```bash
adb shell cmd package compile -m speed-profile dev.ujhhgtg.wekit
```

### 检查 Rust native 库

```bash
./x configure
./x check
./x clippy

# 显式检查 ARM64 ABI
./x check --abi arm64-v8a
./x clippy --abi arm64-v8a
```

`check` 和 `clippy` 默认检查 `arm64-v8a`，这也是唯一可用 ABI。

## APK / Zygisk 二合一

所有 standard / legacy、debug / release APK 都同时是可刷入的 Zygisk 模块，仅支持
`arm64-v8a`。直接安装 APK 用于 Android / Xposed；把同一文件改名 `.zip` 后可通过
Magisk、KernelSU 或 APatch 的模块安装入口刷入。首次刷入后在 WebUI 选择注入目标，
并按管理器要求重启。APK 安装与 Zygisk 刷入分别更新各自部署，不能互相代替。
同一微信实例只启用一种加载方式，操作步骤见 [Zygisk 模式](../zygisk.md)。

```bash
# 两个 flavor 的二合一 debug APK
./x build

# 两个 flavor 的二合一 release APK
./x build --release

# 只构建 legacy
./x build --flavor legacy

# 仅准备应用和 Zygisk native 输入
./x build --native-only

# 额外导出注入器的未剥离符号
./x build --save-symbols

# 正常 APK 安装；默认 standard debug
./x run

# 构建同一 APK，以模块方式刷入；仅显式 --reboot 才重启
./x run --zygisk --device SERIAL --root ksu --reboot
./x run --zygisk --flavor legacy --release
```

APK 仍输出到 `app/build/outputs/apk/<standard|legacy>/<debug|release>/`。
符号归档输出到 `target/zygisk-symbols/WeKit-<commit>-arm64-v8a-symbols.zip`。
注入器始终使用 release profile；`--release` 控制 Android 代码优化。

### 打包与加载

`GenerateZygiskResourcesTask` 将 `wekit-zygisk/template/` 的安装脚本、WebUI 和
`module.prop` 作为资源加入每个 APK 变体，版本信息取自同一 APK 变体。
模块文件在 AGP 签名前进入 APK；签名完成后不得再追加文件或重新打包。
刷入用的 ZIP 与最终 APK 内容完全相同，`./x run --zygisk` 也只在推送到设备时
使用 `.zip` 文件名。CI 检查后发布这份 APK，不另行构建或发布模块 ZIP。

模块安装器直接保存原始 APK 为 `$MODPATH/module.apk`，注入器从这份 APK 读取 DEX。
安装器同时从 `lib/arm64-v8a/libwekit_zygisk.so` 提取注入器至
`$MODPATH/zygisk/arm64-v8a.so`，不另行解包 DEX。

`preAppSpecialize` 检查注入目标并保留模块目录 FD，`postAppSpecialize` 再打开
`module.apk`，将它复制为宿主私有目录中按内容摘要命名的只读 APK。注入器从同一
副本读取 DEX，创建 `InMemoryDexClassLoader`；资源、应用原生库和独立子进程也
使用这份 APK。当前加载流程不依赖 `exemptFd`。

### 安装参数

`--device` 未指定时使用 adb 默认设备；普通 APK 安装同样支持此参数。
`--root` 支持 `magisk`、`ksu`、`ap` 以及 `kernelsu` / `apatch` 别名，未指定时自动检测。
`--root` 和 `--reboot` 仅用于 `--zygisk`。
不再提供独立 `zygisk` 构建/刷入命令或按修改时间选择旧 ZIP 的选项。

原生调试可在 `wekit-zygisk/native` 中直接运行 `cargo build --target aarch64-linux-android`；
先用 `./x configure` 准备 NDK 链接配置。正常出包始终使用 `./x build`，确保两个 Rust 库
都已更新；Gradle 只负责消费这些 native 输入并打包签名。直接 Cargo 构建还需要已
初始化的 LSPlant 运行时依赖，以及可用的 CMake / Ninja。

### ART Hook 实现与验证

Zygisk 的生命周期、companion IPC、APK 加载、JNI 注册和 ART 符号解析由 Rust
实现。Java 方法 Hook 使用静态链接到 `libwekit_zygisk.so` 的 LSPlant，原生
inline hook 由 `wekit-zygisk/native/src/art/inline_hook/` 下的 Rust ARM64 后端
提供；不再使用原先自研的 ART 方法 Hook，也不再依赖 Dobby。

LSPlant 在 `postAppSpecialize` 中、进入模块 Java 代码前初始化。Kotlin
`IHookBridge` 保留回调优先级、参数和结果修改、调用原方法等接口；移除 Hook
句柄只移除对应回调，底层 LSPlant Hook 和 backup 保留到进程退出。

在仓库根目录运行原生桌面验证：

```bash
cargo test -p wekit-zygisk --lib
uv run --locked --project wekit-zygisk --group test python wekit-zygisk/native/tests/arm64_relocation.py
```

CI 还对每个产出的 APK 执行 `.github/scripts/verify-dual-apk.py --check-installer`，
检查签名、对齐、APK/模块版本一致性及模块安装行为，并测试从实际 APK 读取 DEX。
桌面验证和构建通过不代表真机 ART 或微信运行正常。支持的 Hook 目标、后端限制和
真机验证项目见 [Zygisk 原生开发说明](https://github.com/Ujhhgtg/WeKit/blob/master/wekit-zygisk/README.md)。
