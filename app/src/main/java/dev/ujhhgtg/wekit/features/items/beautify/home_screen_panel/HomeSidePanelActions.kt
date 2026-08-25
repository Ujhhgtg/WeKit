package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlinedfilled.Camera
import com.composables.icons.materialsymbols.outlinedfilled.Cancel
import com.composables.icons.materialsymbols.outlinedfilled.Extension
import com.composables.icons.materialsymbols.outlinedfilled.Favorite
import com.composables.icons.materialsymbols.outlinedfilled.Mark_chat_read
import com.composables.icons.materialsymbols.outlinedfilled.Movie
import com.composables.icons.materialsymbols.outlinedfilled.Person_add
import com.composables.icons.materialsymbols.outlinedfilled.Qr_code_scanner
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Update
import com.composables.icons.materialsymbols.outlinedfilled.Wallet
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.settings.SettingsActivity
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.items.beautify.BeautifyText
import dev.ujhhgtg.wekit.features.items.beautify.beautifyText
import dev.ujhhgtg.wekit.utils.killHost
import dev.ujhhgtg.wekit.utils.restartHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal data class HomeSidePanelActionSpec(
    val kind: HomeSidePanelActionKind,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

internal fun homeSidePanelActionSpec(kind: HomeSidePanelActionKind): HomeSidePanelActionSpec = when (kind) {
    HomeSidePanelActionKind.ADD_FRIEND -> HomeSidePanelActionSpec(
        kind,
        R.string.home_side_panel_action_add_friend,
        MaterialSymbols.OutlinedFilled.Person_add,
    )

    HomeSidePanelActionKind.SCAN -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_scan,
        MaterialSymbols.OutlinedFilled.Qr_code_scanner,
    )

    HomeSidePanelActionKind.MOMENTS -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_moments,
        MaterialSymbols.OutlinedFilled.Camera,
    )

    HomeSidePanelActionKind.WALLET -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_wallet,
        MaterialSymbols.OutlinedFilled.Wallet,
    )

    HomeSidePanelActionKind.CHANNELS -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_channels,
        MaterialSymbols.OutlinedFilled.Movie,
    )

    HomeSidePanelActionKind.WECHAT_SETTINGS -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_settings,
        MaterialSymbols.OutlinedFilled.Settings,
    )

    HomeSidePanelActionKind.FAVORITES -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_favorites,
        MaterialSymbols.OutlinedFilled.Favorite,
    )

    HomeSidePanelActionKind.WEKIT_SETTINGS -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_module_settings,
        MaterialSymbols.OutlinedFilled.Extension,
    )

    HomeSidePanelActionKind.RESTART_WECHAT -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_restart_wechat,
        MaterialSymbols.OutlinedFilled.Update,
    )

    HomeSidePanelActionKind.FORCE_STOP_WECHAT -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_force_stop,
        MaterialSymbols.OutlinedFilled.Cancel,
    )

    HomeSidePanelActionKind.MARK_ALL_READ -> HomeSidePanelActionSpec(
        kind,
        R.string.fab_default_mark_all_read,
        MaterialSymbols.OutlinedFilled.Mark_chat_read,
    )
}

internal class HomeSidePanelActionExecutor(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val closePanel: ((() -> Unit)?) -> Unit,
    private val publishMessage: (BeautifyText) -> Unit,
) {

    fun execute(kind: HomeSidePanelActionKind) {
        if (kind == HomeSidePanelActionKind.MARK_ALL_READ) {
            closePanel(null)
            executeAfterPanelClosed(kind)
        } else {
            closePanel { executeAfterPanelClosed(kind) }
        }
    }

    fun openPaymentCode() {
        closePanel {
            val opened = tryStartActivity(
                Intent().setClassName(activity.packageName, PAYMENT_CODE_CLASS),
            ) || tryStartActivity(
                Intent().setClassName(activity.packageName, PAYMENT_CODE_FALLBACK_CLASS),
            )
            if (!opened) {
                publishMessage(beautifyText(R.string.home_side_panel_action_launch_failed))
            }
        }
    }

    private fun executeAfterPanelClosed(kind: HomeSidePanelActionKind) {
        when (kind) {
            HomeSidePanelActionKind.ADD_FRIEND -> {
                startWeChatActivity("com.tencent.mm.plugin.subapp.ui.pluginapp.AddMoreFriendsUI")
            }

            HomeSidePanelActionKind.SCAN -> {
                startWeChatActivity("com.tencent.mm.plugin.scanner.ui.BaseScanUI")
            }

            HomeSidePanelActionKind.MOMENTS -> {
                startWeChatActivity("com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI")
            }

            HomeSidePanelActionKind.WALLET -> {
                startWeChatActivity("com.tencent.mm.plugin.mall.ui.MallIndexUIv2") {
                    putExtra("key_not_goto_launcher_ui_when_back", true)
                }
            }

            HomeSidePanelActionKind.CHANNELS -> {
                startWeChatActivity("com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI")
            }

            HomeSidePanelActionKind.WECHAT_SETTINGS -> {
                startWeChatActivity("com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI")
            }

            HomeSidePanelActionKind.FAVORITES -> {
                startWeChatActivity("com.tencent.mm.plugin.fav.ui.FavoriteIndexUI")
            }

            HomeSidePanelActionKind.WEKIT_SETTINGS -> startActivity(
                Intent(activity, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )

            HomeSidePanelActionKind.RESTART_WECHAT -> restartHost()
            HomeSidePanelActionKind.FORCE_STOP_WECHAT -> killHost()
            HomeSidePanelActionKind.MARK_ALL_READ -> scope.launch(Dispatchers.IO) {
                WeConversationApi.markAllAsRead()
                publishMessage(beautifyText(R.string.fab_all_marked_read))
            }
        }
    }

    private fun startWeChatActivity(
        className: String,
        extras: Intent.() -> Unit = {},
    ) {
        startActivity(Intent().setClassName(activity.packageName, className).apply(extras))
    }

    private fun startActivity(intent: Intent) {
        if (!tryStartActivity(intent)) {
            publishMessage(beautifyText(R.string.home_side_panel_action_launch_failed))
        }
    }

    private fun tryStartActivity(intent: Intent): Boolean {
        try {
            activity.startActivity(intent)
            return true
        } catch (_: ActivityNotFoundException) {
            return false
        }
    }

    private companion object {
        const val PAYMENT_CODE_CLASS =
            "com.tencent.mm.plugin.offline.ui.WalletOfflineCoinPurseUI"
        const val PAYMENT_CODE_FALLBACK_CLASS =
            "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"
    }
}
