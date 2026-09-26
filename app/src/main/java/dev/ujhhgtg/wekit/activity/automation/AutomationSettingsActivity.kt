package dev.ujhhgtg.wekit.activity.automation

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationSpecs
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.animation.predictiveback.weKitNavTransition
import dev.ujhhgtg.wekit.ui.automation.AutomationBatchScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationContactPicker
import dev.ujhhgtg.wekit.ui.automation.AutomationContactPickerScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationContactsScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationHomeScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationItemsScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationMembersScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationPresetsScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationScopeScreen
import dev.ujhhgtg.wekit.ui.automation.LocalAutomationContactPicker
import dev.ujhhgtg.wekit.ui.navigation.LocalNavigator
import dev.ujhhgtg.wekit.ui.navigation.Navigator
import dev.ujhhgtg.wekit.ui.navigation.rememberM3NavEffects
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import kotlinx.serialization.Serializable
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection

/**
 * Settings of one automation feature (red packets, auto reply, custom notifications, ...), driven
 * entirely by its [AutomationSpec]. Named `*SettingsActivity` so
 * [dev.ujhhgtg.wekit.loader.utils.ActivityProxy] routes it when launched from WeChat's process.
 */
@Keep
class AutomationSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val spec = AutomationSpecs.byId(intent.getStringExtra(EXTRA_FEATURE)!!)

        setContent {
            WeKitLocaleProvider(mode = LocaleResourceMode.InjectedHost) {
                val dark = ThemeSettings.themeMode.resolve()
                ModuleTheme(darkTheme = dark) {
                    AutomationSettingsRoot(spec, onFinish = { finish() })
                }
            }
        }
    }

    companion object {
        private const val EXTRA_FEATURE = "automation_feature"

        fun start(context: Context, spec: AutomationSpec) {
            context.startActivity(
                Intent(context, AutomationSettingsActivity::class.java).putExtra(EXTRA_FEATURE, spec.id)
            )
        }
    }
}

@Serializable
sealed interface AutomationRoute : NavKey {
    @Serializable
    data object Home : AutomationRoute

    @Serializable
    data class Scope(val scope: AutomationScope) : AutomationRoute

    @Serializable
    data object Contacts : AutomationRoute

    @Serializable
    data class Members(val groupId: String) : AutomationRoute

    @Serializable
    data object Presets : AutomationRoute

    @Serializable
    data object Items : AutomationRoute

    @Serializable
    data class Batch(val scopes: List<AutomationScope>) : AutomationRoute

    /** A contact screen for the pending [AutomationContactPicker] request [id]. */
    @Serializable
    data class ContactPicker(val id: String) : AutomationRoute
}

@Composable
private fun AutomationSettingsRoot(spec: AutomationSpec, onFinish: () -> Unit) {
    val backStack = rememberNavBackStack<AutomationRoute>(AutomationRoute.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    val back = { if (navigator.backStackSize() <= 1) onFinish() else navigator.pop() }
    val contactPicker = remember(navigator) { AutomationContactPicker(navigator) }

    CompositionLocalProvider(LocalNavigator provides navigator, LocalAutomationContactPicker provides contactPicker) {
        NavDisplay(
            backStack = backStack,
            onBack = back,
            transition = weKitNavTransition(ThemeSettings.pageTransitionAnimation),
            effects = rememberM3NavEffects(),
        ) {
            entry<AutomationRoute.Home> {
                AutomationHomeScreen(spec, onBack = onFinish, onOpen = { navigator.push(it) })
            }
            entry<AutomationRoute.Scope>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                AutomationScopeScreen(spec, key.scope, onBack = back, onOpen = { navigator.push(it) })
            }
            entry<AutomationRoute.Contacts>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                AutomationContactsScreen(spec, onBack = back, onOpen = { navigator.push(it) })
            }
            entry<AutomationRoute.Members>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                AutomationMembersScreen(spec, key.groupId, onBack = back, onOpen = { navigator.push(it) })
            }
            entry<AutomationRoute.Presets>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                AutomationPresetsScreen(spec, onBack = back, onOpen = { navigator.push(it) })
            }
            entry<AutomationRoute.Items>(swipeDismiss = NavSwipeDirection.LeftToRight) {
                AutomationItemsScreen(spec, onBack = back, onOpen = { navigator.push(it) })
            }
            entry<AutomationRoute.Batch>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                AutomationBatchScreen(spec, key.scopes, onBack = back)
            }
            entry<AutomationRoute.ContactPicker>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                AutomationContactPickerScreen(contactPicker, key.id, onBack = back)
            }
        }
    }
}
