package dev.ujhhgtg.wekit.extensions

import android.app.Activity
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.settings.ExtensionsSettingsActivity
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog

/** Dependency-prompt dialogs shared by extension-pack consumers. */
object ExtensionPackDialogs {

    /** 弱依赖:建议安装,取消也允许功能继续。 */
    fun suggestInstall(activity: Activity, pack: ExtensionPack) {
        showComposeDialog(activity) {
            PackDialog(
                titleRes = R.string.extensions_pack_suggest_title,
                messageRes = R.string.extensions_pack_suggest_msg,
                pack = pack,
                onDismiss = onDismiss,
                onConfirm = { openExtensions(activity, pack, autoDownload = true) },
            )
        }
    }

    /** 强依赖:功能不可用,必须安装才能继续。 */
    fun requireInstall(activity: Activity, pack: ExtensionPack) {
        showComposeDialog(activity) {
            PackDialog(
                titleRes = R.string.extensions_pack_required_title,
                messageRes = R.string.extensions_pack_required_msg,
                pack = pack,
                onDismiss = onDismiss,
                onConfirm = { openExtensions(activity, pack, autoDownload = true) },
            )
        }
    }

    fun openExtensions(activity: Activity, pack: ExtensionPack?, autoDownload: Boolean) {
        val intent = Intent(activity, ExtensionsSettingsActivity::class.java)
        if (pack != null) intent.putExtra(ExtensionsSettingsActivity.EXTRA_PACK_ID, pack.id)
        intent.putExtra(ExtensionsSettingsActivity.EXTRA_AUTO_DOWNLOAD, autoDownload)
        activity.startActivity(intent)
    }
}

@Composable
private fun PackDialog(
    titleRes: Int,
    messageRes: Int,
    pack: ExtensionPack,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = stringResource(pack.nameRes)
    AlertDialogContent(
        title = { Text(stringResource(titleRes, name)) },
        text = { Text(stringResource(messageRes, name)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(R.string.extensions_pack_action_install)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}
