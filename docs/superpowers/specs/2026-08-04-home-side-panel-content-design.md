# HomeSidePanel 内容功能设计

## 状态

- 日期：2026-08-04
- 设计状态：已获用户确认，等待 spec 审阅
- 目标包名：`dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel`

## 目标

在现有 HomeSidePanel 侧滑交互、返回键处理、edge-to-edge、FAB 变换和双标题栏处理之上，补齐真实内容功能：真实微信资料和状态、天气、一言、Material 3 UI、暗色模式、实际快捷入口以及天气和一言设置页。

## 范围

本次设计包含：

- Material 3 Compose 内容和暗色模式。
- 真实头像、昵称、微信号和当前微信状态。
- 微信状态的自定义表情和状态错误态。
- Xiaomi Weather 天气卡片、城市索引、缓存、刷新和设置。
- 首次启动从微信个人资料读取天气城市。
- 用户主动触发的系统定位检测、权限和错误处理。
- Hitokoto 一言卡片、预加载、刷新、缓存、限流和设置。
- 现有 Tile/ListItem 的真实入口和 Material Symbols 图标。
- 将 HomeSidePanel 相关源文件迁移到新的功能包。

不包含：

- 天气和一言之外的面板自定义能力。
- 任意 URL 的一言 API 配置。
- 新增全局 ViewModel 或全局 PhoneWindow hook。
- 修改现有侧滑手势、返回键、edge-to-edge、FAB 和标题栏视觉规则。

## 包结构和文件职责

所有新功能文件放在 `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/`，使用统一包名：

```text
dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel
```

文件职责如下：

- `HomeSidePanel.kt`：Feature 注册、微信 Hook、LauncherUI 生命周期和 Session 创建。
- `HomeSidePanelSession.kt`：视图层级、手势分发、dim、缩放、FAB 和标题栏变换。
- `HomeSidePanelGestureState.kt`：纯 Kotlin 手势状态机。
- `HomeSidePanelController.kt`：面板状态、后台预加载、刷新动作和页面模式。
- `HomeSidePanelModels.kt`：资料、状态、天气、一言和 UI 状态模型。
- `HomeSidePanelPreferences.kt`：天气/一言设置和缓存的 WePrefs 访问。
- `HomeSidePanelContent.kt`：主页 Material 3 Compose 内容。
- `HomeSidePanelSettingsContent.kt`：天气和一言卡片内设置页。
- `HomeSidePanelProfileRepository.kt`：微信资料、状态和状态表情读取。
- `HomeSidePanelWeatherRepository.kt`：城市选择、定位、天气请求和天气缓存。
- `HomeSidePanelHitokotoRepository.kt`：一言请求、缓存和请求限流。
- `HomeSidePanelCityIndex.kt`：打包的 Xiaomi Weather 城市索引和搜索/匹配逻辑。

`AddMainScreenFab.kt` 保持原包名和现有行为不变；新的子包仍属于同一 app module，可以访问其 `internal` 宿主视图查询 API。

## 总体架构

现有 `HomeSidePanelSession` 继续负责宿主视图树和交互层，不直接访问数据库、微信混淆类或网络。Session 创建一个绑定到 LauncherUI 的 `HomeSidePanelController`，Compose 内容只消费 `StateFlow<HomeSidePanelUiState>`。

生命周期：

1. `WeMainActivityBeautifyApi.methodDoOnCreate` 完成后创建 Session 和 Controller。
2. Controller 立即启动后台预加载资料、状态、一言和天气。
3. 默认天气城市先使用北京，不能等待个人资料读取或网络请求完成。
4. 面板开始打开时重新读取一次微信资料和当前状态。
5. 有缓存时先显示缓存，再根据过期时间后台刷新。
6. LauncherUI 销毁或 Session detach 时取消 Controller scope 和未完成请求。
7. Controller 不使用全局 ViewModel，避免微信 Activity 重建和账号切换造成状态泄漏。

所有网络、城市索引和数据库工作放在后台线程；Compose 状态更新回到主线程。现有 Hook 注册不增加 `PhoneWindow` hook，也不使用 `getIdentifier`。

## 数据模型

### 微信资料

```kotlin
data class HomeSidePanelProfile(
    val wxId: String,
    val nickname: String,
    val avatarUrl: String,
    val status: HomeSidePanelStatusUiState,
)
```

头像和昵称来自现有 API：

- `WeApi.selfWxId`
- `WeDatabaseApi.getSelfProfileField(SelfProfileField.NAME)`
- `WeDatabaseApi.getAvatarUrl(wxId)`

`SelfProfileField` 增加：

```kotlin
COUNTRY_CODE(12324)
PROVINCE_CODE(12325)
CITY_CODE(12326)
```

现有 `PROVINCE(12293)` 和 `CITY(12292)` 继续用于显示名称和兼容旧资料。

### 微信状态

```kotlin
data class HomeSidePanelStatus(
    val statusId: String,
    val description: String,
    val iconId: String,
    val emoji: HomeSidePanelStatusEmoji?,
)

data class HomeSidePanelStatusEmoji(
    val md5: String?,
    val url: String?,
    val thumbUrl: String?,
    val attachedText: String?,
)
```

UI 状态：

```kotlin
sealed interface HomeSidePanelStatusUiState {
    data object Loading : HomeSidePanelStatusUiState
    data class Ready(val status: HomeSidePanelStatus) : HomeSidePanelStatusUiState
    data object NoStatus : HomeSidePanelStatusUiState
    data class Error(val message: String) : HomeSidePanelStatusUiState
}
```

两个目标版本的服务入口分别为：

- 8.0.65：`q54.f0.f355361a.G().b(wxid)`。
- 8.0.76：`dj4.m0.f260030a.G().b(wxid)`。

最终 Hook/DexKit 解析不得依赖 `f0`、`m0`、`K1`、`H0` 等混淆名称。解析以 TextStatus 表、稳定日志字符串、方法签名和返回类型结构为锚点。返回对象统一读取稳定的 `field_UserName`、`field_StatusID`、`field_IconID`、`field_Description`、`field_ExpireTime` 和 `field_EmojiInfo` 数据。

TextStatus 存储已经过滤过期记录；没有有效状态时返回 `NoStatus`。`field_EmojiInfo` 使用稳定 protobuf 字段解析，优先显示 `thumbUrl`，其次 `url`，再尝试微信本地表情资源，全部失败时保留状态图标和文字。

顶部状态展示规则：

- `Ready`：显示状态表情和状态文字。
- `NoStatus`：显示绿色圆点和「在线」。
- `Error`：显示红色叉号、「获取失败」以及右侧刷新 `IconButton`。
- `Loading`：显示小型加载指示器。

刷新失败不能伪装成「在线」，也不能使用旧状态冒充当前状态。

## 天气设计

### 城市索引

打包 Xiaomi Weather 城市索引，每项包含：

```kotlin
data class WeatherCity(
    val countryCode: String,
    val province: String,
    val city: String,
    val district: String?,
    val cityNum: String,
    val latitude: Double?,
    val longitude: Double?,
)
```

索引用于资料匹配、设置页搜索、定位结果匹配和 Xiaomi `locationKey` 构造。搜索支持城市、省份、区县及拼音/简化输入。

匹配优先级：

1. `countryCode + province + city`。
2. `countryCode + city`。
3. `city`。
4. 无匹配时返回明确失败原因，不猜测其他城市。

默认城市固定为：

```text
北京
locationKey=weathercn:101010100
```

### 个人资料读取

首次初始化立即使用北京作为可用城市，然后在后台读取微信资料：

1. 读取 `COUNTRY_CODE`、省份和城市字段。
2. 只接受 `CN`、`HK`、`MO`、`TW`。
3. 使用资料显示名称匹配城市索引。
4. 匹配成功后切换天气城市并保存。
5. 地区不支持、资料缺失、数据库未就绪或匹配失败时保留北京，并保存明确错误状态。

设置页的“从个人资料读取”完全复用此流程。错误文案必须区分：非支持地区、地区缺失、城市缺失、城市无法匹配和读取异常。

### 自动检测

“自动检测”只在用户主动点击后请求定位权限。首版使用系统 `LocationManager` 获取城市级粗略位置，不自动弹出权限请求。

流程：

1. 检查定位权限和系统定位开关。
2. 权限缺失时请求粗略定位权限。
3. 获取单次位置，设置超时。
4. 使用系统反向地理编码。
5. 将省份/城市匹配到本地 Xiaomi 城市索引。
6. 保存成功城市并刷新天气。

权限拒绝、定位关闭、定位超时、反向地理编码失败和城市匹配失败均显示独立错误。自动检测失败不会清除当前城市，也不会阻止搜索和手动选择。

### 天气请求和 UI 状态

天气接口固定为 Xiaomi Weather：

```text
https://weatherapi.market.xiaomi.com/wtr-v3/weather/all
```

请求使用 `locationKey=weathercn:<city_num>`、`sign=zUFJoAR2ZVrDy1vF3D07`、`isGlobal=false`、`locale=zh_cn` 和天气预报参数。

```kotlin
sealed interface WeatherUiState {
    data object Loading : WeatherUiState
    data class Ready(
        val snapshot: WeatherSnapshot,
        val refreshing: Boolean = false,
    ) : WeatherUiState
    data class Error(
        val message: String,
        val cached: WeatherSnapshot?,
    ) : WeatherUiState
}
```

天气卡片至少显示城市、当前天气、当前温度、体感温度、最高/最低温度、湿度、风速和更新时间，并根据天气文字映射 Material Symbols 图标。

- 点击天气卡片刷新。
- 长按天气卡片切换到天气设置页。
- 有缓存时先显示缓存，再后台刷新。
- 同一时间只允许一个请求，连续点击使用最小刷新间隔。
- 请求失败时保留缓存并显示重试入口。

## 一言设计

### 数据和设置

```kotlin
data class HitokotoSnapshot(
    val uuid: String,
    val text: String,
    val type: String?,
    val source: String?,
    val author: String?,
    val creator: String?,
    val createdAt: String?,
    val fetchedAt: Long,
)

data class HitokotoSettings(
    val categories: Set<String>,
    val minLength: Int?,
    val maxLength: Int?,
    val charset: String,
    val showSource: Boolean,
    val showAuthor: Boolean,
)
```

接口固定为 `https://v1.hitokoto.cn/`，请求使用官方 `c`、`encode=json`、`min_length`、`max_length` 和 `charset` 参数。首版不开放自定义 endpoint。

默认设置为全部分类、不限制长度、`utf-8`、显示来源和作者。

### 预加载、缓存和限流

主页创建后立即启动后台一言预加载。若有上一条成功缓存，先显示缓存，再用新请求替换；没有缓存且请求失败时显示明确失败态和重试入口。

Repository 必须保证：

- 同一时间只有一个请求。
- 连续点击不会重复发起请求。
- 最小请求间隔为一秒，满足官方每秒两次限制。
- 设置保存触发的新请求也必须经过相同限流器。

### 卡片和设置页

- 点击卡片获取另一条随机一言。
- 长按卡片将卡片内容切换为设置页，不启动新的 Activity。
- 设置页有返回按钮、分类多选、最小/最大长度、编码、显示来源、显示作者、恢复默认和保存。
- 长度必须为非负整数，最大长度不能小于最小长度，至少选择一个分类。
- 保存成功后返回展示页并按新设置请求一条一言。
- 卡片使用 `FormatQuote`、`Refresh`、`Settings` 等 Material Symbols。

## Compose 布局和真实入口

内容顺序保持现有 Tile/ListItem 设计：

```text
ProfileHeader
WeatherCard
QuickTiles: 扫一扫 / 收付款 / 收藏
MenuCard: 朋友圈 / 视频号 / 清空未读 / WeKit 设置
HitokotoCard
```

全部颜色来自 `MaterialTheme.colorScheme`，通过现有 `InjectedUiTheme` 自动适配暗色模式。内容使用 edge-to-edge 的安全区内边距，避免被系统状态栏遮挡。

真实入口：

- 扫一扫：微信 `BaseScanUI`，`QrCodeScanner`。
- 收付款：微信钱包入口，使用 `Wallet`/`Payments` 语义图标；不同版本入口差异时降级到钱包首页。
- 收藏：微信 `FavoriteIndexUI`，`CollectionsBookmark`。
- 朋友圈：`WeApi.openMoments()`，`PhotoLibrary`。
- 视频号：微信 `FinderHomeAffinityUI`，`VideoLibrary`。
- 清空未读：`WeConversationApi.markAllAsRead()`，`MarkEmailRead`。
- WeKit 设置：`SettingsActivity`，`Settings`。

入口点击先关闭侧滑面板，再启动目标页面。所有目标通过明确 Activity 或现有 WeKit API 访问，不使用 `getIdentifier`。

## 错误处理

各数据源独立失败，不能因资料、天气或一言失败阻塞整个面板。

- 头像失败使用首字占位头像。
- 昵称失败最终显示“微信用户”。
- 状态失败显示红色叉号、获取失败和刷新按钮。
- 天气网络失败保留缓存；无缓存时保留北京或显示明确加载失败。
- 一言失败保留缓存；无缓存时显示点击重试。
- 所有网络、JSON、权限和宿主读取异常写入 `WeLogger`，但不能抛到 UI 线程。

现有 Hook 注册不包裹 `try-catch` 或 `runCatching`；可选的网络、缓存和视觉操作可以在所属 Repository/Session 内处理异常。

## 测试和验收

新增纯 JVM 测试覆盖：

- `CN/HK/MO/TW` 地区判定。
- 个人资料城市匹配优先级和失败原因。
- 北京默认回退。
- 自动检测权限/定位错误状态映射。
- 天气 URL、`locationKey` 和 JSON 解析。
- 一言参数序列化、设置校验、缓存和限流。
- 状态 `Loading`、`Ready`、`NoStatus`、`Error` 显示映射。
- 自定义表情 protobuf 解析和降级。

涉及宿主解析的 DexKit 变更必须对支持范围 8.0.65–8.0.76 运行对应 desktop dex-test。最终运行：

```bash
./x build
git diff --check
```

设备验收必须确认：

- 现有侧滑、返回键、edge-to-edge、FAB 和双标题栏行为无回归。
- 头像、昵称和状态为真实微信数据。
- 无状态显示绿色点和「在线」。
- 状态读取失败显示红色叉号、获取失败和刷新按钮。
- 暗色模式下内容和设置页可读。
- 一言在面板打开前已经开始预加载。
- 一言点击刷新、长按设置和参数保存正常。
- 天气点击刷新、长按设置、个人资料读取和主动定位正常。
- 非支持地区、资料缺失、城市匹配失败和定位失败有明确提示。
- 权限拒绝后仍可搜索和手动选择城市。
- 网络断开时天气和一言均能降级。
- 微信 8.0.65–8.0.76 不崩溃。

## 实施边界

本 spec 只定义内容功能和包迁移，不改变已经完成的侧滑面板交互实现。实现时必须保留工作树中与负一屏相关的已有修改，只移动和拆分 HomeSidePanel 相关文件，并同步更新测试包名和引用。
