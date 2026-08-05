# HomeSidePanel 原生底栏系统导航区适配设计

## 状态

- 日期：2026-08-05
- 目标分支：`dev`
- 设计方案：已由维护者确认采用方案 A
- 书面规格：等待维护者复核
- 实施状态：未开始

## 背景

`HomeSidePanel` 为 `LauncherUI` 强制启用 edge-to-edge：Hook `enableEdge2Edge()` 返回
`true`，并对窗口调用 `WindowCompat.setDecorFitsSystemWindows(window, false)`。当前实现只请求
重新派发 Insets，没有让微信原生 `LauncherUIBottomTabView` 稳定消费底部系统导航区。

因此在三键虚拟导航模式，以及保留手势指示条的全面屏手势模式下，微信原生底栏可能继续贴着
窗口物理底边，受到导航键或手势指示条遮挡。`ReplaceNavigationBar` 不受影响，因为它的 Compose
底栏自己读取 `WindowInsets.navigationBars`。

进入聊天后临时恢复的现象不能作为修复路径。`ImmersiveChatUi` 会在聊天布局 attach/layout 后，
再次对聊天页所属的共享 `LauncherUI` Window 应用 edge-to-edge；聊天页切换同时制造新的布局和
Insets 派发，返回主页时该窗口状态继续保留。`FloatingChatFooter` 和 `FloatingChatHeader` 还会增加
聊天视图的 pre-draw、margin、padding 和 requestLayout 更新，但它们没有直接修改主页底栏。

## 目标

- `HomeSidePanel` 单独启用时，微信原生底栏始终避开当前底部系统导航区。
- 同时覆盖三键虚拟导航和保留手势指示条的手势导航。
- 导航模式、窗口 Insets 或页面可见性变化后自动收敛到新的正确值。
- 与 `ReplaceNavigationBar` 共存；自定义 Compose 底栏继续只由自身消费 Insets。
- 不依赖用户进入聊天，也不修改三个聊天美化功能。

## 非目标

- 不改 `ReplaceNavigationBar.kt`、`ImmersiveChatUi.kt`、`FloatingChatFooter.kt` 或
  `FloatingChatHeader.kt`。
- 不改微信原生底栏的图标、文字、背景、高度基准或交互。
- 不替换微信现有的 `OnApplyWindowInsetsListener`。
- 不新增 DexKit 声明或改变 Dex 解析逻辑。
- 不承诺运行中关闭功能后完整恢复宿主视图；遵循 WeKit 现有 best-effort teardown 约定。

## 方案比较

### 方案 A：在现有 Session pre-draw 中同步原生底栏 padding

在 `HomeSidePanelSession` 已有的 `OnPreDrawListener` 中定位
`com.tencent.mm.ui.LauncherUIBottomTabView`，读取当前 `navigationBars` 与
`tappableElement` 的底部 Insets，取两者最大值并绝对写入原生底栏的 `paddingBottom`。

优点：

- 与当前 Session 生命周期和幂等 pre-draw 模式一致。
- 不覆盖微信的 Insets listener。
- 能响应导航模式、旋转、窗口和可见性变化。
- 改动集中在 `HomeSidePanel.kt`。

代价是 pre-draw 每帧检查一次，但目标值不变时不写 View 属性，不产生持续 requestLayout。

### 方案 B：给原生底栏安装新的 OnApplyWindowInsetsListener

回调链更直接，但 `ViewCompat.setOnApplyWindowInsetsListener` 会替换该 View 上已有 listener，可能
破坏微信 8.0.74/8.0.76 自己的 API 35 edge-to-edge 兼容逻辑，故不采用。

### 方案 C：只在 `applyLauncherEdgeToEdge()` 后写一次 padding

实现最少，但底栏可能尚未创建或尚未取得有效 Insets，也无法可靠处理导航模式切换和后续窗口
变化，故不采用。

## 详细设计

### 原生底栏定位与缓存

`HomeSidePanelSession` 在其 `parent` View 树内按稳定完整类名
`com.tencent.mm.ui.LauncherUIBottomTabView` 定位原生底栏。Session 缓存已找到的 View；缓存失效
或 View 已脱离窗口时重新查找。未找到时当帧不处理，后续 pre-draw 继续重试，不逐帧打印日志。

不使用资源 ID、子 View 固定索引或混淆字段名。

### Insets 计算

通过 `ViewCompat.getRootWindowInsets(bottomBar)` 获取 `WindowInsetsCompat`。当 Insets 尚不可用时
不改现有 padding，等待后续 pre-draw。

目标底部避让量为：

```text
max(
    WindowInsetsCompat.Type.navigationBars().bottom,
    WindowInsetsCompat.Type.tappableElement().bottom,
)
```

`navigationBars` 覆盖三键虚拟导航；`tappableElement` 补充保留手势指示条时可点击安全区域的高度。
只写 `paddingBottom`，保留原生底栏已有的 left、top、right padding。

padding 使用绝对目标值，不在当前值上累加。仅当目标值变化时调用 `setPadding`。

### 旧版微信内部重复 padding

微信 8.0.65–8.0.69 的 `LauncherUIBottomTabView` 会在特定实验开关、API 34+、手势导航条件下，
给其直接子 `LinearLayout` 额外添加导航栏高度。HomeSidePanel 统一在外层原生底栏消费 Insets 后，
将该直接子 `LinearLayout` 的 `paddingBottom` 绝对归零，避免外层和内层重复避让。

只处理 `LauncherUIBottomTabView` 的直接 `LinearLayout` 子 View，不扫描或修改每个 Tab item 的内部
布局。

### 与 ReplaceNavigationBar 共存

`ReplaceNavigationBar` 保留原生 `LauncherUIBottomTabView` 实例，但会清空或替换其子树：

- 非悬浮模式把 `ComposeView` 放进原生底栏容器；
- 悬浮模式隐藏原生底栏，并把 `ComposeView` 直接放到同一个父 `FrameLayout`。

当原生底栏不可见，或其子树包含 `ComposeView` 时，HomeSidePanel 将原生底栏的
`paddingBottom` 清零，并跳过原生 Insets 适配。这样 Compose 底栏继续使用自己的
`WindowInsets.navigationBars`，不会出现双重底部空间。

检测范围限定在原生底栏及其所在主页容器，不会把 HomeSidePanel 面板或标题栏使用的其他
`ComposeView` 误判为 `ReplaceNavigationBar`。

### 调用时机

在 `HomeSidePanelSession.preDrawListener` 中，完成现有宿主子 View 吸收和结构同步后调用原生底栏
Insets 同步。该方法必须幂等：

- View 未找到或 Insets 未准备好：不写入；
- 目标 padding 未变化：不写入；
- ReplaceNavigationBar 生效：清除 HomeSidePanel 可能写入的旧值后不再处理；
- 导航模式或 Insets 变化：下一帧写入新目标值。

## 文件范围

仅修改：

- `app/src/main/java/dev/ujhhgtg/wekit/features/items/beautify/home_screen_panel/HomeSidePanel.kt`

新增本设计文档和后续实施计划；不修改其他功能源码。

## 验证

自动验证：

```bash
git diff --check
./x build
```

本改动不触及 Dex 声明或解析逻辑，因此不运行 `./x dex-test`。该行为依赖微信宿主视图和真实系统
导航 Insets，按照项目测试策略不新增低价值 JVM 测试。

真机验收矩阵：

1. 仅启用 HomeSidePanel，三键虚拟导航：原生底栏完整位于导航键上方。
2. 仅启用 HomeSidePanel，手势导航且显示指示条：原生底栏完整位于指示条安全区上方。
3. 仅启用 HomeSidePanel，手势导航且隐藏指示条：底栏不出现多余空白。
4. HomeSidePanel + ReplaceNavigationBar 非悬浮模式：Compose 底栏无双重底部 padding。
5. HomeSidePanel + ReplaceNavigationBar 悬浮模式：Compose 底栏位置保持现状。
6. 在主页、聊天页之间往返：主页原生底栏无需先进入聊天即可正确，往返后也不跳动。
7. 旋转屏幕或切换系统导航模式后返回微信：底栏在后续布局帧收敛到新 Insets。

## 风险与约束

- `LauncherUIBottomTabView` 完整类名在支持的微信版本中稳定；若未来版本移除该类，本次同步会
  安静退化为不处理，不影响侧栏其他行为。
- pre-draw 只能做轻量幂等检查；不得每帧反射字段、安装监听或无条件 requestLayout。
- 真机通过上述导航模式矩阵之前，只能报告构建验证完成，不能声称设备行为已经确认修复。
