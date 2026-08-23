package dev.ujhhgtg.wekit.features.items.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.OsmLocationPicker
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getTopMostActivity
import dev.ujhhgtg.wekit.utils.android.showToast
import org.osmdroid.util.GeoPoint
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object FakeLocation : ClickableFeature(), IResolveDex {

    override val technicalId = "虚拟定位"
    override val nameRes = R.string.feature_fake_location_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_fake_location_description

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
    private var pendingWeChatPicker = false

    @Volatile
    private var wechatPickerHooked = false

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
        pendingWeChatPicker = false
        wechatPickerHooked = false
    }

    override fun onClick(context: ComponentActivity) {
        showLocationPickerChooser(context)
    }

    private fun showLocationPickerChooser(context: Context) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.system_fake_location_select)) },
                text = {
                    SegmentedColumn {
                        item {
                            BaseWidget(
                                title = stringResource(R.string.system_fake_location_wechat),
                                description = stringResource(R.string.system_fake_location_wechat_summary),
                                onClick = {
                                    onDismiss()
                                    launchWechatLocationPicker()
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                title = stringResource(R.string.system_fake_location_osm),
                                description = stringResource(R.string.system_fake_location_osm_summary),
                                onClick = {
                                    onDismiss()
                                    showOsmLocationPicker(context)
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
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
    private fun launchWechatLocationPicker() {
        val activity = getTopMostActivity()
        if (activity == null) {
            showToast(localizedSystemString(R.string.system_fake_location_no_activity))
            return
        }

        val redirectUiClass = "${activity.packageName}.plugin.location.ui.RedirectUI".toClass()

        if (!ensureWeChatPickerHooked(redirectUiClass)) {
            showToast(localizedSystemString(R.string.system_fake_location_listener_failed))
            return
        }

        runCatching {
            pendingWeChatPicker = true
            activity.startActivityForResult(
                Intent(activity, redirectUiClass).apply {
                    putExtra("map_view_type", 8)
                },
                WECHAT_LOCATION_REQUEST_CODE
            )
        }.onFailure {
            pendingWeChatPicker = false
            WeLogger.e(TAG, "failed to launch native location picker", it)
            showToast(localizedSystemString(R.string.system_fake_location_launch_failed, it.message.orEmpty()))
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

    private fun ensureWeChatPickerHooked(redirectUiClass: Class<*>): Boolean {
        if (wechatPickerHooked) return true

        return synchronized(this) {
            if (wechatPickerHooked) return@synchronized true

            val onActivityResult = runCatching {
                redirectUiClass.reflekt().firstMethod {
                    name = "onActivityResult"
                    parameters(Int::class, Int::class, Intent::class)
                }.self
            }.onFailure {
                WeLogger.e(TAG, "failed to find native location picker result handler", it)
            }.getOrNull() ?: return@synchronized false

            onActivityResult.hookAfter {
                handleWechatLocationPickerResult(args)
            }
            wechatPickerHooked = true
            true
        }
    }

    @Suppress("DEPRECATION")
    private fun handleWechatLocationPickerResult(args: Array<Any?>) {
        val requestCode = args.getOrNull(0) as? Int ?: return
        if (requestCode != WECHAT_LOCATION_REQUEST_CODE || !pendingWeChatPicker) return

        pendingWeChatPicker = false
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
            showToast(localizedSystemString(R.string.system_fake_location_parse_failed))
            return
        }

        saveLocation(latitude, longitude)
    }

    private fun saveLocation(latitude: Float, longitude: Float) {
        this.latitude = latitude
        this.longitude = longitude
        val locale = Locale.forLanguageTag(WeKitLocaleController.resolvedLocale.androidTag)
        showToast(
            localizedSystemString(
                R.string.system_fake_location_selected,
                String.format(locale, "%.4f", latitude),
                String.format(locale, "%.4f", longitude),
            )
        )
    }
}
