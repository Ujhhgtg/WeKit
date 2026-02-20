package moe.ouom.wekit.hooks.item.moment

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.IXUnhook
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.Initiator.loadClass
import moe.ouom.wekit.util.log.WeLogger

@HookItem(path = "朋友圈/拦截广告", desc = "拦截朋友圈广告")
class AntiSnsAd : BaseSwitchFunctionHookItem() {

    private var unhook: IXUnhook<*>? = null
    override fun entry(classLoader: ClassLoader) {
        val adInfoClass = loadClass("\"com.tencent.mm.plugin.sns.storage.ADInfo\"")
        unhook = XposedHelpers.findAndHookConstructor(
            adInfoClass,
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isNotEmpty() && param.args[0] is String) {
                        param.args[0] = ""
                        WeLogger.i("拦截到ADInfo广告")
                    }
                }
            }
        )
    }

    override fun unload(classLoader: ClassLoader) {
        unhook?.unhook()
        super.unload(classLoader)
    }
}