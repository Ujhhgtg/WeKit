package dev.ujhhgtg.wekit.application

import android.app.Application
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

class ModuleApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        HostInfo.init(this)
        WeKitLocaleController.initialize(this)
    }
}
