package dev.ujhhgtg.wekit.loader.utils

import android.content.Context
import com.tencent.mmkv.MMKV
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.utils.fs.createDirsSafe
import kotlin.io.path.div
import kotlin.io.path.exists

object NativeLoader {

    init {
        System.loadLibrary("dexkit")
        System.loadLibrary("wekit_native")
//        runCatching {
//            System.loadLibrary("nuke_native")
//        }.onFailure {
//            WeLogger.e("NativeLoader", "failed to load nuke native", it)
//        }.onSuccess {
//            WeLogger.i("NativeLoader", "loaded nuke native")
//        }
    }

    fun init(hostCtx: Context) {
        val mmkvDir = hostCtx.filesDir.toPath() / "mmkv"
        if (!mmkvDir.exists()) {
            mmkvDir.createDirsSafe()
        }

        MMKV.initialize(hostCtx, mmkvDir.toString())

        MMKV.mmkvWithID(WePrefs.PREFS_NAME, MMKV.MULTI_PROCESS_MODE)
    }
}
