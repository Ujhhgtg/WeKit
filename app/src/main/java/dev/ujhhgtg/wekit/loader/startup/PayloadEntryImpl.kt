package dev.ujhhgtg.wekit.loader.startup

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.loader.api.PayloadEntry
import dev.ujhhgtg.wekit.loader.api.PayloadEnvironment

/** The signed release manifest names this entry; no installed payload APK is required. */
@Keep
class PayloadEntryImpl : PayloadEntry {
    override fun start(environment: PayloadEnvironment) {
        ModuleLoader.startPayload(environment)
    }
}
