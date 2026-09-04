package dev.ujhhgtg.wekit.loader.entry.zygisk

import android.util.Log
import androidx.annotation.Keep
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.HostLocalizedStrings
import dev.ujhhgtg.wekit.loader.abc.IClassLoaderHelper
import dev.ujhhgtg.wekit.loader.abc.ILoaderService

@Keep
class ZygiskLoaderService(
    private val modulePath: String,
    private val versionName: String,
    private val versionCode: Int,
) : ILoaderService {

    override var classLoaderHelper: IClassLoaderHelper? = null

    override val loaderName: String get() = HostLocalizedStrings.get(R.string.loader_zygisk_name)

    override val entryPointName: String = "dev.ujhhgtg.wekit.loader.entry.zygisk.ZygiskEntry"

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
