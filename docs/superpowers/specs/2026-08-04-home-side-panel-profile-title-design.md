# HomeSidePanel 资料入口与主页标题组件设计

## 状态

- 日期：2026-08-04
- 设计状态：已批准
- 目标功能：`主页侧滑面板` / `HomeSidePanel`

## 目标

在现有侧栏资料展示基础上补齐资料和微信状态入口，并在微信主页标题栏左侧增加与侧栏一致的头像、昵称和状态组件。实现必须兼容 LauncherUI 中同时存在或动态替换的两层 `ActionBarContainer`/`Toolbar`，只在主页 Tab 显示，不破坏微信原有右侧搜索和更多按钮。

## 侧栏资料组件

侧栏顶部资料区域调整为三个独立交互区域：

1. 头像：保持圆形显示，点击后先播放 one-shot 侧栏关闭动画，动画完成后打开微信个人资料页 `SettingsPersonalInfoUI`。
2. 昵称与状态：昵称和状态作为同一个可点击区域，状态行右侧显示 Outlined 右箭头；点击后先关闭侧栏，再打开微信状态编辑页。
3. 设置按钮：资料区域最右侧增加 Outlined `Settings` IconButton，点击进入新的“侧栏设置”二级页，不关闭侧栏。

状态原有展示规则保持：无状态时显示绿色圆点和“在线”，读取失败时显示红色错误图标、“获取失败”和刷新按钮。错误态的刷新按钮只负责刷新状态，不触发页面跳转；昵称、状态文字和右箭头所在的外层区域负责进入状态编辑页。

头像与文字区域的 ripple 必须按各自形状裁切。点击资料或状态跳转时继续复用现有“等待侧栏关闭动画完成后再启动 Activity”的导航约束。

## 主页标题组件

主页标题组件参考 QQ 截图的紧凑两行结构：

- 左侧为圆形头像。
- 右侧第一行为昵称，第二行为当前状态。
- 整体高度适配微信现有 ActionBar，不改变标题栏高度。
- 文本必须限制行数和宽度，避免遮挡右侧搜索、更多等原生菜单。
- 颜色、字号和按压反馈适配 Material 3 暗色/亮色主题。

点击行为：

- 点击标题组件头像：展开 HomeSidePanel。
- 点击标题组件昵称或状态：打开微信状态编辑页。

可见性规则：

- 仅当 MainTabUI 当前逻辑 Tab 为首页 Tab（index 0）时显示。
- 切换通讯录、发现、我，进入聊天页，或主页 ViewPager 处于非 settled 首页状态时立即隐藏。
- 回到主页 Tab 后恢复显示并刷新资料状态。
- 标题组件作为 Toolbar 子 View 参与现有双标题栏变换，因此侧栏打开时与微信标题栏一起 dim、缩放、下沉并被面板覆盖。

## 双标题栏注入

采用结构化多 Toolbar 注入，不依赖混淆资源名，也不使用 `getIdentifier`。

HomeSidePanelSession 在现有 pre-draw 视图同步阶段递归查找 LauncherUI decor 中所有类名为 `androidx.appcompat.widget.Toolbar` 的 View。每个 Toolbar 最多安装一个由当前 Session 管理的 ComposeView 标题组件，并记录到 Session 的弱引用/所有权集合中。

- 微信同时保留两层 ActionBar 时，两层 Toolbar 都安装同样组件；实际处于前景的标题栏自然负责显示和点击。
- 微信替换或重新创建 Toolbar 时，下一次 pre-draw 自动发现并安装。
- 已脱离视图树的 Toolbar 及其 ComposeView 从集合移除并释放 composition。
- Session detach 时释放所有标题 ComposeView，不遗留跨 LauncherUI 的 View 或 Controller 引用。
- 不挪动 ActionBarContainer，不修改其 LayoutParams，避免再次触发 AppCompat ActionBarOverlayLayout 类型转换问题。

标题 ComposeView 共享 Session 的 `HomeSidePanelController.uiState`，不新建第二套资料仓库或网络请求。

## 原生“微信”标题开关

新增唯一一项面板级自定义：

```text
隐藏微信字样
```

- 使用 `WePrefs.Companion.prefOption` 持久化。
- 默认值为 `false`，即默认保留微信原生“微信”标题。
- 在“侧栏设置”二级页使用 Material 3 `Switch` 展示。
- 只在主页 Tab 且开关开启时隐藏原生标题 TextView。
- 原生标题通过稳定的 `android.R.id.text1` 查找，不使用微信混淆资源 ID。
- 非主页 Tab、开关关闭、Session detach 或功能禁用时，恢复该 TextView 在接管前的可见性。
- pre-draw 阶段持续同步，防止微信更新标题时重新显示被隐藏的文字。

本开关是此前“不提供天气和一言之外自定义能力”边界的唯一明确例外，不增加其他侧栏布局或样式选项。

## 设置页与返回层级

`HomeSidePanelCardMode` 新增 `PANEL_SETTINGS`。设置按钮进入该模式，页面包含标题栏返回按钮和“隐藏微信字样”Switch。

返回顺序继续遵循现有规则：

1. 位于侧栏设置、天气设置或一言设置时，系统返回只回到侧栏首页。
2. 位于侧栏首页时，下一次返回才关闭侧栏。
3. 侧栏关闭后不消费微信返回。

设置值变更后立即写入 `prefOption` 并更新当前 Session UI，不要求重启微信。

## Activity 导航与兼容

个人资料入口使用显式 Intent 打开：

```text
com.tencent.mm.plugin.setting.ui.setting.SettingsPersonalInfoUI
```

状态编辑页按以下顺序尝试：

1. `com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivityV2`
2. `com.tencent.mm.plugin.textstatus.ui.TextStatusDoWhatActivity`

每个目标在 `startActivity` 前都必须通过 `Intent.resolveActivity(packageManager)`。状态 Intent 写入微信原生 Companion 使用的 `KEY_IS_ENTER=true`。只有目标可解析时才启动；V2 不可用时回退到旧版，两者均不可用时显示明确 Toast，不能抛出 `ActivityNotFoundException` 导致微信崩溃。

所有标题栏和侧栏入口复用同一个 HomeSidePanelNavigator API，避免两套 Activity 选择逻辑分叉。

## 状态同步

- 标题组件和侧栏组件消费同一个 `HomeSidePanelProfile`。
- LauncherUI resume、面板打开、状态编辑页返回后，现有资料刷新流程负责重新读取状态。
- 标题组件的显示/隐藏由 Session 当前 Tab 状态控制，不写入持久化配置。
- 点击标题头像展开侧栏时取消当前关闭动画，并从当前 progress 平滑动画到完全展开。

## 错误处理

- Activity 不存在：显示 Toast，不启动页面。
- 头像加载失败：继续显示昵称首字占位，不影响点击。
- 状态读取失败：保留现有“获取失败”表现和刷新入口；仍允许点击文字区域进入状态编辑页。
- Toolbar 暂未创建：本帧不注入，后续 pre-draw 重试，不输出 UI 调试日志。

## 测试与验收

本次主要涉及微信宿主 View、Toolbar 生命周期、Activity 解析和点击行为，不为这些行为添加低价值 JVM 测试。若新增与 Android/微信完全解耦的纯状态规则，才复用现有 HomeSidePanel 规则测试。

自动验证：

```bash
./gradlew :app:testStandardDebugUnitTest
./x build
git diff --check
```

未修改 DexKit 声明或解析步骤时不运行 `./x dex-test`。

真机验收必须覆盖：

- 两层标题栏在主页均不会出现缺失、重叠错位或重复点击问题。
- 非首页 Tab 和聊天页不显示资料标题组件。
- 侧栏头像、侧栏状态、标题头像、标题状态四个入口行为正确。
- V2/旧版状态页选择失败时不会崩溃。
- “隐藏微信字样”默认关闭、即时生效、重启后保持，并在非首页 Tab 恢复原生标题。
- 标题组件随侧栏一起 dim、缩放、下沉和被覆盖。
- 设置页系统返回只退回侧栏首页，不同时关闭侧栏。

## 实施边界

- 不使用 `getIdentifier`。
- 不新增 `PhoneWindow` Hook。
- 不修改微信 ActionBarContainer/Toolbar 的 LayoutParams 或父子关系。
- 不新增 DexKit 解析目标，除非实现阶段证明结构化 View 查找无法覆盖支持版本；若发生该情况，必须重新提交设计变更。
- 不新增“隐藏微信字样”之外的面板自定义选项。
