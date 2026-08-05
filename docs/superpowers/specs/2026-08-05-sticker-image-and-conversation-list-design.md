# ViewStickerAsImage 与 BeautifyConversationList 设计

## 状态

本设计已在对话中逐段批准，当前只进入书面 spec 复核阶段。实现前仍须由用户复核本文件；本文件本身不包含实现代码。

## 背景与证据

`reports/Nuke_1.0.3_功能对比报告.md` 将“表情消息以图片打开”列为 Nuke 1.0.3 新增且 WeKit 缺失的功能，将“美化会话列表”列为 WeKit 只有相邻小功能、没有完整预设的部分覆盖项。报告还指出，`WeTextStatusApi` 只能读取状态，不能替代 Nuke 的状态删除拦截与恢复链。

Nuke 1.0.3 的相关反编译证据如下：

- `nuke_1.0.3/bf2.java` 注册“表情消息以图片打开”，从点击 View 的 tag/承载对象取得贴纸文件，失败时递归查找 `ImageView` 并生成最长边不超过 2048px 的 PNG 快照，最后以 `com.tencent.mm.ui.tools.ShowImageUI` 和 `key_image_path` 打开。
- `nuke_1.0.3/af2.java` 只在贴纸消息入口执行上述流程，并且仅在图片查看器启动成功后消费原点击。
- `nuke_1.0.3/ob0.java` 暴露“布局预设”“圆角头像”“突出未读会话”“隐藏分隔线”设置；布局有舒适卡片、紧凑圆角、简洁列表三套预设。
- WeChat 会话列表存在旧版 `ConversationWithCacheAdapter` 与新版 `ConversationAdapter.MvvmConversationAdapter` 两套 `getView(int, View, ViewGroup)` 绑定入口。当前 `HideConversationListDividers` 已分别挂钩两者，`SwipeConversationOperations` 也记录了这两个入口及其版本差异。

WeKit 可复用的底层设施包括：

- `WeMessageApi` 已有 `saveStickerByMd5()` 的 EmojiInfo 解密与 WXGF→GIF 转换链；`MessageInfo.imagePath`、`MessageType.isSticker` 和 XML md5 兜底已在其他消息功能中使用。
- `KnownPaths.moduleCache`、`WePrefs.prefOption`、`WeLogger`、`reflekt`、`showComposeDialog`/`AlertDialogContent` 和 `BeautifyViewPressEffect` 提供缓存、配置、反射、设置 UI 与 Ripple 基础。
- WeKit 的 `Themes`/主题与 `Context.isDarkMode` 能提供宿主主题色及明暗模式判断。

## 范围

### 目标

1. 新增 `ViewStickerAsImage`：点击聊天中的贴纸消息时，优先以可访问的贴纸文件打开微信原生图片查看器；文件不可得时用当前消息 View 生成 PNG 快照。
2. 新增 `BeautifyConversationList`：为首页会话列表提供三套行样式预设和三个独立开关，并统一处理旧版、新版适配器和回收复用。
3. 把双适配器行绑定抽成 `WeConversationListViewApi`，让 `HideConversationListDividers` 与新功能共享同一底层监听入口。
4. 重构 `WeMessageApi.saveStickerByMd5()`，使解密/转换结果可被下载保存和图片预览两个消费者复用。

### 非目标

- 不实现、不修改 `AntiStatusDeletion`。
- 不修改 `WeTextStatusApi`，不增加状态快照、删除 Hook、恢复、日志、水印或相关测试。
- 不激活、修改或依赖全局 `RoundAvatars`；会话列表圆角头像只在会话列表行内生效。
- 不迁移 Nuke 的设置体系、脚本体系、资源 ID 或 obfuscated 类名；WeKit 使用自己的类名、偏好键和现有 UI/主题设施。
- 不改变 WeChat 原始会话排序、过滤、点击、长按、滑动、置顶或数据查询行为。

## 总体方案

采用“基础设施优先、功能作为消费者”的方案：

```text
WeMessageApi sticker decode/write
          └── SaveStickersToLocalStorage
          └── ViewStickerAsImage

WeConversationListViewApi (legacy + MVVM getView)
          ├── HideConversationListDividers
          └── BeautifyConversationList
```

两个功能分别保持独立的 `Feature` 生命周期和提交边界。API 层只负责稳定解析、监听分发与刷新；具体视觉策略和设置状态由 `BeautifyConversationList` 负责。

## ViewStickerAsImage

### Feature 与解析

新增 `features/items/chat/ViewStickerAsImage.kt`，类型为 `SwitchFeature`，不增加子设置，默认行为由主开关决定。

使用 `IResolveDex` 结构化解析 WeChat 的贴纸点击入口。解析约束必须使用稳定字符串、签名和结构关系，不使用 JADX/JEB 生成的混淆运行时名称；重点证据为 `MicroMsg.EmojiClickListener` 和 `exit in teen mode`。该点击入口在支持矩阵中是必需目标，不能用 `allowFailure` 把解析异常静默成 no-op。

### 点击数据流

1. 在点击入口的 before Hook 中取得点击参数中的消息承载 View，并从 View tag/承载对象读取 WeChat 消息对象；如果入口同时提供消息对象参数，按入口实际参数契约使用该对象。
2. 包装为 `MessageInfo`，只继续处理 `messageInfo.type?.isSticker == true`。普通图片、搜狗表情之外的消息和无法识别类型的消息立即返回原流程。
3. 解析 md5：优先使用 `MessageInfo.imagePath`；为空时按现有 `RepeatMessages`/`ForwardMessages` 规则从消息 XML 的 `md5` 属性或标签读取。md5 为空则记录一次错误并保留原点击。
4. 解析当前 Activity：优先从点击 View 的 Context 链取得 Activity；不可得或处于 finishing/destroyed 时使用 WeKit 当前宿主 Activity，仍不可用则保留原点击。

### 文件路径优先级

`WeMessageApi` 增加可复用的“解密并写入指定路径”能力（具体命名由实施计划落定，但语义固定）：

- 通过现有 `WeServiceApi.getEmojiInfoByMd5()`、EmojiFileEncryptMgr 和 `MMWXGFJNI.nativeWxamToGif()` 得到可查看的 GIF 字节。
- `saveStickerByMd5()` 改为调用该公共解码/写入能力，原有 `KnownPaths.downloads` 输出和调用方行为不变。
- 预览消费者将结果写入 `KnownPaths.moduleCache / "view-sticker-as-image"`，文件名以 md5 稳定命名并使用 `.gif`；写入采用临时文件后原子替换，避免查看器读到半文件。

打开时按以下优先级选择路径：

1. 从点击 View 承载对象中反射得到的已解码、存在且非空的本地文件路径。
2. 使用 `WeMessageApi` 将 md5 解密/转换到预览缓存并校验文件存在且非空。
3. 递归查找消息 View 子树中第一个带 Drawable 的 `ImageView`，生成 PNG 快照。

### PNG 快照规则

- 尺寸来源优先使用 `ImageView` 实际宽高；未布局时使用 Drawable intrinsic 宽高；任一维度不可用则失败。
- 等比缩放，最长边不超过 2048px，短边至少为 1px，使用 `ARGB_8888` 和 PNG 编码。
- `ImageView` 有实际尺寸时绘制整个 View；未布局时给 Drawable 设置边界后直接绘制。
- 快照目录为 `KnownPaths.moduleCache / "view-sticker-as-image"`，使用临时 `.png` 文件并在写完后校验长度大于 0。
- 目录清理按 Nuke 的行为对齐：按最后修改时间新到旧排序，最多保留 11 个预览文件，写入新文件前删除更旧的文件；清理失败只记录警告，不影响主流程。

### 打开与错误处理

使用显式组件 `com.tencent.mm.ui.tools.ShowImageUI`，Intent extra 固定为 `key_image_path`。`startActivity()` 成功返回后才将原 Hook 的结果置空、消费原点击；启动失败、文件解析失败、快照失败或 Activity 不可用时记录 `WeLogger` 错误并让 WeChat 原始点击继续执行。

Hook 主体不增加包裹 `hookBefore` 的 `try/catch`；可预期的文件/Activity/启动失败由预览流程内部返回失败并记录，不能吞掉 Dex 或 Hook 编程错误。

## BeautifyConversationList

### WeConversationListViewApi

新增 `features/api/ui/WeConversationListViewApi.kt`，风格对齐 `WeChatMessageViewApi`：

- `@Feature(name = "会话列表 View 绑定监听服务", categories = ["API"], ...)`，继承 `ApiFeature` 并实现 `IResolveDex`。
- 暴露 `fun interface IBindViewListener { fun onBind(param: HookParam, row: View, conversation: Any?) }`、`addListener()`、`removeListener()`。
- 统一解析并 hook 旧版与 MVVM 两个 `getView(int, View, ViewGroup)`。旧版目标在 8.0.65–8.0.69 存在、8.0.74–8.0.76 预期缺失，使用 `allowFailure = true` 并为该版本差异设置明确 placeholder reason；MVVM 目标在所有支持变体都必须成功解析，不得 `allowFailure`。
- Hook after 中把返回值作为行根 View，使用同一入口取得 `getItem(position)` 会话对象，并顺序通知所有监听器；单个监听器异常单独记录，不阻断其他消费者。
- API 持有当前适配器的弱引用并提供 `refresh()`：在主线程对仍存活的 ListView/BaseAdapter 或 RecyclerView.Adapter 调用等价的 notify/rebind；不创建新的数据查询、不改变排序。

### HideConversationListDividers 迁移

`HideConversationListDividers` 删除自己的两个 Dex 声明和直接 Hook，改为在 `onEnable()` 注册 `WeConversationListViewApi` 监听，在 `onDisable()` 移除监听。监听只隐藏当前行中已有的原生横向分隔线，并保留其现有 child-index 查找顺序和 `isGone` 行为。这样该功能继续保持原有开关语义，同时不再与美化功能竞争同一个 `getView` Hook。

### Feature、设置与预设

新增 `features/items/beautify/BeautifyConversationList.kt`，类型为 `ClickableFeature`。主开关控制监听是否安装；点击 feature 行打开 Compose 设置对话框，使用 `showComposeDialog` 和 `AlertDialogContent`。

持久化使用 `WePrefs.prefOption`，偏好键必须使用 WeKit 自有前缀，不复用 Nuke 资源 ID。设置包括：

| 预设 | 行圆角 | 水平 inset | 垂直 inset | 头像圆角 | 浅色背景 | 深色背景 |
|---|---:|---:|---:|---:|---|---|
| 舒适卡片 | 14dp | 10dp | 4dp | 12dp | `#F7FAF9` | `#252827` |
| 紧凑圆角 | 10dp | 6dp | 2dp | 10dp | `#F9FBFA` | `#272928` |
| 简洁列表 | 6dp | 0dp | 0dp | 8dp | `#FCFCFC` | `#232323` |

默认预设为“舒适卡片”。三个独立开关默认均为 `true`：

- `圆角头像`：只裁剪首页会话行内识别到的头像 `ImageView`，不影响聊天页、联系人页或全局 `RoundAvatars`。
- `突出未读会话`：通过会话对象的已缓存 unread accessor 判断未读数量/标志；未读时使用当前 WeKit/WeChat 主题的低对比度强调色覆盖卡片背景，已读时使用预设背景。
- `隐藏分隔线`：由 `HideConversationListDividers` 自身开关决定；`BeautifyConversationList` 的该开关只控制是否将 API 行分隔线设为 gone，并不得隐式启用另一个 Feature。两者同时存在时，任何一个要求显示分隔线都不能覆盖另一个已启用的隐藏状态；最终实现使用共享行状态合并而不是互相写死。

预设颜色、描边色、未读色和 Ripple 色通过 WeKit 现有主题/明暗模式设施取得；固定表中的背景色是预设基色，主题覆盖只调整描边、未读 tint 和按下反馈的透明度。

### 回收行与视觉应用

监听每次绑定必须执行“先恢复、再应用”：

1. 用弱引用或以 View 为 key 的弱映射保存原始 background/foreground、padding、clipToOutline、outlineProvider、elevation、layout 参数以及分隔线可见状态。
2. 行被重新绑定到另一个会话时，先恢复上一次保存的原始状态，清除旧的模块 Drawable、avatar outline 和 inset，避免圆角、未读背景、padding 泄漏到下一行。
3. 根据当前预设创建圆角描边 `GradientDrawable`，以 `RippleDrawable` 包装，并用 `InsetDrawable` 实现水平/垂直 inset；不要依赖某个具体 obfuscated 行容器类型。
4. 通过稳定的 View 结构查找头像 `ImageView`，设置仅作用于该 View 的 outline radius；找不到头像时只跳过头像圆角，不影响整行样式。
5. 保留 WeChat 原有 click/long-click/touch listener，不替换事件监听器；Ripple 只作为背景/前景 Drawable。

### 设置保存与刷新

对话框点击“确定”时一次性写入预设和三个开关，然后调用 `WeConversationListViewApi.refresh()`，使当前可见行重新绑定。取消不写入、不刷新。刷新必须在主线程执行；若当前适配器已回收，设置仍持久化，下一次绑定自动应用。

## 生命周期、兼容性与错误处理

- 两个 Feature 仅在 WeChat 主进程安装，遵循现有 `TargetProcesses` 约束。
- `WeConversationListViewApi` 的旧版 adapter 缺失是支持矩阵中的预期差异；MVVM 解析失败、点击入口解析失败或其他非预期 resolver 异常必须让 Dex 测试失败并可见，不能转为静默 no-op。
- 反射字段/方法访问使用 `reflekt`，并按宿主类缓存 accessor；未找到 unread accessor 时记录一次兼容性警告并按“无未读”处理，不影响其他样式。
- 文件写入使用 `use`、临时路径和删除兜底；查看器启动失败不删除仍可能被 WeChat 使用的当前文件，只在下一次缓存清理时按 mtime 回收。
- `onDisable()` 只做最佳努力的监听移除和模块状态清理；不要求重建 WeChat 已经绑定的所有行，符合项目现有 Feature 生命周期约定。

## 测试与验收

### 纯逻辑测试

只为与 WeChat 解耦的逻辑增加 JVM 测试：

- `ViewStickerAsImage`：贴纸类型门控、md5 提取优先级、2048px 等比尺寸计算、预览文件清理（保留 11 个最新文件）、启动成功才消费点击的结果选择。
- `BeautifyConversationList`：三套预设的完整数值、明暗背景选择、unread accessor 的正/零/缺失结果、先恢复再应用的状态转换、独立开关组合和 inset/radius 计算。

不为 DexKit、WeChat View 结构、原生图片查看器启动或真实回收行为编写虚假的 desktop UI 单元测试。

### Dex 与构建验证

按项目支持矩阵分别执行 8.0.65、8.0.67、8.0.69、8.0.69 Google Play、8.0.74、8.0.76 的相关 `./x dex-test`。检查每个版本的 legacy adapter 预期失败/成功分类和 MVVM 必需成功；点击入口和 WeMessageApi 相关解析若有变化，重跑受影响版本。

每个独立提交至少执行：

```text
相关 ./x dex-test
相关 JVM focused tests
./x build
git diff --check
```

真机验收另行记录，不把 desktop Dex、JVM 测试或构建成功描述为行为已验证。必须手工确认：贴纸点击能进入微信原生图片查看器、文件失败时保留原行为、三种会话预设在旧版/MVVM 列表均可见、未读/分隔线/头像开关按预期生效、行回收不串样式、明暗主题和 Ripple 正常、设置保存后立即刷新。

## 提交边界

实现计划保持两个独立提交和验证边界：

1. `ViewStickerAsImage`：`WeMessageApi` 解码/写入重构、feature、纯逻辑测试。
2. `BeautifyConversationList`：`WeConversationListViewApi`、`HideConversationListDividers` 迁移、feature、纯逻辑测试。

本设计文档单独提交，不能与实现文件混在同一 commit。随后两个实现提交的变更列表中不得出现 `AntiStatusDeletion` 或 `WeTextStatusApi`。
