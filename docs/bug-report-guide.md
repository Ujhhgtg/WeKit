# 问题反馈指南

> **⚠️ 在反馈任何问题前, 请先阅读 [提问的智慧](https://github.com/ryanhanwu/How-To-Ask-Questions-The-Smart-Way/blob/main/README-zh_CN.md)。**

## 反馈渠道

**唯一的反馈渠道是 [GitHub Issues](https://github.com/Ujhhgtg/WeKit/issues)。**

请勿通过以下渠道反馈问题, 此类反馈不会被处理:

- Telegram 讨论组
- 开发者的 Telegram 私聊
- 其他任何非 GitHub Issues 的渠道

## 提交 Issue 前的准备

在提交 Issue 前, 请先确认:

1. **你的问题是否已经有人提过？** 搜索 [已有 Issues](https://github.com/Ujhhgtg/WeKit/issues) 和 [常见问题](faq.md) 避免重复提交。
2. **你的模块版本是否最新？** 请尝试升级到最新的 [CI 构建](installation.md#下载) 再确认问题是否仍然存在。
3. **是否排除了其他模块干扰？** Xposed 模式在 LSPosed 中暂时仅勾选 WeKit;
   Zygisk 模式确认 WebUI 中只启用了需要测试的微信目标。完全结束并重新启动微信后
   再重试。
4. **是否完整填写了 Issue 模板？** GitHub Issue 模板中的每一项都必须填写, 不要留空或删改。

## 提交 Issue 的必需内容

提交 Issue 时, **必须完整提供以下内容**。未完整提交的 Issue 将会被**无条件关闭**, 重复违反者可能会被**拉黑**。

> **一个 Issue 只反馈一个问题**, 多个问题请分别提交。

### 1. 加载框架日志

根据实际加载方式提供日志:

- **LSPosed**: LSPosed -> 底栏「设置」->「禁用详细日志」关闭 -> 底栏 「日志」-> 右上角菜单「保存」-> 上传压缩包
- **Zygisk**: 在 WeKit 模块 WebUI 点击「导出日志」, 上传 `/data/adb/wekit_zygisk/logcat.log`

### 2. 模块调试信息

若模块可正常加载, 提供「调试/复制调试信息」的结果:

模块设置 -> 「调试/复制调试信息」 -> 粘贴至 Issue

### 3. 崩溃日志

若模块可正常加载且要提交的问题是**闪退/崩溃**:

1. 开启「模块设置 -> 调试 -> 崩溃拦截」
2. 触发闪退
3. 上传 `<模块数据目录>/crashes/` 中对应的崩溃日志

### 4. 模块运行日志

若模块加载成功, 上传模块运行日志:

```text
/sdcard/Android/data/<宿主包名>/WeKit/logs/
```

## 高质量的 Issue 应当包含

上述内容是**必需的基础**。除此之外, 以下信息能极大提高问题被定位和修复的效率:

- **问题描述**: 清晰描述期望行为与实际行为
- **复现步骤**: 从打开微信开始, 每一步做什么
- **环境信息**: 微信版本、模块版本、Android 版本、加载方式、LSPosed 或 Root
  管理器版本、是否 Root
- **相关截图/录屏**: 能直观展示问题的画面
- **是否稳定复现**: 每次必现还是偶尔出现

## Issue 处理流程

1. 提交 Issue 后, 维护者会进行标记和分类
2. 若缺少必需内容, Issue 将被标记并等待补充, 超时未补充将关闭
3. 修复完成后 Issue 将被关闭
4. **请勿在已关闭的 Issue 下继续讨论**——新问题请开新 Issue
5. **请勿在 Telegram 等渠道催促进度**——维护者会在有空时处理
