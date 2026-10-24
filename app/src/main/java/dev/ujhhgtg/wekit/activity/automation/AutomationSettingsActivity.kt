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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import dev.ujhhgtg.wekit.features.automation.AutomationScope
import dev.ujhhgtg.wekit.features.automation.AutomationSpec
import dev.ujhhgtg.wekit.features.automation.AutomationSpecs
import dev.ujhhgtg.wekit.features.automation.AutomationStoredScope
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.WeKitLocaleProvider
import dev.ujhhgtg.wekit.ui.animation.predictiveback.weKitNavTransition
import dev.ujhhgtg.wekit.ui.automation.AutomationEditorContext
import dev.ujhhgtg.wekit.ui.automation.AutomationHomeScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationMembersScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationPicker
import dev.ujhhgtg.wekit.ui.automation.AutomationPickerHost
import dev.ujhhgtg.wekit.ui.automation.AutomationReplyScreen
import dev.ujhhgtg.wekit.ui.automation.AutomationScopeDraft
import dev.ujhhgtg.wekit.ui.automation.AutomationScopeScreen
import dev.ujhhgtg.wekit.ui.automation.rememberAutomationFilePicker
import dev.ujhhgtg.wekit.ui.navigation.LocalNavigator
import dev.ujhhgtg.wekit.ui.navigation.Navigator
import dev.ujhhgtg.wekit.ui.navigation.rememberM3NavEffects
import dev.ujhhgtg.wekit.ui.utils.theme.ModuleTheme
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import java.util.UUID
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
                    AutomationSettingsRoot(spec, activity = this, onFinish = { finish() })
                }
            }
        }
    }

    companion object {
        private const val EXTRA_FEATURE = "automation_feature"

        fun start(context: Context, spec: AutomationSpec) {
            context.startActivity(
                Intent(context, AutomationSettingsActivity::class.java)
                    .putExtra(EXTRA_FEATURE, spec.id),
            )
        }
    }
}

@Serializable
sealed interface AutomationRoute : NavKey {
    @Serializable data object Home : AutomationRoute

    /**
     * Editor of [scope]; its unsaved draft is shared under [editorId] with the reply page. A scope
     * not stored yet starts with [name] and [position] (new presets and rules).
     */
    @Serializable
    data class Scope(
        val scope: AutomationScope,
        val editorId: String = UUID.randomUUID().toString(),
        val name: String? = null,
        val position: Int = 0,
    ) : AutomationRoute

    /**
     * Applies the rules chosen here to every scope in [scopes], leaving their other rules intact.
     */
    @Serializable
    data class Batch(
        val scopes: List<AutomationScope>,
        val editorId: String = UUID.randomUUID().toString(),
    ) : AutomationRoute

    @Serializable data class Members(val groupId: String) : AutomationRoute

    /** Reply sequence of rule [keyId] in the editor [editorId]. */
    @Serializable data class Reply(val editorId: String, val keyId: String) : AutomationRoute
}

@Composable
private fun AutomationSettingsRoot(
    spec: AutomationSpec,
    activity: ComponentActivity,
    onFinish: () -> Unit,
) {
    val backStack = rememberNavBackStack<AutomationRoute>(AutomationRoute.Home)
    val navigator = remember(backStack) { Navigator(backStack) }
    val back = { if (navigator.backStackSize() <= 1) onFinish() else navigator.pop() }
    val picker = remember { AutomationPicker() }
    val pickFiles = rememberAutomationFilePicker()
    // Drafts outlive their editor's composition so the reply page can edit them; they die with the
    // Activity.
    val drafts = remember { HashMap<String, AutomationScopeDraft>() }
    val context = { scope: AutomationScope ->
        AutomationEditorContext(activity, scope, pickFiles, picker)
    }
    val openScope = { scope: AutomationScope, name: String?, position: Int ->
        navigator.push(AutomationRoute.Scope(scope, name = name, position = position))
    }

    CompositionLocalProvider(LocalNavigator provides navigator) {
        NavDisplay(
            backStack = backStack,
            onBack = back,
            transition = weKitNavTransition(ThemeSettings.pageTransitionAnimation),
            effects = rememberM3NavEffects(),
        ) {
            entry<AutomationRoute.Home> {
                AutomationHomeScreen(
                    spec = spec,
                    context = context(AutomationScope.Global),
                    onBack = onFinish,
                    onOpenScope = openScope,
                    onBatch = { scopes -> navigator.push(AutomationRoute.Batch(scopes)) },
                )
            }
            entry<AutomationRoute.Scope>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                val draft =
                    remember(key.editorId) {
                        drafts.getOrPut(key.editorId) {
                            val stored =
                                spec.store.snapshot().scopes[key.scope]
                                    ?: AutomationStoredScope(
                                        key.scope,
                                        name = key.name,
                                        position = key.position,
                                    )
                            AutomationScopeDraft(stored)
                        }
                    }
                AutomationScopeScreen(
                    spec = spec,
                    draft = draft,
                    context = context(key.scope),
                    onBack = back,
                    onOpenMembers = { navigator.push(AutomationRoute.Members(it)) },
                    onOpenReply = { navigator.push(AutomationRoute.Reply(key.editorId, it.id)) },
                )
            }
            entry<AutomationRoute.Batch>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                val draft =
                    remember(key.editorId) {
                        drafts.getOrPut(key.editorId) {
                            AutomationScopeDraft(
                                AutomationStoredScope(key.scopes.first()),
                                batch = key.scopes,
                            )
                        }
                    }
                AutomationScopeScreen(
                    spec = spec,
                    draft = draft,
                    context = context(key.scopes.first()),
                    onBack = back,
                    onOpenMembers = {},
                    onOpenReply = { navigator.push(AutomationRoute.Reply(key.editorId, it.id)) },
                )
            }
            entry<AutomationRoute.Members>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                AutomationMembersScreen(
                    spec = spec,
                    groupId = key.groupId,
                    context = context(AutomationScope.contact(key.groupId)),
                    onBack = back,
                    onOpenScope = { openScope(it, null, 0) },
                )
            }
            entry<AutomationRoute.Reply>(swipeDismiss = NavSwipeDirection.LeftToRight) { key ->
                val draft = drafts[key.editorId]
                if (draft == null) {
                    // The draft did not survive activity recreation; there is nothing to edit.
                    LaunchedEffect(key) { back() }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    AutomationReplyScreen(
                        spec = spec,
                        draft = draft,
                        key =
                            spec.key(key.keyId)
                                as
                                dev.ujhhgtg.wekit.features.automation.AutomationRuleKey<
                                    dev.ujhhgtg.wekit.features.automation.AutomationReplyRule,
                                >,
                        context = context(draft.scope),
                        onBack = back,
                    )
                }
            }
        }
        AutomationPickerHost(picker)
    }
}
