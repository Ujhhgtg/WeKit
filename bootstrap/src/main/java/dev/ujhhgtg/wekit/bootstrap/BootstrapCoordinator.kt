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
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
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
import dev.ujhhgtg.wekit.bootstrap.auth.LocalAuthorization
import dev.ujhhgtg.wekit.bootstrap.auth.LocalAuthorizationStore
import dev.ujhhgtg.wekit.bootstrap.auth.PayloadCache
import dev.ujhhgtg.wekit.bootstrap.auth.RegistrationContextRequest
import dev.ujhhgtg.wekit.bootstrap.auth.RegistrationRequest
import dev.ujhhgtg.wekit.bootstrap.auth.ReleaseManifest
import dev.ujhhgtg.wekit.bootstrap.auth.RemoteApiException
import dev.ujhhgtg.wekit.bootstrap.auth.VerifiedRelease
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
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipException

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
    /** Queued messages; one dialog is shown at a time so a failure and its follow-up never overlap. */
    private val pendingMessages = ArrayDeque<QueuedMessage>()
    private var messageDialog: ComponentDialog? = null
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
    /** Set after a non-retryable API failure; cleared only by an explicit user action. */
    private var retryBlocked = false
    /**
     * Work started from the `callApplicationOnCreate` before hook: it overlaps the host's own
     * `Application.onCreate` instead of extending the synchronous startup window. [start] joins it.
     */
    private var prewarm: CompletableFuture<PreparedStart?>? = null
    private var prewarmStartedAt = 0L
    /** `main`, `push`, `appbrand0`, ... used to tell interleaved process logs apart. */
    private var processLabel = "?"
    /** [start] installs the payload entry at most once per process. */
    private var startConsumed = false
    /** Sealed short-lived authorization that lets a start skip the control plane. */
    private lateinit var localAuthorization: LocalAuthorizationStore
    private var restartScheduled = false
    /**
     * Online refresh started for a locally authorized boot before the payload entry runs. It keeps
     * the update path alive when the entry itself throws (StackOverflowError and friends): the
     * worker finishes the check and the download even though the main thread never returned.
     * Null when this process does not run the refresh (non-main processes, online authorization).
     */
    private var pendingRefresh: CompletableFuture<RefreshOutcome>? = null

    /** Everything the main thread needs to install the payload entry after the worker is done. */
    private class PreparedStart(
        val state: InstallationState,
        val identity: CurrentIdentity,
        val release: VerifiedRelease,
        /** Watermark identity: a fresh initialization, or the last online one for an offline boot. */
        val initializationId: String,
        val loaded: PreparedPayload,
        /** True when the payload was decrypted from the local authorization, without the server. */
        val offline: Boolean,
    )

    /** One user-facing message; [restartRequired] adds the "entry was attempted" restart hint. */
    private class QueuedMessage(val text: String, val restartRequired: Boolean)

    /** Outcome of [runPendingRefresh]; the entry path decides how to act on it in [finishPendingRefresh]. */
    private sealed interface RefreshOutcome {
        /** The server still selects the running payload; the local authorization can be renewed. */
        class Current(val authorization: ServerAuthorization) : RefreshOutcome
        /** A newer payload was downloaded and the user has to restart to activate it. */
        object Updated : RefreshOutcome
        /** The server denied the installation; a denial is acted on only after the entry attempt. */
        class Denied(val error: RemoteApiException) : RefreshOutcome
        /** Network or protocol failure; the running payload is unaffected. */
        class Unavailable(val error: Throwable) : RefreshOutcome
    }


    @Synchronized
    private fun bind(
        hostApplication: Application, hostClassLoader: ClassLoader, service: ILoaderService,
        bridge: IHookBridge, mode: InjectionMode, rootAccess: TelegramRootAccess?,
    ) {
        application = hostApplication; hostLoader = hostClassLoader; loaderService = service
        hookBridge = bridge; injectionMode = mode; telegramRootAccess = rootAccess
        mainProcess = Application.getProcessName() == hostApplication.packageName
        processLabel = Application.getProcessName()?.let {
            if (it == hostApplication.packageName) "main" else it.substringAfterLast(':')
        } ?: "?"
        store = InstallationStore(hostApplication)
        localAuthorization = LocalAuthorizationStore(hostApplication)
        identityReader = CurrentIdentityReader(hostApplication)
        if (mainProcess) hostApplication.registerActivityLifecycleCallbacks(Lifecycle())
        initialized = true
    }

    /**
     * Starts native preparation, credential/cache reads, the initialization request and the payload
     * decryption on the bootstrap worker from the `callApplicationOnCreate` before hook, so all of it
     * overlaps the host's own `Application.onCreate` instead of extending the synchronous window.
     *
     * The payload entry is still installed synchronously by [start]; only work that does not need the
     * main thread is moved here. Never throws into the host's startup hook: failures are rethrown by
     * [start], which reports them exactly like a synchronous failure.
     */
    fun prewarm(
        hostApplication: Application, hostClassLoader: ClassLoader, service: ILoaderService,
        bridge: IHookBridge, mode: InjectionMode, rootAccess: TelegramRootAccess?,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "bootstrap prewarm must run on the main thread" }
        if (!BootstrapProcess.isCurrentProcessTargeted()) {
            BootstrapLog.i("Bootstrap", "skipping non-target process ${Application.getProcessName()}")
            return
        }
        val future = CompletableFuture<PreparedStart?>()
        synchronized(this) {
            if (initialized) return
            prewarm = future
            try {
                bind(hostApplication, hostClassLoader, service, bridge, mode, rootAccess)
            } catch (error: Throwable) {
                future.completeExceptionally(error)
                return
            }
            prewarmStartedAt = SystemClock.elapsedRealtime()
            log("prewarm started ${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()} ms " +
                "after process start, before the host's Application.onCreate")
            worker.execute {
                try { future.complete(authorizeAndPrepare(allowEntry = true)) }
                catch (error: Throwable) { future.completeExceptionally(error) }
            }
        }
    }

    /** Main-thread entry point from the `callApplicationOnCreate` after hook. */
    fun start(
        hostApplication: Application, hostClassLoader: ClassLoader, service: ILoaderService,
        bridge: IHookBridge, mode: InjectionMode, rootAccess: TelegramRootAccess?,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "bootstrap startup must run on the main thread" }
        if (!BootstrapProcess.isCurrentProcessTargeted()) {
            BootstrapLog.i("Bootstrap", "skipping non-target process ${Application.getProcessName()}")
            return
        }
        // Tracked outside the try: a failed entry must still report against the pre-entry online
        // authorization, which keeps running on the bootstrap worker.
        var attemptedStart: PreparedStart? = null
        try {
            val enteredAt = millisSincePrewarm()
            if (prewarm != null) log("startup hook entered at $enteredAt ms")
            val pending: CompletableFuture<PreparedStart?>?
            synchronized(this) {
                if (startConsumed) return
                startConsumed = true
                pending = prewarm
                if (pending == null && !initialized)
                    bind(hostApplication, hostClassLoader, service, bridge, mode, rootAccess)
            }
            // Finish installing business hooks before returning to the host's startup callback.
            // Posting the entry later lets the host run with only a partially installed module.
            val preparedStart = if (pending != null) awaitPrewarm(pending, enteredAt)
            else authorizeAndPrepare(allowEntry = true)
            attemptedStart = preparedStart
            if (preparedStart != null) startPreparedEntry(preparedStart)
        } catch (error: Throwable) {
            failed(error)
            // The pre-entry refresh must outlive the entry failure: it is what discovers and
            // downloads the fixed payload. Report the failure against it once the refresh is done.
            attemptedStart?.takeIf { it.offline }?.let { finishPendingRefresh(it, error) }
        }
    }

    private fun awaitPrewarm(pending: CompletableFuture<PreparedStart?>, enteredAt: Long): PreparedStart? {
        val preparedStart = try {
            pending.get()
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
        val joinedAt = millisSincePrewarm()
        log("prewarm joined at $joinedAt ms (host startup window $enteredAt ms, main waited ${joinedAt - enteredAt} ms)")
        return preparedStart
    }

    private fun millisSincePrewarm(): Long =
        if (prewarmStartedAt == 0L) 0 else SystemClock.elapsedRealtime() - prewarmStartedAt

    private fun log(message: String) = BootstrapLog.i("Bootstrap", "[${Process.myPid()} $processLabel] $message")

    private fun submit(action: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        worker.execute {
            try { action() }
            catch (error: Throwable) { failed(error) }
            finally { running.set(false) }
        }
    }

    @Synchronized
    private fun ensureSetup() {
        if (::api.isInitialized) return
        check(Process.is64Bit()) { "WeKit bootstrap requires arm64" }
        val apk = File(loaderService.mainModulePath)
        BootstrapNativeLibraries.initialize(application, apk)
        config = BootstrapConfig.DEFAULT
        api = BootstrapApi(config, store)
        cache = PayloadCache(store, config)
    }

    /** Retry and lifecycle re-check entry point; runs on the bootstrap worker. */
    private fun initialize() {
        authorizeAndPrepare(allowEntry = false)
    }

    /** Authorization decisions that invalidate the cached payload and its local authorization. */
    private val denialCodes =
        setOf("AUTHORIZATION_DENIED", "PAYLOAD_UNAVAILABLE", "INVALID_TOKEN", "RECONFIRM_REQUIRED")

    /**
     * Everything that does not need the main thread: native preparation, the local authorization
     * read, the initialization request and the payload decryption/class loading. Runs on the
     * prewarm worker, or on the startup callback itself when prewarming is unavailable.
     */
    private fun authorizeAndPrepare(allowEntry: Boolean): PreparedStart? {
        // Lifecycle callbacks re-run initialization on every resume; a failure the server
        // marked as non-retryable must not be replayed until the user asks for it.
        if (retryBlocked) return null
        ensureSetup()
        if (payloadReadyForRestart) return null
        if (entryAttempted) { refreshIdentityReport(); return null }
        val state = store.load()
        if (state == null || !state.active || !store.hasKey(state.keyAlias)) {
            if (!mainProcess) { log("no active installation; skipping this process"); return null }
            ensureBinding(state)
            return null
        }
        if (allowEntry) {
            // A locally authorized boot never waits for the control plane: the payload is decrypted
            // and prepared from the local record, and the main process starts the online refresh
            // before the entry runs so a broken payload still discovers and downloads an update.
            // Other processes boot offline and never contact the server, so a child process cannot
            // keep renewing the local authorization of a main process whose entry keeps failing.
            val local = tryLocalBoot(state)
            if (local != null) {
                beginPendingRefresh(local)
                return local
            }
        }
        if (!mainProcess) { log("no local authorization; skipping this process"); return null }
        return authorizeOnline(state, allowEntry)
    }

    /**
     * Decrypts the cached payload from the locally stored authorization. Returns null when no
     * usable record exists (missing, expired, mismatched, corrupt); the caller then falls back to
     * the online path, so a broken record can only delay a start, never block one.
     */
    private fun tryLocalBoot(state: InstallationState): PreparedStart? {
        val installationId = state.installationId!!
        val local = localAuthorization.read(installationId) ?: return null
        if (!local.usable(System.currentTimeMillis())) {
            log("local authorization expired; using the online path")
            localAuthorization.clear(installationId)
            return null
        }
        val opened = try {
            cache.open()
        } catch (_: Exception) {
            log("payload cache is unreadable; discarding the local authorization")
            cache.clearAll()
            localAuthorization.clear(installationId)
            return null
        } ?: return null
        var prepared: PreparedPayload? = null
        var key: ByteArray? = null
        try {
            if (!local.covers(installationId, opened.release)) {
                log("local authorization does not cover the cached payload; discarding it")
                localAuthorization.clear(installationId)
                return null
            }
            val identity = localIdentity()
            if (!identity.ready) { log("local authorization skipped: the account is not ready"); return null }
            key = local.contentKeyBytes()
            val loaded = PayloadLoader.prepare(opened.fd, opened.offset, opened.release.descriptor, key,
                File(store.directory, "native"), BootstrapCoordinator::class.java.classLoader!!)
            prepared = loaded
            log("locally authorized payload prepared at ${millisSincePrewarm()} ms")
            return PreparedStart(state, identity, opened.release, local.initializationId, loaded, offline = true)
        } catch (error: Throwable) {
            prepared?.close()
            localAuthorization.clear(installationId)
            log("local authorization could not load the payload; using the online path: " +
                (error.message ?: error.javaClass.simpleName))
            return null
        } finally {
            key?.fill(0)
            opened.close()
        }
    }

    /** Online authorization and payload preparation; only the main process runs this. */
    private fun authorizeOnline(state: InstallationState, allowEntry: Boolean): PreparedStart? {
        val openedCache = try { cache.open() }
        catch (error: Exception) {
            BootstrapLog.e("Bootstrap", "Invalid payload cache; downloading the authorized object", error)
            cache.clearAll()
            null
        }
        val identity = localIdentity()
        if (!identity.ready) {
            openedCache?.close()
            message(uiText(R.string.wekit_auth_account_not_ready))
            return null
        }
        var initializationId: String? = null
        var key: ByteArray? = null
        var selectedCache = openedCache
        var stage = "AUTHORIZE"
        var loaded: PreparedPayload? = null
        try {
            val authorization = authorizeWithServer(state, identity)
            initializationId = authorization.initializationId
            key = authorization.contentKey()
            log("initialization authorized at ${millisSincePrewarm()} ms")
            stage = "RELEASE_VERIFY"
            val release = authorization.release
            if (openedCache == null || openedCache.release.manifestSha256 != release.manifestSha256) {
                openedCache?.close(); selectedCache = null
                val download = {
                    cache.download(api, state, processNonce, authorization.response, release)
                    persistLocalAuthorization(state, authorization, "download")
                    payloadReadyForRestart = true
                    message(uiText(R.string.wekit_auth_payload_updated))
                }
                // Downloads can outlive startup. Activate them only in a new process, whose
                // synchronous startup still owns the hook-installation window.
                if (allowEntry) submit(download) else download()
                return null
            }
            val selected = selectedCache
            require(selected.release.manifestSha256 == release.manifestSha256) { "Cache differs from authorized manifest" }
            if (!allowEntry) {
                payloadReadyForRestart = true
                message(uiText(R.string.wekit_auth_payload_ready))
                return null
            }
            stage = "PAYLOAD_LOAD"
            val preparedPayload = PayloadLoader.prepare(selected.fd, selected.offset, release.descriptor, key,
                File(store.directory, "native"), BootstrapCoordinator::class.java.classLoader!!)
            loaded = preparedPayload
            persistLocalAuthorization(state, authorization, "boot")
            key.fill(0); key = null
            log("payload prepared at ${millisSincePrewarm()} ms")
            return PreparedStart(state, identity, release, authorization.initializationId, preparedPayload,
                offline = false)
        } catch (error: Throwable) {
            loaded?.close()
            if (initializationId != null) {
                report(state, initializationId, "INITIALIZATION", "FAILED", identity, stage, error)
            }
            throw error
        } finally { selectedCache?.close(); key?.fill(0) }
    }

    private fun localIdentity(): CurrentIdentity =
        if (mainProcess) identityReader.read() else CurrentIdentity(null, null, "UNAVAILABLE_IN_PROCESS", true)

    private class ServerAuthorization(
        /** Full initialization response without the content key, for downloads and reporting. */
        val response: JsonObject,
        val initializationId: String,
        /** Base64url content key; decoded only where it is used. */
        val contentKeyText: String,
        val release: VerifiedRelease,
    ) {
        fun contentKey(): ByteArray = WireProtocol.decode(contentKeyText, 32)
    }

    private fun authorizeWithServer(state: InstallationState, identity: CurrentIdentity): ServerAuthorization {
        val requestStartedAt = SystemClock.elapsedRealtime()
        val response = api.signedPost("/v1/initializations", WireJson.body(initializationBody(state, identity)),
            state, processNonce)
        log("initialization request finished in ${SystemClock.elapsedRealtime() - requestStartedAt} ms")
        val initializationId = response.string("initializationId")
        val contentKeyText = response.string("contentKey")
        WireProtocol.decode(contentKeyText, 32)
        val authorization = JsonObject(response.filterKeys { it != "contentKey" })
        val release = ReleaseManifest.verify(authorization.obj("manifest"), config)
        require(authorization.string("payloadId") == release.descriptor.payloadId)
        return ServerAuthorization(authorization, initializationId, contentKeyText, release)
    }

    /** Renews the local authorization after a successful online authorization. */
    private fun persistLocalAuthorization(
        state: InstallationState,
        authorization: ServerAuthorization,
        reason: String,
    ) {
        try {
            val descriptor = authorization.release.descriptor
            val now = System.currentTimeMillis()
            localAuthorization.write(LocalAuthorization(
                installationId = state.installationId!!,
                initializationId = authorization.initializationId,
                payloadId = descriptor.payloadId,
                apkSha256 = descriptor.apkSha256,
                cipherSha256 = descriptor.cipherSha256,
                manifestSha256 = authorization.release.manifestSha256,
                issuedAt = now,
                expiresAt = now + LocalAuthorization.GRACE_MS,
                contentKey = authorization.contentKeyText,
            ))
            log("local authorization renewed ($reason, ${LocalAuthorization.GRACE_MS / 3_600_000} h)")
        } catch (error: Throwable) {
            BootstrapLog.e("Bootstrap", "cannot persist the local authorization", error)
        }
    }

    /**
     * Starts the online refresh for a locally authorized boot before the payload entry runs.
     *
     * The entry never waits for it, but the refresh is what discovers a newer release: keeping it
     * on the worker means a payload that throws during its own entry still gets replaced on the
     * next start. Only the main process runs it; a child process must never renew the local
     * authorization on behalf of a main process whose payload keeps failing to start.
     */
    private fun beginPendingRefresh(preparedStart: PreparedStart) {
        if (!mainProcess || pendingRefresh != null) return
        val future = CompletableFuture<RefreshOutcome>()
        pendingRefresh = future
        worker.execute {
            // Never leave the future incomplete: [finishPendingRefresh] waits for it on this same
            // worker thread, and an unfinished future would stall every later worker job.
            val outcome = try {
                runPendingRefresh(preparedStart)
            } catch (error: Throwable) {
                RefreshOutcome.Unavailable(error)
            }
            future.complete(outcome)
        }
    }

    /**
     * The online half of the refresh: authorize, compare the manifest and download a newer release.
     * It only discovers and renews; user-visible actions and authorization denials are deferred to
     * [finishPendingRefresh] so they happen after the entry attempt, as the post-entry refresh did.
     */
    private fun runPendingRefresh(preparedStart: PreparedStart): RefreshOutcome = try {
        val authorization = authorizeWithServer(preparedStart.state, preparedStart.identity)
        if (authorization.release.manifestSha256 == preparedStart.release.manifestSha256) {
            log("background authorization matches the prepared payload")
            RefreshOutcome.Current(authorization)
        } else {
            log("the server selected another payload; downloading it in the background")
            cache.download(api, preparedStart.state, processNonce, authorization.response, authorization.release)
            persistLocalAuthorization(preparedStart.state, authorization, "update")
            payloadReadyForRestart = true
            RefreshOutcome.Updated
        }
    } catch (error: RemoteApiException) {
        log("background authorization failed: ${error.code}")
        RefreshOutcome.Denied(error)
    } catch (error: Throwable) {
        log("background authorization failed: ${error.message ?: error.javaClass.simpleName}")
        RefreshOutcome.Unavailable(error)
    }

    /**
     * Acts on the pre-entry refresh after the entry has been attempted, or after it failed with
     * [error]. The result either renews the local authorization and reports the successful entry,
     * reports the failure against the same initialization, or hands a denial to [handleDenial] now
     * that the entry attempt is over.
     *
     * Runs on the worker. [beginPendingRefresh] always queues its job first, so the future is done.
     */
    private fun finishPendingRefresh(preparedStart: PreparedStart, error: Throwable? = null) {
        val future = pendingRefresh ?: return
        worker.execute {
            val outcome = try {
                future.get()
            } catch (failure: Throwable) {
                log("background authorization result unavailable: ${failure.message ?: failure.javaClass.simpleName}")
                return@execute
            }
            try {
                when (outcome) {
                    is RefreshOutcome.Current -> if (error == null) {
                        persistLocalAuthorization(preparedStart.state, outcome.authorization, "refresh")
                        successfulInitializationId = outcome.authorization.initializationId
                        successfulState = preparedStart.state
                        report(preparedStart.state, outcome.authorization.initializationId, "ENTRY_RETURNED", "LOADED",
                            preparedStart.identity)
                        log("local authorization refreshed in the background")
                    } else {
                        // An offline boot has no online initialization of its own, so the pre-entry
                        // authorization is the only way the server learns about this failure.
                        report(preparedStart.state, outcome.authorization.initializationId, "INITIALIZATION", "FAILED",
                            preparedStart.identity, "PAYLOAD_ENTRY", error)
                        log("payload entry failure reported against the pre-entry initialization")
                    }
                    RefreshOutcome.Updated -> message(uiText(R.string.wekit_auth_payload_updated))
                    is RefreshOutcome.Denied -> handleDenial(outcome.error)
                    is RefreshOutcome.Unavailable -> Unit
                }
            } catch (failure: Throwable) {
                log("background refresh was not applied: ${failure.message ?: failure.javaClass.simpleName}")
            }
        }
    }

    /**
     * Handles a terminal authorization denial: the cached payload and its local authorization are
     * removed immediately, and a running payload is stopped by restarting the host afterwards.
     * Returns false when the error is not a denial.
     */
    private fun handleDenial(error: RemoteApiException): Boolean {
        if (error.code !in denialCodes) return false
        retryBlocked = true
        val wiped = wipeLocalAuthorization()
        log("authorization denied by ${error.code}; cached payload wiped=$wiped")
        val text = uiText(R.string.wekit_auth_load_failure, error.message ?: error.code)
        if (!wiped) {
            // Nothing was cached here (already handled, or this process never had a payload):
            // use the ordinary recovery path instead of restarting again.
            if (error.code == "RECONFIRM_REQUIRED") {
                val candidate = store.candidate(store.load())
                pendingBinding = null
                submit { ensureBinding(candidate) }
            } else {
                message(text)
            }
            return true
        }
        if (entryAttempted) {
            // Deleting files cannot unload the payload that is already running in this process.
            mainHandler.post { Toast.makeText(application, text, Toast.LENGTH_LONG).show() }
            if (!restartScheduled) {
                restartScheduled = true
                mainHandler.postDelayed(::restartHost, 1500)
            }
        } else {
            message(text)
        }
        return true
    }

    /** Removes the cached payload, the extracted native payload and the local authorization. */
    private fun wipeLocalAuthorization(): Boolean {
        var wiped = false
        try { if (localAuthorization.clearAll()) wiped = true }
        catch (error: Throwable) { BootstrapLog.e("Bootstrap", "cannot clear the local authorization", error) }
        try { if (cache.clearAll()) wiped = true }
        catch (error: Throwable) { BootstrapLog.e("Bootstrap", "cannot clear the payload cache", error) }
        // A payload that is still running keeps resolving JNI libraries through its own extracted
        // directory, so only the directories of dead processes may go here. A wholesale delete makes
        // the next lazy native load in a live payload process crash with UnsatisfiedLinkError.
        try {
            val native = File(store.directory, "native")
            if (PayloadLoader.purgeDeadProcessNativeDirectories(native)) wiped = true
        } catch (error: Throwable) {
            BootstrapLog.e("Bootstrap", "cannot clear the extracted payload", error)
        }
        return wiped
    }

    /** Runs on the main thread; the host's startup callback must not return before this completes. */
    private fun startPreparedEntry(preparedStart: PreparedStart) {
        check(Looper.myLooper() == Looper.getMainLooper())
        val loaded = preparedStart.loaded
        prepared = loaded
        try {
            val environment = PayloadEnvironment(application, hostLoader, hookBridge, loaderService,
                injectionMode, telegramRootAccess, File(loaderService.mainModulePath), loaded.apk,
                preparedStart.release.descriptor.apkSha256, loaded.nativeDirectory,
                if (mainProcess) authorizationControls else null,
                watermarkToken = preparedStart.initializationId)
            // Keep all inputs alive even if partially initialized business hooks later throw.
            entryAttempted = true
            val entry = loaded.classLoader.loadClass(preparedStart.release.descriptor.entryClass)
                .getDeclaredConstructor().newInstance() as PayloadEntry
            // start() loads the JNI libraries; do not leave the plaintext copies on disk, even if it throws.
            try { entry.start(environment) } finally { loaded.scrubNativeLibraries() }
            log("payload entry returned synchronously on the main thread at ${millisSincePrewarm()} ms")
            loadedWxId = preparedStart.identity.wxId
            successfulInitializationId = if (preparedStart.offline) null else preparedStart.initializationId
            successfulState = preparedStart.state
            reportedAlias = preparedStart.identity.alias
            // Messages from an earlier attempt are stale now; the refresh below may add its own.
            pendingMessages.clear()
            if (preparedStart.offline) {
                // The report needs an initialization row owned by this process; the pre-entry
                // refresh provides it and reports ENTRY_RETURNED now that the entry returned.
                finishPendingRefresh(preparedStart)
            } else {
                // Audit-only report: never extend the host's startup callback for this round trip.
                reportAsync(preparedStart.state, preparedStart.initializationId, "ENTRY_RETURNED", "LOADED",
                    preparedStart.identity)
            }
            mainHandler.post { bindingDialog?.dismiss() }
        } catch (error: Throwable) {
            if (!entryAttempted) { prepared?.close(); prepared = null }
            if (!preparedStart.offline) {
                report(preparedStart.state, preparedStart.initializationId, "INITIALIZATION", "FAILED",
                    preparedStart.identity, "PAYLOAD_ENTRY", error)
            }
            throw error
        }
    }

    /** The report is audit bookkeeping; startup must not wait for its round trip. */
    private fun reportAsync(
        state: InstallationState,
        initializationId: String,
        phase: String,
        result: String,
        identity: CurrentIdentity,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        worker.execute {
            report(state, initializationId, phase, result, identity)
            log("background report finished after ${SystemClock.elapsedRealtime() - startedAt} ms " +
                "(kept out of the startup window)")
        }
    }

    private fun refreshIdentityReport() {
        if (!mainProcess || loadedWxId == null || warnedAccountSwitch) return
        val now = identityReader.read()
        if (now.ready && now.wxId != loadedWxId) {
            warnedAccountSwitch = true
            message(uiText(R.string.wekit_auth_account_changed))
        } else if (now.ready && now.alias != null && now.alias != reportedAlias && successfulInitializationId != null) {
            if (report(successfulState!!, successfulInitializationId!!, "IDENTITY_READ", "IDENTITY_UPDATE", now))
                reportedAlias = now.alias
        }
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
                    store.createKey(state)
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
                retryBlocked = false
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

    private fun report(
        state: InstallationState,
        initializationId: String,
        phase: String,
        result: String,
        identity: CurrentIdentity,
        failureStage: String? = null,
        error: Throwable? = null,
    ): Boolean {
        try {
            val body = WireJson.body(InitializationReport(WireProtocol.requestId(), state.installationId!!,
                processNonce, initializationId, phase, result, identity.wxId, identity.alias, identity.readState,
                errorCode = error?.let { diagnosticCode(failureStage ?: phase, it) },
                errorMessage = error?.let(::diagnosticMessage)))
            api.signedPost("/v1/initializations/report", body, state, processNonce)
            return true
        } catch (error: Exception) { BootstrapLog.e("Bootstrap", "Initialization result report failed", error); return false }
    }

    private fun diagnosticCode(stage: String, error: Throwable): String {
        val messages = throwableChain(error).joinToString(" ") { it.message.orEmpty() }
        val failure = when {
            error is RemoteApiException -> "REMOTE_${sanitizeCode(error.code)}"
            error is ErrnoException -> errnoName(error.errno)
            messages.contains("EACCES") -> "EACCES"
            messages.contains("EPERM") || messages.contains("Permission denied") -> "EPERM"
            error is FileNotFoundException -> "FILE_NOT_FOUND"
            error is ZipException -> "ZIP_INVALID"
            error is SecurityException -> "SECURITY"
            error is IOException -> "IO"
            else -> sanitizeCode(error.javaClass.simpleName.ifEmpty { "ERROR" }).uppercase()
        }
        return "${sanitizeCode(stage).uppercase()}_$failure".take(96)
    }

    private fun diagnosticMessage(error: Throwable): String =
        throwableChain(error).joinToString(" | ") { item ->
            val text = item.message.orEmpty()
                .replace(Regex("[\\u0000-\\u001f\\u007f]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
            // ART reports an overflow only as `stack size <n>KB`; the report needs the loop site.
            val detail =
                if (item is StackOverflowError) {
                    " (${BootstrapFailure.describeOverflow(item)})"
                } else ""
            "${item.javaClass.name}: ${text.ifEmpty { "<no message>" }}$detail"
        }.take(500)

    private fun throwableChain(error: Throwable): List<Throwable> {
        val result = ArrayList<Throwable>()
        val pending = java.util.ArrayDeque<Throwable>().apply { add(error) }
        while (pending.isNotEmpty() && result.size < 6) {
            val item = pending.removeFirst()
            result += item
            item.cause?.takeUnless { cause -> result.any { it === cause } }?.let(pending::addLast)
            item.suppressed.take(2).forEach { suppressed ->
                if (result.none { it === suppressed }) pending.addLast(suppressed)
            }
        }
        return result
    }

    private fun errnoName(errno: Int): String = when (errno) {
        OsConstants.EACCES -> "EACCES"
        OsConstants.EPERM -> "EPERM"
        OsConstants.ENOENT -> "ENOENT"
        OsConstants.EIO -> "EIO"
        OsConstants.ENOSPC -> "ENOSPC"
        OsConstants.EMFILE -> "EMFILE"
        OsConstants.ENFILE -> "ENFILE"
        OsConstants.EBADF -> "EBADF"
        OsConstants.ELOOP -> "ELOOP"
        else -> "ERRNO_$errno"
    }

    private fun sanitizeCode(value: String): String =
        value.replace(Regex("[^A-Za-z0-9_.-]+"), "_").trim('_', '.', '-').ifEmpty { "ERROR" }.take(80)

    private fun failed(error: Throwable) {
        BootstrapLog.e("Bootstrap", "Remote payload initialization failed", error)
        if (error is StackOverflowError) {
            BootstrapLog.w("Bootstrap", BootstrapFailure.describeOverflow(error))
        }
        if (!mainProcess) return
        if (error is RemoteApiException) {
            if (!error.retryable) retryBlocked = true
            if (handleDenial(error)) return
            if (error.code in setOf("REGISTRATION_EXPIRED", "CREDENTIAL_REVOKED", "EPOCH_MISMATCH")) {
                store.candidate(store.load())
                pendingBinding = null
            }
        }
        message(
            BootstrapFailure.loadFailure(application, File(loaderService.mainModulePath), error),
            restartRequired = entryAttempted,
        )
    }

    private fun uiText(id: Int, vararg args: Any): String =
        BootstrapResources.getString(application, File(loaderService.mainModulePath), id, *args)

    /** Queues [text] for the main thread; a message waits for the currently shown dialog to close. */
    private fun message(text: String, restartRequired: Boolean = false) {
        if (!mainProcess) return
        mainHandler.post {
            pendingMessages.addLast(QueuedMessage(text, restartRequired))
            showMessage()
        }
    }
    private fun showMessage() {
        // One dialog at a time: a failure and the "payload updated" follow-up must not overlap.
        // The reference is cleared by onDismiss (which is posted), so keep it as the gate too.
        if (messageDialog != null) return
        val owner = activity.get() ?: return
        if (owner.isFinishing || owner.isDestroyed || bindingDialog?.isShowing == true) return
        val message = pendingMessages.removeFirstOrNull() ?: return
        val buttons = buildList {
            if (payloadReadyForRestart) {
                add(BootstrapDialogAction(uiText(R.string.wekit_auth_restart_wechat)) { restartHost() })
            } else {
                add(BootstrapDialogAction(uiText(R.string.wekit_auth_close)))
            }
            if (!entryAttempted && !payloadReadyForRestart) {
                add(BootstrapDialogAction(uiText(R.string.wekit_auth_more)) { authorizationControls.show(owner) })
                add(BootstrapDialogAction(uiText(R.string.wekit_auth_retry)) { retryBlocked = false; submit { initialize() } })
            }
        }
        // The identity check keeps a late onDismiss of a destroyed activity's dialog from clearing
        // the reference of a dialog that was already shown for the next activity.
        var shown: ComponentDialog? = null
        shown = BootstrapDialogs.create(owner, File(loaderService.mainModulePath), "WeKit",
            if (message.restartRequired) uiText(R.string.wekit_auth_restart_after_entry, message.text) else message.text,
            buttons,
            onDismiss = {
                if (messageDialog === shown) messageDialog = null
                mainHandler.post { showMessage() }
            })
        messageDialog = shown
        shown!!.show()
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
                if (!entryAttempted) add(BootstrapDialogAction(uiText(R.string.wekit_auth_download_again)) { retryBlocked = false; submit { ensureSetup(); cache.clear(); initialize() } })
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
                    retryBlocked = false
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
            if (activity.get() === owner) {
                messageDialog?.dismiss(); messageDialog = null
                bindingDialog?.dismiss(); bindingDialog = null; activity.clear()
            }
        }
        override fun onActivityCreated(owner: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(owner: Activity) = Unit
        override fun onActivityStopped(owner: Activity) = Unit
        override fun onActivitySaveInstanceState(owner: Activity, state: Bundle) = Unit
    }
}
