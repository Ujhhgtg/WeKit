package moe.ouom.wekit.loader.core

import android.content.Context
import com.tencent.mmkv.MMKV
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.utils.HostInfo
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    private var isInitialized = false

    init {
        System.loadLibrary("dexkit")
        System.loadLibrary("wekit_native")
    }

    fun initNative() = initializeMmkv(HostInfo.application)

    fun isInitialized() = isInitialized

    fun initializeMmkv(ctx: Context) {
        if (isInitialized) return

        val mmkvDir = ctx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirectories()
        }

        MMKV.initialize(ctx, mmkvDir.toString())

        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
        MMKV.mmkvWithID(WePrefs.CACHE_PREFS_NAME, MMKV.MULTI_PROCESS_MODE)

        isInitialized = true
    }
}
