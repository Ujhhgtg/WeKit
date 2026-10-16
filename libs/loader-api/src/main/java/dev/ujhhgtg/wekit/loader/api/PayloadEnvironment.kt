package dev.ujhhgtg.wekit.loader.api

import android.app.Activity
import android.app.Application
import android.os.ParcelFileDescriptor
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import java.io.File

interface PayloadEntry {
    fun start(environment: PayloadEnvironment)
}

interface AuthorizationControls {
    fun show(activity: Activity)
}

enum class InjectionMode { LIBXPOSED, ZYGISK }

interface TelegramRootAccess {
    fun hasTelegramRootCompanion(): Boolean
    fun listTelegramRootInstances(): List<String>
    fun copyTelegramRootDatabaseSnapshot(
        packageName: String,
        databaseFd: Int,
        walFd: Int,
        shmFd: Int,
    ): Int
}

data class PayloadEnvironment(
    val hostApplication: Application,
    val hostClassLoader: ClassLoader,
    val hookBridge: IHookBridge,
    val loaderService: ILoaderService,
    val injectionMode: InjectionMode,
    val telegramRootAccess: TelegramRootAccess?,
    val bootstrapApk: File,
    val payloadApk: ParcelFileDescriptor,
    val payloadSha256: String,
    val nativeDirectory: File,
    val authorizationControls: AuthorizationControls? = null,
)
