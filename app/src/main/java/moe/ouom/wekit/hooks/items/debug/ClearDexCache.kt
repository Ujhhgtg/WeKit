package moe.ouom.wekit.hooks.items.debug

import android.content.Context
import androidx.compose.material3.Text
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.dexkit.cache.DexCacheManager
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog

@HookItem(path = "调试/清除适配信息", desc = "点击清除适配信息")
object ClearDexCache : ClickableHookItem() {
    override fun onClick(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("清除适配信息") },
                text = {
                    Text(
                        "这将删除所有的 DEX 适配信息，宿主重启后需要重新适配。\n" +
                                "确定清除吗？"
                    )
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                confirmButton = {
                    TextButton(onClick = {
                        DexCacheManager.clearAllCache()
                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }

    override fun noSwitchWidget(): Boolean = true
}