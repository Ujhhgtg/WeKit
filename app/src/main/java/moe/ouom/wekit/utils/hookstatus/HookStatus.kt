package moe.ouom.wekit.utils.hookstatus

import android.content.Context
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.libxposed.service.XposedServiceHelper.OnServiceListener
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.wekit.BuildConfig
import java.io.File

/**
 * This class is only intended to be used in module process, not in host process.
 */
object HookStatus {

    val xposedService: MutableStateFlow<XposedService?> = MutableStateFlow(null)
    private var xposedServiceListenerRegistered = false
    private val xposedServiceListener = object : OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            xposedService.value = service
        }

        override fun onServiceDied(service: XposedService) {
            xposedService.value = null
        }
    }

    val zygoteHookProvider: String?
        get() = HookStatusImpl.zygoteHookProvider

    val isZygoteHookMode: Boolean
        get() = HookStatusImpl.zygoteHookMode

    val isLegacyXposed: Boolean
        get() {
            try {
                ClassLoader.getSystemClassLoader()
                    .loadClass("de.robv.android.xposed.XposedBridge")
                return true
            } catch (_: ClassNotFoundException) {
                return false
            }
        }

    val isElderDriverXposed: Boolean
        get() = File("/system/framework/edxp.jar").exists()

    fun init(context: Context) {
        if (context.packageName == BuildConfig.APPLICATION_ID) {
            if (!xposedServiceListenerRegistered) {
                XposedServiceHelper.registerListener(xposedServiceListener)
                xposedServiceListenerRegistered = true
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
            return HookType.NONE
        }

    private fun initHookStatusImplInHostProcess() {
        val xposedClass = LoaderExtensionHelper.getXposedBridgeClass()
        var dexObfsEnabled = false
        if (xposedClass != null) {
            dexObfsEnabled = "de.robv.android.xposed.XposedBridge" != xposedClass.name
        }
        var hookProvider: String? = null
        if (dexObfsEnabled) {
            HookStatusImpl.isLsposedDexObfsEnabled = true
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
            HookStatusImpl.zygoteHookProvider = hookProvider
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
                return "Unknown (Zygote)"
            }
            return "None"
        }

    val isModuleEnabled: Boolean
        get() = hookType != HookType.NONE

    enum class HookType {
        NONE,
        ZYGOTE,
    }
}
