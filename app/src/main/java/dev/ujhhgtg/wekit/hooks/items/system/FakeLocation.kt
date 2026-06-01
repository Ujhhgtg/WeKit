package dev.ujhhgtg.wekit.hooks.items.system

import android.content.Context
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.OsmLocationPicker
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.resolve
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "系统与隐私/虚拟定位", description = "预设定微信获取到的经纬度")
object FakeLocation : ClickableHookItem(), IResolvesDex {

    private val methodListener by dexMethod()
    private val methodListenerWgs84 by dexMethod()
    private val methodDefaultManager by dexMethod()

    private const val KEY_LAT = "fake_lat"
    private const val KEY_LNG = "fake_lng"

    override fun onEnable() {
        listOf(methodListener, methodListenerWgs84, methodDefaultManager).forEach {
            it.hookBefore {
                val tencentLocation = args[0]
                tencentLocation::class.resolve().apply {
                    firstMethod {
                        name = "getLatitude"
                    }.hookBefore {
                        result = WePrefs.getFloatOrDef(KEY_LAT, 31.224361F)
                    }

                    firstMethod {
                        name = "getLongitude"
                    }.hookBefore {
                        result = WePrefs.getFloatOrDef(KEY_LNG, 121.469170F)
                    }
                }
            }
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        // cx0.s
        methodListener.find(dexKit) {
            matcher {
                name = "onLocationChanged"
                usingEqStrings("MicroMsg.SLocationListener")
            }
        }

        // cx0.t
        methodListenerWgs84.find(dexKit) {
            matcher {
                name = "onLocationChanged"
                usingEqStrings("MicroMsg.SLocationListenerWgs84")
            }
        }

        // yd.c
        methodDefaultManager.find(dexKit) {
            matcher {
                name = "onLocationChanged"
                usingEqStrings("MicroMsg.DefaultTencentLocationManager", "[mlocationListener]error:%d, reason:%s")
            }
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            OsmLocationPicker(
                onLocationSelected = {
                    onDismiss()
                    WePrefs.putFloat(KEY_LAT, it.latitude.toFloat())
                    WePrefs.putFloat(KEY_LNG, it.longitude.toFloat())
                    showToast("已选择 ${"%.4f".format(it.latitude)}, ${"%.4f".format(it.longitude)}")
                },
                onDismiss = onDismiss
            )
        }
    }
}
