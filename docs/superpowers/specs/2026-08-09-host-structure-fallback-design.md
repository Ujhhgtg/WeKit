# 宿主结构回退策略设计

**日期：** 2026-08-09

## 目标

移除以下功能中通过宿主 `versionCode`、`versionName` 或渠道信息选择兼容路径的判断：

- `WeMessageApi` 的 MD5 图片发送目标解析与实际发送；
- `AutoViewOriginalMedia` 的分层原视频和实况照片路径；
- `AutoDndAfterJoinGroup` 的群成员同步方法解析；
- `PipVoip` 的 MultiTalk 麦克风路径。

兼容选择改由实际 Dex 结构和已解析成员的反射签名决定，使未知但结构兼容的宿主版本自然进入正确路径。

## 总体规则

### 新结构探针与回退

当新旧版本确实存在不同结构时，选择一个只存在于新路径的类、方法、字段或构造函数作为探针：

1. 先按完整、稳定且唯一的 DexKit 约束查找新结构探针；
2. 探针无结果时，将其记录为带明确原因的预期 placeholder，并解析旧路径；
3. 探针存在时，继续解析新路径的其他必需结构，并把旧路径结构记录为预期 placeholder；
4. 只有“零结果”可以触发回退；多结果、约束不唯一、DexKit 异常和新路径后续必需结构解析失败仍必须显式失败。

这符合 `allowFailure` 仅用于支持范围内确实存在版本差异的结构这一约束。回退不能用于掩盖不确定匹配或解析错误。

### 运行时路径选择

运行时不得再次读取宿主版本信息。需要在新旧实现之间选择时，只检查新结构探针的 `isPlaceholder`：

- `false`：使用新路径；
- `true`：使用旧路径。

同一路径内的其他成员必须在 Dex 解析阶段保持严格；不能为每个成员分别静默降级，从而产生部分解析的新路径。

### 签名差异

如果新旧路径使用同一个语义方法，只是参数数量不同：

- DexKit matcher 可以用 `paramCount(oldCount, newCount)` 同时接受两个已确认签名；
- Hook 中共同参数位置相同则不需要运行时分支；
- 如实际调用或读取参数确实不同，使用已解析的 `Method.parameterCount` 或 `Constructor.parameterCount` 选择调用参数，不读取宿主版本。

### 宿主版本判断审批

上述“先尝试再回退”和“判断实际再选择”是仓库默认兼容策略，应尽量避免根据 WeChat 宿主的 `versionCode`、`versionName`、硬编码版本字符串或等价版本常量分流。

如果已经穷尽稳定 Dex 结构、实际反射签名和直接相关运行时属性，仍然必须使用宿主版本判断，则在添加或保留该判断前询问用户并取得明确确认。未经确认，不得用版本判断替代结构探测。

通过 `isHostGooglePlay`/`isGooglePlay` 区分 Google Play 构建不属于宿主版本判断，不受上述确认要求限制；但 resolver 内仍必须从 `DexResolutionContext.host` 读取该元数据。

## `WeMessageApi` 图片发送

### 探针

使用 `methodImgUploadFeatureServiceSendImage` 作为新图片发送路径探针。它属于 `ImgUploadFeatureService` 新路径，旧版 `NetSceneUploadMsgImg` 路径不存在该方法。

### Dex 解析

- 探针存在：
  - 严格解析 `methodAppInfoSetAppId`；
  - 将 `ctorNetSceneUploadMsgImg` 标记为“新图片服务路径生效”的预期 placeholder。
- 探针不存在：
  - 将 `methodAppInfoSetAppId` 标记为“旧图片上传路径生效”的预期 placeholder；
  - 严格解析 `ctorNetSceneUploadMsgImg`。

探针查找只能对零结果回退。新路径的 `methodAppInfoSetAppId` 失败或旧路径构造函数失败均为真正的解析失败。

### 实际发送

`sendImageByMd5()` 检查 `methodImgUploadFeatureServiceSendImage.isPlaceholder`：

- 非 placeholder：构造 Feature Service 参数并调用新图片服务；
- placeholder：构造 `NetSceneUploadMsgImg` 并提交旧网络场景。

Dex 解析和运行时以同一个探针为唯一选择依据，避免两份版本阈值漂移。

## `AutoViewOriginalMedia`

### 探针

使用只在完整新分层媒体布局中存在的
`methodUpdateMediaGalleryVideoOriginButton` 作为探针。8.0.74 已经出现
`classMediaGalleryChatLiveBottomBarLayer` 及其带 `bindContext`、`msgInfo` 锚点的绑定方法，
但还没有更新原视频按钮的方法，因此类和绑定方法都不能用于选择完整新路径。

### Dex 解析

所有版本共有的原图/原视频入口继续严格解析。随后尝试解析声明类、绑定方法和探针：

- 声明类不存在：将声明类和两个新路径方法标记为预期 placeholder；
- 声明类存在：严格解析 `methodBindMediaGalleryChatLiveBottomBar`；
- 探针不存在：只将探针标记为预期 placeholder，保留实际存在的声明类和绑定方法；
- 探针存在：保存其唯一结果。

`classMediaGalleryVideoBottomBarLayer` 属于 `methodUpdateMediaGalleryVideoOriginButton` 的声明类依赖，保持严格解析；它本身不作为版本选择条件。

### Hook 安装

共有原图/原视频 Hook 始终安装。只有当
`methodUpdateMediaGalleryVideoOriginButton.isPlaceholder == false` 时，才安装分层原视频按钮
和实况照片底栏 Hook。新路径中的任一必需方法若解析失败，应在 Dex 阶段失败，而不是
在 Hook 阶段分别跳过。

## `AutoDndAfterJoinGroup`

新旧宿主中的 `ChatroomMembersLogic` 同步方法语义、稳定字符串和 Hook 使用的参数位置相同，仅参数总数为 10 或 11。

因此不建立新旧路径，也不使用 placeholder 探针。单个 DexKit 查询使用：

```kotlin
paramCount(10, 11)
```

其余参数类型、返回类型和稳定字符串约束继续保留，并要求最终结果唯一。错误信息不再包含宿主版本。

当前 Hook 只读取两种签名共有的参数位置，因此运行时无需检查 `method.parameterCount`。若以后需要访问仅 11 参数签名拥有的尾部参数，必须读取 `methodSyncChatroomMembers.method.parameterCount` 后再选择读取方式。

## `PipVoip` MultiTalk 麦克风

### 探针

使用 `methodMultiTalkMic` 作为新路径探针。新路径存在独立的 `onMicClick` 布尔方法；旧路径将相同逻辑内联到 `ControlPanelLogic`。

### Dex 解析

按现有声明类、单个 `boolean` 参数、`void` 返回值和稳定日志字符串查找 `methodMultiTalkMic`：

- 找到唯一结果：保存描述符；
- 零结果：将其标记为“直接 MultiTalk 麦克风方法缺失，使用内联旧路径”的预期 placeholder；
- 多结果或查询异常：解析失败。

不再检查缺失结构是否来自特定 `versionName`。

### 实际控制

现有 `toggleMic()` 继续以 `methodMultiTalkMic.isPlaceholder` 选择路径：

- 非 placeholder：调用直接麦克风方法；
- placeholder：调用 `toggleLegacyMultiTalkMic(viewModel)`。

## 8.0.77 第二代音视频架构回退

8.0.77 不只是改变了 MultiTalk 麦克风方法，而是移除了旧 MultiTalk UI / model /
ILink 结构。现有桌面报告中的首个失败会阻塞后续 delegate，因此不能直接把所有
`BLOCKED` 都视为结构缺失。APK 字符串证据表明：旧 MultiTalk 锚点已消失，但
TalkRoom 的 `enterTalkRoom` / `exitTalkRoom` 锚点仍然存在。两条链必须独立处理。

### `PipVoip` 旧 MultiTalk 画中画组

使用 `classMultiTalkViewModel` 作为旧 MultiTalk 架构探针。它由
`MicroMsg.MT.MultiTalkUIViewModel` 与 `onCameraClick, cur state: ` 两个旧架构稳定字符串
共同限定：

- 唯一命中时，继续严格解析旧 MultiTalk 画中画组；
- 零命中时，将探针及仅服务于该旧架构的 delegate 全部标为预期 placeholder；
- 多命中或组内后续结构失败仍然报错，不得降级。

该 placeholder 组包括：

- `classMultiTalkViewModel`、`fieldMultiTalkViewModel`；
- `methodMultiTalkMinimize`、`methodMultiTalkExit`、`methodMultiTalkMic`、
  `methodMultiTalkCamera`；
- `classMultiTalkManager`、`methodMultiTalkManagerMute`、`methodGetMultiTalkManager`；
- `classMultiTalkEngine`、`methodMultiTalkEngineMic`、`methodGetMultiTalkEngine`；
- `fieldMultiTalkMicState`、`fieldMultiTalkCameraState`。

`classObservableState`、`classMutableObservableState` 和 `methodObservableValue` 是通用
AndroidX Lifecycle 结构，并没有因为 8.0.77 消失，继续独立严格解析，不能因
MultiTalk 探针失败而伪装成版本差异。

运行时以 `classMultiTalkViewModel.isPlaceholder` 为唯一门控：探针可用时安装现有
`MultiTalkMainUI` session、销毁、离开前台和最小化 Hook；探针不可用时不安装这些
多人通话 Hook。单人 `FlutterVoip`、`VoIPMP` 与共用悬浮球 Hook 继续安装。

`methodVoipMpLaunchPage` 是另一条 VoIPMP 链上的独立可选结构，不能与 MultiTalk
探针捆绑。它继续通过自身的零结果成为预期 placeholder；`VoipMpSession.restore()`
在实际使用前检查其 `isPlaceholder`，缺失时记录无法恢复而不访问 placeholder。

### `SplitGroupCall` 旧 MultiTalk / ILink 组

使用 `classSubCoreMultiTalk` 作为旧群 VOIP 架构探针。它由
`MicroMsg.SubCoreMultiTalk` 与 `add , is running , forbid add` 两个旧架构稳定字符串限定：

- 唯一命中时，继续严格解析旧 MultiTalk / ILink 调用链；
- 零命中时，将探针及整条旧群 VOIP 调用链标为预期 placeholder；
- 多命中或探针存在时的任一后续结构失败仍然报错。

该 placeholder 组包括：

- `classSubCoreMultiTalk`、`methodExitMultiTalk`、`methodGetMultiTalkManager`、
  `methodSetStatus`、`methodSetMtSdkMode`；
- `classILinkService`、`classILinkMember`、`classInviteTask`；
- `methodSetName`、`methodPostTask`、`ctorInviteTask`、`ctorHangupTask`；
- `fieldILinkInstance`、`fieldRoomId`。

TalkRoom 的 `methodEnterTalkRoom`、`methodExitTalkRoom`、`methodGetTalkRoomServer`、
`fieldCurrentTalkRoom` 不属于该组，始终独立严格解析。

运行时以 `classSubCoreMultiTalk.isPlaceholder` 判断实际能力：探针不可用时仍注册并
展示该功能，但对话框只提供 `WALKIE_TALKIE`，不展示会访问 placeholder 的 `VOIP`
选项；探针可用时两种模式都保留。批处理入口也按实际 probe 状态校验所选模式，
避免任何旧群 VOIP delegate 被误用。

### 预期桌面结果

- 8.0.65–8.0.76：两个探针均成功，现有路径保持严格解析；
- 8.0.77：上述两个旧架构组全部为 `EXPECTED_FAILURE`，不得出现由探针导致的
  `UNEXPECTED_FAILURE` 或 `BLOCKED`；
- 8.0.77 的 Lifecycle 与 TalkRoom 独立结构仍为 `SUCCESS`；
- 不读取版本号决定上述任何解析或运行时行为。

## 源码约束

- 将总体规则同步写入仓库根目录 `AGENTS.md`，作为后续兼容代码的默认要求。
- 删除上述四个文件中不再使用的 `DexResolutionContext` import。
- 不引入新的宿主版本常量或版本比较帮助函数。
- Resolver 阶段只使用 DexKit metadata，不访问 `.method`、`.clazz`、`.field` 或 `.constructor`。
- Hook/实际调用阶段允许读取已解析成员的反射签名和 `isPlaceholder`。
- 不用 `try-catch` 或 `runCatching` 包裹 Hook 注册或回调。
- 不添加仅验证 matcher、placeholder 布尔分支或参数数量的低价值 JVM 测试。

## 验证

本次会改变 Dex resolver 源码和对应缓存键。完成实现后必须运行：

1. 受影响功能在支持范围 8.0.65–8.0.77 的桌面 DexKit 测试，包括可用的普通版和 Google Play APK；
2. 相关现有 Gradle/构建检查；
3. `./x build`，确保 native 库和 APK 都由 xtask 正确构建；
4. `git diff --check`；
5. 静态复扫，确认上述功能不再读取宿主版本或渠道元数据。

桌面 DexKit 测试只证明目标解析与回退分类正确。图片发送、自动查看原图、加群免打扰、
MultiTalk 麦克风、画中画和分裂群组通话行为仍需要在真实 WeChat 宿主上手工验证。

## 不包含

- 修改数据库查询、Themes 或禁止 Xposed 检测的现有版本/渠道判断；
- 泛化项目内其他 `allowFailure` 或 placeholder 用法；
- 为 8.0.77 的第二代 MultiTalk 架构实现新的多人通话画中画或群 VOIP 调用链；
- 支持 8.0.77 之后尚未验证的全新结构；
- 与这些回退路径无关的功能重构。
