package moe.ouom.wekit.loader.entry.frida

import android.util.Log
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.loader.abc.IClassLoaderHelper
import moe.ouom.wekit.loader.abc.ILoaderService
import java.io.File
import java.lang.reflect.Method

object FridaStartupImpl: ILoaderService {

    private lateinit var mModulePath: File
    private lateinit var mHostDataDir: File
    private var mXblService: Map<String, Method>? = null
    override var classLoaderHelper: IClassLoaderHelper? = null

    internal fun setModulePath(modulePath: File) { mModulePath = modulePath }
    internal fun setHostDataDir(hostDataDir: File) { mHostDataDir = hostDataDir }
    internal fun setXblService(xblService: Map<String, Method>?) { mXblService = xblService }

    private fun unsafeInvokeXblService(m: Method, vararg args: Any?): Any? {
        return m.invoke(null, *args)
    }

    override val entryPointName: String
        get() {
            val m = mXblService?.get("GetEntryPointName")
            if (m != null) return unsafeInvokeXblService(m) as String
            return FridaInjectEntry::class.java.name
        }

    override val loaderVersionName: String
        get() {
            val m = mXblService?.get("GetLoaderVersionName")
            if (m != null) return unsafeInvokeXblService(m) as String
            return BuildConfig.VERSION_NAME
        }

    override val loaderVersionCode: Int
        get() {
            val m = mXblService?.get("GetLoaderVersionCode")
            if (m != null) return unsafeInvokeXblService(m) as Int
            return BuildConfig.VERSION_CODE
        }

    override val mainModulePath: String
        get() = mModulePath.absolutePath

    override fun log(msg: String) = Log.i(BuildConfig.TAG, msg).let {}

    override fun log(tr: Throwable) { Log.e(BuildConfig.TAG, tr.toString(), tr) }

    override fun queryExtension(key: String, vararg args: Any?): Any? {
        val m = mXblService?.get("QueryExtension") ?: return null
        return unsafeInvokeXblService(m, key, args)
    }
}