package dev.ujhhgtg.wekit.loader.utils

import android.content.Context
import com.tencent.mmkv.MMKV
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.preferences.WePrefs
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    private val TAG = nameOf(NativeLoader)
    var initialized = false

    init {
        System.loadLibrary("dexkit")
        System.loadLibrary("wekit_native")
    }

    fun init(hostCtx: Context) {
        if (initialized) return
        initialized = true

        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirectories()
        }

        MMKV.initialize(hostCtx, mmkvDir.toString())

        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }
}
