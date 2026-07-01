package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.features.api.ui.WeConversationContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.chat.AggregateChats.FolderChoice
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.FolderAddIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast

@Feature(
    name = "添加对话至归拢文件夹",
    categories = ["聊天"],
    description = "在首页对话列表长按菜单添加菜单项, 可将该对话加入「对话归拢」的手动文件夹\n需启用「对话归拢」"
)
object AddToAggregationFolder : ClickableFeature(), WeConversationContextMenuApi.IMenuItemsProvider {

    private var showConfigDialog by prefOption("add_to_folder_show_config_dialog", false)

    override fun onEnable() {
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var showConfigInput by remember { mutableStateOf(showConfigDialog) }
            AlertDialogContent(
                title = { Text("添加对话至归拢文件夹") },
                text = {
                    ListItem(
                        headlineContent = { Text("添加后打开配置对话框") },
                        supportingContent = { Text("将对话加入文件夹后, 自动打开该文件夹的编辑对话框") },
                        trailingContent = {
                            Switch(checked = showConfigInput, onCheckedChange = null)
                        },
                        modifier = Modifier.clickable { showConfigInput = !showConfigInput }
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button({
                        showConfigDialog = showConfigInput
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> {
        return listOf(
            WeConversationContextMenuApi.MenuItem(
                id = 777019,
                text = "加入文件夹",
                drawable = FolderAddIcon,
                shouldShow = { context, _ ->
                    val talker = context.talker
                    talker.isNotEmpty() && !AggregateChats.isAggregationFolderId(talker)
                },
            ) { context ->
                onMenuClick(context.activity, context.talker)
            }
        )
    }

    private fun onMenuClick(context: Context, talker: String) {
        if (!AggregateChats.isEnabled) {
            showToast(context, "请先启用「对话归拢」!")
            return
        }

        val folders = AggregateChats.aggregationFolders()
        if (folders.isEmpty()) {
            showToast(context, "暂无文件夹, 请先在「对话归拢」中新建一个")
            return
        }

        showFolderPicker(context, folders, talker)
    }

    private fun showFolderPicker(context: Context, folders: List<FolderChoice>, talker: String) {
        showComposeDialog(context) {
            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text("加入文件夹") },
                text = {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(folders, key = { it.id }) { folder ->
                            FolderPickRow(folder) {
                                if (folder.isAuto) {
                                    showToast(context, "「${folder.name}」为自动归拢文件夹, 无法手动添加对话")
                                    return@FolderPickRow
                                }
                                onDismiss()
                                addToFolder(context, folder, talker)
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                }
            )
        }
    }

    private fun addToFolder(context: Context, folder: FolderChoice, talker: String) {
        if (!AggregateChats.addToFolder(folder.id, talker)) {
            showToast(context, "「${folder.name}」无法手动添加对话")
            return
        }
        showToast(context, "已加入「${folder.name}」")
        if (showConfigDialog) {
            AggregateChats.showAddToFolderDialog(context, folder.id, talker)
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
                text = if (folder.isAuto) "自动归拢, 不可手动添加" else "手动文件夹",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
