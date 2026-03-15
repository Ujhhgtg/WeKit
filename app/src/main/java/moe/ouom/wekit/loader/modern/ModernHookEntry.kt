package moe.ouom.wekit.loader.modern

import android.content.pm.ApplicationInfo
import androidx.annotation.Keep
import dev.ujhhgtg.nameof.nameof
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import moe.ouom.wekit.constants.PackageNames
import moe.ouom.wekit.loader.ModuleLoader
import moe.ouom.wekit.loader.startup.StartupInfo
import moe.ouom.wekit.utils.logging.WeLogger

@Keep
class ModernHookEntry(base: XposedInterface, param: XposedModuleInterface.ModuleLoadedParam)
    : XposedModule(base, param) {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val packageName = param.packageName
        val processName = param.applicationInfo.processName
        if (packageName == PackageNames.WECHAT && param.isFirstPackage) {
            val modulePath = this.applicationInfo.sourceDir
            StartupInfo.setModulePath(modulePath)
            handleLoadPackage(param.classLoader, param.applicationInfo, modulePath, processName)
        }
    }

    fun handleLoadPackage(cl: ClassLoader, ai: ApplicationInfo, modulePath: String, processName: String?) {
        val dataDir = ai.dataDir
        WeLogger.d(TAG, "handleLoadHostPackage: dataDir=$dataDir, modulePath=$modulePath, processName=$processName")
        try {
            ModuleLoader.initialize(cl, Lsp100HookImpl.INSTANCE, modulePath)
        } catch (e: ReflectiveOperationException) {
            WeLogger.e(TAG, "failed to invoke ModuleLoader.initialize")
            throw RuntimeException(e)
        }
    }

    companion object {
        private val TAG = nameof(ModernHookEntry::class)
    }
}
