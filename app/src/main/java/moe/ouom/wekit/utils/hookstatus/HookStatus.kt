package moe.ouom.wekit.utils.hookstatus

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.net.toUri
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.libxposed.service.XposedServiceHelper.OnServiceListener
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.R
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.loader.LoaderExtensionHelper
import moe.ouom.wekit.utils.common.SyncUtils
import moe.ouom.wekit.utils.hookstatus.AbiUtils.getApplicationActiveAbi
import moe.ouom.wekit.utils.log.WeLogger.d
import java.io.File

/**
 * This class is only intended to be used in module process, not in host process.
 */
object HookStatus {
    private var expCpCalled = false
    private var expCpResult = false
    val xposedService: MutableStateFlow<XposedService?> = MutableStateFlow(null)
    private var xposedServiceListenerRegistered = false
    private val xposedServiceListener = object : OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            d("on XPOSED ServiceBind")
            xposedService.value = service
        }

        override fun onServiceDied(service: XposedService) {
            d("on XPOSED ServiceDied")
            xposedService.value = null
        }
    }

    val zygoteHookProvider: String?
        get() = HookStatusImpl.sZygoteHookProvider

    val isLsposedDexObfsEnabled: Boolean
        get() = HookStatusImpl.sIsLsposedDexObfsEnabled

    val isZygoteHookMode: Boolean
        get() = HookStatusImpl.sZygoteHookMode

    val isLegacyXposed: Boolean
        get() {
            try {
                ClassLoader.getSystemClassLoader()
                    .loadClass("de.robv.android.xposed.XposedBridge")
                return true
            } catch (e: ClassNotFoundException) {
                return false
            }
        }

    val isElderDriverXposed: Boolean
        get() = File("/system/framework/edxp.jar").exists()

    fun callTaichiContentProvider(context: Context): Boolean {
        try {
            val contentResolver = context.contentResolver
            val uri = "content://me.weishu.exposed.CP/".toUri()
            var result: Bundle? = Bundle()
            try {
                result = contentResolver.call(uri, "active", null, null)
            } catch (_: RuntimeException) {
                // TaiChi is killed, try invoke
                try {
                    val intent = Intent("me.weishu.exp.ACTION_ACTIVE")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    return false
                }
            }
            if (result == null) {
                result = contentResolver.call(uri, "active", null, null)
            }
            if (result == null) {
                return false
            }
            return result.getBoolean("active", false)
        } catch (_: Exception) {
            return false
        }
    }

    fun init(context: Context) {
        if (context.packageName == BuildConfig.APPLICATION_ID) {
            if (!xposedServiceListenerRegistered) {
                XposedServiceHelper.registerListener(xposedServiceListener)
                xposedServiceListenerRegistered = true
            }
            SyncUtils.async {
                expCpCalled = callTaichiContentProvider(context)
                expCpResult = expCpCalled
            }
        } else {
            // in host process???
            try {
                initHookStatusImplInHostProcess()
            } catch (_: LinkageError) {
            }
        }
    }

    val hookType: HookType
        get() {
            if (isZygoteHookMode) {
                return HookType.ZYGOTE
            }
            return if (expCpResult) HookType.APP_PATCH else HookType.NONE
        }

    @Throws(LinkageError::class)
    private fun initHookStatusImplInHostProcess() {
        val xposedClass = LoaderExtensionHelper.getXposedBridgeClass()
        var dexObfsEnabled = false
        if (xposedClass != null) {
            dexObfsEnabled = "de.robv.android.xposed.XposedBridge" != xposedClass.name
        }
        var hookProvider: String? = null
        if (dexObfsEnabled) {
            HookStatusImpl.sIsLsposedDexObfsEnabled = true
            hookProvider = "LSPosed"
        } else {
            var bridgeTag: String? = null
            if (xposedClass != null) {
                try {
                    bridgeTag = xposedClass.getDeclaredField("TAG").get(null) as String?
                } catch (_: ReflectiveOperationException) {
                }
            }
            if (bridgeTag != null) {
                if (bridgeTag.startsWith("LSPosed")) {
                    hookProvider = "LSPosed"
                } else if (bridgeTag.startsWith("EdXposed")) {
                    hookProvider = "EdXposed"
                } else if (bridgeTag.startsWith("PineXposed")) {
                    hookProvider = "Dreamland"
                }
            }
        }
        if (hookProvider != null) {
            HookStatusImpl.sZygoteHookProvider = hookProvider
        }
    }

    val hookProviderNameForLegacyApi: String
        get() {
            if (isZygoteHookMode) {
                val name: String? = zygoteHookProvider
                if (name != null) {
                    return name
                }
                if (isLegacyXposed) {
                    return "Legacy Xposed"
                }
                if (isElderDriverXposed) {
                    return "EdXposed"
                }
                return "Unknown(Zygote)"
            }
            if (expCpResult) {
                return "Taichi"
            }
            return "None"
        }

    fun isTaiChiInstalled(context: Context): Boolean {
        try {
            val pm = context.packageManager
            pm.getPackageInfo("me.weishu.exp", 0)
            return true
        } catch (e: PackageManager.NameNotFoundException) {
            return false
        }
    }

    val isModuleEnabled: Boolean
        get() = hookType != HookType.NONE

    val hostABI: HashMap<String?, String?>
        get() {
            val scope =
                HostInfo.getApplication().resources
                    .getTextArray(R.array.xposed_scope)
            val result =
                java.util.HashMap<String?, String?>(4)
            for (s in scope) {
                val abi = getApplicationActiveAbi(s.toString())
                if (abi != null) {
                    result[s.toString()] = abi
                }
            }
            return result
        }

    enum class HookType {
        /**
         * No hook.
         */
        NONE,

        /**
         * Taichi, BugHook(not implemented), etc.
         */
        APP_PATCH,

        /**
         * Legacy Xposed, EdXposed, LSPosed, Dreamland, etc.
         */
        ZYGOTE,
    }
}
