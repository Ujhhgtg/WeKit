@file:Suppress("unused")

package dev.ujhhgtg.wekit.loader.entry.lxp

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.loader.startup.ModuleLoader
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

@Keep
class LxpHookEntry : XposedModule() {

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        LxpHookImpl.init(this)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (PackageNames.isWeChat(param.packageName)) {
            if (param.isFirstPackage) {
                ModuleLoader.init(
                    param.classLoader,
                    LxpHookImpl,
                    LxpHookImpl,
                    this.moduleApplicationInfo.sourceDir,
                )
            }
        }
    }
}
