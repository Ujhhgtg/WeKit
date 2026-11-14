# 安装指南

## 下载

本项目不会发布稳定版本, 请从以下渠道下载最新 CI 构建产物 (每夜版):

- [GitHub CI Release](https://github.com/Ujhhgtg/WeKit/releases/tag/CI)
- [GitHub Actions](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml)
- [Telegram 超级群组](https://t.me/+7j5dJ6g16B43OWVl)

**Xposed 和 Zygisk 共用同一份 APK**, 不再单独发布 `wekit-zygisk` 模块 ZIP。所有
debug / release APK 都支持两种安装方式, 仅支持 ARM64
(`arm64-v8a`)。

Xposed 模式要求框架支持 libxposed API 101 或更高版本。

从 GitHub Actions 下载 `wekit-apk` 产物时, 先解压外层归档并取出所需 APK。
Xposed 模式直接安装 APK; Zygisk 模式只将 APK 的扩展名从 `.apk` 改为 `.zip`,
例如 `app-release.apk` → `app-release.zip`, 再交给 Root 管理器。
不要直接刷入 Actions 的外层归档, 也不要解压重打包 APK。

## 安装

同一个微信实例只启用一种 WeKit 加载方式。切换方式前先关闭原方式的作用域或
WebUI 开关, 具体见 [切换加载方式](zygisk.md#切换加载方式)。

### Root + Xposed (以 [LSPosed](https://github.com/JingMatrix/Vector) 为例)

1. 下载模块 APK
2. 安装模块
3. 在 LSPosed 管理器中启用模块, 并勾选微信
4. 重启微信

### 免 Root + Xposed

所用修补框架必须支持 libxposed API 101 或更高版本；仅支持传统 Xposed API 的框架无法加载 WeKit。
请按照兼容框架的说明修补微信并启用模块。

### Root + Zygisk

1. 下载上述 WeKit APK, 将扩展名从 `.apk` 改为 `.zip`
2. 确保你的 Root 管理器中已启用任意 Zygisk 实现

    对于非 Magisk 用户, 请确保安装了任意 Zygisk 模块

    对于 Magisk 用户, 请确保在 Magisk 设置中启用了 `Zygisk`, *或* 安装了任意 Zygisk 模块

    如未安装 Zygisk 模块, 请安装以下三个中任意一个: [Zygisk Next](https://github.com/Dr-TSNG/ZygiskNext), [ReZygisk](https://github.com/PerformanC/ReZygisk/), [NeoZygisk](https://github.com/JingMatrix/NeoZygisk)

3. 在 Root 管理器的模块安装入口刷入改名后的 ZIP, 无须另行安装 WeKit 应用
4. 按 Root 管理器提示重启设备
5. 打开模块 WebUI, 为对应微信实例打开开关

    对于 Magisk 用户, 请使用第三方 WebUI 实现, 例如 [KsuWebUIStandalone](https://github.com/KOWX712/KsuWebUIStandalone)

6. 完全结束并重新启动微信

详细操作和常见问题见 [Zygisk 模式](zygisk.md)。

## 更新

- **Xposed**: 更新已安装或嵌入修补包中的 WeKit APK, 然后完全结束并重新启动微信。
- **Zygisk**: 将新版 APK 改名为 `.zip` 后, 在 Root 管理器中覆盖刷入, 按管理器提示
  重启。注入目标开关会保留, 详细重启要求见 [Zygisk 更新](zygisk.md#更新)。

直接安装 APK 与刷入模块分别更新各自部署。只更新 WeKit 应用不会更新 Zygisk
模块, 只刷入模块也不会更新已安装或嵌入修补包中的 APK。

## 修复微信热更新导致的模块不加载

如果模块不加载且日志没有报错, 请尝试采取以下步骤。

### Root + Xposed

1. 授予模块 Root 权限
2. 打开模块应用
3. 右上角三个点菜单 -> 「修复模块加载」 -> 确定
4. 重启微信

### 免 Root + Xposed (需「注入文件提供器」; 以 MT 管理器为例)

1. 启动 MT 管理器
2. 左上角菜单 -> 右上角三个点菜单 -> 添加本地存储
3. 左上角菜单 -> 微信 -> 使用此文件夹 -> 允许
4. 打开添加的微信, 打开 `./data/`, 删除所有 `tinker` 开头文件夹里的内容
5. 对上述每个文件夹, 依次执行: 长按文件夹 -> 属性 -> 权限 -> 修改 -> 取消打钩全部, 勾选两个「同时应用到所有...」 -> 确定
    - 若打开添加的微信显示的目录为空, 启动微信并重试

## 下一步

- [配置指南](configuration.md) — 了解如何使用和配置功能
