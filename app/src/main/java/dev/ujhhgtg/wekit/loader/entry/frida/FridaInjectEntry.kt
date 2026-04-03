package dev.ujhhgtg.wekit.loader.entry.frida

import android.annotation.SuppressLint
import android.app.ActivityThread
import android.util.Log
import androidx.annotation.Keep
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.loader.entry.common.ModuleLoader
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

@Keep
@SuppressLint("PrivateApi")
object FridaInjectEntry {

    private val TAG = nameOf(FridaInjectEntry)

    @JvmStatic
    fun entry3(modulePath: String, hostDataDir: String?, xblService: Map<String, Method>?) {
        runCatching {
            val hostData = if (hostDataDir == null) findHostDataDir() else File(hostDataDir)
            startup(File(modulePath), hostData, xblService)
        }.onFailure { e ->
            val cause = e.unwrapIte()
            Log.e(TAG, "FridaInjectEntry.entry3: failed", cause)
            throw cause
        }
    }

    @Suppress("unused")
    @JvmStatic
    fun entry2(modulePath: String, hostDataDir: String) {
        runCatching {
            startup(File(modulePath), File(hostDataDir), null)
        }.onFailure { e ->
            val cause = e.unwrapIte()
            Log.e(TAG, "FridaInjectEntry.entry2: failed", cause)
            throw cause
        }
    }

    @Suppress("unused")
    @JvmStatic
    fun entry1(modulePath: String) {
        runCatching {
            startup(File(modulePath), findHostDataDir(), null)
        }.onFailure { e ->
            val cause = e.unwrapIte()
            Log.e(TAG, "FridaInjectEntry.entry1: failed", cause)
            throw cause
        }
    }

    private fun startup(modulePath: File, hostDataDir: File, xblService: Map<String, Method>?) {
        require(modulePath.canRead()) { "modulePath is not readable: $modulePath" }
        require(hostDataDir.canRead()) { "hostDataDir is not readable: $hostDataDir" }
        FridaStartupImpl.apply {
            setModulePath(modulePath)
            setHostDataDir(hostDataDir)
            setXblService(xblService)
        }
        val cl = findHostClassLoader()
        ModuleLoader.init(
            hostDataDir.absolutePath,
            cl,
            FridaStartupImpl,
            null,
            modulePath.absolutePath,
            false
        )
    }

    private fun findHostClassLoader(): ClassLoader {
        return ActivityThread.currentActivityThread().application.classLoader
    }

    private fun findHostDataDir(): File {
        return ActivityThread.currentActivityThread().application.dataDir
    }

    private fun Throwable.unwrapIte(): Throwable {
        var e = this
        while (e is InvocationTargetException) {
            e = e.targetException ?: break
        }
        return e
    }

    @Keep
    class EntryRunnableV3(
        private val modulePath: String,
        private val hostDataDir: String?,
        private val xblService: Map<String, Method>?
    ) : Runnable {
        override fun run() {
            runCatching {
                entry3(modulePath, hostDataDir, xblService)
            }.onFailure { e ->
                Log.e(TAG, "FridaInjectEntry.EntryRunnableV3: failed", e)
            }
        }
    }
}
