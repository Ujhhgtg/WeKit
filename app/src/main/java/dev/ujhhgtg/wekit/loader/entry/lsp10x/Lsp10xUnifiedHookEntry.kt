package dev.ujhhgtg.wekit.loader.entry.lsp10x

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.loader.entry.lsp100.Lsp100HookEntry
import dev.ujhhgtg.wekit.loader.entry.lsp101.Lsp101HookEntry
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.annotations.XposedApiExact
import io.github.libxposed.api.annotations.XposedApiMin

@Keep
class Lsp10xUnifiedHookEntry : XposedModule {

    private val mHandler: Lsp10xHookEntryHandler

    @Suppress("unused")
    @XposedApiExact(100)
    constructor(base: XposedInterface, param: ModuleLoadedParam) : super(base, param) {
        mHandler = Lsp100HookEntry(this)
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        mHandler.onPackageLoaded(param)
    }

    @Suppress("unused")
    @XposedApiMin(101)
    constructor() : super() {
        mHandler = Lsp101HookEntry(this)
    }

    @XposedApiMin(101)
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        (mHandler as Lsp101HookEntry).onModuleLoaded(param)
    }

    @XposedApiMin(101)
    override fun onPackageReady(param: PackageReadyParam) {
        (mHandler as Lsp101HookEntry).onPackageReady(param)
    }
}
