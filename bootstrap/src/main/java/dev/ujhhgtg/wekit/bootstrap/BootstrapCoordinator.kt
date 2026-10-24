package dev.ujhhgtg.wekit.bootstrap

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.widget.Toast
import androidx.activity.ComponentDialog
import androidx.core.net.toUri
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.bootstrap.auth.BindingRequest
import dev.ujhhgtg.wekit.bootstrap.auth.BootstrapApi
import dev.ujhhgtg.wekit.bootstrap.auth.DeviceMetadata
import dev.ujhhgtg.wekit.bootstrap.auth.InitializationReport
import dev.ujhhgtg.wekit.bootstrap.auth.InitializationRequest
import dev.ujhhgtg.wekit.bootstrap.auth.InstallationAction
import dev.ujhhgtg.wekit.bootstrap.auth.InstallationState
import dev.ujhhgtg.wekit.bootstrap.auth.InstallationStore
import dev.ujhhgtg.wekit.bootstrap.auth.PayloadCache
import dev.ujhhgtg.wekit.bootstrap.auth.RegistrationContextRequest
import dev.ujhhgtg.wekit.bootstrap.auth.RegistrationRequest
import dev.ujhhgtg.wekit.bootstrap.auth.ReleaseManifest
import dev.ujhhgtg.wekit.bootstrap.auth.RemoteApiException
import dev.ujhhgtg.wekit.bootstrap.auth.WireJson
import dev.ujhhgtg.wekit.bootstrap.auth.WireProtocol
import dev.ujhhgtg.wekit.bootstrap.auth.integer
import dev.ujhhgtg.wekit.bootstrap.auth.nullableString
import dev.ujhhgtg.wekit.bootstrap.auth.obj
import dev.ujhhgtg.wekit.bootstrap.auth.string
import dev.ujhhgtg.wekit.bootstrap.identity.CurrentIdentity
import dev.ujhhgtg.wekit.bootstrap.identity.CurrentIdentityReader
import dev.ujhhgtg.wekit.bootstrap.payload.PayloadLoader
import dev.ujhhgtg.wekit.bootstrap.payload.PreparedPayload
import dev.ujhhgtg.wekit.bootstrap.ui.BootstrapDialogAction
import dev.ujhhgtg.wekit.bootstrap.ui.BootstrapDialogs
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.abc.ILoaderService
import dev.ujhhgtg.wekit.loader.api.AuthorizationControls
import dev.ujhhgtg.wekit.loader.api.InjectionMode
import dev.ujhhgtg.wekit.loader.api.PayloadEntry
import dev.ujhhgtg.wekit.loader.api.PayloadEnvironment
import dev.ujhhgtg.wekit.loader.api.TelegramRootAccess
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One initialization in this process. Other processes neither wait for nor communicate with it. */
object BootstrapCoordinator {
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "WeKit bootstrap") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val processNonce = WireProtocol.encode(WireProtocol.randomBytes())
    private var initialized = false
    private lateinit var application: Application
    private lateinit var hostLoader: ClassLoader
    private lateinit var loaderService: ILoaderService
    private lateinit var hookBridge: IHookBridge
    private lateinit var injectionMode: InjectionMode
    private var telegramRootAccess: TelegramRootAccess? = null
    private lateinit var store: InstallationStore
    private lateinit var config: BootstrapConfig
    private lateinit var api: BootstrapApi
    private lateinit var cache: PayloadCache
    private lateinit var identityReader: CurrentIdentityReader
    private var mainProcess = false
    private var activity = WeakReference<Activity>(null)
    private var bindingDialog: ComponentDialog? = null
    private var bindingDialogSession: String? = null
    private var pendingBinding: InstallationState? = null
    private var pendingMessage: String? = null
    private var pendingRestartRequired = false
    private var prepared: PreparedPayload? = null
    private var entryAttempted = false
    private var loadedWxId: String? = null
    private var warnedAccountSwitch = false
    private var foreground = false
    private var bindingDismissed = false
    private var successfulInitializationId: String? = null
    private var successfulState: InstallationState? = null
    private var reportedAlias: String? = null
    private var payloadReadyForRestart = false

    @Synchronized
    fun start(
        hostApplication: Application, hostClassLoader: ClassLoader, service: ILoaderService,
        bridge: IHookBridge, mode: InjectionMode, rootAccess: TelegramRootAccess?,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "bootstrap startup must run on the main thread" }
        if (initialized) return
        application = hostApplication; hostLoader = hostClassLoader; loaderService = service
        hookBridge = bridge; injectionMode = mode; telegramRootAccess = rootAccess
        mainProcess = Application.getProcessName() == hostApplication.packageName
        store = InstallationStore(hostApplication)
        identityReader = CurrentIdentityReader(hostApplication)
        if (mainProcess) hostApplication.registerActivityLifecycleCallbacks(Lifecycle())
        initialized = true
        // Finish installing business hooks before returning to the host's startup callback.
        // Posting the entry later lets the host run with only a partially installed module.
        try { initialize(allowEntry = true) }
        catch (error: Throwable) { failed(error) }
    }

    private fun submit(action: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        worker.execute {
            try { action() }
            catch (error: Throwable) { failed(error) }
            finally { running.set(false) }
        }
    }

    private fun ensureSetup() {
        if (::api.isInitialized) return
        check(Process.is64Bit()) { "WeKit bootstrap requires arm64" }
        val apk = File(loaderService.mainModulePath)
        BootstrapNativeLibraries.initialize(application, apk)
        config = BootstrapConfig.DEFAULT
        api = BootstrapApi(config, store)
        cache = PayloadCache(store, config)
    }

    private fun initialize(allowEntry: Boolean = false) {
        ensureSetup()
        if (payloadReadyForRestart) return
        if (entryAttempted) {
            if (mainProcess && loadedWxId != null && !warnedAccountSwitch) {
                val now = identityReader.read()
                if (now.ready && now.wxId != loadedWxId) {
                    warnedAccountSwitch = true
                    message(uiText(R.string.wekit_auth_account_changed))
                } else if (now.ready && now.alias != null && now.alias != reportedAlias && successfulInitializationId != null) {
                    if (report(successfulState!!, successfulInitializationId!!, "IDENTITY_READ", "IDENTITY_UPDATE", now)) reportedAlias = now.alias
                }
            }
            return
        }
        val state = store.load()
        if (state == null || !state.active || !store.hasKey(state.keyAlias)) {
            if (!mainProcess) { BootstrapLog.i("Bootstrap", "No active installation; skipping this process"); return }
            ensureBinding(state)
            return
        }
        val openedCache = try { cache.open() }
        catch (error: Exception) {
            if (!mainProcess) throw error
            BootstrapLog.e("Bootstrap", "Invalid local cache; downloading the authorized object", error)
            cache.clear()
            null
        }
        if (!mainProcess && openedCache == null) { BootstrapLog.i("Bootstrap", "No complete cache; skipping this process"); return }
        val identity = if (mainProcess) identityReader.read() else CurrentIdentity(null, null, "UNAVAILABLE_IN_PROCESS", true)
        if (!identity.ready) {
            openedCache?.close()
            message(uiText(R.string.wekit_auth_account_not_ready))
            return
        }
        var request = initializationBody(state, identity)
        if (!mainProcess && openedCache?.release != null) {
            request = request.copy(requestedPayloadId = openedCache.release.descriptor.payloadId,
                cachedApkSha256 = openedCache.release.descriptor.apkSha256)
        }
        var initializationId: String? = null
        var key: ByteArray? = null
        var selectedCache = openedCache
        try {
            val authorization = run {
                val response = api.signedPost("/v1/initializations", WireJson.body(request), state, processNonce)
                initializationId = response.string("initializationId")
                JsonObject(response.filterKeys { it != "contentKey" })
            }
            val payloadMode = authorization.string("payloadMode")
            if (payloadMode == "LOCAL") {
                val selected = selectedCache ?: error("Local payload cache is missing")
                require(selected.release == null) { "Local payload cache is not a plain APK" }
                val localApkSha256 = selected.localApkSha256 ?: error("Local payload cache is missing")
                if (!allowEntry) {
                    payloadReadyForRestart = true
                    message(uiText(R.string.wekit_auth_payload_ready))
                    return
                }
                check(Looper.myLooper() == Looper.getMainLooper())
                val loaded = PayloadLoader.preparePlain(selected.fd, localApkSha256,
                    File(store.directory, "native"), BootstrapCoordinator::class.java.classLoader!!)
                prepared = loaded
                val environment = PayloadEnvironment(application, hostLoader, hookBridge, loaderService,
                    injectionMode, telegramRootAccess, File(loaderService.mainModulePath), loaded.apk,
                    localApkSha256, loaded.nativeDirectory, if (mainProcess) authorizationControls else null,
                    watermarkToken = initializationId)
                entryAttempted = true
                val entry = loaded.classLoader.loadClass("dev.ujhhgtg.wekit.loader.startup.PayloadEntryImpl")
                    .getDeclaredConstructor().newInstance() as PayloadEntry
                try { entry.start(environment) } finally { loaded.scrubNativeLibraries() }
                BootstrapLog.i("Bootstrap", "Local payload entry returned synchronously on the main thread")
                loadedWxId = identity.wxId
                successfulInitializationId = initializationId
                successfulState = state
                reportedAlias = identity.alias
                report(state, initializationId!!, "ENTRY_RETURNED", "LOADED", identity)
                mainHandler.post { bindingDialog?.dismiss(); pendingMessage = null }
                return
            }
            require(payloadMode == "CLOUD") { "Unknown payload mode: $payloadMode" }
            if (!mainProcess && selectedCache?.release == null) {
                selectedCache?.close()
                selectedCache = null
                BootstrapLog.i("Bootstrap", "Cloud payload cache is not ready in this process")
                return
            }
            key = WireProtocol.decode(authorization.string("contentKey"), 32)
            val release = ReleaseManifest.verify(authorization.obj("manifest"), config)
            require(authorization.string("payloadId") == release.descriptor.payloadId)
            if (mainProcess && (openedCache?.release == null || openedCache.release.manifestSha256 != release.manifestSha256)) {
                openedCache?.close(); selectedCache = null
                val download = {
                    cache.download(api, state, processNonce, authorization, release)
                    payloadReadyForRestart = true
                    message(uiText(R.string.wekit_auth_payload_ready))
                }
                // Downloads can outlive startup. Activate them only in a new process, whose
                // synchronous startup still owns the hook-installation window.
                if (allowEntry) submit(download) else download()
                return
            }
            val selected = selectedCache!!
            val selectedRelease = selected.release ?: error("Cloud payload cache is missing")
            require(selectedRelease.manifestSha256 == release.manifestSha256) { "Cache differs from authorized manifest" }
            if (!allowEntry) {
                payloadReadyForRestart = true
                message(uiText(R.string.wekit_auth_payload_ready))
                return
            }
            check(Looper.myLooper() == Looper.getMainLooper())
            val loaded = PayloadLoader.prepare(selected.fd, selected.offset, release.descriptor, key,
                File(store.directory, "native"), BootstrapCoordinator::class.java.classLoader!!)
            key.fill(0); key = null
            prepared = loaded
            val environment = PayloadEnvironment(application, hostLoader, hookBridge, loaderService,
                injectionMode, telegramRootAccess, File(loaderService.mainModulePath), loaded.apk,
                release.descriptor.apkSha256, loaded.nativeDirectory, if (mainProcess) authorizationControls else null,
                watermarkToken = initializationId)
            // Keep all inputs alive even if partially initialized business hooks later throw.
            entryAttempted = true
            val entry = loaded.classLoader.loadClass(release.descriptor.entryClass)
                .getDeclaredConstructor().newInstance() as PayloadEntry
            // start() loads the JNI libraries; do not leave the plaintext copies on disk, even if it throws.
            try { entry.start(environment) } finally { loaded.scrubNativeLibraries() }
            BootstrapLog.i("Bootstrap", "Payload entry returned synchronously on the main thread")
            loadedWxId = identity.wxId
            successfulInitializationId = initializationId
            successfulState = state
            reportedAlias = identity.alias
            report(state, initializationId!!, "ENTRY_RETURNED", "LOADED", identity)
            mainHandler.post { bindingDialog?.dismiss(); pendingMessage = null }
        } catch (error: Throwable) {
            if (!entryAttempted) { prepared?.close(); prepared = null }
            if (initializationId != null) report(state, initializationId, "INITIALIZATION", "FAILED", identity)
            throw error
        } finally { selectedCache?.close(); key?.fill(0) }
    }

    private fun initializationBody(state: InstallationState, identity: CurrentIdentity): InitializationRequest {
        val info = application.packageManager.getPackageInfo(application.packageName, 0)
        return InitializationRequest(WireProtocol.requestId(), state.installationId!!, processNonce,
            Application.getProcessName(), application.packageName, info.longVersionCode.toString(),
            info.versionName ?: "", Build.VERSION.SDK_INT, "arm64-v8a", loaderService.loaderVersionName,
            identity.wxId, identity.alias, identity.readState, requestedPayloadId = null)
    }

    private fun ensureBinding(previous: InstallationState?) {
        var state = previous
        if (state == null || state.active) state = store.candidate(state)
        if (state.registrationId == null) {
            val mode = if (state.installationId == null) state.registrationMode else "REGISTERED_KEY"
            val body = WireJson.body(RegistrationContextRequest(
                mode,
                WireProtocol.sha256(WireProtocol.decode(state.token, 32)), state.registrationNonce, state.installationId))
            val context = api.publicPost("/v1/registration/challenges", body)
            state = state.copy(registrationId = context.string("registrationId"),
                attestationChallenge = context.nullableString("attestationChallenge"),
                registrationExpiresAt = context.integer("expiresAt"))
            store.save(state)
        }
        if (state.bindingSessionId == null) {
            val mode = if (state.installationId == null) state.registrationMode else "REGISTERED_KEY"
            val firstRegistration = mode != "REGISTERED_KEY"
            if (firstRegistration && !store.hasKey(state.keyAlias)) {
                try {
                    store.createKey(application, state)
                } catch (error: Throwable) {
                    if (mode == "NEW_KEY" && isAttestationGenerationFailure(error)) {
                        // Unlocked devices whose vendor TEE/RKP is broken may still expose
                        // a non-exportable hardware key. Make the lower assurance explicit;
                        // the server and Telegram flow retain all authorization controls.
                        ensureBinding(store.candidate(state, "UNATTESTED_KEY"))
                        return
                    }
                    throw error
                }
            }
            val body = WireJson.body(RegistrationRequest(WireProtocol.requestId(), state.registrationId!!,
                state.registrationNonce, DeviceMetadata(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, application.packageName),
                spki = if (firstRegistration) store.spki(state) else null,
                certificateChain = if (firstRegistration && mode == "NEW_KEY") store.chain(state) else null,
                hardwareKey = firstRegistration && mode == "UNATTESTED_KEY"))
            val registration = api.signedPost("/v1/registrations", body, state, state.registrationNonce, true)
            state = state.copy(installationId = registration.string("installationId"))
            if (registration.string("state") in setOf("EXPIRED", "CANCELLED")) {
                ensureBinding(store.candidate(state))
                return
            }
            state = state.copy(
                bindingSessionId = registration.string("bindingSessionId"), bindingCode = registration.string("bindingCode"),
                bindingExpiresAt = registration.integer("expiresAt"))
            store.save(state)
        }
        pendingBinding = state
        bindingDismissed = false
        mainHandler.post { showBinding() }
    }

    private fun isAttestationGenerationFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        repeat(6) {
            val text = current?.message.orEmpty()
            if (text.contains("SECURE_HW_COMMUNICATION_FAILED", ignoreCase = true) ||
                text.contains("Failed to generate key pair", ignoreCase = true) ||
                current?.javaClass?.name == "android.security.keystore2.AndroidKeyStoreKeyPairGeneratorSpi") return true
            current = current?.cause
        }
        return false
    }

    private fun bindingStatus() {
        val state = store.load()!!
        val body = bindingBody(state)
        val status = api.signedPost("/v1/bindings/status", body, state, state.registrationNonce, true)
        when (status.string("state")) {
            "APPROVED", "CLAIMED" -> {
                val claimed = api.signedPost("/v1/bindings/claim", bindingBody(state), state, state.registrationNonce, true)
                check(claimed.string("state") == "CLAIMED")
                store.save(state.copy(active = true, telegramUserId = claimed.string("telegramUserId"),
                    bindingCode = null, bindingExpiresAt = 0, attestationChallenge = null))
                pendingBinding = null
                mainHandler.post { bindingDialog?.dismiss(); bindingDialog = null; bindingDialogSession = null }
                initialize()
            }
            "EXPIRED", "CANCELLED" -> {
                store.candidate(state)
                pendingBinding = null
                mainHandler.post { bindingDialog?.dismiss(); bindingDialog = null; bindingDialogSession = null }
                message(uiText(R.string.wekit_auth_binding_expired))
            }
        }
    }

    private fun bindingBody(state: InstallationState) = WireJson.body(BindingRequest(WireProtocol.requestId(),
        state.registrationId!!, state.registrationNonce, state.bindingSessionId!!))

    private val pollBinding = object : Runnable {
        override fun run() {
            if (bindingDialog?.isShowing != true || !foreground) return
            submit { bindingStatus() }
            mainHandler.postDelayed(this, 5000)
        }
    }

    private fun showBinding() {
        val state = pendingBinding ?: return
        if (bindingDismissed) return
        val owner = activity.get() ?: return
        if (owner.isFinishing || owner.isDestroyed) return
        if (bindingDialog?.isShowing == true && bindingDialogSession == state.bindingSessionId) return
        bindingDialog?.dismiss()
        val fingerprint = store.fingerprint(state).takeLast(12)
        val code = state.bindingCode!!
        bindingDialogSession = state.bindingSessionId
        bindingDialog = BootstrapDialogs.create(owner, File(loaderService.mainModulePath), uiText(R.string.wekit_auth_bind_title),
            uiText(R.string.wekit_auth_bind_message, config.telegramBotUsername, code, fingerprint),
            listOf(
                BootstrapDialogAction(uiText(R.string.wekit_auth_later)) { bindingDismissed = true },
                BootstrapDialogAction(uiText(R.string.wekit_auth_copy_code), dismissAfter = false) {
                    (owner.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText(uiText(R.string.wekit_auth_code_label), code))
                    Toast.makeText(owner, uiText(R.string.wekit_auth_code_copied), Toast.LENGTH_SHORT).show()
                },
                BootstrapDialogAction(uiText(R.string.wekit_auth_open_telegram), dismissAfter = false) {
                    try { owner.startActivity(Intent(Intent.ACTION_VIEW, "https://t.me/${config.telegramBotUsername}?start=$code".toUri())) }
                    catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(owner, uiText(R.string.wekit_auth_install_telegram, config.telegramBotUsername), Toast.LENGTH_LONG).show()
                    }
                },
            ), onDismiss = { mainHandler.removeCallbacks(pollBinding) })
        bindingDialog!!.show()
        mainHandler.removeCallbacks(pollBinding)
        mainHandler.postDelayed(pollBinding, 5000)
    }

    private fun report(state: InstallationState, initializationId: String, phase: String, result: String, identity: CurrentIdentity): Boolean {
        try {
            val body = WireJson.body(InitializationReport(WireProtocol.requestId(), state.installationId!!,
                processNonce, initializationId, phase, result, identity.wxId, identity.alias, identity.readState))
            api.signedPost("/v1/initializations/report", body, state, processNonce)
            return true
        } catch (error: Exception) { BootstrapLog.e("Bootstrap", "Initialization result report failed", error); return false }
    }

    private fun failed(error: Throwable) {
        BootstrapLog.e("Bootstrap", "Remote payload initialization failed", error)
        if (!mainProcess) return
        if (error is RemoteApiException && error.code == "RECONFIRM_REQUIRED") {
            val candidate = store.candidate(store.load())
            pendingBinding = null
            submit { ensureBinding(candidate) }
            return
        }
        if (error is RemoteApiException && error.code in setOf("REGISTRATION_EXPIRED", "CREDENTIAL_REVOKED", "EPOCH_MISMATCH")) {
            store.candidate(store.load())
            pendingBinding = null
        }
        message(uiText(R.string.wekit_auth_load_failure, error.message ?: error.javaClass.simpleName), restartRequired = entryAttempted)
    }

    private fun uiText(id: Int, vararg args: Any): String =
        BootstrapResources.getString(application, File(loaderService.mainModulePath), id, *args)

    private fun message(text: String, restartRequired: Boolean = false) {
        if (!mainProcess) return
        pendingMessage = text
        pendingRestartRequired = restartRequired
        mainHandler.post { showMessage() }
    }
    private fun showMessage() {
        val text = pendingMessage ?: return
        val owner = activity.get() ?: return
        if (owner.isFinishing || owner.isDestroyed || bindingDialog?.isShowing == true) return
        pendingMessage = null
        val restartRequired = pendingRestartRequired
        pendingRestartRequired = false
        val buttons = buildList {
            if (payloadReadyForRestart) {
                add(BootstrapDialogAction(uiText(R.string.dialog_confirm)) { restartHost() })
            } else {
                add(BootstrapDialogAction(uiText(R.string.wekit_auth_close)))
            }
            if (!entryAttempted && !payloadReadyForRestart) {
                add(BootstrapDialogAction(uiText(R.string.wekit_auth_more)) { authorizationControls.show(owner) })
                add(BootstrapDialogAction(uiText(R.string.wekit_auth_retry)) { submit { initialize() } })
            }
        }
        BootstrapDialogs.create(owner, File(loaderService.mainModulePath), "WeKit",
            if (restartRequired) uiText(R.string.wekit_auth_restart_after_entry, text) else text,
            buttons).show()
    }

    private fun restartHost() {
        BootstrapLog.i("Bootstrap", "restarting host")
        // The business payload is not loaded yet; use the same host restart entry directly.
        val instance = Class.forName("com.tencent.mm.process.KillProcessHelperActivity", false, hostLoader)
            .reflekt().firstField().get()!!
        instance.reflekt().firstMethod().invoke(application, true)
    }

    private val authorizationControls = object : AuthorizationControls {
        override fun show(activity: Activity) {
            val state = store.load()
            val account = state?.telegramUserId ?: uiText(R.string.wekit_auth_not_bound)
            val fingerprint = if (state != null && store.hasKey(state.keyAlias)) store.fingerprint(state).takeLast(12) else uiText(R.string.wekit_auth_not_registered)
            val buttons = buildList {
                add(BootstrapDialogAction(uiText(R.string.wekit_auth_close)))
                if (!entryAttempted) add(BootstrapDialogAction(uiText(R.string.wekit_auth_download_again)) { submit { ensureSetup(); cache.clear(); initialize() } })
                if (state?.active == true) add(BootstrapDialogAction(uiText(R.string.wekit_auth_unbind)) {
                    BootstrapDialogs.create(activity, File(loaderService.mainModulePath), uiText(R.string.wekit_auth_unbind_title),
                        uiText(R.string.wekit_auth_unbind_message),
                        listOf(BootstrapDialogAction(uiText(R.string.wekit_auth_cancel)), BootstrapDialogAction(uiText(R.string.wekit_auth_unbind)) {
                            submit {
                                ensureSetup()
                                api.signedPost("/v1/installations/unbind",
                                    WireJson.body(InstallationAction(WireProtocol.requestId(), state.installationId!!, processNonce)), state, processNonce)
                                store.candidate(state); pendingBinding = null
                                message(uiText(R.string.wekit_auth_unbound))
                            }
                        })).show()
                })
                add(BootstrapDialogAction(uiText(R.string.wekit_auth_rebind)) {
                    submit { ensureSetup(); pendingBinding = null; store.candidate(store.load()); ensureBinding(store.load()) }
                })
            }
            BootstrapDialogs.create(activity, File(loaderService.mainModulePath), uiText(R.string.wekit_auth_settings_title),
                uiText(R.string.wekit_auth_settings_message, account, fingerprint), buttons).show()
        }
    }

    private class Lifecycle : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(owner: Activity) {
            activity = WeakReference(owner)
            foreground = true
            showBinding(); showMessage()
            if (bindingDialog?.isShowing == true) {
                mainHandler.removeCallbacks(pollBinding); mainHandler.postDelayed(pollBinding, 1000)
            } else submit { initialize() }
        }
        override fun onActivityPaused(owner: Activity) { foreground = false; mainHandler.removeCallbacks(pollBinding) }
        override fun onActivityDestroyed(owner: Activity) {
            if (activity.get() === owner) { bindingDialog?.dismiss(); bindingDialog = null; activity.clear() }
        }
        override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(owner: Activity) = Unit
        override fun onActivityStopped(owner: Activity) = Unit
        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
    }
}
