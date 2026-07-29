package dev.ujhhgtg.wekit.activity.testsettings

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.ui.content.nukex.NukeCategoryIcon
import dev.ujhhgtg.wekit.ui.content.nukex.NukeCountAndChevron
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDivider
import dev.ujhhgtg.wekit.ui.content.nukex.NukeEmptyState
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyph
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyphKind
import dev.ujhhgtg.wekit.ui.content.nukex.NukePageScaffold
import dev.ujhhgtg.wekit.ui.content.nukex.NukePreferenceRow
import dev.ujhhgtg.wekit.ui.content.nukex.NukeRevealNavigator
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSearchField
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSettingGroup
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSquircleShape
import dev.ujhhgtg.wekit.ui.content.nukex.NukeStatusPill
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSwitch
import dev.ujhhgtg.wekit.ui.content.nukex.NukeText
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTopAppBar
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTheme
import dev.ujhhgtg.wekit.ui.content.nukex.rememberNukeRevealState
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings

internal data class PreviewCategory(
    val id: String,
    val title: String,
    val glyph: NukeGlyphKind,
    val count: Int,
    val error: Boolean = false,
)

internal data class PreviewFeature(
    val id: String,
    val categoryId: String,
    val title: String,
    val description: String? = null,
    val summary: String? = null,
    val initiallyEnabled: Boolean = false,
    val kind: PreviewFeatureKind = PreviewFeatureKind.Toggle,
)

internal enum class PreviewFeatureKind {
    Toggle,
    ConfigurableToggle,
    Action,
}

private data class SecondaryDestination(
    val id: String,
    val title: String,
    val glyph: NukeGlyphKind,
)

private val categories = listOf(
    PreviewCategory(
        "chat", "聊天", NukeGlyphKind.Send, 11,
    ),
    PreviewCategory(
        "contact", "联系人", NukeGlyphKind.Person, 4,
    ),
    PreviewCategory(
        "explore", "探索", NukeGlyphKind.Search, 1,
    ),
    PreviewCategory(
        "beautify", "美化", NukeGlyphKind.Star, 2,
    ),
    PreviewCategory(
        "simplify", "简化", NukeGlyphKind.CheckCircle, 0,
    ),
    PreviewCategory(
        "entertain", "娱乐", NukeGlyphKind.Heart, 2,
    ),
    PreviewCategory(
        "experimental", "实验性功能", NukeGlyphKind.Info, 3,
    ),
    PreviewCategory(
        "debug", "模块设置及调试", NukeGlyphKind.Settings, 53,
    ),
)

private val secondaryDestinations = listOf(
    SecondaryDestination(
        "update", "检测更新", NukeGlyphKind.CheckCircle,
    ),
    SecondaryDestination(
        "scripts", "脚本设置", NukeGlyphKind.Code,
    ),
    SecondaryDestination(
        "appearance", "界面设置", NukeGlyphKind.Home,
    ),
    SecondaryDestination(
        "about", "关于模块", NukeGlyphKind.Info,
    ),
    SecondaryDestination(
        "reward", "赞赏我们", NukeGlyphKind.Heart,
    ),
)

private val previewFeatures = listOf(
    PreviewFeature(
        id = "AIChat",
        categoryId = "chat",
        title = "AI 聊天",
        description = "让 AI 对名单内收到的每条文字消息进行连续对话回复",
        summary = "gpt-5.6-sol · 白名单 1 个 · 上下文 6 轮",
        initiallyEnabled = true,
        kind = PreviewFeatureKind.ConfigurableToggle,
    ),
    PreviewFeature(
        id = "AntiRevoke",
        categoryId = "chat",
        title = "防撤回",
        description = "阻止微信撤回消息，并在会话中插入自定义提示",
        summary = "yyyy/MM/dd HH:mm",
        initiallyEnabled = true,
        kind = PreviewFeatureKind.ConfigurableToggle,
    ),
    PreviewFeature(
        id = "AntiRevokeNoTip",
        categoryId = "chat",
        title = "阻止消息撤回（无提示）",
        description = "正式版时将此功能被移除",
    ),
    PreviewFeature(
        id = "AutoReceiveRedPacket",
        categoryId = "chat",
        title = "自动领取红包",
        description = "检测新红包消息并按所选模式自动领取",
        summary = "网络请求 · 延迟 0",
        kind = PreviewFeatureKind.ConfigurableToggle,
    ),
    PreviewFeature(
        id = "AutoReceiveTransferMoney",
        categoryId = "chat",
        title = "自动收款",
        description = "检测待收款转账并自动确认收款",
        summary = "延迟 0 毫秒 · 黑名单",
        kind = PreviewFeatureKind.ConfigurableToggle,
    ),
    PreviewFeature(
        id = "ChatAutoReply",
        categoryId = "chat",
        title = "聊天自动回复",
        description = "按任务规则匹配收到的文字消息并自动回复",
        summary = "已启用 1/1 个任务",
        kind = PreviewFeatureKind.ConfigurableToggle,
    ),
    PreviewFeature(
        id = "MaskAllAsRead",
        categoryId = "chat",
        title = "一键已读",
        initiallyEnabled = true,
    ),
    PreviewFeature(
        id = "DisableTypingStatus",
        categoryId = "chat",
        title = "禁止发送输入状态",
        description = "阻止微信向聊天对象发送“正在输入”状态",
    ),
    PreviewFeature(
        id = "DisplayMessageDetails",
        categoryId = "chat",
        title = "显示消息详情",
        description = "在每条聊天消息气泡上方显示精确到秒的发送时间",
    ),
    PreviewFeature(
        id = "SwipeToDeleteConversation",
        categoryId = "chat",
        title = "左滑删除会话",
        description = "在微信会话列表向左滑动，可直接删除对应会话",
    ),
    PreviewFeature(
        id = "SwipeToQuoteMessage",
        categoryId = "chat",
        title = "左滑引用消息",
        description = "在微信聊天中向左滑动消息气泡，达到阈值后快速引用该消息",
    ),
    PreviewFeature(
        id = "DisplayContactId",
        categoryId = "contact",
        title = "显示联系人 ID",
        description = "在联系人资料设置页添加入口，用于查看当前联系人的微信内部 ID，重启微信后生效",
    ),
    PreviewFeature(
        id = "IncreaseForwardingLimit",
        categoryId = "contact",
        title = "提高批量转发人数上限",
        description = "将微信联系人选择页的批量转发上限提高到 999 人",
    ),
    PreviewFeature(
        id = "ModifyFriendsCount",
        categoryId = "contact",
        title = "修改好友数量",
        description = "修改联系人页显示的好友数量，不会改变实际联系人数据",
        summary = "显示 1234 个好友",
        kind = PreviewFeatureKind.ConfigurableToggle,
    ),
    PreviewFeature(
        id = "OpenUserCard",
        categoryId = "contact",
        title = "打开用户资料",
        description = "通过 wxid、群 id 或公众号 id 打开微信资料页",
        kind = PreviewFeatureKind.Action,
    ),
    PreviewFeature(
        id = "AntiMomentsRevoke",
        categoryId = "explore",
        title = "朋友圈防撤回",
        description = "保留他人删除的朋友圈内容，并在正文前添加已删除标记",
    ),
    PreviewFeature(
        id = "CustomInputHint",
        categoryId = "beautify",
        title = "自定义输入框提示",
        description = "自定义微信输入框未输入内容时显示的灰色提示文字，重新进入页面后生效",
        summary = "未设置",
        kind = PreviewFeatureKind.ConfigurableToggle,
    ),
    PreviewFeature(
        id = "MaterialTabView",
        categoryId = "beautify",
        title = "Material 底部导航栏",
        description = "使用紧凑的胶囊式导航栏替换微信主页底栏",
    ),
    PreviewFeature(
        id = "ChatAvatarRotator",
        categoryId = "entertain",
        title = "聊天头像旋转",
        initiallyEnabled = true,
        kind = PreviewFeatureKind.ConfigurableToggle,
    ),
    PreviewFeature(
        id = "EnableRoundAvatar",
        categoryId = "entertain",
        title = "启用圆形头像",
        initiallyEnabled = true,
    ),
    PreviewFeature(
        id = "BypassTeenMode",
        categoryId = "experimental",
        title = "绕过小程序防沉迷",
        description = "绕过后可能没有声音，看广告能恢复",
    ),
    PreviewFeature(
        id = "FocusPadMode",
        categoryId = "experimental",
        title = "强制启用平板模式",
    ),
    PreviewFeature(
        id = "BlockXposedDetection",
        categoryId = "experimental",
        title = "阻止 Xposed 检测",
        description = "阻止应用检测 Xposed 框架，但愿这能保你一命，Google Play 版微信请勿开启",
    ),
)

@Composable
internal fun NukeSettingsRoot() {
    var query by rememberSaveable { mutableStateOf("") }
    var destinationId by rememberSaveable { mutableStateOf<String?>(null) }
    var safetyMode by rememberSaveable { mutableStateOf(false) }
    val enabledStates = remember {
        mutableStateMapOf<String, Boolean>().apply {
            previewFeatures.forEach { put(it.id, it.initiallyEnabled) }
        }
    }
    val revealState = rememberNukeRevealState()

    PredictiveBackHandler(enabled = destinationId != null) { events ->
        revealState.predictiveConceal(
            events = events,
            optimizeExitOrigin = ThemeSettings.nukePageExitOptimization,
        ) {
            destinationId = null
        }
    }

    NukeRevealNavigator(
        state = revealState,
        base = {
            NukeHomePage(
                query = query,
                onQueryChange = { query = it },
                safetyMode = safetyMode,
                onSafetyModeChange = { safetyMode = it },
                enabledStates = enabledStates,
                onOpenDestination = { id, origin ->
                    if (id == "reward") return@NukeHomePage
                    revealState.reveal(origin) {
                        destinationId = id
                    }
                },
            )
        },
        revealed = {
            destinationId?.let { id ->
                NukeDestinationPage(
                    destinationId = id,
                    enabledStates = enabledStates,
                    onBack = { origin ->
                        revealState.conceal(
                            from = origin.takeIf {
                                ThemeSettings.nukePageExitOptimization
                            },
                        ) {
                            destinationId = null
                        }
                    },
                )
            }
        },
    )
}

@Composable
private fun NukeHomePage(
    query: String,
    onQueryChange: (String) -> Unit,
    safetyMode: Boolean,
    onSafetyModeChange: (Boolean) -> Unit,
    enabledStates: MutableMap<String, Boolean>,
    onOpenDestination: (String, Offset) -> Unit,
) {
    val colors = NukeTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        NukeTopAppBar(
            title = "Nuke 设置"
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "search") {
                NukeSearchField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = "搜索",
                )
            }
            if (query.isBlank()) {
                item(key = "safety") {
                    NukeSettingGroup(title = "安全") {
                        NukePreferenceRow(
                            title = "安全模式",
                            description = "在不稳定环境中保守加载模块能力。",
                            leading = { NukeCategoryIcon(NukeGlyphKind.Info) },
                            trailing = {
                                NukeSwitch(
                                    checked = safetyMode,
                                    onCheckedChange = onSafetyModeChange,
                                )
                            },
                            onClick = { onSafetyModeChange(!safetyMode) },
                        )
                    }
                }
                categories.chunked(3).forEachIndexed { index, group ->
                    item(key = "hooker_group_$index") {
                        NukeCategoryGroup(
                            title = if (index == 0) {
                                "模块"
                            } else {
                                null
                            },
                            entries = group,
                            onOpenDestination = onOpenDestination,
                        )
                    }
                }
                secondaryDestinations.chunked(3).forEachIndexed { index, group ->
                    item(key = "secondary_group_$index") {
                        NukeSecondaryGroup(
                            title = if (index == 0) {
                                "通用"
                            } else {
                                null
                            },
                            entries = group,
                            onOpenDestination = onOpenDestination,
                        )
                    }
                }
            } else {
                item(key = "search_results") {
                    NukeSearchResults(
                        query = query,
                        enabledStates = enabledStates,
                    )
                }
            }
            item(key = "tail") {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun NukeCategoryGroup(
    title: String?,
    entries: List<PreviewCategory>,
    onOpenDestination: (String, Offset) -> Unit,
) {
    NukeSettingGroup(title = title) {
        entries.forEachIndexed { index, entry ->
            NukePreferenceRow(
                title = entry.title,
                leading = {
                    NukeCategoryIcon(
                        glyph = entry.glyph,
                        error = entry.error,
                    )
                },
                trailing = {
                    NukeCountAndChevron(
                        text = entry.count.toString(),
                        error = entry.error,
                    )
                },
                onClick = { origin -> onOpenDestination(entry.id, origin) },
            )
            if (index < entries.lastIndex) {
                NukeDivider()
            }
        }
    }
}

@Composable
private fun NukeSecondaryGroup(
    title: String?,
    entries: List<SecondaryDestination>,
    onOpenDestination: (String, Offset) -> Unit,
) {
    NukeSettingGroup(title = title) {
        entries.forEachIndexed { index, entry ->
            NukePreferenceRow(
                title = entry.title,
                leading = { NukeCategoryIcon(entry.glyph) },
                trailing = { NukeCountAndChevron(text = null) },
                onClick = { origin -> onOpenDestination(entry.id, origin) },
            )
            if (index < entries.lastIndex) {
                NukeDivider()
            }
        }
    }
}

@Composable
private fun NukeSearchResults(
    query: String,
    enabledStates: MutableMap<String, Boolean>,
) {
    val normalizedQuery = query.trim().lowercase()
    val results = previewFeatures.filter { feature ->
        val categoryTitle = categories
            .firstOrNull { it.id == feature.categoryId }
            ?.title
            .orEmpty()
        listOf(
            feature.title,
            feature.description.orEmpty(),
            categoryTitle,
            feature.id,
        ).any {
            matchesNukeQuery(it.lowercase(), normalizedQuery)
        }
    }
    NukeSettingGroup(title = "搜索结果") {
        if (results.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NukeText(
                    text = "没有匹配结果",
                    color = NukeTheme.colors.textPrimary,
                    fontSize = 15,
                    lineHeight = 20,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(4.dp))
                NukeText(
                    text = "试试其他功能名称或关键词",
                    color = NukeTheme.colors.textSecondary,
                    fontSize = 12,
                    lineHeight = 17,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            results.forEachIndexed { index, feature ->
                NukeFeatureRow(
                    feature = feature,
                    checked = enabledStates[feature.id] == true,
                    onCheckedChange = { enabledStates[feature.id] = it },
                    onConfigure = {},
                )
                if (index < results.lastIndex) NukeDivider()
            }
        }
    }
}

private fun matchesNukeQuery(
    candidate: String,
    query: String,
): Boolean {
    if (query.isBlank() || candidate.contains(query)) return true
    var queryIndex = 0
    candidate.forEach { character ->
        if (queryIndex < query.length && character == query[queryIndex]) {
            queryIndex += 1
        }
    }
    return queryIndex == query.length
}

@Composable
private fun NukeDestinationPage(
    destinationId: String,
    enabledStates: MutableMap<String, Boolean>,
    onBack: (Offset) -> Unit,
) {
    when (destinationId) {
        "debug" -> NukeDebugPage(onBack)
        "update" -> NukeUpdatePage(onBack)
        "scripts" -> NukeScriptsPage(onBack)
        "appearance" -> NukeAppearancePage(onBack)
        "about" -> NukeAboutPage(onBack)

        else -> {
            val category = categories.firstOrNull { it.id == destinationId }
            if (category != null) {
                NukeCategoryPage(
                    category = category,
                    enabledStates = enabledStates,
                    onBack = onBack,
                )
            }
        }
    }
}

@Composable
private fun NukeCategoryPage(
    category: PreviewCategory,
    enabledStates: MutableMap<String, Boolean>,
    onBack: (Offset) -> Unit,
) {
    var configuredFeature by remember { mutableStateOf<PreviewFeature?>(null) }
    val features = previewFeatures.filter { it.categoryId == category.id }
    features.forEach { feature ->
        if (feature.id !in enabledStates) enabledStates[feature.id] = feature.initiallyEnabled
    }

    NukePageScaffold(
        title = category.title,
        onBack = onBack,
    ) {
        item {
            if (features.isEmpty()) {
                NukeEmptyState(
                    title = "暂无功能",
                    description = "当前分组还没有可展示的功能",
                )
            } else {
                NukeSettingGroup(title = category.title) {
                    features.forEachIndexed { index, feature ->
                        NukeFeatureRow(
                            feature = feature,
                            checked = enabledStates[feature.id] == true,
                            onCheckedChange = { enabledStates[feature.id] = it },
                            onConfigure = {
                                if (
                                    feature.id == "AIChat" ||
                                    feature.id == "AutoReceiveRedPacket" ||
                                    feature.id == "ChatAvatarRotator"
                                ) {
                                    configuredFeature = feature
                                }
                            },
                        )
                        if (index < features.lastIndex) NukeDivider()
                    }
                }
            }
        }
    }

    configuredFeature?.let { feature ->
        NukeConfigurationDialog(
            feature = feature,
            onDismiss = { configuredFeature = null },
        )
    }
}

@Composable
private fun NukeFeatureRow(
    feature: PreviewFeature,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onConfigure: () -> Unit,
) {
    NukePreferenceRow(
        title = feature.title,
        description = feature.description,
        onClick = {
            when (feature.kind) {
                PreviewFeatureKind.ConfigurableToggle -> onConfigure()
                PreviewFeatureKind.Toggle -> onCheckedChange(!checked)
                PreviewFeatureKind.Action -> Unit
            }
        },
        trailing = {
            feature.summary?.let { summary ->
                NukeText(
                    text = summary,
                    modifier = Modifier.width(94.dp),
                    color = NukeTheme.colors.textSecondary,
                    fontSize = 11,
                    lineHeight = 15,
                    textAlign = TextAlign.End,
                    maxLines = 2,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (
                feature.kind == PreviewFeatureKind.ConfigurableToggle ||
                feature.kind == PreviewFeatureKind.Action
            ) {
                NukeGlyph(
                    kind = NukeGlyphKind.Chevron,
                    color = NukeTheme.colors.accent.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            if (feature.kind != PreviewFeatureKind.Action) {
                NukeSwitch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                )
            }
        },
    )
}

private data class DebugHooker(
    val name: String,
    val id: String,
    val normal: Boolean,
    val reason: String = "No recorded exception",
)

private data class DebugFixture(
    val normalCount: Int,
    val unavailableCount: Int,
    val errorCount: Int,
    val hookers: List<DebugHooker>,
)

private val lightDebugFixture = DebugFixture(
    normalCount = 7,
    unavailableCount = 0,
    errorCount = 0,
    hookers = listOf(
        DebugHooker("NetSceneQueue", "NetSceneQueue", true),
        DebugHooker("ConversationStorage", "ConversationStorage", true),
        DebugHooker("SettingMenuInject", "SettingMenuInject", true),
        DebugHooker(
            "强制启用平板模式",
            "FocusPadMode",
            true,
        ),
        DebugHooker(
            "聊天头像旋转",
            "ChatAvatarRotator",
            true,
        ),
        DebugHooker(
            "阻止 Xposed 检测",
            "BlockXposedDetection",
            true,
        ),
        DebugHooker(
            "启用圆形头像",
            "EnableRoundAvatar",
            true,
        ),
    ),
)

private val darkDebugFixture = DebugFixture(
    normalCount = 49,
    unavailableCount = 0,
    errorCount = 4,
    hookers = listOf(
        DebugHooker("ChattingUi", "ChattingUi", true),
        DebugHooker("NativeFileSystem", "NativeFileSystem", true),
        DebugHooker(
            "StorageFeatureService",
            "StorageFeatureService",
            false,
            "Dex descriptor StorageFeatureService.ClassMessengerStorageService analysis failed",
        ),
        DebugHooker("ImageSend", "ImageSend", true),
        DebugHooker("CoreAccount", "CoreAccount", true),
    ),
)

@Composable
private fun NukeDebugPage(
    onBack: (Offset) -> Unit,
) {
    var selectedHooker by remember { mutableStateOf<DebugHooker?>(null) }
    val fixture = if (NukeTheme.colors.isLight) lightDebugFixture else darkDebugFixture
    NukePageScaffold(
        title = "模块设置及调试",
        onBack = onBack,
    ) {
        item {
            NukeSettingGroup(title = "操作") {
                NukePreferenceRow(
                    title = "重启宿主",
                    description = "重新启动当前宿主应用。",
                    leading = { NukeCategoryIcon(NukeGlyphKind.Restart) },
                )
            }
        }
        item {
            NukeSettingGroup(title = "状态概览") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    NukeStatusPill(
                        "正常 ${fixture.normalCount}",
                        Color(0xFF16A34A),
                    )
                    NukeStatusPill(
                        "不可用 ${fixture.unavailableCount}",
                        Color(0xFFD97706),
                    )
                    NukeStatusPill(
                        "异常 ${fixture.errorCount}",
                        Color(0xFFDC2626),
                    )
                }
            }
        }
        item {
            NukeSettingGroup(title = "Hookers") {
                fixture.hookers.forEachIndexed { index, hooker ->
                    NukeDebugHookerRow(
                        hooker = hooker,
                        onClick = { selectedHooker = hooker },
                    )
                    if (index < fixture.hookers.lastIndex) NukeDivider()
                }
            }
        }
    }

    selectedHooker?.let { hooker ->
        NukeHookerDetailDialog(
            hookerName = hooker.name,
            hookerId = hooker.id,
            normal = hooker.normal,
            reason = hooker.reason,
            onDismiss = { selectedHooker = null },
        )
    }
}

@Composable
private fun NukeDebugHookerRow(
    hooker: DebugHooker,
    onClick: () -> Unit,
) {
    val statusColor = if (hooker.normal) Color(0xFF16A34A) else Color(0xFFDC2626)
    NukePreferenceRow(
        title = hooker.name,
        description = "${hooker.reason}\n${hooker.id}",
        leading = {
            Box(
                Modifier
                    .size(34.dp)
                    .background(
                        statusColor.copy(alpha = 0.12f),
                        NukeSquircleShape(11.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                NukeGlyph(
                    kind = if (hooker.normal) NukeGlyphKind.CheckCircle else NukeGlyphKind.Error,
                    color = statusColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        trailing = {
            NukeStatusPill(
                if (hooker.normal) {
                    "正常"
                } else {
                    "异常"
                },
                statusColor,
            )
        },
        onClick = { onClick() },
    )
}
