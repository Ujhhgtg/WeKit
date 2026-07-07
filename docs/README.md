# WeKit

WeKit 是一个功能丰富的微信 Xposed 模块, 提供大量微信增强功能, 涵盖聊天体验、界面美化、隐私保护、自动化等多个方面。

[![CI 状态](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml/badge.svg)](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml)

## 特色功能

- 基于 JavaScript 的脚本引擎
- 贴纸包同步 (Telegram Stickers Sync)
- 通知进化 (MessagingStyle)
- Markdown 消息渲染
- 指纹支付 (基于 TEE 的安全加密)
- 自动抢红包
- 单向删除好友检测
- 发送 SILK/MP3 语音
- 聊天工具栏
- 发送卡片消息
- 原生 Hook
- 支持免 Root 框架

## 导航

- [🚀 快速开始](getting-started.md)
- [📥 安装指南](installation.md)
- [⚙️ 配置指南](configuration.md)
- [❓ 常见问题](faq.md)
- [🛠 开发指南](development.md)

## 修改内容 (相比 [上游](https://github.com/cwuom/WeKit))

- 添加 WAuxiliary 与 NewMiko 目前公开源代码中的部分功能
- 移除全部校验, 减少模块体积, 避免不必要性能开销
- 移植 UI 至 Jetpack Compose
- 添加, 修复, 增强 WAuxiliary 部分闭源功能
- 移植其他模块的一些功能
- AGP 升级至 9.X
- 反射移植至 KavaRef
- 原生库移植至 Rust
- 支持全部 4 种 ABI (arm64-v8a, armeabi-v7a, x86_64, x86)
- 修复问题
- 无须禁用「Xposed API 调用保护」
- 大量新功能

## 联系

[GitHub 仓库](https://github.com/Ujhhgtg/WeKit)

[Telegram 超级群组](https://t.me/+7j5dJ6g16B43OWVl)

## 致谢

[WeKit 上游](https://github.com/cwuom/WeKit)

[WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)

[NewMiko](https://github.com/dartcv/NewMiko/blob/archives/)

[QAuxiliary](https://github.com/cinit/QAuxiliary)

[FingerprintPay](https://github.com/eritpchy/FingerprintPay)

[WADN](https://github.com/Ujhhgtg/wauxv_deobf_new) [WAD](https://github.com/Ujhhgtg/wauxv_deobf)
