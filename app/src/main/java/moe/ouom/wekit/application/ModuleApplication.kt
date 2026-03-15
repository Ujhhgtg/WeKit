package moe.ouom.wekit.application

import android.app.Application
import android.util.Log
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.loader.hookapi.IClassLoaderHelper
import moe.ouom.wekit.loader.hookapi.ILoaderService
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
            private var classLoaderHelper: IClassLoaderHelper? = null

            override fun log(msg: String) = Log.i(BuildConfig.TAG, msg).let {}
            override fun log(tr: Throwable) = Log.e("ovom", tr.toString(), tr).let {}
            override fun queryExtension(key: String, vararg args: Any?) = null
            override fun getClassLoaderHelper() = classLoaderHelper
            override fun setClassLoaderHelper(helper: IClassLoaderHelper?) {
                classLoaderHelper = helper
            }
        }

        StartupInfo.setModulePath(apkPath)
        StartupInfo.setLoaderService(loaderService)
    }
}