# WeKit

WeKit 是一个功能丰富的微信增强模块, 支持通过 Xposed 框架或 Zygisk 模块加载, 提供大量微信增强功能。

[![CI 状态](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml/badge.svg)](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml)

## 导航

- [🚀 快速开始](getting-started.md)
- [📥 安装指南](installation.md)
- [🧩 Zygisk 模式](zygisk.md)
- [⚙️ 配置指南](configuration.md)
- [❓ 常见问题](faq.md)
- [🛠 开发指南](development/README.md)

## 修改内容 (相比 [上游](https://github.com/cwuom/WeKit))

- 添加 Auxiliary 与 NewMiko 目前公开源代码中的部分功能
- 移除全部校验, 减少模块体积, 避免不必要性能开销
- 移植 UI 至 Jetpack Compose
- 添加, 修复, 增强若干闭源模块部分功能
- 移植其他模块的一些功能
- AGP 升级至 9.X
- 反射移植至 reflekt
- 原生库移植至 Rust
- 修复问题
- 无须禁用「Xposed API 调用保护」
- 大量新功能

## 联系

[GitHub 仓库](https://github.com/Ujhhgtg/WeKit)

[Telegram 超级群组](https://t.me/+7j5dJ6g16B43OWVl)

## 致谢

[WeKit 上游](https://github.com/cwuom/WeKit)

[WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)

[NewMiko](https://github.com/Ujhhgtg/NewMiko/)

[QAuxiliary](https://github.com/cinit/QAuxiliary)

[FingerprintPay](https://github.com/eritpchy/FingerprintPay)

[WADN](https://github.com/Ujhhgtg/wauxv_deobf_new) [WAD](https://github.com/Ujhhgtg/wauxv_deobf)

[FunBox](https://github.com/Ujhhgtg/funbox_deobf)

[LSPlant](https://github.com/LSPosed/LSPlant)
