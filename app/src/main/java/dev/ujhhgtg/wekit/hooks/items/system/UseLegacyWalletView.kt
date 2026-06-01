package dev.ujhhgtg.wekit.hooks.items.system

import com.tencent.mm.ui.base.preference.Preference
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "系统与隐私/恢复旧版卡包界面", description = "使用旧版「卡包」而非「小店与卡包」")
object UseLegacyWalletView : SwitchHookItem(), IResolvesDex {

    override fun onEnable() {
        methodGetOrderAndCardEntranceInfo.hookAfter {
            result.asResolver()
                .firstField {
                    type = Int::class.java
                }.set(1)
        }

        methodMoreTabUIHandlePrefOnClick.hookBefore {
            val field = Preference::class.resolve()
                .firstField { type = String::class }
                .of(args[1] as Preference)

            if (field.get<String>() == "settings_mm_cardpackage_new") {
                field.set("settings_mm_cardpackage")
            }
        }
    }

    private val methodGetOrderAndCardEntranceInfo by dexMethod()

    private val methodMoreTabUIHandlePrefOnClick by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodGetOrderAndCardEntranceInfo.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.EcsOrderService", "getOrderAndCardEntranceInfo use finder logic")
            }
        }

        methodMoreTabUIHandlePrefOnClick.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.MoreTabUI", "account has not already!", "onPreferenceTreeClick")
            }
        }
    }
}
