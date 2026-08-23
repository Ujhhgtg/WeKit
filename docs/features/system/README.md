# 系统与隐私

系统功能、隐私保护、环境伪装等。

## 功能列表

- [禁止微信检测 Xposed](prevent-xposed-detection.md) — 防止微信检测 Xposed 框架
- [禁用微信热更新](disable-host-hot-updates.md) — 禁止微信热更新, 避免被强制更新到不兼容版本
- [禁用存储空间不足检测](disable-low-available-storage-detection.md) — 移除 Root 隐藏模块导致的可用空间检测错误限制
- [阻止微信清理模块数据](prevent-module-data-deletion.md) — 阻止微信存储空间清理功能删除模块数据
- [虚拟定位](fake-location.md) — 预设微信获取到的经纬度
- [环境伪装](spoof-environment.md) — 伪装未启用 ADB/开发者选项/VPN, 协助通过环境安全检测
- [省电模式](power-saver.md) — 通过优化措施减少微信耗电
- [强制平板模式](force-tablet-mode.md) — 让微信识别当前设备为平板
- [预见性返回动画](predictive-back-gestures.md) — 为微信界面强制启用 Android 预见性返回动画 [需 SDK >= 33]
- [去除文章广告](remove-article-ads.md) — 清除微信内置文章中的广告数据
- [禁用 WebView 安全警告](disable-webview-safety-warnings.md) — 关闭 WebView 的网址安全警告
- [禁用「转发截图」提示](disable-share-screenshot-toast.md) — 关闭截图后分享提示
- [移除二维码扫描限制](remove-qr-code-scan-limit.md) — 移除长按图片和相册扫描二维码的限制
- [禁止屏幕高亮度](disable-high-brightness.md) — 阻止微信将屏幕亮度调得过高
- [隐藏模块应用](hide-module-from-app-list.md) — 防止微信查询到模块安装状态 [实验性]
- [移除分享签名校验](remove-external-app-sharing-signature-verify.md) — 移除第三方应用分享到微信的签名校验
- [灰度测试管理器](feature-flag-manager.md) — 查看和覆盖微信灰度 Feature Flag
- [修改运动步数](modify-sports-step-count.md) — 修改微信运动步数
- [运动排行榜自动点赞](auto-like-sports-rank.md) — 收到新排行榜后自动为符合条件的好友点赞
- [自动批准设备登录](auto-approve-device-login.md) — 自动勾选选项并确认多设备登录请求
- [强制启用 WebView 菜单](enable-webview-features.md) — 强制显示 WebView 页面右上角菜单
- [恢复旧版「我」界面卡包](use-legacy-wallet-view-in-me-page.md) — 使用旧版「卡包」代替「小店与卡包」
- [链接跳转系统打开方式](link-external-app-jump.md) — 打开链接时弹窗选择系统应用打开
- [二维码扫描记录](qr-code-record.md) — 记录扫描过的二维码 URL
- [清理缓存垃圾](auto-clean-cache.md) — 自动或手动清理微信缓存, 每 30 分钟自动清理一次
- [API 服务器](api-server.md) — 启动 MCP 与 REST API 服务器, 让人类与 AI 能够访问微信能力
