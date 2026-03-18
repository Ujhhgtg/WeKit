package moe.ouom.wekit.application

import android.app.Application
import android.util.Log
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.loader.abc.IClassLoaderHelper
import moe.ouom.wekit.loader.abc.ILoaderService
import moe.ouom.wekit.loader.startup.StartupInfo
import moe.ouom.wekit.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        initStartupInfo()
    }

    private fun initStartupInfo() {
        val apkPath = applicationInfo.sourceDir

        val loaderService = object : ILoaderService {
            override var classLoaderHelper: IClassLoaderHelper? = null
            override val entryPointName: String = "Module"
            override val loaderVersionName: String = BuildConfig.VERSION_NAME
            override val loaderVersionCode: Int = BuildConfig.VERSION_CODE
            override val mainModulePath: String = apkPath
            override fun log(msg: String) = Log.i(BuildConfig.TAG, msg).let {}
            override fun log(tr: Throwable) = Log.e(BuildConfig.TAG, tr.toString(), tr).let {}
            override fun queryExtension(key: String, vararg args: Any?) = null

        }

        StartupInfo.modulePath= apkPath
        StartupInfo.loaderService = loaderService
    }
}