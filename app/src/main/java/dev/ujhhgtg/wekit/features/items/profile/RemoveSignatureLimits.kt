package dev.ujhhgtg.wekit.features.items.profile

import android.view.View
import android.widget.TextView
import com.tencent.mm.plugin.setting.ui.setting.EditSignatureUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookHandle
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly

@Feature(name = "移除个性签名限制", categories = ["个人资料"], description = "允许大于 30 字与包含特殊字符的个性签名")
object RemoveSignatureLimits : SwitchFeature(), IResolveDex {

    private lateinit var stringMatchesMethodUnhook: HookHandle

    private lateinit var setFiltersUnhook: HookHandle

    override fun onEnable() {
        EditSignatureUI::class.reflekt()
            .firstMethod { name = "initView" }.apply {
                hookBefore {
                    setFiltersUnhook = "${PackageNames.WECHAT}.ui.widget.MMEditText".toClass().reflekt()
                        .firstMethod {
                            name = "setFilters"
                        }.hookBeforeDirectly {
                            result = null
                        }
                }

                hookAfter {
                    val activity = thisObject as EditSignatureUI
                    activity.enableOptionMenu(true)
                    (activity.reflekt()
                        .firstField { type = TextView::class }
                        .get()!! as TextView).visibility = View.GONE
                }
            }

        methodTextWatcherAfterTextChanged.hookBefore {
            result = null
        }

        methodConfirmButtonOnClickListenerOnClick.apply {
            hookBefore {
                stringMatchesMethodUnhook = String::class.java.reflekt()
                    .firstMethod { name = "matches" }
                    .hookBeforeDirectly { result = false }
            }
            hookAfter {
                stringMatchesMethodUnhook.unhook()
                setFiltersUnhook.unhook()
            }
        }
    }

    private val methodTextWatcherAfterTextChanged by dexMethod {
        searchPackages("${PackageNames.WECHAT}.plugin.setting.ui.setting")
        matcher {
            declaredClass {
                addMethod {
                    name = "<init>"
                    paramTypes("${PackageNames.WECHAT}.plugin.setting.ui.setting.EditSignatureUI", "java.lang.String")
                }
                addInterface { className = "android.text.TextWatcher" }
            }

            name = "afterTextChanged"
        }
    }

    private val methodConfirmButtonOnClickListenerOnClick by dexMethod {
        searchPackages("${PackageNames.WECHAT}.plugin.setting.ui.setting")
        matcher {
            declaredClass {
                addMethod {
                    name = "<init>"
                    paramTypes("${PackageNames.WECHAT}.plugin.setting.ui.setting.EditSignatureUI")
                }
                addInterface { className = $$"android.view.MenuItem$OnMenuItemClickListener" }
            }

            name = "onMenuItemClick"
            usingEqStrings(".*[", "].*")
        }
    }
}
