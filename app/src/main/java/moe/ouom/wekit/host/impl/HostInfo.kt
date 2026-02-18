@file:JvmName("HostInfo")

package moe.ouom.wekit.host.impl

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.pm.PackageInfoCompat
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.utils.log.WeLogger

const val PACKAGE_NAME_WECHAT = "com.tencent.mm"
const val PACKAGE_NAME_SELF = BuildConfig.APPLICATION_ID

lateinit var hostInfo: HostInfoImpl

fun init(applicationContext: Application) {
    if (::hostInfo.isInitialized) throw IllegalStateException("Host Information Provider has been already initialized")
    val packageInfo = getHostInfo(applicationContext)
    val packageName = applicationContext.packageName

    hostInfo = HostInfoImpl(
        applicationContext,
        packageName,
        applicationContext.applicationInfo.loadLabel(applicationContext.packageManager).toString(),
        PackageInfoCompat.getLongVersionCode(packageInfo),
        PackageInfoCompat.getLongVersionCode(packageInfo).toInt(),
        packageInfo.versionName.toString(),
        when (packageName) {
            PACKAGE_NAME_WECHAT -> HostSpecies.WeChat
            PACKAGE_NAME_SELF -> HostSpecies.WeKit
            else -> HostSpecies.Unknown
        },
    )
}

private fun getHostInfo(context: Context): PackageInfo {
    try {
        return context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_META_DATA
        )
    } catch (e: PackageManager.NameNotFoundException) {
        WeLogger.e("Can not get PackageInfo!", e)
        throw e
    }
}

fun isWeChat(): Boolean {
    return hostInfo.hostSpecies == HostSpecies.WeChat
}

fun requireMinWeChatVersion(versionCode: Int): Boolean {
    WeLogger.d("requireMinWeChatVersion: $versionCode, ${hostInfo.versionCode}, ${isWeChat()}")
    return isWeChat() && hostInfo.versionCode >= versionCode
}

fun requireMinVersion(versionCode: Long, hostSpecies: HostSpecies): Boolean {
    return hostInfo.hostSpecies == hostSpecies && hostInfo.versionCode >= versionCode
}

val isInModuleProcess: Boolean
    get() = hostInfo.hostSpecies == HostSpecies.WeKit

val isInHostProcess: Boolean get() = !isInModuleProcess

val isAndroidxFileProviderAvailable: Boolean by lazy {
    val ctx = hostInfo.application
    // check if androidx.core.content.FileProvider is available
    val pm = ctx.packageManager
    try {
        pm.getProviderInfo(
            ComponentName(
                hostInfo.packageName,
                "androidx.core.content.FileProvider"
            ), 0
        )
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

data class HostInfoImpl(
    val application: Application,
    val packageName: String,
    val hostName: String,
    val versionCode: Long,
    val versionCode32: Int,
    val versionName: String,
    val hostSpecies: HostSpecies
)

enum class HostSpecies {
    WeChat,
    WeKit,
    Unknown
}

fun overrideVersionCodeForLSPatchModified1(newVersionCode: Int) {
    WeLogger.w("Overriding version code from ${hostInfo.versionCode32} to $newVersionCode")
    hostInfo = HostInfoImpl(
        hostInfo.application,
        hostInfo.packageName,
        hostInfo.hostName,
        newVersionCode.toLong(),
        newVersionCode,
        hostInfo.versionName,
        hostInfo.hostSpecies
    )
}