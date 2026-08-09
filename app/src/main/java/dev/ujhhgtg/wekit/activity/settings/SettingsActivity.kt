package dev.ujhhgtg.wekit.activity.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_circle
import com.composables.icons.materialsymbols.outlined.Add_circle
import com.composables.icons.materialsymbols.outlined.Article
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Call
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Checklist
import com.composables.icons.materialsymbols.outlined.Comedy_mask
import com.composables.icons.materialsymbols.outlined.Contact_page
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Imagesearch_roller
import com.composables.icons.materialsymbols.outlined.Movie
import com.composables.icons.materialsymbols.outlined.Newspaper
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Package_2
import com.composables.icons.materialsymbols.outlined.Payments
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Terminal
import com.composables.icons.materialsymbols.outlined.Tune
import com.composables.icons.materialsymbols.outlined.Wand_stars
import com.composables.icons.materialsymbols.outlinedfilled.Article
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Tune
import dev.ujhhgtg.wekit.activity.testsettings.NukeSettingsContent
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeaturesProvider
import dev.ujhhgtg.wekit.features.core.NewFeatures
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBar
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarDefaults
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarItem
import dev.ujhhgtg.wekit.ui.content.MiuixStackNavigator
import dev.ujhhgtg.wekit.ui.content.miuixAppBarBlur
import dev.ujhhgtg.wekit.ui.content.miuixAppBarColor
import dev.ujhhgtg.wekit.ui.content.rememberMiuixBlurBackdrop
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.SettingsUiEngine
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text as M3Text

val LocalComponentActivity = staticCompositionLocalOf<ComponentActivity> { error("not provided") }

@Keep
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CompositionLocalProvider(
                LocalComponentActivity provides this
            ) {
                WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                    when (ThemeSettings.uiEngine) {
                        SettingsUiEngine.MIUIX -> ModuleTheme {
                            SettingsRoot(onFinish = { finish() })
                        }

                        SettingsUiEngine.NUKE -> NukeSettingsContent()
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Feature categories (name -> icon)
// ---------------------------------------------------------------------------

val FEATURE_CATEGORIES = listOf(
    "聊天" to MaterialSymbols.Outlined.Chat,
    "联系人与群组" to MaterialSymbols.Outlined.Contacts,
    "红包与支付" to MaterialSymbols.Outlined.Payments,
    "朋友圈" to MaterialSymbols.Outlined.Camera,
    "系统与隐私" to MaterialSymbols.Outlined.Wand_stars,
    "音视频通话" to MaterialSymbols.Outlined.Call,
    "通知" to MaterialSymbols.Outlined.Notifications,
    "界面美化" to MaterialSymbols.Outlined.Imagesearch_roller,
    "公众号" to MaterialSymbols.Outlined.Newspaper,
    "小程序" to MaterialSymbols.Outlined.Package_2,
    "视频号" to MaterialSymbols.Outlined.Movie,
    "个人资料" to MaterialSymbols.Outlined.Account_circle,
    "调试" to MaterialSymbols.Outlined.Bug_report,
    "脚本 (Java)" to MaterialSymbols.Outlined.Terminal,
    "娱乐" to MaterialSymbols.Outlined.Comedy_mask,
    "批量操作" to MaterialSymbols.Outlined.Checklist,
    "首页右上角菜单" to MaterialSymbols.Outlined.Add_circle,
    "联系人详情页面" to MaterialSymbols.Outlined.Contact_page,
)

/**
 * Pseudo-category shown above the real ones. Deliberately kept out of [FEATURE_CATEGORIES] —
 * it isn't something a feature can declare in its `@Feature(categories = ...)`.
 */
const val NEW_FEATURES_CATEGORY = "新功能"

/**
 * Features whose source file entered the repo within [NewFeatures.WINDOW_DAYS] days of the build's
 * HEAD commit (collected at compile time by `GenerateNewFeaturesTask`), newest first.
 *
 * Features that belong to no real category — the `API` internals — are dropped: they carry no
 * switch a user would meaningfully flip.
 */
val NEW_FEATURE_ITEMS: List<BaseFeature> by lazy {
    val visibleCategories = FEATURE_CATEGORIES.mapTo(mutableSetOf()) { it.first }
    FeaturesProvider.ALL_HOOK_ITEMS.associateBy { it.name }.values
        .mapNotNull { item ->
            NewFeatures.ADDED_AT_BY_NAME[item.name]?.let { addedAt -> item to addedAt }
        }
        .filter { (item, _) -> item.categories.any { it in visibleCategories } }
        .sortedWith(
            compareByDescending<Pair<BaseFeature, Long>> { it.second }
                .thenBy { it.first.name },
        )
        .map { (item, _) -> item }
}

// ---------------------------------------------------------------------------
//  Root: three-tab pager + floating bottom bar, with category drill-down
// ---------------------------------------------------------------------------

/** Navigation targets for the Settings activity's stack. */
private sealed interface SettingsNavTarget {
    data object Main : SettingsNavTarget
    data class Category(val name: String) : SettingsNavTarget
    data object License : SettingsNavTarget
}

@Composable
private fun SettingsRoot(onFinish: () -> Unit) {
    val stack = remember { mutableStateListOf<SettingsNavTarget>(SettingsNavTarget.Main) }
    val pagerState = rememberPagerState(pageCount = { TAB_ITEMS.size })
    val scope = rememberCoroutineScope()

    MiuixStackNavigator(stack = stack, onExitRoot = onFinish) { screen, push, pop ->
        when (screen) {
            SettingsNavTarget.Main -> MainPagerScreen(
                pagerState = pagerState,
                onOpenCategory = { push(SettingsNavTarget.Category(it)) },
                onOpenLicense = { push(SettingsNavTarget.License) },
            )

            is SettingsNavTarget.Category -> CategoryDetailScreen(
                categoryName = screen.name,
                onBack = pop,
            )

            SettingsNavTarget.License -> LicenseScreen(
                onBack = pop,
            )
        }
    }

    // Back unwinds one level at a time: sub-screens first (handled by the navigator's own
    // predictive-back pop), then the home tab, then finish. Enabled only at the stack root so
    // this handler never shadows the navigator's — it is registered later, so it would
    // otherwise win and collapse the stack and the tab in a single press.
    BackHandler(enabled = stack.size == 1 && pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }
}

@Composable
private fun MainPagerScreen(
    pagerState: PagerState,
    onOpenCategory: (String) -> Unit,
    onOpenLicense: () -> Unit,
) {
    val isDragged by pagerState.interactionSource.collectIsDraggedAsState()
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val barBottomPadding = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                key = { it },
            ) { page ->
                when (page) {
                    0 -> HomePager(onOpenFeatures = { scope.launch { pagerState.animateScrollToPage(1) } })
                    1 -> FeaturesPager(onOpenCategory = onOpenCategory)
                    2 -> LogsPager()
                    else -> SettingsPager(onOpenLicense = onOpenLicense)
                }
            }
        }

        val haptic = LocalHapticFeedback.current

        FloatingBottomBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
                .padding(bottom = barBottomPadding),
            selectedIndex = { pagerState.targetPage },
            // Track the pager's fractional scroll 1:1 during a finger swipe; a tab *tap*
            // (programmatic animateScrollToPage, isDragged == false) springs the pill across
            // with the press/glass bulge instead of a flat translate.
            progress = { pagerState.currentPage + pagerState.currentPageOffsetFraction },
            isTracking = { isDragged },
            onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
            backdrop = backdrop,
            tabsCount = TAB_ITEMS.size,
            isBlurEnabled = true,
            colors = FloatingBottomBarDefaults.colors(
                containerColor = MiuixTheme.colorScheme.surfaceContainer,
                indicatorColor = MiuixTheme.colorScheme.primary,
                contentColor = MiuixTheme.colorScheme.onSurfaceSecondary,
                activeContentColor = MiuixTheme.colorScheme.primary,
            ),
        ) {
            // Key the fill crossfade to targetPage (same driver as the pill), not
            // settledPage — settledPage only updates when animateScrollToPage fully
            // finishes, so the icon would fill a beat after the pill has arrived.
            val target = pagerState.targetPage
            TAB_ITEMS.forEachIndexed { index, item ->
                FloatingBottomBarItem(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        scope.launch { pagerState.animateScrollToPage(index) }
                    },
                    modifier = Modifier.defaultMinSize(minWidth = 76.dp),
                ) {
                    Crossfade(
                        targetState = index == target,
                        animationSpec = tween(200),
                        label = "navIcon",
                    ) { selected ->
                        M3Icon(
                            imageVector = if (selected) item.filled else item.outlined,
                            contentDescription = item.label,
                        )
                    }
                    M3Text(
                        text = item.label,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible
                    )
                }
            }
        }
    }
}

private data class NavItem(val label: String, val outlined: ImageVector, val filled: ImageVector)

private val TAB_ITEMS = listOf(
    NavItem("主页", MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home),
    NavItem("功能", MaterialSymbols.Outlined.Tune, MaterialSymbols.OutlinedFilled.Tune),
    NavItem("日志", MaterialSymbols.Outlined.Article, MaterialSymbols.OutlinedFilled.Article),
    NavItem("设置", MaterialSymbols.Outlined.Settings, MaterialSymbols.OutlinedFilled.Settings),
)

/** Bottom padding so scrollable content clears the floating bar. */
val CONTENT_BOTTOM_INSET = 88.dp

// ---------------------------------------------------------------------------
//  Shared scaffold: miuix Scaffold + collapsing TopAppBar + scrollable column
// ---------------------------------------------------------------------------

@Composable
fun MiuixListScaffold(
    title: String,
    navigationIcon: @Composable (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    // Match KernelSU / InstallerX's miuix app bar blur path: textureBlur uses a fixed pixel
    // radius, avoiding the oversized dp->px blur that made section titles smear into blocks.
    val barBackdrop = rememberMiuixBlurBackdrop()
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.miuixAppBarBlur(barBackdrop),
                color = barBackdrop.miuixAppBarColor(),
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = { navigationIcon?.invoke() },
            )
        },
        popupHost = {},
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .then(barBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
//  Grouped card: emit each row as its own LazyColumn item so the list stays
//  virtualized, while the per-corner squircle background makes the rows read as
//  one continuous card (round top on the first row, round bottom on the last).
// ---------------------------------------------------------------------------

/**
 * Squircle background matching [Card]'s look but applied per-row, so a long feature list can be
 * emitted as individual `item {}`s (virtualized) instead of one giant `item { Card { forEach } }`
 * that composes every row at once. [index]/[count] pick which corners are rounded.
 */
@Composable
fun Modifier.groupedCardItem(index: Int, count: Int): Modifier {
    val r = CardDefaults.CornerRadius
    val z = 0.dp
    val top = index == 0
    val bottom = index == count - 1
    return fillMaxWidth()
        .squircleSurface(
            color = MiuixTheme.colorScheme.surfaceContainer,
            topStart = if (top) r else z,
            topEnd = if (top) r else z,
            bottomEnd = if (bottom) r else z,
            bottomStart = if (bottom) r else z,
        )
}

// ---------------------------------------------------------------------------
//  Shared feature row (miuix) — used by category detail and search
// ---------------------------------------------------------------------------

@Composable
fun FeatureRow(
    item: BaseFeature,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalComponentActivity.current
    val configKey = item.name

    DisposableEffect(configKey) {
        (item as SwitchFeature).setToggleCompletionCallback { onCheckedChange(item.isEnabled) }
        onDispose {}
    }

    fun toggle(requested: Boolean) {
        item as SwitchFeature
        if (item.onBeforeToggle(requested, context)) {
            WePrefs.putBool(configKey, requested)
            item.isEnabled = requested
            onCheckedChange(requested)
        }
    }

    when (item) {
        is ClickableFeature -> BasicComponent(
            onClick = {
                runCatching { item.onClick(context) }
                    .onFailure { WeLogger.e("SettingsActivity", "onClick failed for ${item.displayName}", it) }
            },
            endActions = {
                if (!item.noSwitchWidget) {
                    Switch(checked = checked, onCheckedChange = { toggle(it) })
                }
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.name,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                    color = BasicComponentDefaults.titleColor().color,
                )
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector = MaterialSymbols.Outlined.Settings,
                    contentDescription = "Configurable",
                    modifier = Modifier
                        .padding(end = if (!item.noSwitchWidget) 8.dp else 0.dp)
                        .size(20.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = item.description,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = BasicComponentDefaults.summaryColor().color,
            )
        }

        is SwitchFeature -> SwitchPreference(
            title = item.name,
            summary = item.description,
            checked = checked,
            onCheckedChange = { toggle(it) },
        )
    }
}


