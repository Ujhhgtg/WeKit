package dev.ujhhgtg.wekit.loader.entry.lsp100

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.loader.entry.common.ModuleLoader
import dev.ujhhgtg.wekit.loader.entry.lsp100.Lsp100HookImpl.Companion.init
import dev.ujhhgtg.wekit.loader.entry.lsp10x.Lsp10xHookEntryHandler
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.annotations.XposedApiExact

@Keep
@XposedApiExact(100)
class Lsp100HookEntry(private val self: XposedModule) : Lsp10xHookEntryHandler {

    init {
        init(self)
    }

    @XposedApiExact(100)
    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (PackageNames.isWeChat(param.packageName)) {
            if (param.isFirstPackage()) {
                val ai = param.applicationInfo
                ModuleLoader.init(
                    ai.dataDir,
                    param.classLoader,
                    Lsp100HookImpl.INSTANCE,
                    Lsp100HookImpl.INSTANCE,
                    self.applicationInfo.sourceDir,
                    true
                )
            }
        }
    }
}
