package dev.ujhhgtg.wekit.features.items.system

import android.provider.Settings
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object SpoofEnvironment : SwitchFeature(), IResolveDex {

    override val technicalId = "环境伪装"
    override val nameRes = R.string.feature_spoof_environment_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_spoof_environment_description

    override fun onEnable() {
        Settings.Global::class.reflekt()
            .firstMethod {
                name = "getInt"
                parameterCount = 3
            }.hookBefore {
                val name = args[1] as? String? ?: return@hookBefore
                if (name == "adb_enabled")
                    result = 0
            }

        Settings.Secure::class.reflekt()
            .firstMethod {
                name = "getInt"
                parameterCount = 3
            }.hookBefore {
                val name = args[1] as? String? ?: return@hookBefore
                if (name == "development_settings_enabled")
                    result = 0
            }

        methodIsVpnEnabled.hookBefore {
            result = false
        }
    }

    private val methodIsVpnEnabled by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings("MicroMsg.WalletSecurityUtilService")
            }

            usingEqStrings("connectivity")
            usingNumbers(4)
        }
    }
}
