package dev.ujhhgtg.wekit.features.items.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Parcelable
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.OsmLocationPicker
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.hookAfterDirectly
import org.osmdroid.util.GeoPoint
import java.util.concurrent.ConcurrentHashMap

@Feature(name = "虚拟定位", categories = ["系统与隐私"], description = "预设定微信获取到的经纬度")
object FakeLocation : ClickableFeature(), IResolveDex {

    private val methodListener by dexMethod {
        matcher {
            name = "onLocationChanged"
            usingEqStrings("MicroMsg.SLocationListener")
        }
    }
    private val methodListenerWgs84 by dexMethod {
        matcher {
            name = "onLocationChanged"
            usingEqStrings("MicroMsg.SLocationListenerWgs84")
        }
    }
    private val methodDefaultManager by dexMethod {
        matcher {
            name = "onLocationChanged"
            usingEqStrings("MicroMsg.DefaultTencentLocationManager", "[mlocationListener]error:%d, reason:%s")
        }
    }

    private const val KEY_LAT = "fake_lat"
    private const val KEY_LNG = "fake_lng"

    private const val DEFAULT_LAT = 31.224361F
    private const val DEFAULT_LNG = 121.469170F
    private const val WECHAT_LOCATION_REQUEST_CODE = 6
    private const val TAG = "FakeLocation"

    private var latitude by prefOption(KEY_LAT, DEFAULT_LAT)
    private var longitude by prefOption(KEY_LNG, DEFAULT_LNG)

    private val hookedLocationClasses = ConcurrentHashMap.newKeySet<Class<*>>()
    private val locationIntentRegex = Regex("""lat ([-+]?[0-9]*\.?[0-9]+);lng ([-+]?[0-9]*\.?[0-9]+);""")

    @Volatile
    private var pendingWechatLocationPick = false

    @Volatile
    private var wechatLocationPickerResultHooked = false

    override fun onEnable() {
        listOf(methodListener, methodListenerWgs84, methodDefaultManager).forEach {
            it.hookBefore {
                val tencentLocation = args[0]
                hookTencentLocation(tencentLocation)
            }
        }
    }

    override fun onDisable() {
        hookedLocationClasses.clear()
    }

    override fun onClick(context: Context) {
        showLocationPickerChooser(context)
    }

    private fun showLocationPickerChooser(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("选择虚拟定位") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("微信原生地图选点") },
                            supportingContent = { Text("调用微信内置地图选择位置") },
                            modifier = Modifier.clickable {
                                onDismiss()
                                launchWechatLocationPicker(context)
                            }
                        )
                        ListItem(
                            headlineContent = { Text("OSM 地图选点") },
                            supportingContent = { Text("使用 OpenStreetMap 选择位置") },
                            modifier = Modifier.clickable {
                                onDismiss()
                                showOsmLocationPicker(context)
                            }
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }

    private fun showOsmLocationPicker(context: Context) {
        showComposeDialog(context) {
            OsmLocationPicker(
                initialLocation = GeoPoint(
                    latitude.toDouble(),
                    longitude.toDouble(),
                ),
                onLocationSelected = {
                    onDismiss()
                    saveLocation(it.latitude.toFloat(), it.longitude.toFloat())
                },
                onDismiss = onDismiss
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun launchWechatLocationPicker(context: Context) {
        val activity = context.findActivity() ?: getTopMostActivity(allowPaused = true)
        if (activity == null) {
            showToast("无法获取当前 Activity")
            return
        }

        val redirectUiClass = "${activity.packageName}.plugin.location.ui.RedirectUI"
            .toClassOrNull()
        if (redirectUiClass == null) {
            showToast("当前微信版本不支持原生地图选点")
            return
        }

        if (!ensureWechatLocationPickerResultHooked(redirectUiClass)) {
            showToast("无法监听微信地图选点结果")
            return
        }

        runCatching {
            pendingWechatLocationPick = true
            activity.startActivityForResult(
                Intent(activity, redirectUiClass).apply {
                    putExtra("map_view_type", 8)
                },
                WECHAT_LOCATION_REQUEST_CODE
            )
        }.onFailure {
            pendingWechatLocationPick = false
            WeLogger.e(TAG, "failed to launch native location picker", it)
            showToast("启动微信地图失败: ${it.message}")
        }
    }

    private fun hookTencentLocation(tencentLocation: Any?) {
        val locationClass = tencentLocation?.javaClass ?: return
        if (locationClass in hookedLocationClasses) return

        val locationRef = locationClass.reflekt()
        val getLatitudeMethod = locationRef.firstMethod { name = "getLatitude" }
        val getLongitudeMethod = locationRef.firstMethod { name = "getLongitude" }
        if (!hookedLocationClasses.add(locationClass)) return

        getLatitudeMethod.hookBefore {
            result = latitude.toDouble()
        }

        getLongitudeMethod.hookBefore {
            result = longitude.toDouble()
        }
    }

    private fun ensureWechatLocationPickerResultHooked(redirectUiClass: Class<*>): Boolean {
        if (wechatLocationPickerResultHooked) return true

        return synchronized(this) {
            if (wechatLocationPickerResultHooked) return@synchronized true

            runCatching {
                val onActivityResult = redirectUiClass.reflekt().firstMethod {
                    name = "onActivityResult"
                    parameters(Int::class, Int::class, Intent::class)
                }.self

                onActivityResult.hookAfterDirectly {
                    handleWechatLocationPickerResult(args)
                }
                wechatLocationPickerResultHooked = true
            }.onFailure {
                WeLogger.e(TAG, "failed to hook native location picker result", it)
            }.isSuccess
        }
    }

    @Suppress("DEPRECATION")
    private fun handleWechatLocationPickerResult(args: Array<Any?>) {
        val requestCode = args.getOrNull(0) as? Int ?: return
        if (requestCode != WECHAT_LOCATION_REQUEST_CODE || !pendingWechatLocationPick) return

        pendingWechatLocationPick = false
        val resultCode = args.getOrNull(1) as? Int ?: return
        if (resultCode != Activity.RESULT_OK) return

        val intent = args.getOrNull(2) as? Intent ?: return
        val locationIntent = intent.getParcelableExtra<Parcelable>("KLocationIntent") ?: return
        val locationData = locationIntent.reflekt()
            .firstMethod { returnType = String::class }
            .invoke() as? String ?: return

        val match = locationIntentRegex.find(locationData)
        val latitude = match?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val longitude = match?.groupValues?.getOrNull(2)?.toFloatOrNull()
        if (latitude == null || longitude == null) {
            WeLogger.w(TAG, "failed to parse native location result: $locationData")
            showToast("解析微信地图选点失败")
            return
        }

        saveLocation(latitude, longitude)
    }

    private fun saveLocation(latitude: Float, longitude: Float) {
        this.latitude = latitude
        this.longitude = longitude
        showToast("已选择 ${"%.4f".format(latitude)}, ${"%.4f".format(longitude)}")
    }

    private tailrec fun Context.findActivity(): Activity? {
        return when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}
