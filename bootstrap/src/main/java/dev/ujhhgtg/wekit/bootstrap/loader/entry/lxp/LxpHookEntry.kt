@file:Suppress("unused")

package dev.ujhhgtg.wekit.bootstrap.loader.entry.lxp

import android.app.Application
import androidx.annotation.Keep
import dev.ujhhgtg.wekit.bootstrap.BootstrapLifecycle
import dev.ujhhgtg.wekit.bootstrap.BootstrapLog
import dev.ujhhgtg.wekit.bootstrap.BootstrapProcess
import dev.ujhhgtg.wekit.loader.api.InjectionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

@Keep
class LxpHookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) { LxpHookImpl.init(this) }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.packageName.startsWith("com.tencent.mm") || !param.isFirstPackage) return
        if (!BootstrapProcess.isCurrentProcessTargeted()) {
            BootstrapLog.i("Bootstrap", "detaching non-target process ${Application.getProcessName()}")
            return
        }
        BootstrapLifecycle.install(param.classLoader, LxpHookImpl, LxpHookImpl, InjectionMode.LIBXPOSED, null)
    }
}
