package moe.ouom.wekit.hooks.items.system

import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.abc.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "系统与隐私/禁止应用检测 Xposed", desc = "防止应用检测 Xposed 框架是否存在")
object DisableXposedDetection : SwitchHookItem(), IResolvesDex {

    private val methodCheckStackTraceElements by dexMethod()

    override fun onEnable() {
        methodCheckStackTraceElements.hookBefore { param ->
            param.result = false
        }
    }

    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodCheckStackTraceElements.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.app")
            matcher {
                usingEqStrings(
                    "de.robv.android.xposed.XposedBridge",
                    "com.zte.heartyservice.SCC.FrameworkBridge"
                )
            }
        }

        return descriptors
    }
}