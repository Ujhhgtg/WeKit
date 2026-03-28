package dev.ujhhgtg.wekit.loader.entry.lsp101

import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.loader.entry.common.ModuleLoader
import dev.ujhhgtg.wekit.loader.entry.lsp10x.Lsp10xHookEntryHandler
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.annotations.XposedApiMin

@XposedApiMin(101)
class Lsp101HookEntry(private val self: XposedModule) : Lsp10xHookEntryHandler {

    @Suppress("unused")
    fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        Lsp101HookImpl.init(self)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
    }

    fun onPackageReady(param: PackageReadyParam) {
        if (PackageNames.isWeChat(param.packageName)) {
            if (param.isFirstPackage()) {
                val ai = param.applicationInfo
                ModuleLoader.init(
                    ai.dataDir,
                    param.classLoader,
                    Lsp101HookImpl.INSTANCE,
                    Lsp101HookImpl.INSTANCE,
                    self.moduleApplicationInfo.sourceDir,
                    true
                )
            }
        }
    }
}
