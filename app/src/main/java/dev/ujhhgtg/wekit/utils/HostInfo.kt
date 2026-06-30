package dev.ujhhgtg.wekit.utils

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.constants.PackageNames

enum class HostSpecies { WeChat, WeKit, Unknown }

data class HostInfoImpl(
    val application: Application,
    val packageName: String,
    val hostName: String,
    val versionCode: Long,
    val versionName: String,
    val hostSpecies: HostSpecies
)

object HostInfo {

    private lateinit var _info: HostInfoImpl

    val info: HostInfoImpl get() = _info
    val application: Application get() = _info.application
    val appInfo: ApplicationInfo get() = application.applicationInfo
    val packageName: String get() = _info.packageName
    val versionCode: Long get() = _info.versionCode
    val versionName: String get() = _info.versionName
    val isModule: Boolean get() = _info.hostSpecies == HostSpecies.WeKit
    val isHost: Boolean get() = !isModule

    val isHostGooglePlay: Boolean by lazy {
        com.tencent.mm.boot.BuildConfig.BUILD_TAG.contains("GP", ignoreCase = true)
    }

    fun init(application: Application) {
        check(!::_info.isInitialized) { "HostInfo has already been initialized" }

        val pm = application.packageManager
        val packageName = application.packageName
        val packageInfo = try {
            pm.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            WeLogger.e(This.Class.simpleName, "failed to get package info", e)
            throw e
        }

        _info = HostInfoImpl(
            application = application,
            packageName = packageName,
            hostName = application.applicationInfo.loadLabel(pm).toString(),
            versionCode = packageInfo.longVersionCode,
            versionName = packageInfo.versionName.orEmpty(),
            hostSpecies = run {
                if (PackageNames.isWeChat(packageName)) return@run HostSpecies.WeChat
                return@run when (packageName) {
                    PackageNames.MODULE -> HostSpecies.WeKit
                    else -> HostSpecies.Unknown
                }
            }
        )
    }
}
