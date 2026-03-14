package moe.ouom.wekit.hooks.items.miniapps

import android.content.Context
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.enumValueOfClass
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

@HookItem(path = "小程序/去除菜单限制", desc = "移除小程序右上角菜单的限制")
object RemoveMiniAppMenuLimits : SwitchHookItem(), IDexFind {

    private lateinit var showAndClickableEnumValue: Any

    override fun onLoad() {
        methodGetMenuItemVisibility1.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    val returnType = methodGetMenuItemVisibility1.method.returnType
                    if (!::showAndClickableEnumValue.isInitialized) {
                        showAndClickableEnumValue = enumValueOfClass(returnType, "SHOW_CLICKABLE")
                    }
                    param.result = showAndClickableEnumValue
                }
            }
        }
    }

    private val methodGetMenuItemVisibility1 by dexMethod()

    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        methodGetMenuItemVisibility1.find(dexKit, descriptors) {
            searchPackages("com.tencent.mm.plugin.appbrand.menu")
            matcher {
                declaredClass {
                    addMethod {
                        usingNumbers(39)
                    }
                }
                addParamType(Context::class.java)
                addParamType {
                    className("com.tencent.mm.plugin.appbrand.page", StringMatchType.Contains)
                }
            }
        }

        return descriptors
    }
}
