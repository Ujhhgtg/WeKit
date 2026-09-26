# Zygisk 模式

WeKit 支持通过 Zygisk 注入微信, 无须安装 Xposed 框架或 WeKit 应用。
Xposed APK 与 Zygisk 模块已合并为同一文件, 下载 APK 后只需改扩展名即可刷入。

## 使用条件

- 设备已 Root
- 设备和微信使用 ARM64 (`arm64-v8a`), Android 9 或更新版本
- Root 管理器已启用任意 Zygisk 实现
- Root 管理器支持打开 KernelSU WebUI, 或安装了独立 WebUI 实现
- 微信版本在 WeKit 的 [支持范围](getting-started.md#宿主版本支持) 内

## 安装

1. 从 [下载渠道](installation.md#下载) 获取最新的 WeKit APK; 默认选择 standard,
   legacy 也支持 Zygisk。
2. 将 APK 的扩展名从 `.apk` 改为 `.zip`, 在 Magisk、KernelSU 或 APatch 的模块
   安装入口刷入。安装器仅支持在 Root 管理器内安装, 不支持 Recovery 刷入。
3. 按照 Root 管理器的提示重启设备。
4. 打开 Root 管理器中的 WeKit 模块 WebUI。
5. 为需要使用 WeKit 的微信打开开关。
6. 完全结束并重新启动对应的微信。

APK 改名即可, 不要解压重打包。若下载的是 GitHub Actions 的 `wekit-apk` 外层
归档, 先解压取出 APK, 再改名刷入。

全新安装默认不注入任何应用, 必须先在 WebUI 中打开目标开关。覆盖更新会保留
已有开关。同一微信实例只启用一种 WeKit 加载方式, 参见 [切换加载方式](#切换加载方式)。

## 选择注入目标

WebUI 会自动列出设备上所有 Android 用户中包名以 `com.tencent.mm` 开头的应用。

一个开关对应一个 Android 用户下的一个微信包。打开后, 该微信的主进程和子进程都会
注入 WeKit。

页面首次打开时会自动扫描。安装、卸载或新增微信分身后, 点击「刷新列表」即可重新
扫描。重新扫描会保留已有目标的开关状态, 新发现的目标默认关闭。

开关变更只影响之后启动的进程。关闭目标后, 也需要完全结束并重新启动对应微信
才能停止加载 WeKit。

## 更新

1. 下载新版 WeKit APK, 将扩展名改为 `.zip`。
2. 在 Root 管理器中覆盖刷入 WeKit 模块, 保留已有注入目标开关。
3. 查看安装器对 Zygisk 原生注入器 (`libwekit_zygisk.so`) 的比较结果:
   - so 内容有变化: 提示需要重启设备, 加载新注入器。
   - so 内容未变、仅 APK 更新: 提示无须因本次 APK 更新重启设备;
     更新激活后, 完全结束微信的全部进程并重新启动即可。
4. 若 Root 管理器仍将更新保留为待重启状态, 按管理器要求重启设备。

安装器逐字节比较新 so 与当前模块目录中的 so, 不使用版本号、文件大小或待重启
的更新包作为依据。只有当前模块已启用且 so 未变时才请求热安装; 首次安装、
模块未启用或无法完成比较时均提示重启设备。热安装需要 Root 管理器支持,
不会替换运行中进程的代码; APK 和原生注入器仍按管理器的模块激活流程生效。

直接安装新版 WeKit APK 只更新 Android 应用, 不会更新已刷入的 Zygisk 模块。

## 切换加载方式

同一个微信实例不要同时通过 Xposed 和 Zygisk 加载 WeKit。

- **切换到 Zygisk**: 先在 Xposed 框架中取消该微信实例的 WeKit 作用域;
  若使用嵌入模块的免 Root 修补包, 先移除其中的 WeKit。再按上述步骤刷入模块并
  打开 WebUI 目标开关。
- **切换到 Xposed**: 先关闭 WebUI 中对应微信实例的开关, 再安装 WeKit APK 并在
  Xposed 框架中启用对应作用域。

切换后完全结束并重新启动微信; 刷入模块时仍按 Root 管理器要求重启设备。

## 停用与卸载

停用某个微信实例时, 关闭 WebUI 对应开关并完全结束、重新启动该微信。
卸载整个 Zygisk 模块时, 使用 Root 管理器的卸载入口并按提示重启。

覆盖更新保留注入目标配置; 卸载模块会删除该配置, 重新安装后需重新选择目标。
模块卸载脚本不会清理微信应用数据或 WeKit 功能配置。

## 常见问题

### 安装后 WeKit 没有加载

依次确认:

1. Root 管理器中的 Zygisk 已启用。
2. WeKit 模块已启用。
3. WebUI 中正确 Android 用户和微信包名的开关已打开。
4. 打开开关或更新模块后, 微信已经完全结束并重新启动。
5. 刷入的是最新二合一 APK 改名后的 ZIP, 且更新已按 Root 管理器要求激活。
6. 同一微信实例没有同时通过 Xposed 加载 WeKit。

standard / legacy、debug / release 均支持 Zygisk, debug 构建本身不是不加载的原因。

### 提示模块包无效或平台不支持

确认刷入的是 WeKit APK 改扩展名得到的 ZIP, 不是 Actions 的外层产物归档或重新
压缩的文件。当前仅支持 ARM64, 且必须通过 Root 管理器安装; 文件损坏时重新下载。

### WebUI 没有显示微信

1. 点击「刷新列表」。
2. 确认微信包名为 `com.tencent.mm` 或以 `com.tencent.mm` 开头。
3. 展开页面底部的「WebUI 日志」查看扫描错误。

### 更新后仍然像旧版本

确认更新操作是在 Root 管理器中刷入模块, 而非只安装了新版 WeKit 应用。
若管理器仍提示待重启, 先重启设备; 已激活更新时, 完全结束微信的全部进程后
重新启动。热更新只会影响之后新启动的进程。

## 日志

WebUI 页面底部的「WebUI 日志」用于排查应用扫描和开关保存问题。

遇到注入、加载或闪退问题时, 点击 WebUI 的「导出日志」。日志会保存到:

```text
/data/adb/wekit_zygisk/logcat.log
```

提交问题时, 请同时提供:

- WebUI 导出的 `logcat.log`
- 页面底部显示的 WebUI 日志
- 微信版本、Android 版本、Root 管理器及其版本、Zygisk 实现及其版本
- 实际刷入的 WeKit APK 文件名、版本和变体 (standard / legacy、debug / release)
- 出现问题的 Android 用户和微信包名

完整要求见 [问题反馈指南](bug-report-guide.md)。
