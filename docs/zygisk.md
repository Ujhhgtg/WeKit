# Zygisk 模式

WeKit 支持通过 Zygisk 注入微信, 无须安装 Xposed 框架。

## 使用条件

- 设备已 Root
- Root 管理器已启用任意 Zygisk 实现
- Root 管理器支持打开 KernelSU WebUI, 或安装了独立 WebUI 实现
- 微信版本在 WeKit 的 [支持范围](getting-started.md#宿主版本支持) 内

## 安装

1. 从 [下载渠道](installation.md#下载) 获取最新的 `wekit-zygisk` ZIP。
2. 在 Root 管理器中安装模块 ZIP。
3. 按照 Root 管理器的提示重启设备。
4. 打开 Root 管理器中的 WeKit 模块 WebUI。
5. 为需要使用 WeKit 的微信打开开关。
6. 完全结束并重新启动对应的微信。

Zygisk 版本安装后默认不注入任何应用, 必须先在 WebUI 中打开目标开关。

## 选择注入目标

WebUI 会自动列出设备上所有 Android 用户中包名以 `com.tencent.mm` 开头的应用。

一个开关对应一个 Android 用户下的一个微信包。打开后, 该微信的主进程和子进程都会
注入 WeKit。

页面首次打开时会自动扫描。安装、卸载或新增微信分身后, 点击「刷新列表」即可重新
扫描。重新扫描会保留已有目标的开关状态, 新发现的目标默认关闭。

## 更新

更新模块 ZIP 后, 必须完全结束并重新启动微信。若新版本发布时标记为更新了 Zygisk 相关逻辑, 则需重启设备。

部分 Root 管理器 (如 `KernelSU`) 支持热更新 Zygisk 模块, 此时通常不需要重启设备; 如果 Root 管理器提示需要重启, 请以其提示为准。

## 常见问题

### 安装后 WeKit 没有加载

依次确认:

1. Root 管理器中的 Zygisk 已启用。
2. WeKit 模块已启用。
3. WebUI 中正确 Android 用户和微信包名的开关已打开。
4. 打开开关或更新模块后, 微信已经完全结束并重新启动。
5. 使用的是最新 release ZIP, 而不是 debug 包或旧构建。

### WebUI 没有显示微信

1. 点击「刷新列表」。
2. 确认微信包名为 `com.tencent.mm` 或以 `com.tencent.mm` 开头。
3. 展开页面底部的「WebUI 日志」查看扫描错误。

### 更新后仍然像旧版本

完全结束微信的全部进程后重新启动。热更新只会影响之后新启动的进程。

## 日志

WebUI 页面底部的「WebUI 日志」用于排查应用扫描和开关保存问题。

遇到注入、加载或闪退问题时, 点击 WebUI 的「导出日志」。日志会保存到:

```text
/data/adb/wekit_zygisk/logcat.log
```

提交问题时, 请同时提供:

- WebUI 导出的 `logcat.log`
- 页面底部显示的 WebUI 日志
- 微信版本、Android 版本、Root 管理器及其版本
- WeKit Zygisk ZIP 的版本
- 出现问题的 Android 用户和微信包名

完整要求见 [问题反馈指南](bug-report-guide.md)。
