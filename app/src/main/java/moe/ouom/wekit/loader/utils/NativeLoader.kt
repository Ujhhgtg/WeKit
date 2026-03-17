package moe.ouom.wekit.loader.utils

import com.tencent.mmkv.MMKV
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.utils.HostInfo
import moe.ouom.wekit.utils.logging.WeLogger
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    private val TAG = nameof(NativeLoader)
    var initialized = false

    fun init() {
        if (initialized) return
        initialized = true

        val ctx = HostInfo.application

        WeLogger.i(TAG, "loading native libs...")
        System.loadLibrary("dexkit")
        System.loadLibrary("wekit_native")

        val mmkvDir = ctx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirectories()
        }

        MMKV.initialize(ctx, mmkvDir.toString())

        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
        MMKV.mmkvWithID(WePrefs.CACHE_PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }
}
