package dev.ujhhgtg.wekit.application

import android.app.Application
import android.content.Context
import dev.ujhhgtg.lsparanoid.generated.LspBootstrap
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

class ModuleApplication : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        LspBootstrap.loadInstalled()
    }

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        WeKitLocaleController.initializeModuleProcess(this)
    }
}
