package dev.ujhhgtg.wekit.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(
    id = "重置适配信息",
    nameRes = "feature_reset_dex_cache_name",
    categoryIds = [FeatureCategoryIds.DEBUG],
    descriptionRes = "feature_reset_dex_cache_description",
)
object ResetDexCache : ClickableFeature() {

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("清除适配信息") },
                text = {
                    Text(
                        "这将删除所有的 DEX 适配信息，宿主重启后需要重新适配。\n" +
                                "确定清除吗？"
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        CoroutineScope(Dispatchers.IO).launch {
                            showToastSuspend("正在清除...")
                            DexCacheManager.clearAllCache()
                            showToastSuspend("清除成功!")
                            withContext(Dispatchers.Main) {
                                onDismiss()
                            }
                        }
                    }) { Text("确定") }
                })
        }
    }

    override val noSwitchWidget = true
}
