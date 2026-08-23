@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package dev.ujhhgtg.wekit.activity.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
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
import com.composables.icons.materialsymbols.outlined.Chevron_right
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
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.testsettings.NukeSettingsContent
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBar
import dev.ujhhgtg.wekit.ui.content.FloatingBottomBarDefaults
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.content.m3AppBarBlur
import dev.ujhhgtg.wekit.ui.content.m3AppBarColor
import dev.ujhhgtg.wekit.ui.content.rememberMaterial3BlurBackdrop
import dev.ujhhgtg.wekit.ui.navigation.LocalNavigator
import dev.ujhhgtg.wekit.ui.navigation.Navigator
import dev.ujhhgtg.wekit.ui.navigation.rememberM3NavEffects
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.SettingsUiEngine
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.ui.animation.predictiveback.weKitNavTransition
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

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
                        SettingsUiEngine.MATERIAL3 -> ModuleTheme {
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
//  Feature categories
// ---------------------------------------------------------------------------

data class FeatureCategory(
    val id: String,
    @StringRes val titleRes: Int,
    val icon: ImageVector,
)

val FEATURE_CATEGORIES = listOf(
    FeatureCategory(FeatureCategoryIds.CHAT, R.string.feature_category_chat_title, MaterialSymbols.Outlined.Chat),
    FeatureCategory(FeatureCategoryIds.CONTACTS_GROUPS, R.string.feature_category_contacts_groups_title, MaterialSymbols.Outlined.Contacts),
    FeatureCategory(FeatureCategoryIds.PAYMENT, R.string.feature_category_payment_title, MaterialSymbols.Outlined.Payments),
    FeatureCategory(FeatureCategoryIds.MOMENTS, R.string.feature_category_moments_title, MaterialSymbols.Outlined.Camera),
    FeatureCategory(FeatureCategoryIds.SYSTEM_PRIVACY, R.string.feature_category_system_privacy_title, MaterialSymbols.Outlined.Wand_stars),
    FeatureCategory(FeatureCategoryIds.VOIP, R.string.feature_category_voip_title, MaterialSymbols.Outlined.Call),
    FeatureCategory(FeatureCategoryIds.NOTIFICATIONS, R.string.feature_category_notifications_title, MaterialSymbols.Outlined.Notifications),
    FeatureCategory(FeatureCategoryIds.BEAUTIFY, R.string.feature_category_beautify_title, MaterialSymbols.Outlined.Imagesearch_roller),
    FeatureCategory(FeatureCategoryIds.OFFICIAL_ACCOUNTS, R.string.feature_category_official_accounts_title, MaterialSymbols.Outlined.Newspaper),
    FeatureCategory(FeatureCategoryIds.MINIAPPS, R.string.feature_category_miniapps_title, MaterialSymbols.Outlined.Package_2),
    FeatureCategory(FeatureCategoryIds.CHANNELS, R.string.feature_category_channels_title, MaterialSymbols.Outlined.Movie),
    FeatureCategory(FeatureCategoryIds.PROFILE, R.string.feature_category_profile_title, MaterialSymbols.Outlined.Account_circle),
    FeatureCategory(FeatureCategoryIds.DEBUG, R.string.feature_category_debug_title, MaterialSymbols.Outlined.Bug_report),
    FeatureCategory(FeatureCategoryIds.SCRIPTING_JAVA, R.string.feature_category_scripting_java_title, MaterialSymbols.Outlined.Terminal),
    FeatureCategory(FeatureCategoryIds.ENTERTAIN, R.string.feature_category_entertain_title, MaterialSymbols.Outlined.Comedy_mask),
    FeatureCategory(FeatureCategoryIds.BATCH, R.string.feature_category_batch_title, MaterialSymbols.Outlined.Checklist),
    FeatureCategory(FeatureCategoryIds.HOME_SCREEN_MENU, R.string.feature_category_home_screen_menu_title, MaterialSymbols.Outlined.Add_circle),
    FeatureCategory(FeatureCategoryIds.CONTACT_DETAILS, R.string.feature_category_contact_details_title, MaterialSymbols.Outlined.Contact_page),
)

/**
 * Pseudo-category shown above the real ones. Deliberately kept out of [FEATURE_CATEGORIES] —
 * it isn't something a feature can declare in its `categoryIds` property.
 */
const val NEW_FEATURES_CATEGORY = "new_features"
const val ENABLED_FEATURES_CATEGORY = "enabled_features"

@StringRes
fun featureCategoryTitleRes(categoryId: String): Int =
    when (categoryId) {
        NEW_FEATURES_CATEGORY -> R.string.feature_category_new_features_title
        ENABLED_FEATURES_CATEGORY -> R.string.feature_category_enabled_title
        FeatureCategoryIds.API -> R.string.feature_category_api_title
        else -> FEATURE_CATEGORIES.first { it.id == categoryId }.titleRes
    }

/**
 * Features whose source file entered the repo within [NewFeatures.WINDOW_DAYS] days of the build's
 * HEAD commit (joined by generated Feature source keys at compile time), newest first.
 *
 * Features that belong to no real category — the `API` internals — are dropped: they carry no
 * switch a user would meaningfully flip.
 */
val NEW_FEATURE_ITEMS: List<BaseFeature>
    get() = FeatureCategoryState.newItems

// ---------------------------------------------------------------------------
//  Root: three-tab pager + floating bottom bar, with category drill-down
// ---------------------------------------------------------------------------

/** Navigation targets for the Settings activity's stack. */
@Serializable
sealed interface SettingsRoute : NavKey {
    @Serializable
    data object Main : SettingsRoute
    @Serializable
    data class Category(val id: String) : SettingsRoute
    @Serializable
    data object License : SettingsRoute
}

@Composable
private fun SettingsRoot(onFinish: () -> Unit) {
    val backStack = rememberNavBackStack<SettingsRoute>(SettingsRoute.Main)
    val navigator = remember(backStack) { Navigator(backStack) }
    val pagerState = rememberPagerState(pageCount = { TAB_ITEMS.size })
    val scope = rememberCoroutineScope()

    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = {
                if (navigator.backStackSize() <= 1) onFinish() else navigator.pop()
            },
            transition = weKitNavTransition(ThemeSettings.pageTransitionAnimation),
            effects = rememberM3NavEffects(),
        ) {
            entry<SettingsRoute.Main> {
                MainPagerScreen(
                    pagerState = pagerState,
                    onOpenCategory = { navigator.push(SettingsRoute.Category(it)) },
                    onOpenLicense = { navigator.push(SettingsRoute.License) },
                )
            }
            entry<SettingsRoute.Category>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                CategoryDetailScreen(categoryId = key.id, onBack = { navigator.pop() })
            }
            entry<SettingsRoute.License>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                LicenseScreen(onBack = { navigator.pop() })
            }
        }
    }

    // Back at the stack root returns the pager to the home tab; further back finishes.
    BackHandler(enabled = navigator.backStackSize() == 1 && pagerState.currentPage != 0) {
        scope.launch { pagerState.animateScrollToPage(0) }
    }
}

@Composable
private fun MainPagerScreen(
    pagerState: PagerState,
    onOpenCategory: (String) -> Unit,
    onOpenLicense: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val backdrop = rememberLayerBackdrop()
    val barBottomPadding = 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
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
                    0 -> HomePager()
                    1 -> FeaturesPager(onOpenCategory = onOpenCategory)
                    2 -> LogsPager()
                    else -> SettingsPager(onOpenLicense = onOpenLicense)
                }
            }
        }

        FloatingBottomBar(
            items = TAB_ITEMS,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = barBottomPadding),
            selectedIndex = { pagerState.targetPage },
            onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
            backdrop = backdrop,
            colors = FloatingBottomBarDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                indicatorColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                activeContentColor = MaterialTheme.colorScheme.primary,
            ),
            iconContent = { item, index ->
                Crossfade(
                    targetState = index == pagerState.targetPage,
                    animationSpec = tween(200),
                    label = "navIcon",
                ) { selected ->
                    Icon(
                        imageVector = if (selected) item.filled else item.outlined,
                        contentDescription = stringResource(item.labelRes),
                    )
                }
            },
            labelContent = { item, _ ->
                Text(
                    text = stringResource(item.labelRes),
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible
                )
            },
        )
    }
}

private data class NavItem(
    @StringRes val labelRes: Int,
    val outlined: ImageVector,
    val filled: ImageVector,
)

private val TAB_ITEMS = listOf(
    NavItem(R.string.nav_home, MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home),
    NavItem(R.string.nav_features, MaterialSymbols.Outlined.Tune, MaterialSymbols.OutlinedFilled.Tune),
    NavItem(R.string.nav_logs, MaterialSymbols.Outlined.Article, MaterialSymbols.OutlinedFilled.Article),
    NavItem(R.string.nav_settings, MaterialSymbols.Outlined.Settings, MaterialSymbols.OutlinedFilled.Settings),
)

/** Bottom padding so scrollable content clears the floating bar. */
val CONTENT_BOTTOM_INSET = 88.dp

// ---------------------------------------------------------------------------
//  Shared scaffold (Material 3 Expressive)
// ---------------------------------------------------------------------------

@Composable
fun M3ListScaffold(
    title: String,
    navigationIcon: @Composable (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val barBackdrop = rememberMaterial3BlurBackdrop()
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            LargeFlexibleTopAppBar(
                modifier = Modifier.m3AppBarBlur(barBackdrop),
                title = { Text(title) },
                navigationIcon = { navigationIcon?.invoke() },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = barBackdrop.m3AppBarColor(),
                    scrolledContainerColor = barBackdrop.m3AppBarColor(),
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(barBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
            contentPadding = innerPadding,
            content = content,
        )
    }
}

// ---------------------------------------------------------------------------
//  Shared feature row (Material 3) — used by category detail and search
// ---------------------------------------------------------------------------

@Composable
fun FeatureRow(
    item: BaseFeature,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val context = LocalComponentActivity.current
    val localizedContext = LocalWeKitLocalizedContext.current
    val configKey = item.technicalId
    val localizedName = item.localizedName(localizedContext)
    val localizedDescription = item.localizedDescription(localizedContext)

    DisposableEffect(configKey) {
        (item as SwitchFeature).setToggleCompletionCallback {
            FeatureCategoryState.notifyToggleChanged()
            onCheckedChange(item.isEnabled)
        }
        onDispose {}
    }

    fun toggle(requested: Boolean) {
        item as SwitchFeature
        if (item.onBeforeToggle(requested, context)) {
            WePrefs.putBool(configKey, requested)
            item.isEnabled = requested
            FeatureCategoryState.notifyToggleChanged()
            onCheckedChange(requested)
        }
    }

    when (item) {
        is ClickableFeature -> {
            val openConfig: () -> Unit = {
                runCatching { item.onClick(context) }
                    .onFailure { WeLogger.e("SettingsActivity", "onClick failed for ${item.technicalPath}", it) }
            }

            if (item.noSwitchWidget) {
                // Pure action row: the whole row triggers the feature's action.
                BaseWidget(
                    iconPlaceholder = false,
                    title = localizedName,
                    description = localizedDescription,
                    onClick = openConfig,
                    trailingContent = {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Chevron_right,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            } else {
                // Dual click areas: the main area opens the feature's config, the
                // switch past the divider toggles it (WeAgent "Memory" row pattern).
                SwitchWidget(
                    iconPlaceholder = false,
                    title = localizedName,
                    description = localizedDescription,
                    onClick = openConfig,
                    trailingDivider = true,
                    checked = checked,
                    onCheckedChange = { toggle(it) },
                )
            }
        }

        is SwitchFeature -> SwitchWidget(
            iconPlaceholder = false,
            title = localizedName,
            description = localizedDescription,
            checked = checked,
            onCheckedChange = { toggle(it) },
        )
    }
}
