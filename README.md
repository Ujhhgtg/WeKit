# WeKit

适用于微信的 Xposed 模块

[功能一览](https://github.com/Ujhhgtg/WeKit/wiki/%E5%8A%9F%E8%83%BD%E4%B8%80%E8%A7%88)


## CI 状态

[![CI](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml/badge.svg)](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml)

## 修改内容 (相比上游)

- 添加 WAuxiliary 与 NewMiko 目前公开源代码中的部分功能
- 移除全部校验, 减少模块体积, 避免不必要性能开销
- 移植 UI 至 Jetpack Compose
- 添加, 修复, 增强 WAuxiliary 部分闭源功能
- 移植其他模块的一些功能
- AGP 升级至 9.X
- 反射移植至 KavaRef
- 原生库移植至 Rust
- 支持 arm64 与 x86_64
- 修复问题
- 无须禁用「Xposed API 调用保护」
- 大量新功能

## 宿主需求

- 包名: `com.tencent.mm` 或以 `com.tencent.mm` 开头的任意包名
- 版本: 非 Play: 8.0.67~8.0.69 (完美), < 8.0.67 (未测试, 不提供支持) | Play: 8.0.68 (完美), < 8.0.68 (未测试)
- 架构: arm64 或 x86_64
- Android 版本: >= 10 (SDK >= 29)
- Xposed API 版本: >= 51 (51~101)

## 特色功能

- 基于 JavaScript 的脚本引擎, [API ~~文档~~参考](app/src/main/java/dev/ujhhgtg/wekit/hooks/items/scripting_js/globals.d.ts)
- ~~实验性的基于 Kotlin Scripting 的脚本引擎 (直接与宿主与模块互通)~~ (暂未开发完成)
- 贴纸包同步 (Telegram Stickers Sync)
- 通知进化
- Markdown 消息渲染
- 指纹支付 (基于 TEE 的安全加密)
- 自动抢红包
- 原生 Hook
- 支持免 Root 框架 (未经完全测试, 但大部分功能应正常工作; 修补后的包名需要以 `com.tencent.mm` 开头才能被模块识别到并加载)

## 下载

本项目不会发布稳定版本，请从 [GitHub Actions](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml) 下载最新 CI 构建产物

Releases 中会发布“稳定的 CI”，但不保证真的稳定，且可能无法享受最新功能与修复，所以我还是建议你使用每夜版

## 开发

### 1. 克隆仓库

```bash
git clone https://github.com/Ujhhgtg/WeKit.git --recursive
```

### 2. 安装系统依赖

#### A. Debian 系

```bash
sudo apt update -y && sudo apt full-upgrade -y
sudo apt install gcc-multilib rustup
rustup toolchain install stable
rustup default stable
rustup target add x86_64-linux-android aarch64-linux-android
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$(grep '^ndk' ./gradle/libs.versions.toml | sed 's/.*= "\(.*\)"/\1/')"
```

#### B. Arch Linux

```bash
# 确保已在 /etc/pacman.conf 中启用 multilib 软件源
yay -Syu lib32-glibc rustup
rustup toolchain install stable
rustup default stable
rustup target add x86_64-linux-android aarch64-linux-android
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;$(grep '^ndk' ./gradle/libs.versions.toml | sed 's/.*= "\(.*\)"/\1/')"
```

#### C. Windows

没试过, 不提供支持, 自行解决, 解决不了的话去用 Linux 或者 WSL

### 3. 构建

构建期间会自动编译 Rust 原生库, 无须手动编译

构建产物支持 arm64 与 x86_64, 其他架构不受支持 (如需支持, 自行修改 [这里](./app/build.gradle.kts#L65-L69) 和 [这里](./app/build.gradle.kts#L301-L302))

```bash
chmod +x ./gradlew
./gradlew :app:assembleRelease
```

## Q&A

1. - Q: 我的微信突然卡得要死, 狂吃内存
   - A：尝试禁用「Xposed API 调用保护」和「隐藏应用列表」
2. - Q: XXX
   - A: [![问 DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ujhhgtg/WeKit)

## 注意

一切开发旨在学习，请勿用于非法用途

使用本项目的源代码必须遵守 GPL-3.0 许可证，详见 LICENSE

使用本模块代码或分发修改版时**必须**继续以 GPL-3.0 协议开源

## 贡献须知与建议

- 本 Fork 接受从其他模块移植/提取的功能

- 编写 UI 时请尽量使用 Jetpack Compose, 如果你还不会用, 那你真的应该去学习一下, 很适合模块 UI

- 提交 PR 前请确保可以通过编译, 功能正常, 不影响其他功能

- 请不要编写类似于这样的无用注释:

   ```kotlin
   // 初始化反射
   initReflection()
   ```

- HookItem 的 onEnable() 与任何 .hookBefore() .hookAfter() 内不要捕获未处理异常, 调用处已有相关异常处理与日志打印

- 注释建议使用英文, 不会可以用翻译或者问 AI (不是强制要求, 英语不好我会帮你翻译的)

- 声明 HookItem 尽量使用 object 声明全局单例

- 在开发与调试 UI 相关代码 (比如 '左划引用消息') 中的 onClick 等事件时, 如果反射调用微信代码, 建议使用 try-catch 无条件捕获未知异常, 因为微信疑似会静默忽略全部异常且不打印日志

- 建议使用 KavaRef 来进行反射, 注意 KavaRef 不会自动缓存除了 `createInstance()` 使用的构造函数以外的方法, 所以对于频繁调用的方法请手动缓存, [KavaRef 文档](https://highcapable.github.io/KavaRef/zh-cn/library/kavaref-core.html)

- 本 Fork 是硬分支 (Hard Fork), 接受与上游不兼容的更改

## 已知问题

- ~~每次 DEX 解析缓存清空后, WeServiceApi 第一次将会必定解析失败, 重启应用重试即可恢复正常, 原因暂时未知~~: 已修复, 原因: 解析 DEX 时不能使用其他类已解析好的 DEX, 其他场景比如 hook 内可以使用

- ~~注入的 ComposeView 中部分组件失去点按涟漪效果~~: 原因未知, 但是某次升级依赖后问题自己解决了

- 安装模块后第一次解析 DEX 时微信可能会崩溃: 临时解决方法: 重启重试或禁用 '崩溃拦截 (Native)'

- ~~聊天工具栏必须先打开菜单至少一次才能正常工作: 暂未找到解决办法, 欢迎贡献修复~~: 已修复, 副作用: 原有的工具菜单底部的分页指示器消失, 不影响其他功能

- 切换至聊天数量少的分组可能导致列表滑动状态异常: 临时解决办法: 启用 '界面美化/隐藏主页下滑「最近」页'; 暂未找到更完善的解决办法, 欢迎贡献修复

## StickersSync

### 使用方式
1. 创建目录 /storage/emulated/0/Android/data/com.tencent.mm/files/WeKit/stickers
2. 将贴纸包复制到该目录下, 每个包为一个目录, 包中包含贴纸图片 (支持 png jpg/jpeg gif webp, webp 格式将会在第一次加载时自动转换为微信支持的 png 格式)
3. (可选) 在包中添加 `.pack_icon.png` 文件以设置贴纸包图标 (不添加会导致表情面板里贴纸包图标变成滚木, 随便找个表情复制一份改个名就够了)

### 提示
- 每个包第一次加载较慢, 后续加载速度将极大提升
- 使用微信自带存储空间清理工具时, 请勿清除 `缓存 -> 其他`, 这可能导致 WeKit 数据被清空

### 目录结构

```none
/storage/emulated/0/Android/data/com.tencent.mm/files/WeKit/stickers
├─ pack1/
│  ├─ sticker1.png
│  ├─ sticker2.png
│  └─ .pack_icon.png
├─ pack2/
│  └─ ...
└─ pack3/
   └─ ...
```

## 致谢

[WeKit 上游](https://github.com/cwuom/WeKit)

[WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)

[NewMiko](https://github.com/dartcv/NewMiko/blob/archives/)

[QAuxiliary](https://github.com/cinit/QAuxiliary)

[FingerprintPay](https://github.com/eritpchy/FingerprintPay)
