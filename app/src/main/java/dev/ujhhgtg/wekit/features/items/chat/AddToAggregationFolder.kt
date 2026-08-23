package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.chat.ConversationAggregation.FolderChoice
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.FolderAddIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast

object AddToAggregationFolder : ClickableFeature(), WeConversationContextMenuApi.IMenuItemsProvider {

    override val technicalId = "添加对话至归拢文件夹"
    override val nameRes = R.string.feature_add_to_aggregation_folder_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_add_to_aggregation_folder_description

    private var showConfigDialog by prefOption("add_to_folder_show_config_dialog", false)

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var showConfigInput by remember { mutableStateOf(showConfigDialog) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_add_to_aggregation_folder_name)) },
                text = {
                    SwitchWidget(
                        title = stringResource(R.string.chat_add_folder_open_config),
                        description = stringResource(R.string.chat_add_folder_open_config_description),
                        checked = showConfigInput,
                        onCheckedChange = {
                            showConfigInput = it
                            showConfigDialog = it
                        },
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } },
            )
        }
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777019,
                text = localizedChatString(R.string.chat_add_folder_menu),
                drawable = FolderAddIcon,
                shouldShow = { context, _ ->
                    val talker = context.talker
                    talker.isNotEmpty() && !ConversationAggregation.isAggregationFolderId(talker)
                },
            ) { context ->
                onMenuClick(context.activity, context.talker)
            }
        )
    }

    private fun onMenuClick(context: Context, talker: String) {
        if (!ConversationAggregation.isEnabled) {
            showToast(context, context.localizedChatString(R.string.chat_add_folder_enable_grouping_first))
            return
        }

        val folders = ConversationAggregation.aggregationFolders()
        if (folders.isEmpty()) {
            showToast(context, context.localizedChatString(R.string.chat_add_folder_none_available))
            return
        }

        showFolderPicker(context, folders, talker)
    }

    private fun showFolderPicker(context: Context, folders: List<FolderChoice>, talker: String) {
        showComposeDialog(context) {
            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(stringResource(R.string.chat_add_folder_menu)) },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(folders, key = { it.id }) { folder ->
                            FolderPickRow(folder) {
                                if (folder.isAuto) {
                                    showToast(
                                        context,
                                        context.localizedChatString(R.string.chat_add_folder_automatic_unavailable, folder.name),
                                    )
                                    return@FolderPickRow
                                }
                                onDismiss()
                                addToFolder(context, folder, talker)
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                }
            )
        }
    }

    private fun addToFolder(context: Context, folder: FolderChoice, talker: String) {
        if (!ConversationAggregation.addToFolder(folder.id, talker)) {
            showToast(context, context.localizedChatString(R.string.chat_add_folder_manual_unavailable, folder.name))
            return
        }
        showToast(context, context.localizedChatString(R.string.chat_add_folder_success, folder.name))
        if (showConfigDialog) {
            ConversationAggregation.showAddToFolderDialog(context, folder.id, talker)
        }
    }

    @Composable
    private fun FolderPickRow(folder: FolderChoice, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp)
        ) {
            Text(folder.name)
            Text(
                text = stringResource(
                    if (folder.isAuto) R.string.chat_add_folder_automatic_summary
                    else R.string.chat_add_folder_manual_summary,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
