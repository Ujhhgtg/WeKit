package moe.ouom.wekit.hooks.item.dev

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.dexkit.cache.DexCacheManager
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.compose.showComposeDialog

@HookItem(path = "开发者选项/清除适配信息", desc = "点击清除适配信息")
class DexCacheCleaner : BaseClickableFunctionHookItem() {
    override fun onClick(context: Context?) {
        showComposeDialog(context) { onDismiss ->
            AlertDialog(onDismissRequest = onDismiss,
                title = { Text("清除适配信息") },
                text = { Text("这将删除所有的 DEX 适配信息，宿主重启后需要重新适配。\n" +
                        "确定清除吗？") },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
                confirmButton = { TextButton(onClick = {
                    DexCacheManager.clearAllCache()
                    onDismiss()
                }) { Text("确定") } })
        }
    }

    override fun noSwitchWidget(): Boolean = true
}