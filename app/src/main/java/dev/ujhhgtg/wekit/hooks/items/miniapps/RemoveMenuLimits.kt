package dev.ujhhgtg.wekit.hooks.items.miniapps

import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.enumValueOfClass
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.StringMatchType

@HookItem(path = "小程序/去除菜单限制", description = "移除小程序右上角菜单的限制")
object RemoveMenuLimits : SwitchHookItem(), IResolvesDex {

    private lateinit var showAndClickableEnumValue: Any

    override fun onEnable() {
        listOf(
            methodGetMenuItemVisibility1,
            methodGetMenuItemVisibility2
        ).forEach {
            it.hookBefore {
                if (!::showAndClickableEnumValue.isInitialized) {
                    val returnType = methodGetMenuItemVisibility1.method.returnType
                    showAndClickableEnumValue = enumValueOfClass(returnType, "SHOW_CLICKABLE")
                }
                result = showAndClickableEnumValue
            }
        }
    }

    private val methodGetMenuItemVisibility1 by dexMethod()
    private val methodGetMenuItemVisibility2 by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodGetMenuItemVisibility1.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.appbrand.menu")
            matcher {
                declaredClass {
                    addMethod {
                        usingNumbers(39)
                    }
                }
                returnType("com.tencent.mm.plugin.appbrand.menu", StringMatchType.Contains)
            }
        }

        methodGetMenuItemVisibility2.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.appbrand.menu")
            matcher {
                declaredClass {
                    addMethod {
                        usingNumbers(30)
                    }
                }
                returnType("com.tencent.mm.plugin.appbrand.menu", StringMatchType.Contains)
            }
        }
    }
}
