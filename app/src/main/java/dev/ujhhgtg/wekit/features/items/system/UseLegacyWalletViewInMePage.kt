package dev.ujhhgtg.wekit.features.items.system

import com.tencent.mm.ui.base.preference.Preference
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature

@Feature(name = "恢复旧版「我」界面卡包", categories = ["系统与隐私"], description = "在「我」界面使用旧版「卡包」而非「小店与卡包」")
object UseLegacyWalletViewInMePage : SwitchFeature(), IResolveDex {

    override fun onEnable() {
        methodGetOrderAndCardEntranceInfo.hookAfter {
            result!!.reflekt()
                .firstField {
                    type = Int::class.java
                }.set(1)
        }

        methodMoreTabUIHandlePrefOnClick.hookBefore {
            val field = Preference::class.reflekt()
                .firstField { type = String::class }

            val pref = args[1] as Preference
            if (field.get(pref) as? String? == "settings_mm_cardpackage_new") {
                field.set(pref, "settings_mm_cardpackage")
            }
        }
    }

    private val methodGetOrderAndCardEntranceInfo by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.EcsOrderService", "getOrderAndCardEntranceInfo use finder logic")
        }
    }

    private val methodMoreTabUIHandlePrefOnClick by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.MoreTabUI", "account has not already!", "onPreferenceTreeClick")
        }
    }
}
