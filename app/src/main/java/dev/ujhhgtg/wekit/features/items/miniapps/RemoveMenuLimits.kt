package dev.ujhhgtg.wekit.features.items.miniapps

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.TargetProcesses
import dev.ujhhgtg.wekit.utils.enumValueOfClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.base.AccessFlagsMatcher
import java.lang.reflect.Modifier

object RemoveMenuLimits : SwitchFeature(), IResolveDex {

    override val technicalId = "去除菜单限制"
    override val nameRes = R.string.feature_remove_menu_limits_name
    override val categoryIds = listOf(FeatureCategoryIds.MINIAPPS)
    override val descriptionRes = R.string.feature_remove_menu_limits_description

    private lateinit var showAndClickableEnumValue: Any

    override val shouldLoadInCurrentProcess get() = TargetProcesses.isInMain || TargetProcesses.currentType == TargetProcesses.PROC_APPBRAND

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

    private val methodGetMenuItemVisibility1 by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.menu")

        matcher {
            declaredClass {
                superClass {
                    modifiers(AccessFlagsMatcher(Modifier.ABSTRACT))
                }

                addMethod {
                    usingNumbers(39)
                }
            }

            returnType("com.tencent.mm.plugin.appbrand.menu", StringMatchType.Contains)
        }
    }

    private val methodGetMenuItemVisibility2 by dexMethod {
        searchPackages("com.tencent.mm.plugin.appbrand.menu")
        matcher {
            declaredClass {
                superClass {
                    modifiers(AccessFlagsMatcher(Modifier.ABSTRACT))
                }

                addMethod {
                    usingNumbers(30)
                }
            }

            returnType("com.tencent.mm.plugin.appbrand.menu", StringMatchType.Contains)
        }
    }
}
