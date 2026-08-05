# ViewStickerAsImage 与 BeautifyConversationList 设计

## 状态

本设计已完成逐段方案讨论并单独提交；当前根据 WeKit 规范、Nuke 1.0.3 反编译结果和 WeChat 8.0.65–8.0.76 宿主源码进行书面 spec 复核。本文档仍不包含实现代码，复核完成后须由用户确认，才能进入实施计划。

## 背景与证据

`/home/ujhhgtg/coding/nuke_deobf_clean/reports/Nuke_1.0.3_功能对比报告.md` 将“表情消息以图片打开”列为 Nuke 1.0.3 新增且 WeKit 缺失的功能，将“美化会话列表”列为 WeKit 只有相邻小功能、没有完整预设的部分覆盖项。报告还指出，`WeTextStatusApi` 只能读取状态，不能替代 Nuke 的状态删除拦截与恢复链。

Nuke 1.0.3 的相关反编译证据如下：

- `nuke_1.0.3/bf2.java` 注册“表情消息以图片打开”，从点击 View 的 tag/承载对象取得贴纸文件，失败时递归查找 `ImageView` 并生成最长边不超过 2048px 的 PNG 快照，最后以 `com.tencent.mm.ui.tools.ShowImageUI` 和 `key_image_path` 打开。
- `nuke_1.0.3/af2.java` 只在贴纸消息入口执行上述流程，并且仅在 `startActivity()` 未抛出异常后消费原点击。
- `nuke_1.0.3/ob0.java` 暴露“布局预设”“圆角头像”“突出未读会话”“隐藏分隔线”设置；布局有舒适卡片、紧凑圆角、简洁列表三套预设。
- WeChat 会话列表存在旧版 `ConversationWithCacheAdapter` 与新版 `ConversationAdapter.MvvmConversationAdapter` 两套 `getView(int, View, ViewGroup)` 绑定入口。当前 `HideConversationListDividers` 已分别挂钩两者，`SwipeConversationOperations` 也记录了这两个入口及其版本差异。

WeKit 可复用的底层设施包括：

- `WeMessageApi` 已有 `saveStickerByMd5()` 的 EmojiInfo 解密与 WXGF→GIF 转换链；`MessageInfo.imagePath`、`MessageType.isSticker` 和 XML md5 兜底已在其他消息功能中使用。
- `KnownPaths.moduleCache`、`WePrefs.prefOption`、`WeLogger`、`reflekt` 和 `showComposeDialog`/`AlertDialogContent` 提供缓存、配置、反射与设置 UI 基础。
- `Context.isDarkMode` 提供宿主明暗模式判断；本功能使用自己的明暗色值，不读取 `Themes` 私有 palette，也不复用 `BeautifyViewPressEffect` 的全局背景 Hook。

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

两个功能分别保持独立的 `Feature` 生命周期和提交边界。API 层负责稳定解析、监听分发、宿主 adapter 刷新和共享分隔线所有权；卡片、未读背景、头像和设置状态只由 `BeautifyConversationList` 负责。

## ViewStickerAsImage

### Feature 与解析

新增 `features/items/chat/ViewStickerAsImage.kt`，类型为 `SwitchFeature`，不增加子设置，默认行为由主开关决定。

使用 `IResolveDex` 解析两个必需目标，两个 delegate 均不得使用 `allowFailure`：

1. 贴纸处理方法：限定在 `com.tencent.mm.ui.chatting.viewitems`，参数数量为 1、返回 `void`，并同时包含 `MicroMsg.EmojiClickListener` 与 `exit in teen mode`。8.0.65 的该参数是 View tag wrapper，其余已检查版本为 MsgInfo。
2. 点击入口：声明类取自前一 delegate 的 `data.declaredClassName`，参数固定为 `[android.view.View, 任意聊天上下文类型, 任意第三参数]`，返回 `void`；实际 MsgInfo 类型从点击入口的 `data.paramTypeNames[2]` 取得。后续 matcher 只能使用 `.data` 元数据，不能在 resolver 中加载宿主 `Class`/`Method`。

该结构在 8.0.65、8.0.67、8.0.69、8.0.74、8.0.76 的宿主源码中均为 `void entry(View, context, MsgInfo)` 调用单参数 handler；8.0.69 Google Play 由必需的 Dex 测试确认。运行时混淆类名和聊天上下文类型均不得写入 matcher。

另外解析 `MsgInfo → EmojiInfo` 与 `EmojiInfo` 解密路径链：

- Emoji resolver getter：`com.tencent.mm.feature.emoji` 中零参数方法，返回实际 resolver 类型。
- `resolveEmojiInfo`：唯一参数取点击入口第三参数的 MsgInfo 类型，返回 `com.tencent.mm.storage.emotion.EmojiInfo`；同签名的聊天组件 static wrapper 必须排除，选择非 static resolver 方法。
- `getEmojiDecryptPath`：声明类为 `EmojiInfo`、零参数、返回 `String`，并同时使用 `MicroMsg.emoji.EmojiInfo`、`[cpan] get icon path failed. product id and md5 are null.`、`decrypt/`、`getDecryptPath decrypt %s` 作为稳定证据。

这三个目标在支持矩阵中也必须成功解析，不用 `allowFailure`。它们对应 Nuke 的 `MsgInfo → EmojiInfo → getDecryptPath()` 路径，而不是从 `MessageInfo.imagePath` 猜测本地文件。

### 点击数据流

1. 在三参数点击入口的 before Hook 中直接取得 `args[0] as View` 与 `args[2]` 的 WeChat `MsgInfo`，并构造 `MessageInfo(args[2]!!)`。不把 View tag 作为正常消息来源；tag 只保留为诊断时可用的宿主证据。
2. 只继续处理 `messageInfo.type?.isSticker == true`。普通图片、非贴纸消息和无法识别类型的消息立即返回原流程；`MessageType.isSticker` 已包含普通贴纸与搜狗表情类型。
3. 解析 md5：优先使用非空白的 `MessageInfo.imagePath`；为空白时按现有 `RepeatMessages`/`ForwardMessages` 规则从消息 XML 的 `md5` 属性、再从 `md5` 标签读取。三者都为空时记录错误并保留原点击，不增加额外的“只记录一次”状态机。
4. 解析当前 Activity：优先从点击 View 的 Context 链取得 Activity；不可得或处于 finishing/destroyed 时使用 WeKit 当前宿主 Activity，仍不可用则保留原点击。

### 文件路径优先级

`WeMessageApi` 增加可复用的 `decodeStickerToFile(md5, destination)`（实施时可采用等价命名），统一负责 EmojiInfo 解密、WXGF→GIF 转换与文件写入：

- 通过现有 `WeServiceApi.getEmojiInfoByMd5()`、EmojiFileEncryptMgr 和 `MMWXGFJNI.nativeWxamToGif()` 得到 GIF 字节；转换结果为空时失败，不写最终文件。
- 写入 destination 同目录的临时文件，flush/close 后校验非空，再尝试原子替换最终文件；底层文件系统不支持原子 move 时，回退为同目录 replace，并且任何失败都删除临时文件。已有非空 destination 视为缓存命中，直接复用并更新 mtime。
- `saveStickerByMd5()` 改为调用该公共能力，原有 `KnownPaths.downloads` 输出、文件名和调用方行为不变。
- 预览消费者使用 `KnownPaths.moduleCache / "view-sticker-as-image" / "decoded"`，文件名以 md5 稳定命名并使用 `.gif`。每次需要生成新 GIF 前按 mtime 保留 10 个最新旧文件，写入后总数最多为 11；缓存命中会更新 mtime。

打开时按以下优先级选择路径：

1. 使用已解析的 `MsgInfo → EmojiInfo → getDecryptPath()` 链取得微信已解密路径；只接受存在、为普通文件且长度大于 0 的绝对路径。该 getter 可能在宿主目录中执行一次解密，因此不是单纯字段读取。
2. 使用 `WeMessageApi.decodeStickerToFile()` 将 md5 转换到预览 GIF 缓存，并重新校验文件存在、为普通文件且长度大于 0。
3. 递归查找点击 View 子树中第一个带 Drawable 的 `ImageView`，生成 PNG 快照。

整个 before Hook 保持同步：缓存命中、宿主解密路径、GIF 转换、快照绘制和 `startActivity()` 都在点击所在线程完成。这样只有在查看器启动请求未抛异常时才消费原点击；不引入“先消费、后台失败”的新交互。GIF 缓存优先降低重复点击成本，首次解密/转换可能产生可感知延迟，需在真机验收中记录。

### PNG 快照规则

- 尺寸来源优先使用 `ImageView` 实际宽高；未布局时使用 Drawable intrinsic 宽高；任一维度不可用则失败。
- 等比缩放，最长边不超过 2048px，短边至少为 1px，使用 `ARGB_8888` 和 PNG 编码，并始终 recycle Bitmap。
- `ImageView` 有实际尺寸时绘制整个 View；未布局时给 Drawable 设置边界后直接绘制。
- 快照目录为 `KnownPaths.moduleCache / "view-sticker-as-image" / "snapshots"`；先创建目录，清理旧快照，再使用 `File.createTempFile()` 生成 `.png`，写完后校验为普通文件且长度大于 0。
- 快照目录按最后修改时间新到旧排序，写入新文件前最多保留 10 个旧文件，因此写入后总数最多为 11。清理只处理 `snapshots` 子目录，不删除稳定 md5 GIF；清理失败只记录警告，不影响主流程。

### 打开与错误处理

使用显式组件 `com.tencent.mm.ui.tools.ShowImageUI`，Intent extra 固定为 `key_image_path`。`startActivity()` 未抛出启动异常后，才将 `void` Hook 的结果置为 `null` 并消费原点击；这只表示 Android 已接受启动请求，不保证目标 Activity 已创建或成功读取文件。文件解析失败、快照失败、Activity 不可用或启动抛出异常时记录 `WeLogger` 并让 WeChat 原始点击继续执行。

Hook 主体不增加包裹 `hookBefore` 的 `try/catch`/`runCatching`；可预期的文件、Activity 和启动失败由各自 helper 返回失败并记录，不能吞掉 Dex、参数契约或 Hook 编程错误。

## BeautifyConversationList

### WeConversationListViewApi

新增 `features/api/ui/WeConversationListViewApi.kt`，风格对齐 `WeChatMessageViewApi`：

- `@Feature(name = "会话列表 View 绑定监听服务", categories = ["API"], ...)`，继承 `ApiFeature` 并实现 `IResolveDex`。
- 暴露 `fun interface IBindViewListener { fun onBind(param: HookParam, row: View, conversation: Any) }`、`addListener()`、`removeListener()`。监听器存储使用 `CopyOnWriteArrayList`，拒绝重复注册。
- 统一解析并 hook 旧版与 MVVM 两个 `getView(int, View, ViewGroup): View`。两个 matcher 都必须固定参数和返回类型，并使用稳定 logger 字符串，不使用混淆类名。
- legacy 目标在 8.0.65、8.0.67、8.0.69 及 8.0.69 Google Play 存在，在 8.0.74、8.0.76 缺失。仅 legacy delegate 使用 `allowFailure = true`；`resolveDex` 必须读取 `DexResolutionContext.host`，仅对 8.0.74/8.0.76 显式提交 `expectedFailure = true` placeholder 与版本原因。旧版中解析不到目标仍是非预期失败。
- MVVM 目标在所有支持变体都必须成功解析，不得使用 `allowFailure`。其 matcher 使用 `MicroMsg.ConversationAdapter.MvvmConversationAdapter`、重复项 logger 字符串和完整 `getView` 签名。
- Hook after 中直接把返回值转为行根 `View`，把 `thisObject` 转为 `BaseAdapter`，以 `args[0] as Int` 调用 `adapter.getItem(position)!!` 取得直接 Conversation 模型，并顺序通知所有监听器。`getView/getItem` 参数契约错误应直接暴露；只有单个监听器自身的异常被隔离记录，不阻断其他消费者。
- API 保存最新一次绑定的 `WeakReference<BaseAdapter>`；当 `args[2] is ListView` 时同时保存 `WeakReference<ListView>`。宿主矩阵均为 `ListView + BaseAdapter`，不设计无证据的 RecyclerView 分支。
- `refresh()` 切到主线程，取得仍存活的 adapter；若有 ListView，则先确认 `listView.adapter === adapter`，再调用虚分派的 `adapter.notifyDataSetChanged()`，保留 8.0.74/8.0.76 自定义 override 的宿主同步逻辑。adapter 已回收时不查询或重建数据，等待后续正常 bind。

### 共享分隔线所有权

`WeConversationListViewApi` 同时提供分隔线协调器，避免两个 Feature 各自写死同一个 View。协调器按 owner 保存隐藏请求，并在每次 bind/设置刷新时计算：

```text
hideDivider =
    HideConversationListDividers.isEnabled
    OR (BeautifyConversationList.isEnabled AND beautifyHideDividersPreference)
```

只有两方都为 false 时才恢复模块仍拥有的状态。协调器使用弱状态保存：

- 行级：沿用 `(0, 1, 1, 1)`、再 `(0, 1, 1)` 的子 View 查找顺序，保存其原始 visibility；仅当当前值仍是模块写入的 `GONE` 时恢复。
- ListView 级：第一次处理 live ListView 时保存原始 divider 与 dividerHeight；隐藏时安装模块持有的透明 Drawable 并设高度 0，恢复前确认当前 divider 仍是模块对象。

这样保留现有 WeKit 的行内分隔线行为，同时覆盖 Nuke 已验证的 ListView 原生分隔线间距。共享协调器只拥有分隔线状态，不拥有卡片背景或头像状态。

### HideConversationListDividers 迁移

`HideConversationListDividers` 删除自己的两个 Dex 声明和直接 Hook，改为在 `onEnable()` 向协调器注册 owner 隐藏请求，在 `onDisable()` 移除请求并触发一次最佳努力刷新。它不再直接修改行 View；现有 child-index 与 `isGone` 语义由共享协调器保留。该功能的主开关仍独立于 `BeautifyConversationList`。

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
- `突出未读会话`：从 `getItem(position)` 返回的直接 Conversation 模型中，以 `reflekt` 向上查找精确字段名 `field_unReadCount`，按运行时模型类缓存 accessor；`Number.toInt() > 0` 为未读。找不到或读取失败时每个运行时类只记录一次兼容性警告，并按已读处理，不查询数据库。
- `隐藏分隔线`：只提交 `BeautifyConversationList` 自己的协调器请求，不隐式启用 `HideConversationListDividers`。最终隐藏状态按共享协调器的 OR 规则计算，一方关闭不能覆盖另一方仍启用的隐藏请求。

预设背景使用表中固定基色。为避免扩大 `Themes` 的私有实现边界，描边、未读 tint 和 Ripple 使用 WeKit 自有的固定明暗色值，并通过 `Context.isDarkMode` 选择：浅色描边 `Color.argb(22, 22, 29, 28)`、深色描边 `Color.argb(34, 255, 255, 255)`；浅色未读背景 `#EAF8F2`、深色未读背景 `#253E37`；浅色 Ripple `Color.argb(24, 0, 106, 98)`、深色 Ripple `Color.argb(42, 255, 255, 255)`。不读取 `Themes` 私有字段，也不依赖 `BeautifyViewPressEffect`。

### 回收行与视觉应用

`BeautifyConversationList` 使用 `WeakHashMap<View, RowVisualState>` 保存自己拥有的行状态。每次绑定执行“先恢复模块仍拥有的状态，再应用当前状态”：

1. 行状态只保存原始 background 与 padding、模块安装的 Drawable identity，以及上一次识别的 avatar 弱引用。卡片样式不修改 foreground、elevation、LayoutParams、margin 或子层级；水平/垂直 inset 仅表示 `InsetDrawable` 的绘制内缩，不改变行测量高度。
2. 重新绑定前，仅当 `row.background === moduleDrawable` 时恢复原 background 与 padding；若 WeChat 或其他 Feature 已替换 background，则把当前值更新为新的 baseline，不覆盖外部修改。
3. 根据当前预设创建圆角描边 `GradientDrawable`，以 `InsetDrawable` 实现表中的水平/垂直绘制 inset，再以 `RippleDrawable` 包装并设为 row background；设置后重新应用保存的原始 padding。
4. 头像查找采用不依赖混淆容器类型的有界 DFS：只遍历可见节点、最大深度 8；候选必须是已布局的 `ImageView`，宽高均大于 0，短边至少 32dp、长边至多 84dp，宽高差比例不超过 0.22；按面积减去形状偏差评分选最高者。找不到时只跳过头像圆角。
5. 对头像保存原始 `outlineProvider` 与 `clipToOutline`，安装模块专属 `ViewOutlineProvider`，以预设头像圆角 dp 生成 round-rect outline，设置 `clipToOutline = true` 并 `invalidateOutline()`。恢复时仅当当前 provider 仍是模块对象才恢复原状态；全局 `RoundAvatars` 若同时启用，其宿主图片加载参数仍独立生效，后写入且仍由模块持有的行内 outline 决定当前行裁剪。
6. 分隔线由 API 协调器在卡片/头像应用后统一处理。保留 WeChat 原有 click、long-click、touch listener，不替换事件监听器。

### 设置保存与刷新

对话框使用本地 Compose state；点击“确定”时一次性写入预设和三个开关，更新分隔线协调器请求，然后调用 `WeConversationListViewApi.refresh()`。取消不写入、不刷新。刷新在主线程调用当前宿主 `BaseAdapter.notifyDataSetChanged()`；若 adapter 已回收，设置仍持久化，下一次绑定自动应用。

## 生命周期、兼容性与错误处理

- 两个 Feature 和 API 只在 WeChat 主进程安装，沿用 `SwitchFeature`/`ApiFeature` 默认进程约束。
- legacy adapter 缺失只允许在 8.0.74/8.0.76 通过 `DexResolutionContext.host` 产生带版本原因的 `EXPECTED_FAILURE`；MVVM、贴纸 handler/entry、EmojiInfo resolver/decrypt-path 解析失败或其他 resolver 异常必须让 Dex 测试失败并可见，不能转为静默 no-op。
- 运行时宿主字段/方法访问使用 `reflekt` 并按宿主类缓存 accessor；解析阶段的依赖关系只使用 delegate `.data` 元数据。
- 文件写入使用 `use`、同目录临时路径、replace 回退和删除兜底；查看器启动失败不立即删除当前路径，decoded GIF 与 snapshot 分别在各自目录的下一次 mtime 清理中回收。
- `onDisable()` 只做最佳努力的监听/分隔线 owner 移除和模块状态清理；不要求重建 WeChat 已经绑定的所有行，符合项目现有 Feature 生命周期约定。

## 测试与验收

### 自动化测试边界

本功能不新增 JVM 单元测试。贴纸功能的关键行为依赖 WeChat host、DexKit、Android View/Bitmap/Activity、原生 WXGF 转换、文件系统与 Hook 时序；`shouldConsumeStickerClick(x) = x` 一类恒等 helper 及其测试属于为满足流程而增加的低价值测试，禁止引入。可抽离的 md5、缩放与清理 helper 直接由实现使用，通过代码审查、现有测试套件、Dex 测试和真机验收验证。

`BeautifyConversationList` 同样不为 Android View、反射 accessor 或回收流程增加 JVM 测试；如实现仅包含预设常量和简单布尔/数值转换，也不为这些机械逻辑新建测试文件。

### Dex 与构建验证

按项目支持矩阵分别执行 8.0.65、8.0.67、8.0.69、8.0.69 Google Play、8.0.74、8.0.76 的相关 `./x dex-test`：

- legacy conversation delegate 在 8.0.65/67/69/69 Play 必须成功，在 8.0.74/76 必须是带明确 host 版本原因的 `EXPECTED_FAILURE`。
- MVVM conversation、sticker handler/entry、EmojiInfo resolver/decrypt-path delegate 在所有支持变体都必须成功，不得出现 `UNEXPECTED_FAILURE`、`BLOCKED` 或 `INCOMPLETE`。
- 只有 Dex 声明或 resolver 发生变化时才重跑受影响版本；不把已通过的昂贵 Dex 测试用于无关文档/纯逻辑变化。

每个独立提交至少执行：

```text
相关 ./x dex-test
现有相关 JVM tests
./x build
git diff --check
```

真机验收另行记录，不把 desktop Dex、JVM 测试或构建成功描述为行为已验证。必须手工确认：贴纸点击能进入微信原生图片查看器、缓存命中与首次转换均可用、文件/启动失败时保留原行为、三种会话预设在旧版/MVVM 列表均可见、未读/分隔线/头像开关按预期生效、`HideConversationListDividers` 与美化开关的 OR 合并正确、行回收不串样式、明暗主题和 Ripple 正常、设置保存后立即刷新。

## 提交边界

实现计划保持两个独立提交和验证边界：

1. `ViewStickerAsImage`：`WeMessageApi` 解码/写入重构、feature。
2. `BeautifyConversationList`：`WeConversationListViewApi`、`HideConversationListDividers` 迁移、feature。

本设计文档单独提交，不能与实现文件混在同一 commit。随后两个实现提交的变更列表中不得出现 `AntiStatusDeletion` 或 `WeTextStatusApi`。
