package dev.ujhhgtg.wekit.features.items.system.predictive_back

import android.content.pm.ActivityInfo
import dev.ujhhgtg.reflekt.reflekt

/** Hidden `ActivityInfo`/`ApplicationInfo` bits that decide ahead-of-time back dispatch. */
object PredictiveBackFlags {

    const val PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 2
    const val PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3
    const val PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3

    /**
     * Opts [info] into ahead-of-time back dispatch, overriding the manifest. The platform reads the
     * flag from the Activity's own ActivityInfo whenever it checks, so this must run before the
     * Activity is attached (`ActivityThread.handleLaunchActivity`).
     */
    fun enable(info: ActivityInfo) {
        val field = info.reflekt().firstField { name = "privateFlags" }
        var flags = field.get() as Int
        flags = flags or PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK
        flags = flags and PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK.inv()
        field.set(flags)
    }
}
