package dev.ujhhgtg.wekit.hooks.items.profile

import android.view.View
import android.widget.TextView
import com.highcapable.kavaref.extension.toClass
import com.tencent.mm.plugin.setting.ui.setting.EditSignatureUI
import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "个人资料/移除个性签名限制", description = "允许大于 30 字与包含特殊字符的个性签名")
object RemoveSignatureLimits : SwitchHookItem(), IResolvesDex {

    private lateinit var stringMatchesMethodUnhook: XC_MethodHook.Unhook

    private lateinit var setFiltersUnhook: XC_MethodHook.Unhook

    override fun onEnable() {
        EditSignatureUI::class.resolve()
            .firstMethod { name = "initView" }.apply {
                hookBefore {
                    setFiltersUnhook = "${PackageNames.WECHAT}.ui.widget.MMEditText".toClass().resolve()
                        .firstMethod {
                            name = "setFilters"
                        }.hookBeforeDirectly {
                            result = null
                        }
                }

                hookAfter {
                    val activity = thisObject as EditSignatureUI
                    activity.enableOptionMenu(true)
                    activity.asResolver()
                        .firstField { type = TextView::class }
                        .get<TextView>()!!.visibility = View.GONE
                }
            }

        methodTextWatcherAfterTextChanged.hookBefore {
            result = null
        }

        methodConfirmButtonOnClickListenerOnClick.apply {
            hookBefore {
                stringMatchesMethodUnhook = String::class.java.resolve()
                    .firstMethod { name = "matches" }
                    .hookBeforeDirectly { result = false }
            }
            hookAfter {
                stringMatchesMethodUnhook.unhook()
                setFiltersUnhook.unhook()
            }
        }
    }

    private val methodTextWatcherAfterTextChanged by dexMethod()

    private val methodConfirmButtonOnClickListenerOnClick by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodTextWatcherAfterTextChanged.find(dexKit) {
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

        methodConfirmButtonOnClickListenerOnClick.find(dexKit) {
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
}
