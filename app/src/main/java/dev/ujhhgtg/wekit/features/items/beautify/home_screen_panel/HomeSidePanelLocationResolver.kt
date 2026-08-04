package dev.ujhhgtg.wekit.features.items.beautify.home_screen_panel

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

sealed interface LocationResolution {
    data object NeedPermission : LocationResolution
    data object LocationDisabled : LocationResolution
    data object Timeout : LocationResolution
    data object GeocoderFailed : LocationResolution
    data object CityNotFound : LocationResolution
    data class Success(val city: WeatherCity) : LocationResolution
    data class Error(val message: String) : LocationResolution
}

internal fun locationResolutionMessage(resolution: LocationResolution): String = when (resolution) {
    LocationResolution.NeedPermission -> "需要定位权限，请允许后重试"
    LocationResolution.LocationDisabled -> "请先开启系统定位服务"
    LocationResolution.Timeout -> "定位超时，请重试或手动选择城市"
    LocationResolution.GeocoderFailed -> "无法将当前位置转换为城市"
    LocationResolution.CityNotFound -> "天气城市库中找不到当前城市"
    is LocationResolution.Success -> ""
    is LocationResolution.Error -> resolution.message
}

interface HomeSidePanelLocationResolver {
    fun hasCoarsePermission(activity: Activity): Boolean

    suspend fun resolve(activity: Activity): LocationResolution
}

@Suppress("DEPRECATION")
internal class AndroidHomeSidePanelLocationResolver(
    private val cityIndex: HomeSidePanelCityIndex,
    private val timeoutMs: Long = 12_000L,
) : HomeSidePanelLocationResolver {

    override fun hasCoarsePermission(activity: Activity): Boolean =
        hostDeclaresCoarsePermission(activity) &&
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED

    override suspend fun resolve(activity: Activity): LocationResolution {
        if (!hostDeclaresCoarsePermission(activity)) {
            return LocationResolution.Error("当前微信版本未声明粗略定位权限，请手动选择城市")
        }
        if (!hasCoarsePermission(activity)) return LocationResolution.NeedPermission

        val locationManager = activity.getSystemService(LocationManager::class.java)
            ?: return LocationResolution.Error("当前微信无法访问系统定位服务，请手动选择城市")
        val provider = enabledProvider(locationManager)
            ?: return LocationResolution.LocationDisabled
        val location = try {
            withTimeoutOrNull(timeoutMs) {
                requestLocation(activity, locationManager, provider)
            }
        } catch (error: SecurityException) {
            WeLogger.w(TAG, "location permission was revoked during request", error)
            return LocationResolution.NeedPermission
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            WeLogger.w(TAG, "location request failed", error)
            return LocationResolution.Error("定位失败，请重试或手动选择城市")
        } ?: return LocationResolution.Timeout

        val address = try {
            geocode(activity, location)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            WeLogger.w(TAG, "reverse geocoding failed", error)
            return LocationResolution.GeocoderFailed
        } ?: return LocationResolution.GeocoderFailed

        val province = address.adminArea.orEmpty().ifBlank { address.subAdminArea.orEmpty() }
        val city = address.locality.orEmpty()
            .ifBlank { address.subAdminArea.orEmpty() }
            .ifBlank { address.adminArea.orEmpty() }
        when (val result = cityIndex.matchLocation(province, city)) {
            is WeatherCityMatchResult.Success -> return LocationResolution.Success(result.city)
            is WeatherCityMatchResult.Error -> return LocationResolution.CityNotFound
        }
    }

    private fun hostDeclaresCoarsePermission(activity: Activity): Boolean = try {
        activity.packageManager
            .getPackageInfo(activity.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.contains(Manifest.permission.ACCESS_COARSE_LOCATION) == true
    } catch (error: PackageManager.NameNotFoundException) {
        WeLogger.w(TAG, "failed to read host permission declarations", error)
        false
    }

    private fun enabledProvider(locationManager: LocationManager): String? =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { provider ->
                locationManager.isProviderEnabled(provider)
            }

    private suspend fun requestLocation(
        activity: Activity,
        locationManager: LocationManager,
        provider: String,
    ): Location? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            locationManager.getCurrentLocation(
                provider,
                cancellationSignal,
                activity.mainExecutor,
            ) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        }
    } else {
        suspendCancellableCoroutine { continuation ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    locationManager.removeUpdates(this)
                    if (continuation.isActive) continuation.resume(location)
                }
            }
            continuation.invokeOnCancellation { locationManager.removeUpdates(listener) }
            locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
        }
    }

    private suspend fun geocode(activity: Activity, location: Location): Address? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            Geocoder(activity, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
        }

    private companion object {
        const val TAG = "HomeSidePanelLocation"
    }
}
