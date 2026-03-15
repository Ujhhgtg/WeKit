# WeKit

适用于微信的 Xposed 模块

## CI 状态

[![CI](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml/badge.svg)](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml)

## 修改内容 (相比上游)

- 添加 WAuxiliary 与 NewMiko 目前公开源代码中的部分功能
- 移除全部校验, 减少模块体积, 避免不必要性能开销 (注意: 签名无论 Release/Debug 均使用默认 Debug 签名, 请勿从不安全来源安装模块)
- 移植 UI 至 Jetpack Compose
- 添加, 修复, 增强 WAuxiliary 部分闭源功能
- 移植其他模块的一些功能
- AGP 升级至 9.X
- 反射移植至 KavaRef
- 原生库移植至 Rust
- 支持 aarch64 与 x86_64
- 修复问题
- 大量新功能

## 宿主版本

- 非 Play: 8.0.67~8.0.69 (完美), < 8.0.67 (未测试, 不保证提供支持)
- Play: 未测试

## 特色功能

- 基于 JavaScript 的脚本引擎, [API ~~文档~~参考](app/src/main/java/moe/ouom/wekit/hooks/items/scripting_js/globals.d.ts)
- ~~实验性的基于 Kotlin Scripting 的脚本引擎 (直接与宿主与模块互通)~~ (暂未开发完成)
- 贴纸包同步 (Telegram Stickers Sync)
- 通知进化
- Markdown 消息渲染
- 自动抢红包

## 下载

本项目不会发布稳定版本，请从 [GitHub Actions](https://github.com/Ujhhgtg/WeKit/actions/workflows/ci.yml) 下载最新 CI 构建产物

Releases 中会发布“稳定的 CI”，但不保证真的稳定，且可能无法享受最新功能与修复，所以我还是建议你使用每夜版

## Q&A

1. - Q: 我的微信突然卡得要死, 狂吃内存
   - A：禁用 'Xposed API 调用保护' 和 '隐藏应用列表' (我原以为删了动态加载就可以开着这俩，调试的时候被坑死了)
2. - Q: XXX
   - A: 找 [DeepWiki](https://deepwiki.com/Ujhhgtg/WeKit)

## 注意

一切开发旨在学习，请勿用于非法用途

使用本项目的源代码必须遵守 GPL-3.0 许可证，详见 LICENSE

使用本模块代码或分发修改版时**必须**继续以 GPL-3.0 协议开源

## 贡献须知与建议

* 本 Fork 接受从其他模块移植/提取的功能

* 编写 UI 时请尽量使用 Jetpack Compose, 如果你还不会用, 那你真的应该去学习一下, 很适合模块 UI

* 提交 PR 前请确保可以通过编译, 功能正常, 不影响其他功能

* 请不要编写类似于这样的无用注释:

   ```kotlin
   // 初始化反射
   initReflection()
   ```

* HookItem 的 onLoad() 内请不要用 try-catch 块包围整个方法来捕获未知异常, onLoad() 的调用处已有相关异常处理与日志打印

* 注释建议使用英文, 不会可以用翻译或者问 AI (不是强制要求, 英语不好我会帮你翻译的)

* 声明 HookItem 尽量使用 object 声明全局单例

* 在开发与调试 UI 相关代码 (比如 '左划引用消息') 中的 onClick 等事件时, 如果反射调用微信代码, 建议使用 try-catch 无条件捕获未知异常, 因为微信疑似会静默忽略全部异常且不打印日志

* 建议使用 KavaRef 来进行反射, 注意 KavaRef 不会自动缓存构造函数以外的成员方法, 所以对于频繁调用的方法请手动缓存, [KavaRef 文档](https://highcapable.github.io/KavaRef/zh-cn/library/kavaref-core.html)

* 本 Fork 是硬分支 (Hard Fork), 接受与上游不兼容的更改

## 已知问题

- ~~每次 DEX 解析缓存清空后, WeServiceApi 第一次将会必定解析失败, 重启应用重试即可恢复正常, 原因暂时未知~~: 已修复, 原因: 解析 DEX 时不能使用其他类已解析好的 DEX, 其他场景比如 hook 内可以使用

- ~~注入的 ComposeView 中部分组件失去点按涟漪效果~~: 原因未知, 但是某次升级依赖后问题自己解决了

- 安装模块后第一次解析 DEX 时微信可能会崩溃: 临时解决方法: 重启重试或禁用 '崩溃拦截 (Native)'

- ~~聊天工具栏必须先打开菜单至少一次才能正常工作: 暂未找到解决办法, 欢迎贡献修复~~: 已修复, 副作用: 原有的工具菜单底部的分页指示器消失, 不影响其他功能

- 切换至聊天数量少的分组可能导致列表滑动状态异常: 临时解决办法: 切回其他分组并向下滑动; 暂未找到解决办法, 欢迎贡献修复

<details>

<summary>功能一览</summary>

## 功能一览

### 聊天

* 修改文本显示内容
* 发送自定义卡片消息
* 引用消息直达
* 显示消息发送精确时间
* 显示消息详情
* 禁止上传输入状态
* 禁用消息折叠
* 自动发送原图
* 自动接收原图
* 自动语音转文字
* 表情游戏控制
* 语音存本地
* 表情存本地
* 链接跳转系统打开方式
* 防撤回
* 净化消息菜单项
* 左划引用消息 (不影响消息多选)
* 贴纸包同步 (Stickers Sync)

### 联系人与群组

* 显示原始微信 ID
* 显示群成员头衔身份
* 禁用置顶聊天折叠
* 移除消息批量转发限制

### 红包与支付

* 允许领取私聊发出的红包
* 修改转账时显示的余额
* 自动抢红包

### 朋友圈

* 反删除
* 去广告
* 伪集赞

### 系统与隐私

* 文章去广告
* 强制启用 WebView 右上角三个点菜单
* 强制平板模式
* 禁止屏幕高亮度
* 禁止检测 Xposed
* 禁用 WebView 安全警告
* 禁止热更新
* 移除二维码扫描限制
* 移除第三方应用分享签名校验
* 自动批准设备登录
* 自动清理缓存垃圾
* 管理灰度测试

### 通知

* 通知进化

### 界面美化

* 主屏幕添加浮动操作按钮
* 底栏替换为 Material Design 样式

### 小程序

* 去开屏广告
* 去视频广告

### 视频号

* 复制视频下载链接
* 禁用评论长度限制

### 个人资料

* 上传透明头像
* 清空资料信息
* 自定义昵称

### 脚本

* JavaScript 脚本引擎

</details>

## 致谢

[WeKit 上游](https://github.com/cwuom/WeKit)

[WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)

[NewMiko](https://github.com/dartcv/NewMiko/blob/archives/)
