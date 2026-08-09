package dev.ujhhgtg.wekit.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Download
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.DownloadIcon
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Feature(
    id = "图片保存到本地",
    nameRes = "feature_download_images_to_local_storage_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_download_images_to_local_storage_description",
)
object DownloadImagesToLocalStorage : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "DownloadImagesToLocalStorage"

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777021,
                "下载",
                DownloadIcon,
                MaterialSymbols.Outlined.Download,
                { msgInfo -> msgInfo.type == MessageType.IMAGE }
            ) { _, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val path = WeMessageApi.downloadImage(msgInfo.serverId) ?: run {
                        WeLogger.e(TAG, "failed to cache & download image")
                        showToastSuspend("图片下载失败! 查看日志以了解错误详情")
                        return@launch
                    }
                    showToastSuspend("已将图片下载到 $path")
                }
            }
        )
    }
}
