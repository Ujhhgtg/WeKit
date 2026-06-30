# 聊天功能
- [AI 回复](ai-reply.md) — 在聊天工具栏中添加 AI 回复功能, 支持自定义 OpenAI 兼容 API, 自动获取聊天上下文并智能回复

聊天功能提供了消息增强、输入栏优化、群管理等一系列聊天体验提升。

## 功能列表

- [阻止消息撤回 3](anti-message-recall-3.md) — 阻止对方撤回消息, 同时插入撤回提示
- [阻止消息撤回 1](anti-message-recall-1.md) — 阻止对方撤回消息, 无撤回提示
- [聊天输入栏增强](chat-input-bar-enhancements.md) — 长按发送/加号打开菜单 (发送 XML 卡片消息、@所有人) ; 长按语音按钮选择 AMR/MP3 文件发送为语音
- [聊天工具栏](chat-toolbar.md) — 在输入框上方将加号菜单中的功能以芯片形式展示, 方便快速使用
- [自动查看原图](auto-view-original-media.md) — 打开图片和视频时自动点击查看原图
- [自动语音转文字](auto-speech-to-text.md) — 自动将语音消息转为文字显示
- [显示消息时间](display-message-send-time.md) — 在消息旁显示精确发送时间, 可点击格式
- [对话分组](conversation-grouping.md) — 在主页顶部添加「全部/未读/群聊/好友/公众号」Tab 栏, 快速筛选对话
- [合并消息显示](merge-messages-into-groups.md) — 将同一发送者的连续消息合并为一组, Telegram 风格
- [Markdown 渲染](markdown-rendering.md) — 在聊天中渲染 Markdown 格式消息, 支持表格、任务列表、删除线等
- [左划引用消息](swipe-to-quote.md) — 左划消息快速引用
- [引用消息直达](quoted-message-direct-jump.md) — 点击被引用消息跳转到原文位置
- [快捷清除引用](quick-remove-quote.md) — 退格时输入框为空则自动清除引用
- [显隐全部对话](toggle-all-conversations-visibility.md) — 首页右上角菜单一键隐藏/显示所有对话
- [清空未读](mark-all-as-read.md) — 首页右上角菜单一键标记全部已读
- [显示群成员身份](display-group-member-roles.md) — 群聊中显示群主、管理员、成员身份标识
- [显示群成员实名尾字](display-group-member-real-names-last-char.md) — 通过转账接口获取群成员实名尾字
- [爆破群成员实名首字](exploit-group-member-real-names-first-char.md) — 通过转账校验接口获取群成员实名首字
- [禁用置顶聊天折叠](disable-pinned-chats-collapsing.md) — 隐藏「折叠置顶聊天」选项, 需重启微信 2 次
- [禁用消息折叠](disable-message-collapsing.md) — 阻止聊天消息被折叠
- [禁止上传正在输入状态](disable-typing-status-uploading.md) — 关闭「对方正在输入」状态上传
- [移除通话时聊天限制](remove-limits-during-calls.md) — 通话中也可以正常聊天
- [移除媒体发送数量限制](remove-send-media-count-limit.md) — 解除发送图片/视频的数量限制
- [移除消息菜单项](remove-chat-message-context-menu-items.md) — 从长按菜单中移除指定的菜单项
- [语音保存到本地](save-voices-to-local-storage.md) — 在语音消息菜单添加保存按钮
- [贴纸保存到本地](save-stickers-to-local-storage.md) — 在贴纸消息菜单添加保存按钮
- [转发收藏语音](forward-favorite-voices.md) — 允许从收藏中转发语音消息
- [隐藏发送消息头像](hide-outgoing-messages-avatar.md) — 隐藏自己发出消息的头像, Telegram 风格
- [自动启用发送原图](auto-enable-no-compress-on-send-media.md) — 发送媒体时自动勾选原图选项
- [显示消息详情](display-message-details.md) — 查看消息的详细信息
- [修改文本消息显示](modify-text-message-display.md) — 修改本地消息的显示内容
- [消息复读](repeat-messages.md) — 一键复读收到的消息
- [表情游戏控制](emoji-game-control.md) — 自定义猜拳和骰子的结果
- [伪装语音时长](fake-voice-duration.md) — 发送语音时预设显示的时长
- [贴纸包同步](stickers-sync.md) — 从本地目录导入贴纸包, 支持 Telegram StickersSync
- [虚拟视频通话](virtual-voip-video.md) — 视频通话时播放本地视频代替摄像头画面
- [伪造聊天记录消息](fabricate-chat-history-message.md) — 创建伪造的聊天记录分享卡片
- [重定向文件下载路径](redirect-download-path.md) — 将接收的文件保存到自定义目录
- [拦截异常大小贴纸表情](block-abnormal-size-stickers.md) — 防止异常大小表情导致闪退
