package dev.ujhhgtg.wekit.bootstrap

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.activity.ComponentDialog
import dev.ujhhgtg.wekit.bootstrap.payload.PayloadLoader
import dev.ujhhgtg.wekit.bootstrap.payload.PreparedPayload
import dev.ujhhgtg.wekit.bootstrap.ui.BootstrapDialogAction
import dev.ujhhgtg.wekit.bootstrap.ui.BootstrapDialogs
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.api.InjectionMode
import dev.ujhhgtg.wekit.loader.api.PayloadEntry
import dev.ujhhgtg.wekit.loader.api.PayloadEnvironment
import dev.ujhhgtg.wekit.loader.api.TelegramRootAccess
import java.io.File
import java.lang.ref.WeakReference

/** Loads the release payload embedded in bootstrap-local without authentication or networking. */
object EmbeddedPayloadCoordinator {
    private const val TAG = "BootstrapLocal"
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var application: Application
    private lateinit var hostLoader: ClassLoader
    private lateinit var loaderService: ILoaderService
    private lateinit var hookBridge: IHookBridge
    private lateinit var injectionMode: InjectionMode
    private var telegramRootAccess: TelegramRootAccess? = null
    private var mainProcess = false
    private var initialized = false
    private var entryAttempted = false
    private var prepared: PreparedPayload? = null
    private var failureMessage: String? = null
    private var failureRestartRequired = false
    private var failureDialog: ComponentDialog? = null
    private var activity = WeakReference<Activity>(null)

    @Synchronized
    fun start(
        hostApplication: Application,
        hostClassLoader: ClassLoader,
        service: ILoaderService,
        bridge: IHookBridge,
        mode: InjectionMode,
        rootAccess: TelegramRootAccess?,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "bootstrap startup must run on the main thread" }
        if (!BootstrapProcess.isCurrentProcessTargeted()) {
            BootstrapLog.i(TAG, "skipping non-target process ${Application.getProcessName()}")
            return
        }
        if (initialized) return
        application = hostApplication
        hostLoader = hostClassLoader
        loaderService = service
        hookBridge = bridge
        injectionMode = mode
        telegramRootAccess = rootAccess
        mainProcess = Application.getProcessName() == hostApplication.packageName
        initialized = true
        if (mainProcess) hostApplication.registerActivityLifecycleCallbacks(Lifecycle())
        try {
            initialize()
        } catch (error: Throwable) {
            failed(error)
        }
    }

    private fun initialize() {
        check(Process.is64Bit()) { "WeKit bootstrap requires arm64" }
        val bootstrapApk = File(loaderService.mainModulePath)
        BootstrapNativeLibraries.initialize(application, bootstrapApk)
        check(Looper.myLooper() == Looper.getMainLooper())
        val loaded = PayloadLoader.prepareEmbedded(
            bootstrapApk,
            File(application.noBackupFilesDir, "wekit-loader/native"),
            EmbeddedPayloadCoordinator::class.java.classLoader!!,
        )
        prepared = loaded
        val environment = PayloadEnvironment(
            application,
            hostLoader,
            hookBridge,
            loaderService,
            injectionMode,
            telegramRootAccess,
            bootstrapApk,
            loaded.apk,
            loaded.apkSha256,
            loaded.nativeDirectory,
        )
        entryAttempted = true
        val entry = loaded.classLoader
            .loadClass("dev.ujhhgtg.wekit.loader.startup.PayloadEntryImpl")
            .getDeclaredConstructor()
            .newInstance() as PayloadEntry
        try {
            entry.start(environment)
        } finally {
            loaded.scrubNativeLibraries()
        }
        BootstrapLog.i(TAG, "embedded release payload entry returned synchronously on the main thread")
    }

    private fun failed(error: Throwable) {
        BootstrapLog.e(TAG, "Embedded payload initialization failed", error)
        if (!mainProcess) return
        failureMessage = uiText(
            R.string.wekit_auth_load_failure,
            error.message ?: error.javaClass.simpleName,
        )
        failureRestartRequired = entryAttempted
        mainHandler.post(::showFailure)
    }

    private fun showFailure() {
        val message = failureMessage ?: return
        val owner = activity.get() ?: return
        if (owner.isFinishing || owner.isDestroyed || failureDialog?.isShowing == true) return
        val text = if (failureRestartRequired) {
            uiText(R.string.wekit_auth_restart_after_entry, message)
        } else {
            message
        }
        failureDialog = BootstrapDialogs.create(
            owner,
            File(loaderService.mainModulePath),
            "WeKit",
            text,
            listOf(BootstrapDialogAction(uiText(R.string.wekit_auth_close))),
        )
        failureDialog!!.show()
    }

    private fun uiText(id: Int, vararg args: Any): String =
        BootstrapResources.getString(application, File(loaderService.mainModulePath), id, *args)

    private class Lifecycle : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(owner: Activity) {
            activity = WeakReference(owner)
            showFailure()
        }

        override fun onActivityDestroyed(owner: Activity) {
            if (activity.get() === owner) {
                failureDialog?.dismiss()
                failureDialog = null
                activity.clear()
            }
        }

        override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(owner: Activity) = Unit
        override fun onActivityPaused(owner: Activity) = Unit
        override fun onActivityStopped(owner: Activity) = Unit
        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
    }
}
