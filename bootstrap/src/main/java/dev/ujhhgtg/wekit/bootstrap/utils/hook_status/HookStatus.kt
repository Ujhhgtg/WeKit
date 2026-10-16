package dev.ujhhgtg.wekit.bootstrap.utils.hook_status

import android.content.Context
import dev.ujhhgtg.wekit.bootstrap.constants.PackageNames
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import io.github.libxposed.service.XposedServiceHelper.OnServiceListener
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Activation status detection via the libxposed service.
 *
 * Only intended to be used in the module process. Modern frameworks (LSPosed etc.)
 * bind an [XposedService] to the module process, from which the activation state and
 * scope can be read.
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

    fun init(context: Context) {
        if (context.packageName == PackageNames.MODULE && !xposedServiceListenerRegistered) {
            XposedServiceHelper.registerListener(xposedServiceListener)
            xposedServiceListenerRegistered = true
        }
    }
}
