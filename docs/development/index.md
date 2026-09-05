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
| adb | 安装 APK 或刷入 Zygisk ZIP 时需要 |

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
| `./x build` | 构建 APK，或仅构建 Rust native 库 |
| `./x run` | 通过 Gradle 安装 APK |
| `./x check` | 对 Rust native 库执行 `cargo check` |
| `./x clippy` | 对 Rust native 库执行 `cargo clippy -- -D warnings` |
| `./x i18n-check` | 校验 Android 国际化资源目录 |
| `./x dex-test` | 在桌面测试 DexKit 解析器 |
| `./x dex-report-diff <report...>` | 离线比较版本报告中的方法和构造函数签名 |
| `./x extensions` | 构建和管理按需下载的扩展包 |
| `./x cloudflared-build` | 构建嵌入式 cloudflared bridge |

使用 `./x --help` 或 `./x <命令> --help` 查看当前支持的参数。

### Rust Android 配置

```bash
./x configure
```

该命令使用版本目录配置的 NDK，为 ARM64 生成 linker 配置，包括：

```text
app/src/main/rust/wekit-native/.cargo/config.toml
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

# 只构建 Rust native 库，跳过 Gradle
./x configure
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

模块安装器直接保存原始 APK 为 `$MODPATH/module.apk`，注入器从这份 APK 读取 DEX。
无需嵌套 APK、独立 `classes*.dex` 文件或 `dex.list`。宿主私有目录保留按 APK 内容摘要
区分的只读 APK，供资源、原生库和独立子进程使用。

`--device` 未指定时使用 adb 默认设备；普通 APK 安装同样支持此参数。
`--root` 支持 `magisk`、`ksu`、`ap` 以及 `kernelsu` / `apatch` 别名，未指定时自动检测。
`--root` 和 `--reboot` 仅用于 `--zygisk`。
不再提供独立 `zygisk` 构建/刷入命令或按修改时间选择旧 ZIP 的选项。

原生调试可在 `wekit-zygisk/native` 中直接运行 `cargo build --target aarch64-linux-android`；
先用 `./x configure` 准备 NDK 链接配置。正常出包始终使用 `./x build`，确保两个 Rust 库
都已更新；Gradle 只负责消费这些 native 输入并打包签名。
