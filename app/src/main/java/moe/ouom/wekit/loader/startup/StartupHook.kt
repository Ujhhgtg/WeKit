package moe.ouom.wekit.loader.startup

import android.app.Application
import android.content.Context
import android.util.Log
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.host.HostInfo
import moe.ouom.wekit.loader.core.NativeLoader
import moe.ouom.wekit.loader.core.WeLauncher
import moe.ouom.wekit.loader.hookimpl.InMemoryClassLoaderHelper
import moe.ouom.wekit.loader.hookimpl.LibXposedApiByteCodeGenerator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

object StartupHook {

    fun execStartupInit(ctx: Context) {
        execPostStartupInit(ctx)
        deleteDirIfNecessary(ctx)
    }

    fun execPostStartupInit(ctx: Context) {
        HostInfo.init(ctx as Application)
        StartupInfo.getLoaderService().setClassLoaderHelper(InMemoryClassLoaderHelper.INSTANCE)
        LibXposedApiByteCodeGenerator.init()
        NativeLoader.initNative()
        WeLauncher.init(ctx.classLoader, ctx)
    }

    fun initializeAfterAppCreate(ctx: Context) {
        execStartupInit(ctx)
        deleteDirIfNecessary(ctx)
    }

    private fun deleteDirIfNecessary(ctx: Context) {
        runCatching {
            ctx.dataDir.toPath().resolve("app_qqprotect").deleteRecursively()
        }.onFailure(::logError)
    }

    private fun Path.deleteRecursively() {
        if (!exists()) return
        if (isDirectory()) {
            listDirectoryEntries().forEach { it.deleteRecursively() }
        }
        Files.delete(this)
    }

    private fun logError(th: Throwable) {
        val msg = Log.getStackTraceString(th)
        Log.e(BuildConfig.TAG, msg)
        runCatching {
            StartupInfo.getLoaderService().log(th)
        }.onFailure {
            if (it is NoClassDefFoundError || it is NullPointerException) {
                Log.e("Xposed", msg)
                Log.e("EdXposed-Bridge", msg)
            } else throw it
        }
    }
}