package moe.ouom.wekit.hooks.items.miniapps

import android.content.Context
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

@HookItem(path = "小程序/去除小程序限制", desc = "移除小程序部分功能 (如复制链接) 的限制")
object RemoveMiniAppLimits : BaseSwitchFunctionHookItem(), IDexFind {

    override fun entry(classLoader: ClassLoader) {
        methodGetCopyLinkButtonState.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val returnType = methodGetCopyLinkButtonState.method.returnType
                    val enumClickable = returnType.asResolver()
                        .firstField { name = "SHOW_CLICKABLE" }
                    param.result = enumClickable
                }
            }
        }
    }

    private val methodGetCopyLinkButtonState by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodGetCopyLinkButtonState.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.appbrand.menu")
            matcher {
                declaredClass {
                    addMethod {
                        usingNumbers(30)
                    }
                }
                addParamType {
                    className("com.tencent.mm.plugin.appbrand.page", StringMatchType.Contains)
                }
                addParamType(Context::class.java)
            }
        }

        return descriptors
    }
}