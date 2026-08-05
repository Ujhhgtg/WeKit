# 加入群聊自动免打扰设计

**日期：** 2026-08-05

## 目标

新增一个可开关的 WeKit 功能“加入群聊自动免打扰”。当当前用户的本地群成员状态被 WeChat 同步确认首次包含在某个群聊中时，自动将该群设置为消息免打扰。

功能覆盖通过邀请、扫码以及其他最终汇入同一群成员同步路径的“自己加入别人已有群聊”方式。不处理当前用户自己创建或拉起的群聊。

功能放在“联系人与群组”分类下。

## 不采用数据库插入作为语义触发器

`chatroom` 表的 `insert`/`update` 只表示本地群资料被写入，不能证明用户刚刚入群。相同的写入路径也可能来自完整同步、增量同步、群详情刷新、数据库恢复、迁移或同步重放；新群首次写入时成员列表也可能已经完整，也可能为空。

因此不直接根据 `chatroom` 插入触发免打扰。数据库监听最多作为同步后的状态观察手段，不作为本功能的入群语义来源。

## 触发入口

Hook WeChat 的群成员同步语义方法 `ChatroomMembersLogic.N(...)`。8.0.76 的源码对应 `e01.v1.N(...)`，稳定日志锚点为：

```text
MicroMsg.ChatroomMembersLogic
SyncAddChatroomMember
```

已确认的 8.0.76 链路：

```text
BigBallContactAssemblerImpl.G0(...)
    -> ChatroomMembersLogic.N(...)
    -> ChatroomMembersLogic.M(...)
    -> ChatroomStorage.replace(...)
```

对应源码位置：

- `wechat_8076/.../lq1/e.java:857-895`
- `wechat_8076/.../e01/v1.java:160-198`
- `wechat_8076/.../e01/v1.java:202-380`
- `wechat_8076/.../com/tencent/mm/storage/a3.java:147-177`

实现不能硬编码 8.0.76 的类名，而应为 8.0.65–8.0.76 通过稳定结构和元数据解析对应方法。resolver 阶段只能使用 DexKit metadata，不得通过 JVM 反射加载 WeChat/Android 类。

## 入群判定

在原方法执行前保存同步前状态：

```text
oldRoomExists
oldMemberList
oldMemberVersion / old room metadata
```

让 WeChat 正常完成成员同步和数据库写入后，再读取同步后的群状态：

```text
newMemberList
newMemberVersion / new room metadata
```

仅当以下条件同时满足时触发：

```text
roomId 是群聊
oldMemberList 不包含当前用户
newMemberList 包含当前用户
```

具体规则：

- 对不存在旧 `chatroom` 行的新群，只要同步后的成员列表包含当前用户，就视为自己加入了别人已有的群；不要求先有空行。
- 对已有群，只有当前用户从成员列表缺失变为存在才触发。
- 当前用户自己创建或拉起的群聊不触发，即使首次同步后的成员列表包含当前用户。实现需要利用同步上下文中的群主/创建者信息或其他已验证的创建路径标志，将该路径排除；不能仅凭“新群且包含当前用户”判定为应触发。
- 其他成员加入、成员昵称变化、普通成员详情刷新不触发。
- 如果本次同步成员列表为空或无法确认当前用户存在，不触发；后续群详情同步提供完整成员信息时再次判断。
- 当前用户已经存在时不触发。
- 同时支持普通 `@chatroom` 和 OpenIM `@im.chatroom` 房间。

## Hook 执行顺序

```text
hookBefore:
    读取并保存旧群状态

原方法:
    让 WeChat 完成原有成员同步和数据库写入

hookAfter:
    读取新群状态
    判断旧状态 -> 新状态是否为当前用户入群
    异步执行一次免打扰设置
```

不在数据库事务或同步调用栈内直接发送 room oplog。Hook 不修改 WeChat 原方法的输入和同步结果。

## 免打扰操作

复用现有 API：

```kotlin
WeConversationApi.setDnd(roomId, true)
```

群聊路径使用 WeChat 的 room oplog `OpModChatRoomNotify`，而不是修改普通联系人的 mute bit。对应 WeKit 实现位于：

- `app/src/main/java/dev/ujhhgtg/wekit/features/api/core/WeConversationApi.kt`

确认入群后：

1. 读取当前免打扰状态；
2. 已经免打扰则不提交；
3. 未免打扰则提交一次 `setDnd(roomId, true)`；
4. 提交失败只记录 room ID、同步版本和失败原因，不重试，也不影响 WeChat 同步流程。

本地 `rcontact.lvbuff` 可能晚于 oplog 提交才反映新状态，因此“请求已提交”和“本地读取已变更”不等价。

## 去重和生命周期

不新增持久化表，也不把已处理 room ID 永久写入偏好。功能启用前已存在的群不应因首次启用而批量触发，账号切换和数据库恢复也不应留下永久错误状态。

使用进程内去重键：

```text
(roomId, memberVersion)
```

无可靠成员版本时使用：

```text
(roomId, hash(normalizedMemberList))
```

在功能禁用、WeChat 进程重启、账号切换时清理；达到上限时按时间淘汰。去重只防止同一次同步重复提交，不改变核心的成员状态转换判断。

## 版本兼容

目标版本：8.0.65、8.0.67、8.0.69、8.0.74、8.0.76。需要分别验证每个支持 APK；普通版和 Google Play 版在测试 APK 可用时分别验证。

若某版本中不存在同一同步方法，必须先由源码确认其等价同步入口，再增加 resolver 分支。不能为了获得绿色结果，把应当存在的 resolver 设置成 `allowFailure`。

新增或修改 Dex resolver 后，必须运行受影响版本的桌面解析测试。resolver 源码变化会改变方法 hash，并要求设备端重新解析一次；不得保留旧 hash 来绕过缓存失效。

## 错误处理约束

- 不用 `try-catch` 或 `runCatching` 包裹 `hookBefore`/`hookAfter`。
- 对确实可能为空的同步成员数据保留合理分支：无法确认当前用户存在时等待后续同步。
- 免打扰提交失败只记录日志，不重试，不阻塞或回滚 WeChat 成员同步。
- 不新增与本功能无关的防御性 safe cast 或静默 fallback。

## 验证

### 桌面 DexKit 验证

对受影响的每个支持 APK 运行：

```bash
./x dex-test
```

报告必须明确显示所有初始化、解析、worker、native、unexpected、blocked 和 incomplete 状态；不得有未预期失败。

### 构建和静态检查

```bash
./x build
git diff --check
```

### 实机行为

至少验证：

1. 功能关闭时入群不改变免打扰状态；
2. 邀请或扫码加入别人已有群后自动免打扰且不重复提交；
3. 自己创建或拉起群后不自动免打扰；
4. 新群首次成员列表为空时不误触发，后续完整同步确认自己加入别人已有群后触发；
5. 其他成员加入已有群不触发；
6. 已免打扰的群不重复提交；
7. `@chatroom` 与 `@im.chatroom`；
8. 重启 WeChat 后已有群普通同步不被误判为新加入；
9. `setDnd` 失败时同步仍正常且不发生重试风暴。

## 明确不包含

- 群聊白名单或黑名单；
- 延迟时间配置；
- 已存在群聊的批量补免打扰；
- 失败重试；
- 独立持久化历史状态；
- 对不同入群来源提供不同配置；
- 当前用户自己创建或拉起的群聊自动免打扰。
