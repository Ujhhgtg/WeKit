package moe.ouom.wekit.hooks.item.dev

import android.content.Context
import com.afollestad.materialdialogs.MaterialDialog
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.dexkit.cache.DexCacheManager
import moe.ouom.wekit.hooks.core.annotation.HookItem

@HookItem(path = "开发者选项/清除适配信息", desc = "点击清除适配信息")
class DexCacheCleaner : BaseClickableFunctionHookItem() {
    override fun onClick(context: Context?) {
        context?.let {
            MaterialDialog(it)
                .title(text = "警告")
                .message(text = "这将删除所有的 DEX 适配信息，宿主重启后可能需要一些时间去重新适配。\n确定清除吗？")
                .positiveButton(text = "清除") { _ ->
                    DexCacheManager.clearAllCache()
                }
                .negativeButton(text = "取消") { dialog ->
                    dialog.dismiss()
                }
                .show()
        }
    }

    override fun noSwitchWidget(): Boolean = true
}