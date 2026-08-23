package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AutoCacheImages : ClickableFeature(), WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "自动缓存图片"
    override val nameRes = R.string.feature_auto_cache_images_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_cache_images_description

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
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(if (useWhitelistState) R.string.chat_auto_cache_whitelist_selected else R.string.chat_auto_cache_blacklist_selected),
                                description = stringResource(if (useWhitelistState) R.string.chat_auto_cache_images_whitelist_description else R.string.chat_auto_cache_images_blacklist_description),
                                checked = useWhitelistState,
                                onCheckedChange = {
                                    useWhitelistState = it
                                    useWhitelist = it
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(if (useWhitelistState) R.string.chat_auto_cache_configure_whitelist else R.string.chat_auto_cache_configure_blacklist),
                                description = stringResource(R.string.chat_auto_cache_select_contacts_hint),
                                onClick = {
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
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
            )
        }
    }
}
