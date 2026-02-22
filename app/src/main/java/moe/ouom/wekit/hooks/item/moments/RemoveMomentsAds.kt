package moe.ouom.wekit.hooks.item.moments

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.extension.toClass
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.log.WeLogger

@HookItem(path = "朋友圈/拦截朋友圈广告", desc = "拦截朋友圈广告")
object RemoveMomentsAds : BaseSwitchFunctionHookItem() {

    private const val TAG = "RemoveMomentsAds"

    override fun entry(classLoader: ClassLoader) {
        val adInfoClass = "com.tencent.mm.plugin.sns.storage.ADInfo".toClass(classLoader)
        adInfoClass.asResolver()
            .firstConstructor {
                parameters(String::class)
            }
            .self
            .hookBefore { param ->
                if (param.args.isNotEmpty() && param.args[0] is String) {
                    param.args[0] = ""
                    WeLogger.i(TAG, "拦截到 ADInfo 广告")
                }
            }
    }
}