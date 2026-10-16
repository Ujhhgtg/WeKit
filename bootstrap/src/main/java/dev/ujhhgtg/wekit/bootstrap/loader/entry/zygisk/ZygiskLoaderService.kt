package dev.ujhhgtg.wekit.bootstrap.loader.entry.zygisk

import android.util.Log
import androidx.annotation.Keep
import dev.ujhhgtg.wekit.loader.abc.ILoaderService

@Keep
class ZygiskLoaderService(
    private val modulePath: String,
    private val versionName: String,
    private val versionCode: Int,
) : ILoaderService {

    override val loaderName: String get() = "Zygisk"

    override val entryPointName: String = "dev.ujhhgtg.wekit.bootstrap.loader.entry.zygisk.ZygiskEntry"

    override val loaderVersionName: String get() = versionName

    override val loaderVersionCode: Int get() = versionCode

    override val mainModulePath: String get() = modulePath

    override fun log(msg: String) {
        Log.i(TAG, msg)
    }

    override fun log(tr: Throwable) {
        Log.e(TAG, tr.toString(), tr)
    }

    override fun queryExtension(key: String, vararg args: Any?): Any? = null

    companion object {
        private const val TAG = "ZygiskLoaderService"
    }
}
