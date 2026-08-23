package dev.ujhhgtg.wekit.features.items.system

import com.tencent.mm.ui.base.preference.Preference
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object UseLegacyWalletViewInMePage : SwitchFeature(), IResolveDex {

    override val technicalId = "恢复旧版「我」界面卡包"
    override val nameRes = R.string.feature_use_legacy_wallet_view_in_me_page_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_use_legacy_wallet_view_in_me_page_description

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
