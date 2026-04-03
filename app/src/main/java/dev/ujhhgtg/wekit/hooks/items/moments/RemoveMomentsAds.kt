package dev.ujhhgtg.wekit.hooks.items.moments

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.tencent.mm.plugin.sns.storage.ADInfo
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(path = "朋友圈/拦截朋友圈广告", desc = "拦截朋友圈广告")
object RemoveMomentsAds : SwitchHookItem() {

    private val TAG = nameOf(RemoveMomentsAds)

    override fun onEnable() {
        ADInfo::class.asResolver()
            .firstConstructor {
                parameters(String::class)
            }
            .hookBefore { param ->
                if (param.args.isNotEmpty() && param.args[0] is String) {
                    param.args[0] = ""
                    WeLogger.i(TAG, "blocked ad")
                }
            }
    }
}
