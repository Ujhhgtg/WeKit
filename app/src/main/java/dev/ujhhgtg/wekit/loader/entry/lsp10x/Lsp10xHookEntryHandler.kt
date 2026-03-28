package dev.ujhhgtg.wekit.loader.entry.lsp10x

import androidx.annotation.Keep
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam

@Keep
interface Lsp10xHookEntryHandler {
    fun onPackageLoaded(param: PackageLoadedParam)
}
