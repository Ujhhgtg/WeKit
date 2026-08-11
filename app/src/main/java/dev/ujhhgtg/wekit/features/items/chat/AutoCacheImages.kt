package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(
    id = "自动缓存图片",
    nameRes = "feature_auto_cache_images_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_auto_cache_images_description",
)
object AutoCacheImages : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    private const val TAG = "AutoCacheImages"

    private var useWhitelist by WePrefs.prefOption("autocache_images_use_whitelist", false)
    private var whitelist by WePrefs.prefOption("autocache_images_whitelist", emptySet())
    private var blacklist by WePrefs.prefOption("autocache_images_blacklist", emptySet())

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: return
        if (type != MessageType.IMAGE.code) return

        // 自己发出的图片本身就在本地, 无需缓存
        if (values.getAsInteger("isSend") == 1) return

        val talker = values.getAsString("talker") ?: return

        if (useWhitelist) {
            if (talker !in whitelist) return
        } else {
            if (talker in blacklist) return
        }

        val msgSvrId = values.getAsLong("msgSvrId") ?: return
        if (msgSvrId == 0L) return

        WeLogger.i(TAG, "detected image message; msgSvrId=$msgSvrId, auto caching")
        CoroutineScope(Dispatchers.IO).launch {
            val path = WeMessageApi.cacheImage(msgSvrId)
            if (path != null) {
                WeLogger.i(TAG, "cached image to $path")
            } else {
                WeLogger.e(TAG, "failed to auto-cache image msgSvrId=$msgSvrId")
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_cache_images_name)) },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable { useWhitelistState = !useWhitelistState },
                            trailingContent = { Switch(checked = useWhitelistState, onCheckedChange = null) },
                            supportingContent = { Text(stringResource(if (useWhitelistState) R.string.chat_auto_cache_images_whitelist_description else R.string.chat_auto_cache_images_blacklist_description)) },
                            content = { Text(stringResource(if (useWhitelistState) R.string.chat_auto_cache_whitelist_selected else R.string.chat_auto_cache_blacklist_selected)) },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()
                                val currentList = if (useWhitelistState) whitelist else blacklist

                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = stringResource(if (useWhitelistState) R.string.chat_auto_cache_select_whitelist else R.string.chat_auto_cache_select_blacklist),
                                        contacts = contacts,
                                        initialSelectedWxIds = currentList,
                                        onDismiss = onDismiss
                                    ) { selectedIds ->
                                        if (useWhitelistState) {
                                            whitelist = selectedIds
                                        } else {
                                            blacklist = selectedIds
                                        }
                                        showToast(localizedChatQuantity(R.plurals.chat_auto_cache_contacts_saved, selectedIds.size, selectedIds.size))
                                        onDismiss()
                                    }
                                }
                            },
                            supportingContent = { Text(stringResource(R.string.chat_auto_cache_select_contacts_hint)) },
                            content = { Text(stringResource(if (useWhitelistState) R.string.chat_auto_cache_configure_whitelist else R.string.chat_auto_cache_configure_blacklist)) },
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        useWhitelist = useWhitelistState
                        onDismiss()
                    }) { Text(stringResource(R.string.dialog_confirm)) }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
            )
        }
    }
}
