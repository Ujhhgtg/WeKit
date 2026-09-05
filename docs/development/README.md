# 开发指南

本页介绍 WeKit 的开发环境、构建命令和产物。专题说明请参阅：

- [DexKit 解析器测试](linux-dex-test.md)
- [国际化开发指南](i18n.md)

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
| `./x zygisk` | 配置、构建、打包、安装或清理 Zygisk 模块 |

使用 `./x --help` 或 `./x <命令> --help` 查看当前支持的参数。

### Rust Android 配置

```bash
./x configure
```

该命令从已安装的 NDK 中选择版本号最高且主版本不低于 29 的版本，为 ARM64
生成：

```text
app/src/main/rust/wekit-native/.cargo/config.toml
```

完整 APK 模式的 `./x build` 和 `./x run` 会自动执行该步骤。直接运行
`./x build --native-only`、`./x check` 或 `./x clippy` 前，应先执行一次
`./x configure`。

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
2. 为所选 ABI 编译 release 模式的 `libwekit_native.so`
3. 将 native 库复制到 `app/src/main/jniLibs/<abi>/`
4. 执行对应的 Gradle `assemble` 任务

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

## Zygisk 模块

Zygisk 模块使用 standard APK payload，仅支持 `arm64-v8a`。

### 构建 ZIP

```bash
# 默认：debug APK、ARM64 release loader 和 release ZIP
./x zygisk build

# release APK + release Zygisk
./x zygisk build --apk-release --release --force

# release APK + debug Zygisk
./x zygisk build --apk-release --debug

# 复用已有 standard APK 输出
./x zygisk build --skip-apk-build

# 显式指定 payload APK
./x zygisk build --skip-apk-build \
  --apk app/build/outputs/apk/standard/debug/app-standard-debug.apk

# 同时保存未剥离 native 符号
./x zygisk build --save-symbols
```

常用参数：

| 参数 | 说明 |
|------|------|
| `--apk-debug` | 构建或自动选择 debug APK，默认行为 |
| `--apk-release` | 构建或自动选择 release APK |
| `--debug` | 使用 debug Zygisk loader 和 debug ZIP |
| `--release` | 使用优化后的 release Zygisk loader 和 release ZIP，默认行为 |
| `--force` | 构建前删除对应 native 输出和未剥离符号 |
| `--ndk <VERSION>` | 覆盖 `gradle/libs.versions.toml` 中的 Zygisk NDK 版本 |
| `--skip-apk-build` | 不执行 Gradle，按 APK profile 复用现有 APK |
| `--apk <PATH>` | 指定 payload APK |
| `--save-symbols` | 额外生成未剥离 native 符号 ZIP |

模块 ZIP 输出到：

```text
wekit-zygisk/release/WeKit-<versionCode>-git+<commit>-<debug|release>.zip
```

使用 `--save-symbols` 时，符号包输出到：

```text
wekit-zygisk/symbols/WeKit-<versionCode>-git+<commit>-<debug|release>-symbols.zip
```

### Native 构建与清理

```bash
# 默认构建 release loader；使用 --debug 构建 debug loader
./x zygisk native
./x zygisk native --debug --force --abi arm64-v8a

# 清理两种 profile 的 loader 库和未剥离符号
./x zygisk clean

# 只清理指定 profile/ABI
./x zygisk clean --profile release --abi arm64-v8a
```

`zygisk native` 默认处理 `arm64-v8a`，并使用 release profile。`--abi` 也接受
`arm64`、`aarch64` 等别名。loader 由 Cargo 交叉编译，并使用 NDK 提供的 linker
和 `llvm-strip`。

### 安装到设备

```bash
# 默认构建 release ZIP，安装到指定设备的 KernelSU，然后重启
./x zygisk flash --device SERIAL --root ksu --reboot

# 安装 release 目录中最新的 release ZIP，不重新构建
./x zygisk flash --skip-build

# 安装最新的 debug ZIP
./x zygisk flash --debug --skip-build
```

`--device` 未指定时使用 adb 默认设备。`--root` 支持 `magisk`、`ksu` 和 `ap`，也接受
`kernelsu`、`apatch` 别名；未指定时由安装脚本自动检测。`--reboot` 仅在安装成功后
重启设备。

`--skip-build` 按 Zygisk profile 选择 release 或 debug ZIP，再使用
`wekit-zygisk/release/` 中修改时间最新的对应 ZIP。
