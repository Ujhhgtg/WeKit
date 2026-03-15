package moe.ouom.wekit.utils

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import moe.ouom.wekit.constants.PackageNames
import moe.ouom.wekit.utils.logging.WeLogger

enum class HostSpecies { WeChat, WeKit, Unknown }

data class HostInfoImpl(
    val application: Application,
    val packageName: String,
    val hostName: String,
    val versionCode: Long,
    val versionCode32: Int,
    val versionName: String,
    val hostSpecies: HostSpecies
)

object HostInfo {

    private lateinit var _info: HostInfoImpl

    val info: HostInfoImpl get() = _info

    @JvmStatic
    val application: Application get() = _info.application
    val appInfo: ApplicationInfo get() = application.applicationInfo
    val packageName: String get() = _info.packageName
    val versionCode32: Int get() = _info.versionCode32
    val versionCode: Int get() = versionCode32
    val isModule: Boolean get() = _info.hostSpecies == HostSpecies.WeKit

    @JvmStatic
    val isHost: Boolean get() = !isModule

    val isHostGooglePlay: Boolean by lazy {
        runCatching {
            Class.forName("com.tencent.mm.boot.BuildConfig")
                .getField("BUILD_TAG")
                .get(null) as? String
        }.getOrNull()?.contains("GP", ignoreCase = true) ?: false
    }

    fun init(application: Application) {
        check(!::_info.isInitialized) { "HostInfo has already been initialized" }

        val pm = application.packageManager
        val packageName = application.packageName
        val packageInfo = try {
            pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            WeLogger.e("Cannot get PackageInfo!", e)
            throw e
        }

        _info = HostInfoImpl(
            application = application,
            packageName = packageName,
            hostName = application.applicationInfo.loadLabel(pm).toString(),
            versionCode = PackageInfoCompat.getLongVersionCode(packageInfo),
            versionCode32 = PackageInfoCompat.getLongVersionCode(packageInfo).toInt(),
            versionName = packageInfo.versionName.orEmpty(),
            hostSpecies = when (packageName) {
                PackageNames.WECHAT -> HostSpecies.WeChat
                PackageNames.THIS -> HostSpecies.WeKit
                else -> HostSpecies.Unknown
            }
        )
    }
}