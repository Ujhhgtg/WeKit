package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.ListItem
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun readReceiptNetworkFailureCategory(failure: Throwable): String = when (failure) {
    is SocketTimeoutException -> "timeout"
    is UnknownHostException -> "dns"
    is SSLException -> "tls"
    is ConnectException -> "connect"
    is IOException -> "io"
    else -> "response"
}

private class ReadReceiptsLocalFailure(
    @StringRes val messageRes: Int,
    vararg formatArgs: Any,
) : IllegalStateException() {
    val formatArgs: Array<out Any> = formatArgs
}

private sealed interface ReadReceiptRuntimeError {
    fun message(context: Context): String

    class Resource(
        @StringRes private val id: Int,
        vararg private val formatArgs: Any,
    ) : ReadReceiptRuntimeError {
        override fun message(context: Context): String = context.localizedChatString(id, *formatArgs)
    }

    companion object {
        fun from(failure: Throwable): ReadReceiptRuntimeError = when (failure) {
            is ReadReceiptsTunnelException -> Resource(failure.errorCode.messageRes)
            is BrowserLoginException -> Resource(failure.errorCode.messageRes)
            is ReadReceiptsLocalFailure -> Resource(
                failure.messageRes,
                *failure.formatArgs,
            )

            else -> Resource(R.string.read_receipts_unknown_error)
        }
    }
}

private sealed interface ReadReceiptsUiText {
    @Composable
    fun resolve(): String

    fun resolve(context: Context): String

    class Resource(
        @StringRes private val id: Int,
        vararg private val formatArgs: Any,
    ) : ReadReceiptsUiText {
        @Composable
        override fun resolve(): String = stringResource(id, *formatArgs)

        override fun resolve(context: Context): String =
            context.localizedChatString(id, *formatArgs)
    }

    companion object {
        fun from(
            failure: Throwable,
            @StringRes fallbackRes: Int,
        ): ReadReceiptsUiText = when (failure) {
            is ReadReceiptsTunnelException -> Resource(failure.errorCode.messageRes)
            is BrowserLoginException -> Resource(failure.errorCode.messageRes)
            is ReadReceiptsLocalFailure -> Resource(
                failure.messageRes,
                *failure.formatArgs,
            )

            else -> Resource(fallbackRes)
        }
    }
}

private val ReadReceiptsRuntimeState.labelRes: Int
    @StringRes get() = when (this) {
        ReadReceiptsRuntimeState.STOPPED -> R.string.read_receipts_state_stopped
        ReadReceiptsRuntimeState.STARTING -> R.string.read_receipts_state_starting
        ReadReceiptsRuntimeState.RUNNING -> R.string.read_receipts_state_running
        ReadReceiptsRuntimeState.STOPPING -> R.string.read_receipts_state_stopping
        ReadReceiptsRuntimeState.FAILED -> R.string.read_receipts_state_failed
    }

private val ReadReceiptsTunnelState.labelRes: Int
    @StringRes get() = when (this) {
        ReadReceiptsTunnelState.STOPPED -> R.string.read_receipts_state_stopped
        ReadReceiptsTunnelState.STARTING -> R.string.read_receipts_state_starting
        ReadReceiptsTunnelState.CONNECTED -> R.string.read_receipts_tunnel_state_connected
        ReadReceiptsTunnelState.RECONNECTING -> R.string.read_receipts_tunnel_state_reconnecting
        ReadReceiptsTunnelState.NEEDS_USER_ACTION -> R.string.read_receipts_state_needs_user_action
        ReadReceiptsTunnelState.FAILED -> R.string.read_receipts_state_failed
        ReadReceiptsTunnelState.STOPPING -> R.string.read_receipts_state_stopping
    }

private val ReadReceiptsTunnelState.browserLoginLabelRes: Int
    @StringRes get() = when (this) {
        ReadReceiptsTunnelState.STOPPED -> R.string.read_receipts_browser_login_state_signed_out
        ReadReceiptsTunnelState.STARTING -> R.string.read_receipts_browser_login_state_waiting
        ReadReceiptsTunnelState.CONNECTED -> R.string.read_receipts_browser_login_state_authorized
        ReadReceiptsTunnelState.FAILED -> R.string.read_receipts_browser_login_state_failed
        ReadReceiptsTunnelState.RECONNECTING -> R.string.read_receipts_browser_login_state_restoring
        ReadReceiptsTunnelState.NEEDS_USER_ACTION -> R.string.read_receipts_state_needs_user_action
        ReadReceiptsTunnelState.STOPPING -> R.string.read_receipts_browser_login_state_cancelling
    }

/** Runs one connection owner's terminal policy exactly once. */
internal class ConnectionTransactionOwner(
    private val releaseOwnership: () -> Unit,
    private val finishOwnership: (() -> Boolean)? = null,
) {
    private var finished = false

    fun <T> finish(
        terminal: OriginRequestTerminal<T>,
        onCompletedSuccess: (T) -> Unit,
        onCompletedFailure: (Throwable) -> Unit,
        onSuperseded: () -> Unit,
    ): Boolean {
        synchronized(this) {
            if (finished) return false
            finished = true
        }
        val wasCurrent = finishOwnership?.invoke() ?: run {
            releaseOwnership()
            true
        }
        val effectiveTerminal = if (wasCurrent) terminal else OriginRequestTerminal.Superseded
        when (effectiveTerminal) {
            is OriginRequestTerminal.Completed -> effectiveTerminal.result.fold(
                onSuccess = onCompletedSuccess,
                onFailure = onCompletedFailure,
            )

            OriginRequestTerminal.Superseded -> onSuperseded()
        }
        return true
    }
}

/** Keeps connection UI ownership bound to the latest transaction. */
internal class ConnectionTransactionOwnership(
    private val onActiveChanged: (Boolean) -> Unit,
) {
    private var nextOwnerId = 0L
    private var currentOwnerId: Long? = null

    @Synchronized
    fun acquire(): ConnectionTransactionOwner {
        val ownerId = ++nextOwnerId
        if (currentOwnerId == null) onActiveChanged(true)
        currentOwnerId = ownerId
        return ConnectionTransactionOwner(
            releaseOwnership = {},
            finishOwnership = { releaseIfCurrent(ownerId) },
        )
    }

    @Synchronized
    private fun releaseIfCurrent(ownerId: Long): Boolean {
        if (currentOwnerId != ownerId) return false
        currentOwnerId = null
        onActiveChanged(false)
        return true
    }
}

/** Owns configuration persistence across delayed connection and metadata continuations. */
internal class ConfigurationTransactionOwnership {
    private var nextOwnerId = 0L
    private var currentOwnerId: Long? = null

    @Synchronized
    fun acquire(): ConfigurationTransactionOwner {
        val ownerId = ++nextOwnerId
        currentOwnerId = ownerId
        return ConfigurationTransactionOwner(this, ownerId)
    }

    @Synchronized
    fun supersede() {
        currentOwnerId = null
    }

    @Synchronized
    internal fun isCurrent(ownerId: Long): Boolean = currentOwnerId == ownerId

    @Synchronized
    internal fun runIfCurrent(ownerId: Long, action: () -> Unit): Boolean {
        if (currentOwnerId != ownerId) return false
        action()
        return true
    }

    @Synchronized
    internal fun finishIfCurrent(ownerId: Long, action: () -> Unit): Boolean {
        if (currentOwnerId != ownerId) return false
        action()
        currentOwnerId = null
        return true
    }
}

internal class ConfigurationTransactionOwner(
    private val ownership: ConfigurationTransactionOwnership,
    private val ownerId: Long,
) {
    fun isCurrent(): Boolean = ownership.isCurrent(ownerId)

    fun runIfCurrent(action: () -> Unit): Boolean = ownership.runIfCurrent(ownerId, action)

    fun finishIfCurrent(action: () -> Unit = {}): Boolean =
        ownership.finishIfCurrent(ownerId, action)
}

/** Restores a notification-rejected current transaction, or only propagates replacement. */
internal fun finishNotificationRejectionRestore(
    stopTerminal: OriginRequestTerminal<Unit>,
    originalFailure: Throwable,
    savePrevious: () -> Unit,
    restartPrevious: () -> Unit,
    onFinished: (OriginRequestTerminal<Unit>) -> Unit,
) {
    when (stopTerminal) {
        is OriginRequestTerminal.Completed -> {
            savePrevious()
            restartPrevious()
            onFinished(OriginRequestTerminal.Completed(Result.failure(originalFailure)))
        }

        OriginRequestTerminal.Superseded -> onFinished(OriginRequestTerminal.Superseded)
    }
}

internal fun finishBuiltInStackStop(
    tunnelResult: Result<Unit>,
    stopOrigin: (((Long, OriginRequestTerminal<Unit>) -> Unit) -> Unit),
    onFinished: (Long, OriginRequestTerminal<Unit>) -> Unit,
) {
    stopOrigin { generation, originTerminal ->
        val terminal = when (originTerminal) {
            is OriginRequestTerminal.Completed -> OriginRequestTerminal.Completed(
                tunnelResult.fold(
                    onSuccess = { originTerminal.result },
                    onFailure = { Result.failure(it) },
                ),
            )

            OriginRequestTerminal.Superseded -> OriginRequestTerminal.Superseded
        }
        onFinished(generation, terminal)
    }
}

internal fun configurationRollbackTerminal(
    originalFailure: Throwable,
    restartTerminal: OriginRequestTerminal<Unit>,
): OriginRequestTerminal<Unit> = when (restartTerminal) {
    is OriginRequestTerminal.Completed -> OriginRequestTerminal.Completed(
        if (restartTerminal.result.isSuccess) {
            Result.failure(originalFailure)
        } else {
            Result.failure(
                ReadReceiptsLocalFailure(
                    R.string.read_receipts_configuration_rollback_failed,
                ),
            )
        },
    )

    OriginRequestTerminal.Superseded -> OriginRequestTerminal.Superseded
}

/** Coalesces a stack stop without collapsing [OriginRequestTerminal.Superseded] into failure. */
internal class CoalescedOriginCallbacks<T> {
    private var callbacks: MutableList<(OriginRequestTerminal<T>) -> Unit>? = null

    @Synchronized
    fun register(callback: ((OriginRequestTerminal<T>) -> Unit)?): Boolean {
        val current = callbacks
        if (current != null) {
            if (callback != null) current += callback
            return false
        }
        callbacks = mutableListOf<(OriginRequestTerminal<T>) -> Unit>().apply {
            if (callback != null) add(callback)
        }
        return true
    }

    fun complete(
        terminal: OriginRequestTerminal<T>,
        isCurrent: () -> Boolean = { true },
    ): Int {
        val completed = synchronized(this) {
            val current = callbacks ?: return 0
            callbacks = null
            current.toList()
        }
        completed.asReversed().forEachIndexed { index, callback ->
            callback(
                if (index == 0 && isCurrent()) terminal else OriginRequestTerminal.Superseded,
            )
        }
        return completed.size
    }
}

@Feature(
    id = "已读追踪",
    nameRes = "feature_read_receipts_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_read_receipts_description",
)
object ReadReceipts : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener,
    WeChatMessageViewApi.IMessageViewLifecycleListener {

    private const val TAG = "ReadReceipts"

    // ── Preferences ─────────────────────────────────────────────────────────
    private var serializedConfiguration by prefOption("read_receipts_configuration", "")
    private var lastBuiltInPort by prefOption("read_receipts_last_built_in_port", 0)
    private var lastBuiltInState by prefOption(
        "read_receipts_last_built_in_state",
        ReadReceiptsRuntimeState.STOPPED.name,
    )
    private var serializedRecords by prefOption("read_receipts_records", emptySet())

    private val configurationLock = Any()

    @Volatile
    private var loadedConfiguration: ReadReceiptsConfiguration? = null

    private const val BUILT_IN_RECORD_ENDPOINT = "builtin://local"
    private const val RECORD_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1000
    private const val MAX_POLL_WORKERS = 4
    private const val MAX_FAILURE_BACKOFF_MILLIS = 5L * 60 * 1000
    private const val MAX_WX_ID_BYTES = 128
    private const val MAX_CONTENT_BYTES = 16 * 1024
    private const val MAX_REGISTRATION_BODY_BYTES = 20 * 1024
    private const val ORIGIN_STOP_TIMEOUT_MILLIS = 10_000L
    private const val TUNNEL_CANDIDATE_VERIFY_TIMEOUT_MILLIS = 30_000L
    private const val BROWSER_METADATA_RECONCILE_ATTEMPTS = 50
    private const val BROWSER_METADATA_RECONCILE_DELAY_MILLIS = 100L

    private data class ResolvedBackend(
        val backend: ReadReceiptBackend,
        val requestEndpoint: String,
        val pixelEndpoint: String,
        val recordEndpoint: String,
    )

    @Volatile
    private var runtimeError: ReadReceiptRuntimeError? = null

    private val originController = NativeReadReceiptsServerController()
    private val originScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val originGeneration = AtomicLong()
    private val originLifecycleMutex = Mutex()
    private val originRequestBoundary = OriginRequestBoundary()
    private val builtInStopCallbacks = CoalescedOriginCallbacks<Unit>()
    private val configurationTransactionOwnership = ConfigurationTransactionOwnership()
    private val settingsDialogGeneration = AtomicLong()

    private data class OriginRequest(
        val generation: Long,
        val port: Int?,
        val forceRestart: Boolean,
    )

    private fun configuration(): ReadReceiptsConfiguration {
        loadedConfiguration?.let { return it }
        return synchronized(configurationLock) {
            loadedConfiguration?.let { return@synchronized it }
            val serialized = serializedConfiguration
            val persisted = serialized.takeIf(String::isNotBlank)
                ?.let(ReadReceiptsConfigurationCodec::decode)
            val value = when {
                persisted != null -> persisted
                serialized.isBlank() -> migrateLegacyConfiguration()
                else -> ReadReceiptsConfiguration()
            }
            if (persisted == null) {
                serializedConfiguration = ReadReceiptsConfigurationCodec.encode(value)
            }
            loadedConfiguration = value
            value
        }
    }

    private fun saveConfiguration(value: ReadReceiptsConfiguration) {
        configurationTransactionOwnership.supersede()
        persistConfiguration(value)
    }

    private fun persistConfiguration(value: ReadReceiptsConfiguration) {
        val encoded = ReadReceiptsConfigurationCodec.encode(value)
        val canonical = ReadReceiptsConfigurationCodec.decode(encoded)!!
        synchronized(configurationLock) {
            serializedConfiguration = encoded
            loadedConfiguration = canonical
        }
    }

    private fun migrateLegacyConfiguration(): ReadReceiptsConfiguration {
        val mode = WePrefs.getStringOrDef(
            "read_receipts_backend_mode",
            ReadReceiptsServerMode.THIRD_PARTY.name,
        ).let { name ->
            ReadReceiptsServerMode.entries.firstOrNull { it.name == name }
                ?: ReadReceiptsServerMode.THIRD_PARTY
        }
        val legacyPort = WePrefs.getIntOrDef("read_receipts_built_in_port", 0)
        val automaticPort = WePrefs.getBoolOrDef(
            "read_receipts_automatic_port",
            true,
        )
        return ReadReceiptsConfiguration(
            mode = mode,
            thirdPartyUrl = WePrefs.getStringOrDef("read_receipts_third_party_url", ""),
            prefix = WePrefs.getStringOrDef("read_receipts_prefix", "#"),
            pollIntervalSecs = WePrefs.getIntOrDef("read_receipts_poll_interval", 5)
                .takeIf { it > 0 } ?: 5,
            automaticPort = automaticPort,
            builtInPort = legacyPort.takeIf { it in 1..65535 } ?: 3000,
            automaticLifecycle = WePrefs.getBoolOrDef(
                "read_receipts_automatic_lifecycle",
                true,
            ),
            tunnelMode = WePrefs.getStringOrDef("read_receipts_tunnel_mode", "QUICK")
                .takeIf(String::isNotBlank)
                ?: "QUICK",
            hostname = WePrefs.getStringOrDef("read_receipts_hostname", ""),
            selectedAccountId = WePrefs.getStringOrDef(
                "read_receipts_selected_account_id",
                "",
            ),
            selectedAccountName = WePrefs.getStringOrDef(
                "read_receipts_selected_account_name",
                "",
            ),
            selectedTunnelId = WePrefs.getStringOrDef(
                "read_receipts_selected_tunnel_id",
                "",
            ),
            selectedTunnelName = WePrefs.getStringOrDef(
                "read_receipts_selected_tunnel_name",
                "",
            ),
        )
    }

    // ── HTTP ────────────────────────────────────────────────────────────────
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /**
     * SHA-256 of `wxId + 0x00 + content + 0x00 + createTime`, lowercase hex. Must match the
     * server's `compute_msg_id`. Folding in [createTime] (epoch millis, decimal string) keeps two
     * identical-text messages from colliding onto the same id.
     */
    private fun computeId(wxId: String, content: String, createTime: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(wxId.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(content.toByteArray(Charsets.UTF_8))
        md.update(0)
        md.update(createTime.toString().toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private val registrationCalls = ConcurrentHashMap.newKeySet<Call>()

    /** Registers plaintext before the intercepted send is emitted. The underlying call is cancellable. */
    private suspend fun registerMessage(
        endpoint: String,
        wxId: String,
        content: String,
        createTime: Long,
    ): ReadReceiptRuntimeError? {
        val bodyJson = buildJsonObject {
            put("wxId", wxId)
            put("content", content)
            put("createTime", createTime)
        }.toString()
        if (bodyJson.toByteArray(Charsets.UTF_8).size > MAX_REGISTRATION_BODY_BYTES) {
            return ReadReceiptRuntimeError.Resource(
                R.string.read_receipts_registration_request_too_large,
            )
        }
        val body = bodyJson.toRequestBody(jsonMediaType)
        val request = Request.Builder().url("$endpoint/register").post(body).build()
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            registrationCalls += call
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    registrationCalls -= call
                    WeLogger.w(TAG, "register request failed (${readReceiptNetworkFailureCategory(e)})")
                    continuation.resumeIfActive(
                        ReadReceiptRuntimeError.Resource(R.string.read_receipts_registration_failed),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    registrationCalls -= call
                    response.use {
                        if (it.isSuccessful) {
                            continuation.resumeIfActive(null)
                        } else {
                            WeLogger.w(TAG, "register failed: HTTP ${it.code}")
                            continuation.resumeIfActive(
                                ReadReceiptRuntimeError.Resource(
                                    R.string.read_receipts_registration_http_failed,
                                    it.code,
                                ),
                            )
                        }
                    }
                }
            })
        }
    }

    private fun <T> kotlinx.coroutines.CancellableContinuation<T>.resumeIfActive(value: T) {
        if (isActive) resume(value)
    }

    private suspend fun executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response) { _, cancelledResponse, _ ->
                            cancelledResponse.close()
                        }
                    } else {
                        response.close()
                    }
                }
            })
        }

    /** Queries the distinct-IP read count for a persisted record. Returns null on any failure. */
    private suspend fun fetchCount(record: ReadReceiptRecord): Int? {
        val endpoint = pollingEndpoint(record) ?: return null
        val request = runCatching {
            Request.Builder()
                .url("$endpoint/count?wxId=${record.wxId}&id=${record.id}")
                .get()
                .build()
        }.getOrElse {
            WeLogger.w(TAG, "invalid count endpoint (response)")
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) {
                        WeLogger.w(
                            TAG,
                            "count request failed (${readReceiptNetworkFailureCategory(e)})",
                        )
                    }
                    continuation.resumeIfActive(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            WeLogger.w(TAG, "count failed: HTTP ${it.code}")
                            continuation.resumeIfActive(null)
                            return
                        }
                        val count = runCatching {
                            DefaultJson.parseToJsonElement(it.body.string())
                                .jsonObject["count"]
                                ?.jsonPrimitive
                                ?.content
                                ?.toIntOrNull()
                        }.getOrNull()
                        continuation.resumeIfActive(count)
                    }
                }
            })
        }
    }

    // ── Live "已读 x 人" state ─────────────────────────────────────────────────

    private data class RecordKey(
        val id: String,
        val wxId: String,
        val backend: ReadReceiptBackend,
        val endpoint: String,
    )

    private data class ActiveReceiptView(
        val view: TextView,
        val record: ReadReceiptRecord,
        val generation: Long,
    )

    private data class ActiveBinding(
        val message: MessageInfo,
        val receiptView: ActiveReceiptView,
    )

    private data class PollBackoff(
        val failures: Int,
        val nextAttemptAtMillis: Long,
    )

    private val recordLock = Any()
    private var records: Set<ReadReceiptRecord> = emptySet()

    /** Last successful count, isolated by historical backend identity. */
    private val counts = ConcurrentHashMap<RecordKey, Int>()

    /** Attached message-root views and the exact tracked generation currently occupying each row. */
    private val activeViews = Collections.synchronizedMap(WeakHashMap<View, ActiveBinding>())
    private val backoffs = ConcurrentHashMap<RecordKey, PollBackoff>()
    private val pollWake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var featureScope: CoroutineScope? = null

    @Volatile
    private var pollJob: Job? = null

    private fun ReadReceiptRecord.key() = RecordKey(id, wxId, backend, endpoint)

    private fun loadRecords() {
        val decoded = buildList {
            for (value in serializedRecords) {
                val record = ReadReceiptRecordCodec.decode(value)
                if (record == null) {
                    WeLogger.w(TAG, "discarding malformed persisted read-receipt record")
                } else {
                    add(record)
                }
            }
        }
        val pruned = ReadReceiptRecordCodec.prune(
            decoded,
            System.currentTimeMillis(),
            RECORD_RETENTION_MILLIS,
        )
        synchronized(recordLock) {
            records = pruned
            serializedRecords = pruned.mapTo(linkedSetOf(), ReadReceiptRecordCodec::encode)
        }
    }

    private fun findRecord(wxId: String, id: String): ReadReceiptRecord? = synchronized(recordLock) {
        records.asSequence()
            .filter { it.wxId == wxId && it.id == id }
            .maxByOrNull(ReadReceiptRecord::createdAtMillis)
    }

    private fun insertRecord(record: ReadReceiptRecord) {
        synchronized(recordLock) {
            records = ReadReceiptRecordCodec.prune(
                records + record,
                System.currentTimeMillis(),
                RECORD_RETENTION_MILLIS,
            )
            serializedRecords = records.mapTo(linkedSetOf(), ReadReceiptRecordCodec::encode)
        }
    }

    private fun requestedBuiltInPort(value: ReadReceiptsConfiguration = configuration()): Int =
        if (value.automaticPort) 0 else value.builtInPort

    private fun normalizedHttpsEndpoint(value: String): String? {
        return normalizeThirdPartyReadReceiptEndpoint(value)
    }

    private fun verifiedTunnelEndpoint(): String? =
        ReadReceiptsTunnelController.verifiedEndpoint()

    private fun resolveBackend(): Pair<ResolvedBackend?, ReadReceiptRuntimeError?> {
        val configuration = configuration()
        return when (configuration.mode) {
            ReadReceiptsServerMode.THIRD_PARTY -> {
                val endpoint = normalizedHttpsEndpoint(configuration.thirdPartyUrl)
                    ?: return null to ReadReceiptRuntimeError.Resource(
                        R.string.chat_read_receipts_server_missing,
                    )
                ResolvedBackend(
                    backend = ReadReceiptBackend.THIRD_PARTY,
                    requestEndpoint = endpoint,
                    pixelEndpoint = endpoint,
                    recordEndpoint = endpoint,
                ) to null
            }

            ReadReceiptsServerMode.BUILT_IN -> {
                val origin = originController.snapshot()
                if (origin.state != ReadReceiptsRuntimeState.RUNNING || origin.port == null) {
                    return null to ReadReceiptRuntimeError.Resource(
                        R.string.read_receipts_built_in_not_running,
                    )
                }
                val publicEndpoint = verifiedTunnelEndpoint()
                    ?: return null to ReadReceiptRuntimeError.Resource(
                        R.string.read_receipts_public_health_check_pending,
                    )
                ResolvedBackend(
                    backend = ReadReceiptBackend.BUILT_IN,
                    requestEndpoint = "http://127.0.0.1:${origin.port}",
                    pixelEndpoint = publicEndpoint,
                    recordEndpoint = BUILT_IN_RECORD_ENDPOINT,
                ) to null
            }
        }
    }

    private fun pollingEndpoint(record: ReadReceiptRecord): String? = when (record.backend) {
        ReadReceiptBackend.THIRD_PARTY -> record.endpoint
        ReadReceiptBackend.BUILT_IN -> {
            val origin = originController.snapshot()
            if (origin.state != ReadReceiptsRuntimeState.RUNNING || origin.port == null) {
                null
            } else {
                "http://127.0.0.1:${origin.port}"
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    private fun tunnelMode(configuration: ReadReceiptsConfiguration): ReadReceiptsTunnelMode =
        ReadReceiptsTunnelMode.entries.firstOrNull { it.name == configuration.tunnelMode }
            ?: ReadReceiptsTunnelMode.QUICK

    private fun startBuiltInStack(
        configuration: ReadReceiptsConfiguration,
        token: String? = null,
        onFinished: ((OriginRequestTerminal<Unit>) -> Unit)? = null,
    ) {
        val mode = tunnelMode(configuration)
        startBuiltInCandidate(
            configuration = configuration,
            startTunnel = { port, complete ->
                ReadReceiptsTunnelController.startVisible(
                    mode = mode,
                    originPort = port,
                    hostname = configuration.hostname,
                    token = token,
                    onHandoff = complete,
                )
            },
            onFinished = onFinished,
        )
    }

    /** Starts the fixed/automatic origin before handing its actual port to one tunnel candidate. */
    private fun startBuiltInCandidate(
        configuration: ReadReceiptsConfiguration,
        startTunnel: (Int, (OriginRequestTerminal<Unit>) -> Unit) -> Unit,
        onFinished: ((OriginRequestTerminal<Unit>) -> Unit)? = null,
    ) {
        val mode = tunnelMode(configuration)
        if (
            mode in setOf(
                ReadReceiptsTunnelMode.TOKEN,
                ReadReceiptsTunnelMode.BROWSER_LOGIN,
            ) && configuration.automaticPort
        ) {
            onFinished?.invoke(
                OriginRequestTerminal.Completed(
                    Result.failure(
                        ReadReceiptsLocalFailure(
                            if (mode == ReadReceiptsTunnelMode.TOKEN) {
                                R.string.read_receipts_token_fixed_port_route_required
                            } else {
                                R.string.read_receipts_browser_fixed_port_route_required
                            },
                        ),
                    ),
                ),
            )
            return
        }
        startOrigin(requestedBuiltInPort(configuration)) { terminal ->
            when (terminal) {
                is OriginRequestTerminal.Completed -> terminal.result.fold(
                    onSuccess = { port ->
                        startTunnel(port) { handoffTerminal ->
                            onFinished?.invoke(handoffTerminal)
                        }
                    },
                    onFailure = { error ->
                        onFinished?.invoke(
                            OriginRequestTerminal.Completed(Result.failure(error)),
                        )
                    },
                )

                OriginRequestTerminal.Superseded -> {
                    onFinished?.invoke(OriginRequestTerminal.Superseded)
                }
            }
        }
    }

    private fun browserConfiguration(
        base: ReadReceiptsConfiguration,
        metadata: CommittedBrowserTunnelMetadata,
    ): ReadReceiptsConfiguration = base.copy(
        mode = ReadReceiptsServerMode.BUILT_IN,
        automaticPort = false,
        builtInPort = metadata.fixedOriginPort,
        tunnelMode = ReadReceiptsTunnelMode.BROWSER_LOGIN.name,
        hostname = metadata.canonicalHostname,
        selectedAccountId = metadata.accountId,
        selectedAccountName = "",
        selectedTunnelId = metadata.tunnelId,
        selectedTunnelName = metadata.tunnelName,
    )

    private fun authoritativeBrowserMetadata(
        expectedTunnelId: String? = null,
        expectedHostname: String? = null,
        expectedPort: Int? = null,
        requireVerifiedEndpoint: Boolean = false,
    ): CommittedBrowserTunnelMetadata? {
        val metadata = when (val decision =
            ReadReceiptsTunnelController.browserMetadataRebindDecision
        ) {
            BrowserMetadataRebindDecision.Keep -> return null
            is BrowserMetadataRebindDecision.Replace -> decision.metadata
        }
        if (expectedTunnelId != null && metadata.tunnelId != expectedTunnelId) return null
        if (expectedHostname != null && metadata.canonicalHostname != expectedHostname) return null
        if (expectedPort != null && metadata.fixedOriginPort != expectedPort) return null
        if (
            requireVerifiedEndpoint &&
            ReadReceiptsTunnelController.verifiedEndpoint() != metadata.canonicalHostname
        ) {
            return null
        }
        return metadata
    }

    private fun authoritativeBrowserConfiguration(
        base: ReadReceiptsConfiguration,
        expectedTunnelId: String? = null,
        expectedHostname: String? = null,
        expectedPort: Int? = null,
        requireVerifiedEndpoint: Boolean = false,
    ): ReadReceiptsConfiguration? = authoritativeBrowserMetadata(
        expectedTunnelId = expectedTunnelId,
        expectedHostname = expectedHostname,
        expectedPort = expectedPort,
        requireVerifiedEndpoint = requireVerifiedEndpoint,
    )?.let { browserConfiguration(base, it) }

    /** Lifecycle-only persistence for an already-selected Browser configuration. */
    private fun reconcileActiveBrowserConfiguration(): ReadReceiptsConfiguration? {
        val current = configuration()
        if (tunnelMode(current) != ReadReceiptsTunnelMode.BROWSER_LOGIN) return null

        val reconciled = authoritativeBrowserConfiguration(current) ?: return null
        if (reconciled != current) saveConfiguration(reconciled)
        return reconciled
    }

    private suspend fun awaitBrowserConfiguration(
        owner: ConfigurationTransactionOwner,
        base: ReadReceiptsConfiguration,
        expectedTunnelId: String,
        expectedHostname: String,
        expectedPort: Int,
        requireVerifiedEndpoint: Boolean,
        maxAttempts: Int?,
    ): OriginRequestTerminal<ReadReceiptsConfiguration>? {
        var attempts = 0
        while (maxAttempts == null || attempts < maxAttempts) {
            currentCoroutineContext().ensureActive()
            if (!owner.isCurrent()) return OriginRequestTerminal.Superseded
            val authoritative = withContext(Dispatchers.Main.immediate) {
                if (!owner.isCurrent()) return@withContext OriginRequestTerminal.Superseded
                if (attempts % BROWSER_METADATA_RECONCILE_ATTEMPTS == 0) {
                    ReadReceiptsTunnelController.refresh()
                }
                if (!owner.isCurrent()) return@withContext OriginRequestTerminal.Superseded
                val reconciled = authoritativeBrowserConfiguration(
                    base = base,
                    expectedTunnelId = expectedTunnelId,
                    expectedHostname = expectedHostname,
                    expectedPort = expectedPort,
                    requireVerifiedEndpoint = requireVerifiedEndpoint,
                )
                reconciled?.let {
                    OriginRequestTerminal.Completed(Result.success(it))
                }
            }
            if (authoritative != null) return authoritative
            attempts++
            delay(BROWSER_METADATA_RECONCILE_DELAY_MILLIS)
        }
        return null
    }

    private fun startBrowserSelection(
        owner: ConfigurationTransactionOwner,
        candidate: ReadReceiptsConfiguration,
        onCommitPending: () -> Unit,
        onFinished: (OriginRequestTerminal<ReadReceiptsConfiguration>) -> Unit,
    ) {
        startBuiltInCandidate(
            configuration = candidate,
            startTunnel = { port, complete ->
                originScope.launch {
                    val selection = ReadReceiptsTunnelController.selectExistingTunnel(
                        id = candidate.selectedTunnelId,
                        canonicalRoot = candidate.hostname,
                        fixedPort = port,
                    )
                    val authoritative = awaitBrowserConfiguration(
                        owner = owner,
                        base = candidate,
                        expectedTunnelId = candidate.selectedTunnelId,
                        expectedHostname = candidate.hostname,
                        expectedPort = port,
                        requireVerifiedEndpoint = selection.isFailure,
                        maxAttempts = BROWSER_METADATA_RECONCILE_ATTEMPTS,
                    )
                    withContext(Dispatchers.Main.immediate) {
                        when {
                            authoritative != null -> onFinished(authoritative)
                            selection.isSuccess && owner.isCurrent() -> onCommitPending()
                            selection.isSuccess -> onFinished(OriginRequestTerminal.Superseded)
                            else -> complete(
                                OriginRequestTerminal.Completed(
                                    Result.failure(selection.exceptionOrNull()!!),
                                ),
                            )
                        }
                    }
                    if (
                        authoritative == null && selection.isSuccess && owner.isCurrent()
                    ) {
                        val reconciled = awaitBrowserConfiguration(
                            owner = owner,
                            base = candidate,
                            expectedTunnelId = candidate.selectedTunnelId,
                            expectedHostname = candidate.hostname,
                            expectedPort = port,
                            requireVerifiedEndpoint = false,
                            maxAttempts = null,
                        )
                        withContext(Dispatchers.Main.immediate) {
                            onFinished(reconciled ?: OriginRequestTerminal.Superseded)
                        }
                    }
                }
            },
            onFinished = { terminal ->
                when (terminal) {
                    is OriginRequestTerminal.Completed -> terminal.result.onFailure { error ->
                        onFinished(OriginRequestTerminal.Completed(Result.failure(error)))
                    }
                    OriginRequestTerminal.Superseded -> onFinished(OriginRequestTerminal.Superseded)
                }
            },
        )
    }

    /**
     * Applies only a committed runtime candidate. Manual token handoff and Browser selection share
     * the same stop/origin/rollback boundary; Browser configuration comes back from service metadata.
     */
    private fun runBuiltInCandidateTransaction(
        candidate: ReadReceiptsConfiguration,
        starter: (
            ReadReceiptsConfiguration,
            ConfigurationTransactionOwner,
            (OriginRequestTerminal<ReadReceiptsConfiguration>) -> Unit,
        ) -> Unit,
        onFinished: (OriginRequestTerminal<Unit>) -> Unit,
    ) {
        val owner = configurationTransactionOwnership.acquire()
        val previous = configuration()
        val candidateMode = tunnelMode(candidate)
        val canonicalCandidate = if (
            candidateMode in setOf(
                ReadReceiptsTunnelMode.TOKEN,
                ReadReceiptsTunnelMode.BROWSER_LOGIN,
            )
        ) {
            val canonicalHostname = ReadReceiptsTunnelService.canonicalPublicRoot(candidate.hostname)
                ?: run {
                    owner.finishIfCurrent()
                    onFinished(
                        OriginRequestTerminal.Completed(
                            Result.failure(
                                ReadReceiptsLocalFailure(
                                    R.string.read_receipts_managed_tunnel_requires_hostname,
                                ),
                            ),
                        ),
                    )
                    return
                }
            candidate.copy(hostname = canonicalHostname)
        } else {
            candidate
        }
        if (
            candidateMode == ReadReceiptsTunnelMode.BROWSER_LOGIN &&
            !ExistingTunnel.isCanonicalId(canonicalCandidate.selectedTunnelId)
        ) {
            owner.finishIfCurrent()
            onFinished(
                OriginRequestTerminal.Completed(
                    Result.failure(
                        ReadReceiptsLocalFailure(
                            R.string.read_receipts_invalid_cloudflare_tunnel,
                        ),
                    ),
                ),
            )
            return
        }
        val previousWasActive = originController.status() in setOf(
            ReadReceiptsRuntimeState.STARTING,
            ReadReceiptsRuntimeState.RUNNING,
            ReadReceiptsRuntimeState.STOPPING,
        )
        val needsReplacement = previousWasActive &&
            readReceiptsBuiltInRuntimeChanged(previous, canonicalCandidate)

        fun finishSuperseded() {
            owner.finishIfCurrent()
            onFinished(OriginRequestTerminal.Superseded)
        }

        fun restore(error: Throwable) {
            if (!owner.isCurrent()) {
                finishSuperseded()
                return
            }
            if (
                !previousWasActive &&
                ReadReceiptsTunnelController.status.needsNotificationSettings
            ) {
                stopOrigin { stopTerminal ->
                    when (stopTerminal) {
                        is OriginRequestTerminal.Completed -> {
                            if (owner.finishIfCurrent { persistConfiguration(previous) }) {
                                onFinished(
                                    OriginRequestTerminal.Completed(Result.failure(error)),
                                )
                            } else {
                                finishSuperseded()
                            }
                        }
                        OriginRequestTerminal.Superseded -> finishSuperseded()
                    }
                }
                return
            }
            stopBuiltInStack { stopTerminal ->
                when (stopTerminal) {
                    is OriginRequestTerminal.Completed -> {
                        if (!owner.runIfCurrent { persistConfiguration(previous) }) {
                            finishSuperseded()
                            return@stopBuiltInStack
                        }
                        val stopFailure = stopTerminal.result.exceptionOrNull()
                        if (stopFailure != null) {
                            if (owner.finishIfCurrent()) {
                                onFinished(
                                    OriginRequestTerminal.Completed(
                                        Result.failure(stopFailure),
                                    ),
                                )
                            } else {
                                finishSuperseded()
                            }
                            return@stopBuiltInStack
                        }
                        if (previousWasActive) {
                            startBuiltInStack(previous) { restartTerminal ->
                                when (
                                    val rollbackTerminal = configurationRollbackTerminal(
                                        error,
                                        restartTerminal,
                                    )
                                ) {
                                    is OriginRequestTerminal.Completed -> {
                                        if (owner.finishIfCurrent()) {
                                            onFinished(rollbackTerminal)
                                        } else {
                                            finishSuperseded()
                                        }
                                    }

                                    OriginRequestTerminal.Superseded -> finishSuperseded()
                                }
                            }
                        } else {
                            if (owner.finishIfCurrent()) {
                                onFinished(
                                    OriginRequestTerminal.Completed(Result.failure(error)),
                                )
                            } else {
                                finishSuperseded()
                            }
                        }
                    }

                    OriginRequestTerminal.Superseded -> finishSuperseded()
                }
            }
        }

        fun startCandidate() {
            if (!owner.isCurrent()) {
                finishSuperseded()
                return
            }
            starter(canonicalCandidate, owner) { terminal ->
                when (terminal) {
                    is OriginRequestTerminal.Completed -> terminal.result.fold(
                        onSuccess = { committedCandidate ->
                            if (
                                owner.finishIfCurrent {
                                    persistConfiguration(committedCandidate)
                                }
                            ) {
                                onFinished(
                                    OriginRequestTerminal.Completed(Result.success(Unit)),
                                )
                            } else {
                                finishSuperseded()
                            }
                        },
                        onFailure = ::restore,
                    )

                    OriginRequestTerminal.Superseded -> finishSuperseded()
                }
            }
        }

        if (needsReplacement) {
            stopBuiltInStack { terminal ->
                when (terminal) {
                    is OriginRequestTerminal.Completed -> terminal.result.fold(
                        onSuccess = {
                            if (owner.isCurrent()) startCandidate() else finishSuperseded()
                        },
                        onFailure = { error ->
                            if (owner.finishIfCurrent()) {
                                onFinished(
                                    OriginRequestTerminal.Completed(Result.failure(error)),
                                )
                            } else {
                                finishSuperseded()
                            }
                        },
                    )

                    OriginRequestTerminal.Superseded -> finishSuperseded()
                }
            }
        } else {
            startCandidate()
        }
    }

    private fun applyAndStartBuiltInStack(
        candidate: ReadReceiptsConfiguration,
        token: String?,
        onFinished: (OriginRequestTerminal<Unit>) -> Unit,
    ) = runBuiltInCandidateTransaction(
        candidate = candidate,
        starter = { canonicalCandidate, owner, complete ->
            startBuiltInStack(canonicalCandidate, token) { terminal ->
                when (terminal) {
                    is OriginRequestTerminal.Completed -> terminal.result.fold(
                        onSuccess = {
                            originScope.launch {
                                val verified = awaitTunnelCandidateVerification(
                                    owner,
                                    canonicalCandidate,
                                )
                                withContext(Dispatchers.Main.immediate) {
                                    complete(
                                        when (verified) {
                                            is OriginRequestTerminal.Completed -> {
                                                OriginRequestTerminal.Completed(
                                                    verified.result.map { canonicalCandidate },
                                                )
                                            }

                                            OriginRequestTerminal.Superseded -> {
                                                OriginRequestTerminal.Superseded
                                            }
                                        },
                                    )
                                }
                            }
                        },
                        onFailure = { error ->
                            complete(
                                OriginRequestTerminal.Completed(Result.failure(error)),
                            )
                        },
                    )
                    OriginRequestTerminal.Superseded -> complete(OriginRequestTerminal.Superseded)
                }
            }
        },
        onFinished = onFinished,
    )

    private suspend fun awaitTunnelCandidateVerification(
        owner: ConfigurationTransactionOwner,
        candidate: ReadReceiptsConfiguration,
    ): OriginRequestTerminal<Unit> {
        val expectedEndpoint = when (tunnelMode(candidate)) {
            ReadReceiptsTunnelMode.QUICK -> null
            ReadReceiptsTunnelMode.TOKEN,
            ReadReceiptsTunnelMode.BROWSER_LOGIN,
            -> candidate.hostname
        }
        val terminal = withTimeoutOrNull(TUNNEL_CANDIDATE_VERIFY_TIMEOUT_MILLIS) {
            var attempts = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!owner.isCurrent()) return@withTimeoutOrNull OriginRequestTerminal.Superseded
                val status = withContext(Dispatchers.Main.immediate) {
                    if (attempts % BROWSER_METADATA_RECONCILE_ATTEMPTS == 0) {
                        ReadReceiptsTunnelController.refresh()
                    }
                    ReadReceiptsTunnelController.status
                }
                if (!owner.isCurrent()) return@withTimeoutOrNull OriginRequestTerminal.Superseded
                val verifiedEndpoint = status.publicUrl?.let(
                    ::normalizeThirdPartyReadReceiptEndpoint,
                )
                if (
                    status.state == ReadReceiptsTunnelState.CONNECTED &&
                    verifiedEndpoint != null &&
                    (expectedEndpoint == null || verifiedEndpoint == expectedEndpoint)
                ) {
                    return@withTimeoutOrNull OriginRequestTerminal.Completed(Result.success(Unit))
                }
                if (
                    status.state in setOf(
                        ReadReceiptsTunnelState.FAILED,
                        ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                    )
                ) {
                    return@withTimeoutOrNull OriginRequestTerminal.Completed(
                        Result.failure(
                            status.errorCode?.let { errorCode ->
                                ReadReceiptsTunnelException(
                                    errorCode,
                                    "browser candidate verification failed",
                                )
                            } ?: ReadReceiptsLocalFailure(
                                R.string.read_receipts_candidate_verification_failed,
                            ),
                        ),
                    )
                }
                attempts++
                delay(BROWSER_METADATA_RECONCILE_DELAY_MILLIS)
            }
            @Suppress("UNREACHABLE_CODE")
            OriginRequestTerminal.Superseded
        }
        if (terminal != null) return terminal
        return if (owner.isCurrent()) {
            OriginRequestTerminal.Completed(
                Result.failure(
                    ReadReceiptsLocalFailure(
                        R.string.read_receipts_candidate_verification_timed_out,
                    ),
                ),
            )
        } else {
            OriginRequestTerminal.Superseded
        }
    }

    private fun applyAndSelectBrowserStack(
        candidate: ReadReceiptsConfiguration,
        onCommitPending: () -> Unit,
        onFinished: (OriginRequestTerminal<Unit>) -> Unit,
    ) = runBuiltInCandidateTransaction(
        candidate = candidate,
        starter = { canonicalCandidate, owner, complete ->
            startBrowserSelection(owner, canonicalCandidate, onCommitPending, complete)
        },
        onFinished = onFinished,
    )

    private fun applyConfigurationAfterStoppingStack(
        candidate: ReadReceiptsConfiguration,
        onFinished: (OriginRequestTerminal<Unit>) -> Unit,
    ) {
        val owner = configurationTransactionOwnership.acquire()
        stopBuiltInStack { terminal ->
            when (terminal) {
                is OriginRequestTerminal.Completed -> terminal.result.fold(
                    onSuccess = {
                        if (owner.finishIfCurrent { persistConfiguration(candidate) }) {
                            onFinished(OriginRequestTerminal.Completed(Result.success(Unit)))
                        } else {
                            onFinished(OriginRequestTerminal.Superseded)
                        }
                    },
                    onFailure = { error ->
                        if (owner.finishIfCurrent()) {
                            onFinished(OriginRequestTerminal.Completed(Result.failure(error)))
                        } else {
                            onFinished(OriginRequestTerminal.Superseded)
                        }
                    },
                )

                OriginRequestTerminal.Superseded -> {
                    owner.finishIfCurrent()
                    onFinished(OriginRequestTerminal.Superseded)
                }
            }
        }
    }

    private fun stopBuiltInStack(
        onFinished: ((OriginRequestTerminal<Unit>) -> Unit)? = null,
    ) {
        if (!builtInStopCallbacks.register(onFinished)) return
        ReadReceiptsTunnelController.stop { tunnelResult ->
            finishBuiltInStackStop(
                tunnelResult = tunnelResult,
                stopOrigin = ::stopOriginTracked,
            ) { generation, terminal ->
                when (terminal) {
                    is OriginRequestTerminal.Completed -> {
                        builtInStopCallbacks.complete(
                            terminal = terminal,
                            isCurrent = { originGeneration.get() == generation },
                        )
                    }

                    OriginRequestTerminal.Superseded -> {
                        builtInStopCallbacks.complete(OriginRequestTerminal.Superseded)
                    }
                }
            }
        }
    }

    internal fun onTunnelServiceStopped() {
        if (originController.status() != ReadReceiptsRuntimeState.STOPPED) stopOrigin()
    }

    private fun startOrigin(
        requestedPort: Int,
        onFinished: ((OriginRequestTerminal<Int>) -> Unit)? = null,
    ) {
        val request = newOriginRequest(
            port = requestedPort,
            forceRestart = false,
            desiredState = ReadReceiptsRuntimeState.STARTING,
        )
        submitOriginRequest(request) { terminal ->
            when (terminal) {
                is OriginRequestTerminal.Completed -> {
                    onFinished?.invoke(
                        OriginRequestTerminal.Completed(terminal.result.map { it!! }),
                    )
                }

                OriginRequestTerminal.Superseded -> {
                    onFinished?.invoke(OriginRequestTerminal.Superseded)
                }
            }
        }
    }

    private fun stopOrigin(
        onFinished: ((OriginRequestTerminal<Unit>) -> Unit)? = null,
    ) = stopOriginTracked { _, terminal -> onFinished?.invoke(terminal) }

    private fun stopOriginTracked(
        onFinished: (Long, OriginRequestTerminal<Unit>) -> Unit,
    ) {
        val request = newOriginRequest(
            port = null,
            forceRestart = false,
            desiredState = ReadReceiptsRuntimeState.STOPPING,
        )
        submitOriginRequest(request) { terminal ->
            when (terminal) {
                is OriginRequestTerminal.Completed -> {
                    onFinished(
                        request.generation,
                        OriginRequestTerminal.Completed(terminal.result.map { Unit }),
                    )
                }

                OriginRequestTerminal.Superseded -> {
                    onFinished(request.generation, OriginRequestTerminal.Superseded)
                }
            }
        }
    }

    private fun newOriginRequest(
        port: Int?,
        forceRestart: Boolean,
        desiredState: ReadReceiptsRuntimeState,
    ): OriginRequest = originRequestBoundary.mutate {
        OriginRequest(
            generation = originGeneration.incrementAndGet(),
            port = port,
            forceRestart = forceRestart,
        ).also {
            lastBuiltInState = desiredState.name
        }
    }

    private fun submitOriginRequest(
        request: OriginRequest,
        onTerminal: ((OriginRequestTerminal<Int?>) -> Unit)? = null,
    ) {
        val delivery = onTerminal?.let(::OriginTerminalDelivery)
        originScope.launch {
            val execution = OriginRequestExecution<Int?, ReadReceiptsStatus>(
                isCurrent = { request.isCurrent() },
                lifecycleMutex = originLifecycleMutex,
            )
            val terminal = execution.execute(
                reconcile = { reconcileOrigin(request) },
                snapshot = originController::snapshot,
                publish = { result, status ->
                    originRequestBoundary.mutate {
                        if (!request.isCurrent()) return@mutate false
                        result.fold(
                            onSuccess = { port ->
                                lastBuiltInPort = port ?: 0
                                lastBuiltInState = status.state.name
                                runtimeError = null
                            },
                            onFailure = { error ->
                                lastBuiltInPort = status.port ?: 0
                                lastBuiltInState = status.state.name
                                runtimeError = ReadReceiptRuntimeError.from(error)
                            },
                        )
                        true
                    }
                },
            )
            withContext(Dispatchers.Main.immediate) {
                if (delivery != null) {
                    originRequestBoundary.deliverCurrent(
                        delivery = delivery,
                        terminal = terminal,
                        isCurrent = { request.isCurrent() },
                    )
                }
            }
        }
    }

    /** Runs under [originLifecycleMutex] and returns one typed execution terminal. */
    private suspend fun reconcileOrigin(request: OriginRequest): OriginRequestTerminal<Int?> {
        if (!request.isCurrent()) return OriginRequestTerminal.Superseded
        val requestedPort = request.port
        if (requestedPort == null) {
            val terminal = stopOriginAndAwait(request)
            if (!request.isCurrent()) return OriginRequestTerminal.Superseded
            val result = if (terminal == ReadReceiptsRuntimeState.STOPPED) {
                Result.success<Int?>(null)
            } else {
                Result.failure(
                    ReadReceiptsLocalFailure(
                        R.string.read_receipts_origin_stop_timed_out,
                    ),
                )
            }
            return OriginRequestTerminal.Completed(result)
        }

        val status = originController.snapshot()
        if (!request.isCurrent()) return OriginRequestTerminal.Superseded
        if (request.forceRestart) {
            if (stopOriginAndAwait(request) == null) {
                if (!request.isCurrent()) return OriginRequestTerminal.Superseded
                return OriginRequestTerminal.Completed(
                    Result.failure(
                        ReadReceiptsLocalFailure(
                            R.string.read_receipts_origin_stop_before_apply_timed_out,
                        ),
                    ),
                )
            }
            if (!request.isCurrent()) return OriginRequestTerminal.Superseded
            return startOriginNative(request, requestedPort)
        }

        return startOriginFromStatus(request, requestedPort, status)
    }

    private suspend fun startOriginFromStatus(
        request: OriginRequest,
        requestedPort: Int,
        status: ReadReceiptsStatus,
    ): OriginRequestTerminal<Int?> = when (status.state) {
        ReadReceiptsRuntimeState.RUNNING -> {
            if (requestedPort == 0 || status.port == requestedPort) {
                OriginRequestTerminal.Completed(Result.success(status.port!!))
            } else {
                val terminal = stopOriginAndAwait(request)
                if (!request.isCurrent()) return OriginRequestTerminal.Superseded
                if (terminal == ReadReceiptsRuntimeState.STOPPED) {
                    startOriginNative(request, requestedPort)
                } else {
                    OriginRequestTerminal.Completed(
                        Result.failure(
                            ReadReceiptsLocalFailure(
                                R.string.read_receipts_origin_port_switch_failed,
                            ),
                        ),
                    )
                }
            }
        }
        ReadReceiptsRuntimeState.STARTING -> {
            val settled = awaitOriginStartSettlement(request)
            if (!request.isCurrent()) return OriginRequestTerminal.Superseded
            if (settled == null) {
                OriginRequestTerminal.Completed(
                    Result.failure(
                        ReadReceiptsLocalFailure(
                            R.string.read_receipts_origin_start_timed_out,
                        ),
                    ),
                )
            } else {
                startOriginFromStatus(request, requestedPort, settled)
            }
        }

        ReadReceiptsRuntimeState.STOPPING -> {
            val terminal = awaitOriginTerminal(request)
            if (!request.isCurrent()) return OriginRequestTerminal.Superseded
            if (terminal == null) {
                OriginRequestTerminal.Completed(
                    Result.failure(
                        ReadReceiptsLocalFailure(
                            R.string.read_receipts_origin_stop_timed_out,
                        ),
                    ),
                )
            } else {
                startOriginNative(request, requestedPort)
            }
        }

        ReadReceiptsRuntimeState.STOPPED,
        ReadReceiptsRuntimeState.FAILED,
        -> startOriginNative(request, requestedPort)
    }

    private fun startOriginNative(
        request: OriginRequest,
        requestedPort: Int,
    ): OriginRequestTerminal<Int?> {
        if (!request.isCurrent()) return OriginRequestTerminal.Superseded
        val result = originController
            .startBuiltIn(requestedPort, ReadReceiptsTunnelController.originAuthenticator())
            .map { it as Int? }
        return if (request.isCurrent()) {
            OriginRequestTerminal.Completed(result)
        } else {
            OriginRequestTerminal.Superseded
        }
    }

    private suspend fun stopOriginAndAwait(request: OriginRequest): ReadReceiptsRuntimeState? {
        val status = originController.snapshot()
        if (!request.isCurrent()) return null
        return when (status.state) {
            ReadReceiptsRuntimeState.STOPPED,
            ReadReceiptsRuntimeState.FAILED,
            -> status.state

            ReadReceiptsRuntimeState.STOPPING -> awaitOriginTerminal(request)
            ReadReceiptsRuntimeState.STARTING,
            ReadReceiptsRuntimeState.RUNNING,
            -> {
                originController.stopBuiltIn()
                if (!request.isCurrent()) return null
                awaitOriginTerminal(request)
            }
        }
    }

    private suspend fun awaitOriginTerminal(
        request: OriginRequest,
    ): ReadReceiptsRuntimeState? = withTimeoutOrNull(
        ORIGIN_STOP_TIMEOUT_MILLIS,
    ) {
        while (true) {
            val status = originController.snapshot()
            if (!request.isCurrent()) return@withTimeoutOrNull null
            when (status.state) {
                ReadReceiptsRuntimeState.STOPPED,
                ReadReceiptsRuntimeState.FAILED,
                -> return@withTimeoutOrNull status.state

                else -> {
                    delay(50)
                    if (!request.isCurrent()) return@withTimeoutOrNull null
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ReadReceiptsRuntimeState.FAILED
    }

    private suspend fun awaitOriginStartSettlement(
        request: OriginRequest,
    ): ReadReceiptsStatus? = withTimeoutOrNull(ORIGIN_STOP_TIMEOUT_MILLIS) {
        while (true) {
            val status = originController.snapshot()
            if (!request.isCurrent()) return@withTimeoutOrNull null
            if (status.state != ReadReceiptsRuntimeState.STARTING) {
                return@withTimeoutOrNull status
            }
            delay(50)
            if (!request.isCurrent()) return@withTimeoutOrNull null
        }
        @Suppress("UNREACHABLE_CODE")
        ReadReceiptsStatus(ReadReceiptsRuntimeState.FAILED)
    }

    private fun OriginRequest.isCurrent(): Boolean =
        originGeneration.get() == generation

    override fun onEnable() {
        loadRecords()
        featureScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        val configuration = configuration()
        if (
            configuration.mode == ReadReceiptsServerMode.BUILT_IN &&
            configuration.automaticLifecycle
        ) {
            ReadReceiptsTunnelController.refresh()
            featureScope!!.launch {
                repeat(BROWSER_METADATA_RECONCILE_ATTEMPTS) {
                    val reconciled = withContext(Dispatchers.Main.immediate) {
                        reconcileActiveBrowserConfiguration()
                    }
                    if (reconciled != null) {
                        if (requestedBuiltInPort(reconciled) != requestedBuiltInPort(configuration)) {
                            withContext(Dispatchers.Main.immediate) {
                                startOrigin(requestedBuiltInPort(reconciled)) { terminal ->
                                    if (
                                        terminal is OriginRequestTerminal.Completed &&
                                        terminal.result.isSuccess &&
                                        ReadReceiptsTunnelController.status.state !=
                                        ReadReceiptsTunnelState.CONNECTED
                                    ) {
                                        ReadReceiptsTunnelController.needsVisibleStart()
                                    }
                                }
                            }
                        }
                        return@launch
                    }
                    delay(BROWSER_METADATA_RECONCILE_DELAY_MILLIS)
                }
            }
            startOrigin(requestedBuiltInPort(configuration)) { terminal ->
                when (terminal) {
                    is OriginRequestTerminal.Completed -> {
                        if (terminal.result.isSuccess) {
                            ReadReceiptsTunnelController.needsVisibleStart()
                        }
                    }

                    OriginRequestTerminal.Superseded -> Unit
                }
            }
        }

        WeChatInputBarMenuApi.methodSendMessage.hookBefore(100) {
            val chatFooter = thisObject!!.reflekt().firstField {
                type = ChatFooter::class
            }.get()!! as ChatFooter

            val text = chatFooter.lastText
            val configuration = configuration()
            if (!text.startsWith(configuration.prefix)) return@hookBefore
            result = null

            val (backend, endpointError) = resolveBackend()
            if (backend == null) {
                val error = endpointError!!
                runtimeError = error
                showToast(
                    chatFooter.context,
                    chatFooter.context.localizedChatString(
                        R.string.read_receipts_error_prefix,
                        error.message(chatFooter.context),
                    ),
                )
                return@hookBefore
            }

            val actualText = text.removePrefix(configuration.prefix)
            val selfWxId = WeApi.selfWxId
            if (
                selfWxId.toByteArray(Charsets.UTF_8).size > MAX_WX_ID_BYTES ||
                actualText.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES
            ) {
                val error = ReadReceiptRuntimeError.Resource(
                    R.string.read_receipts_sender_or_content_too_large,
                )
                runtimeError = error
                showToast(
                    chatFooter.context,
                    chatFooter.context.localizedChatString(
                        R.string.read_receipts_error_prefix,
                        error.message(chatFooter.context),
                    ),
                )
                return@hookBefore
            }
            // Assigned now (epoch millis) so two identical-text messages get distinct ids.
            val createTime = System.currentTimeMillis()
            val id = computeId(selfWxId, actualText, createTime)

            val pixelUrl = "${backend.pixelEndpoint}/pixel?wxId=$selfWxId&amp;id=$id"

            val escapedText = actualText
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

            val target = WeCurrentConversationApi.value

            val xml =
                """
            <msg>
              <appmsg appid="" sdkver="0">
                <title>$escapedText</title>
                <action>view</action>
                <type>57</type>
                <refermsg>
                  <type>49</type>
                  <svrid>3081795456970157299</svrid>
                  <fromusr>wxid_</fromusr>
                  <chatusr>wxid_</chatusr>
                  <displayname> </displayname>
                  <msgsource>&lt;msgsource&gt;&lt;alnode&gt;&lt;fr&gt;2&lt;/fr&gt;&lt;/alnode&gt;&lt;sec_msg_node&gt;&lt;/sec_msg_node&gt;&lt;/msgsource&gt;</msgsource>
                  <content>&lt;msg&gt;&lt;appmsg&#x20;appid=&quot;&quot;&#x20;sdkver=&quot;0&quot;&gt;&lt;title&gt;当前版本不支持展示该内容，请升级至最新版本。&lt;/title&gt;&lt;action&gt;view&lt;/action&gt;&lt;type&gt;51&lt;/type&gt;&lt;url&gt;https://support.weixin.qq.com/security/readtemplate?t=w_security_center_website/upgrade&lt;/url&gt;&lt;finderFeed&gt;&lt;objectId&gt;14667626555619936481&lt;/objectId&gt;&lt;objectNonceId&gt;8625307247096037618_0_12_2_1_1748600110424042_f7dd7f2e-3d3e-11f0-adb0-43719c7e1fc7&lt;/objectNonceId&gt;&lt;feedType&gt;4&lt;/feedType&gt;&lt;username&gt;v2_060000231003b20faec8cae38d1ac4d6c800e435b077830e54ceb941efb42210f69f736d359b@finder&lt;/username&gt;&lt;avatar&gt;&lt;![CDATA[https://wx.qlogo.cn/finderhead/ver_1/MiawsaiaO8qpgTJBRD70ROuXN6En8LoKZ266tvlLeRGRHbb7CvcqKrxH19a2mxiafeuCoakYZhsf1u3AYEB3BooKZ6lpCfRVnsfjMfMHC4ibR67iaV6rR4qZ5Irmal16AFpQ0/0]]&gt;&lt;/avatar&gt;&lt;desc&gt;(⃔&amp;#x20;*`꒳´&amp;#x20;*&amp;#x20; )⃕↝&lt;/desc&gt;&lt;mediaCount&gt;1&lt;/mediaCount&gt;&lt;authIconType&gt;1&lt;/authIconType&gt;&lt;authIconUrl&gt;&lt;![CDATA[https://dldir1v6.qq.com/weixin/checkresupdate/auth_icon_level3_2e2f94615c1e4651a25a7e0446f63135.png]]&gt;&lt;/authIconUrl&gt;&lt;mediaList&gt;&lt;media&gt;&lt;mediaType&gt;4&lt;/mediaType&gt;&lt;url&gt;&lt;![CDATA[http://wxapp.tc.qq.com/251/20302/stodownload?encfilekey=rjD5jyTuFrIpZ2ibE8T7YmwgiahniaXswqz0uUhqGrF2B7C1FqN4dW4RUFEqbMlm05rmPXfSmjgCf3G9ia8ia5kibCH5kxIczTrbCbgAqYUvKicB0IA1udGCuzXpw&amp;hy=SH&amp;idx=1&amp;m=&amp;uzid=7a15c&amp;token=cztXnd9GyrE6cgMDsjj0eZ1MdRB3Eib2ic7rNkGkF4Z9FR5nuld6Yiap9VEugIeCegbHKzjOSMHy5EPTzfChDe3YZJjiaR7aiaFbEzmJ7lsaIjCkSIMxuHkzHibDgX42h1Lq3VySAfoEl06sU0vskxMYumKLA4llQm1WU2hX00ItegJ0c&amp;basedata=CAESBnhXVDE1MRoGeFdUMTExGgZ4V1QxMTIaBnhXVDE1MxoGeFdUMTU2GgZ4V1QxNTEaBnhXVDE1NxoGeFdUMTU4IhgKCgoGeFdUMTEyEAEKCgoGeFdUMTU3EAEqBwiYHRAAGAI&amp;sign=60es22k_sbg7L-LeRKkcDVtXNMBrP54gaTyqCSSs7KRwQm_cI792BPZxaghvauP9954aUbkgAXldv-6hcaDvjA&amp;ctsc=12&amp;extg=10eb900&amp;svrbypass=AAuL%2FQsFAAABAAAAAAC%2B28t6CjV1pwlsLoU5aBAAAADnaHZTnGbFfAj9RgZXfw6Vfkx7FpiL%2B22LVp4HLkn05tij40%2FAsJD%2BPQrMho6FgQX6w1ETaBHqHtM%3D&amp;svrnonce=1748600110]]&gt;&lt;/url&gt;&lt;thumbUrl&gt;&lt;![CDATA[$pixelUrl]]&gt;&lt;/thumbUrl&gt;&lt;coverUrl&gt;&lt;![CDATA[$pixelUrl]]&gt;&lt;/coverUrl&gt;&lt;width&gt;1080.0&lt;/width&gt;&lt;height&gt;1920.0&lt;/height&gt;&lt;videoPlayDuration&gt;8&lt;/videoPlayDuration&gt;&lt;/media&gt;&lt;/mediaList&gt;&lt;sourceCommentScene&gt;1&lt;/sourceCommentScene&gt;&lt;finderShareExtInfo&gt;&lt;![CDATA[{&quot;hasInput&quot;:false,&quot;tabContextId&quot;:&quot;4-1748600105044&quot;,&quot;contextId&quot;:&quot;1-1-17-e669331b7d4243ecae426b3a64ec81b5&quot;,&quot;shareSrcScene&quot;:4}]]&gt;&lt;/finderShareExtInfo&gt;&lt;/finderFeed&gt;&lt;/appmsg&gt;&lt;/msg&gt;</content>
                  <createtime>1748600455</createtime>
                </refermsg>
              </appmsg>
            </msg>
            """.trimIndent()

            val record = ReadReceiptRecord(
                id = id,
                wxId = selfWxId,
                backend = backend.backend,
                endpoint = backend.recordEndpoint,
                createdAtMillis = createTime,
            )
            featureScope!!.launch {
                val registrationError = registerMessage(
                    backend.requestEndpoint,
                    selfWxId,
                    actualText,
                    createTime,
                )
                if (registrationError != null) {
                    withContext(Dispatchers.Main.immediate) {
                        if (!ReadReceipts.isActive) return@withContext
                        val error = registrationError
                        runtimeError = error
                        showToast(
                            chatFooter.context,
                            chatFooter.context.localizedChatString(
                                R.string.read_receipts_error_prefix,
                                error.message(chatFooter.context),
                            ),
                        )
                    }
                    return@launch
                }

                withContext(Dispatchers.Main.immediate) {
                    coroutineContext.ensureActive()
                    if (!ReadReceipts.isActive) return@withContext
                    if (!WeMessageApi.sendXmlAppMsg(target, xml)) {
                        val error = ReadReceiptRuntimeError.Resource(R.string.read_receipts_send_failed)
                        runtimeError = error
                        showToast(
                            chatFooter.context,
                            chatFooter.context.localizedChatString(
                                R.string.read_receipts_error_prefix,
                                error.message(chatFooter.context),
                            ),
                        )
                        return@withContext
                    }
                    insertRecord(record)
                    runtimeError = null
                    if (chatFooter.lastText == text) chatFooter.lastText = ""
                    showToast(
                        chatFooter.context,
                        chatFooter.context.localizedChatString(R.string.chat_read_receipts_sent),
                    )
                }
            }
        }

        WeChatMessageViewApi.addListener(this)
        WeChatMessageViewApi.addLifecycleListener(this)
    }

    override fun onDisable() {
        val configuration = configuration()
        if (
            configuration.mode == ReadReceiptsServerMode.BUILT_IN &&
            configuration.automaticLifecycle
        ) {
            stopBuiltInStack()
        }
        WeChatMessageViewApi.removeListener(this)
        WeChatMessageViewApi.removeLifecycleListener(this)
        registrationCalls.forEach(Call::cancel)
        registrationCalls.clear()
        pollJob?.cancel()
        pollJob = null
        featureScope?.cancel()
        featureScope = null
        activeViews.clear()
        counts.clear()
        backoffs.clear()
        while (pollWake.tryReceive().isSuccess) {
            // Drain wake-ups left by the cancelled coordinator before the next enable.
        }
    }

    // ── View listener: detect tracked self-messages and render the count ───────

    /** Pulls `wxId` and `id` out of an embedded `/pixel?wxId=..&id=..` URL, tolerating `&`/`&amp;`. */
    private val pixelParamRegex =
        Regex("""/pixel\?wxId=([^&"<\s]+)(?:&amp;|&)id=([0-9a-fA-F]+)""")

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        val timeTV = findTimeView(view) ?: return
        val record = findRecord(msgInfo)
        if (record == null) {
            clearReceiptState(timeTV)
            return
        }

        stampAndRender(msgInfo, timeTV, record)

        // An already-attached recycled row receives lifecycle attach before legacy bind callbacks.
        // Refresh only that existing active identity after all bind listeners have advanced tags.
        mainHandler.post {
            val current = synchronized(activeViews) { activeViews[view] } ?: return@post
            if (current.message.instance !== msgInfo.instance) return@post
            if (current.receiptView.record.key() != record.key()) return@post
            if (timeTV.getTag(READ_RECEIPTS_MESSAGE_ID_TAG) != msgInfo.id) return@post
            val generation = timeTV.getTag(READ_RECEIPTS_BINDING_GENERATION_TAG) as Long
            val refreshed = ActiveBinding(
                msgInfo,
                ActiveReceiptView(timeTV, record, generation),
            )
            synchronized(activeViews) {
                if (activeViews[view] === current) activeViews[view] = refreshed
            }
            stampAndRender(msgInfo, timeTV, record)
        }
    }

    override fun onMessageViewAttached(view: View, message: MessageInfo) {
        val record = findRecord(message) ?: return
        val timeTV = findTimeView(view)!!
        val generation = nextGeneration(timeTV)
        val active = ActiveBinding(
            message,
            ActiveReceiptView(timeTV, record, generation),
        )
        synchronized(activeViews) {
            activeViews[view] = active
        }
        stampAndRender(message, timeTV, record)
        backoffs.compute(record.key()) { _, previous ->
            PollBackoff(previous?.failures ?: 0, 0)
        }
        ensurePolling()
        pollWake.trySend(Unit)
    }

    override fun onMessageViewDetached(view: View, message: MessageInfo) {
        removeActiveBinding(view, message)
    }

    override fun onMessageViewRecycled(view: View, message: MessageInfo) {
        removeActiveBinding(view, message)
    }

    private fun removeActiveBinding(view: View, message: MessageInfo) {
        val current = synchronized(activeViews) { activeViews[view] } ?: return
        if (current.message.instance !== message.instance) return
        val receiptView = current.receiptView
        if (receiptView.view.getTag(READ_RECEIPTS_MESSAGE_ID_TAG) != message.id) return
        val generation = receiptView.view.getTag(READ_RECEIPTS_BINDING_GENERATION_TAG) as Long
        synchronized(activeViews) {
            if (activeViews[view] === current) activeViews.remove(view)
        }
        if (receiptView.view.getTag(READ_RECEIPTS_MESSAGE_ID_TAG) == message.id &&
            receiptView.view.getTag(READ_RECEIPTS_BINDING_GENERATION_TAG) == generation
        ) {
            MessageTimeEnhancements.renderMessageTime(
                message,
                receiptView.view,
                forceVisible = true,
                readReceiptCount = null,
            )
            clearReceiptState(receiptView.view)
        }
        val empty = synchronized(activeViews) { activeViews.isEmpty() }
        if (empty) {
            pollJob?.cancel()
            pollJob = null
        }
    }

    private fun findRecord(message: MessageInfo): ReadReceiptRecord? {
        if (message.isSend == 0) return null
        val match = pixelParamRegex.find(message.content) ?: return null
        val (wxId, id) = match.destructured
        return findRecord(wxId, id.lowercase())
    }

    private fun findTimeView(view: View): TextView? {
        val tag = view.tag ?: return null
        return tag.reflekt()
            .firstField { name = "timeTV"; superclass() }
            .get() as? TextView
    }

    private fun nextGeneration(timeTV: TextView): Long {
        val generation = ((timeTV.getTag(READ_RECEIPTS_BINDING_GENERATION_TAG) as? Long) ?: 0L) + 1
        timeTV.setTag(READ_RECEIPTS_BINDING_GENERATION_TAG, generation)
        return generation
    }

    @SuppressLint("SetTextI18n")
    private fun clearReceiptState(timeTV: TextView) {
        timeTV.setTag(READ_RECEIPTS_MESSAGE_ID_TAG, null)
        timeTV.setTag(READ_RECEIPTS_COUNT_TAG, null)
        timeTV.setTag(READ_RECEIPTS_NATIVE_TEXT_TAG, null)
    }

    private fun stampAndRender(
        message: MessageInfo,
        timeTV: TextView,
        record: ReadReceiptRecord,
    ) {
        val count = counts[record.key()]
        timeTV.setTag(READ_RECEIPTS_MESSAGE_ID_TAG, message.id)
        if (timeTV.getTag(READ_RECEIPTS_NATIVE_TEXT_TAG) == null) {
            timeTV.setTag(READ_RECEIPTS_NATIVE_TEXT_TAG, timeTV.text.toString())
        }
        MessageTimeEnhancements.renderMessageTime(
            message,
            timeTV,
            forceVisible = true,
            readReceiptCount = count,
        )
        timeTV.setTag(READ_RECEIPTS_COUNT_TAG, ReadReceiptCountState(count))
    }

    // ── Poll loop ──────────────────────────────────────────────────────────────

    private fun ensurePolling() {
        val scope = featureScope ?: return
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            try {
                while (isActive) {
                    val activeRecords = synchronized(activeViews) {
                        activeViews.values
                            .map { it.receiptView.record }
                            .distinctBy { it.key() }
                    }
                    if (activeRecords.isEmpty()) return@launch

                    val now = System.currentTimeMillis()
                    val due = activeRecords.filter {
                        (backoffs[it.key()]?.nextAttemptAtMillis ?: 0L) <= now
                    }
                    if (due.isNotEmpty()) {
                        pollRecords(due)
                        continue
                    }

                    val nextAttempt = activeRecords.minOf {
                        backoffs[it.key()]?.nextAttemptAtMillis ?: now
                    }
                    withTimeoutOrNull((nextAttempt - now).coerceAtLeast(1L)) {
                        pollWake.receive()
                    }
                }
            } finally {
                val current = coroutineContext[Job]
                if (pollJob === current) pollJob = null
            }
        }
    }

    private suspend fun pollRecords(records: List<ReadReceiptRecord>) = coroutineScope {
        val queue = Channel<ReadReceiptRecord>(records.size)
        records.forEach { queue.trySend(it) }
        queue.close()
        List(minOf(MAX_POLL_WORKERS, records.size)) {
            launch {
                for (record in queue) pollRecord(record)
            }
        }.joinAll()
    }

    private suspend fun pollRecord(record: ReadReceiptRecord) {
        val key = record.key()
        val count = fetchCount(record)
        val completedAt = System.currentTimeMillis()
        if (count == null) {
            val failures = (backoffs[key]?.failures ?: 0) + 1
            val multiplier = 1L shl (failures - 1).coerceAtMost(6)
            val retryDelay = (configuration().pollIntervalSecs * 1000L * multiplier)
                .coerceAtMost(MAX_FAILURE_BACKOFF_MILLIS)
            backoffs[key] = PollBackoff(failures, completedAt + retryDelay)
            return
        }

        backoffs[key] = PollBackoff(
            failures = 0,
            nextAttemptAtMillis = completedAt + configuration().pollIntervalSecs * 1000L,
        )
        counts[key] = count
        val targets = synchronized(activeViews) {
            activeViews.entries
                .filter { it.value.receiptView.record.key() == key }
                .map { it.key to it.value }
        }
        for ((root, target) in targets) {
            mainHandler.post {
                val receiptView = target.receiptView
                if (receiptView.view.getTag(READ_RECEIPTS_MESSAGE_ID_TAG) != target.message.id) {
                    return@post
                }
                if (receiptView.view.getTag(READ_RECEIPTS_BINDING_GENERATION_TAG) != receiptView.generation) {
                    return@post
                }
                val current = synchronized(activeViews) { activeViews[root] }
                if (current !== target) return@post
                MessageTimeEnhancements.renderMessageTime(
                    target.message,
                    receiptView.view,
                    forceVisible = true,
                    readReceiptCount = count,
                )
                receiptView.view.setTag(READ_RECEIPTS_COUNT_TAG, ReadReceiptCountState(count))
            }
        }
    }

    // ── Settings dialog ─────────────────────────────────────────────────────────

    private fun testThirdPartyEndpoint(
        context: ComponentActivity,
        value: String,
        scope: CoroutineScope,
        isCurrentDialog: () -> Boolean,
    ): Job? {
        val endpoint = normalizedHttpsEndpoint(value)
        if (endpoint == null) {
            showToast(
                context,
                context.localizedChatString(R.string.read_receipts_invalid_third_party_https),
            )
            return null
        }
        return scope.launch {
            val request = Request.Builder()
                .url("$endpoint/count?wxId=wekit-health-check&id=${"0".repeat(64)}")
                .get()
                .build()
            val result = runCatching {
                executeCancellable(request).use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                }
            }
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.Main.immediate) {
                if (!isCurrentDialog()) return@withContext
                result.exceptionOrNull()?.let {
                    WeLogger.w(
                        TAG,
                        "server connection failed (${readReceiptNetworkFailureCategory(it)})",
                    )
                }
                showToast(
                    context,
                    result.fold(
                        onSuccess = {
                            context.localizedChatString(
                                R.string.read_receipts_server_connection_succeeded,
                            )
                        },
                        onFailure = {
                            context.localizedChatString(
                                R.string.read_receipts_server_connection_failed,
                            )
                        },
                    ),
                )
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        val dialogGeneration = settingsDialogGeneration.incrementAndGet()
        showComposeDialog(context) {
            val initialConfiguration = remember { configuration() }
            val dialogScope = rememberCoroutineScope()
            var serverTestJob by remember { mutableStateOf<Job?>(null) }
            DisposableEffect(dialogGeneration) {
                onDispose {
                    serverTestJob?.cancel()
                    settingsDialogGeneration.compareAndSet(
                        dialogGeneration,
                        dialogGeneration + 1,
                    )
                }
            }
            var modeInput by remember { mutableStateOf(initialConfiguration.mode) }
            var serverInput by remember { mutableStateOf(initialConfiguration.thirdPartyUrl) }
            var prefixInput by remember { mutableStateOf(initialConfiguration.prefix) }
            var intervalInput by remember {
                mutableStateOf(initialConfiguration.pollIntervalSecs.toString())
            }
            var automaticPortInput by remember {
                mutableStateOf(initialConfiguration.automaticPort)
            }
            var builtInPortInput by remember {
                mutableStateOf(initialConfiguration.builtInPort.toString())
            }
            var automaticLifecycleInput by remember {
                mutableStateOf(initialConfiguration.automaticLifecycle)
            }
            var tunnelModeInput by remember {
                mutableStateOf(tunnelMode(initialConfiguration))
            }
            var hostnameInput by remember { mutableStateOf(initialConfiguration.hostname) }
            var tokenInput by remember { mutableStateOf("") }
            var revealToken by remember { mutableStateOf(false) }
            var connectionTransactionActive by remember { mutableStateOf(false) }
            val connectionTransactionOwnership = remember {
                ConnectionTransactionOwnership { connectionTransactionActive = it }
            }
            var originStatus by remember { mutableStateOf(originController.snapshot()) }
            var tunnelStatus by remember { mutableStateOf(ReadReceiptsTunnelController.status) }
            var committedCredentialMetadata by remember {
                mutableStateOf(ReadReceiptsTunnelController.committedCredentialMetadata)
            }
            var browserLoginState by remember {
                mutableStateOf(ReadReceiptsTunnelController.browserLoginState)
            }
            var browserAccountId by remember {
                mutableStateOf(ReadReceiptsTunnelController.browserAccountId)
            }
            var browserTunnels by remember {
                mutableStateOf(ReadReceiptsTunnelController.browserExistingTunnels)
            }
            var selectedBrowserTunnelId by remember {
                mutableStateOf(initialConfiguration.selectedTunnelId)
            }
            var selectedConfiguredHostname by remember { mutableStateOf<String?>(null) }
            var manualBrowserHostname by remember {
                mutableStateOf(initialConfiguration.hostname)
            }
            var browserOperationActive by remember { mutableStateOf(false) }
            var browserActionError by remember { mutableStateOf<ReadReceiptsUiText?>(null) }
            var browserCommitPending by remember { mutableStateOf(false) }
            var hydratedBrowserAuthority by remember {
                mutableStateOf<CommittedBrowserTunnelMetadata?>(null)
            }

            LaunchedEffect(Unit) {
                ReadReceiptsTunnelController.refresh()
                while (true) {
                    originStatus = withContext(Dispatchers.IO) { originController.snapshot() }
                    tunnelStatus = ReadReceiptsTunnelController.status
                    committedCredentialMetadata =
                        ReadReceiptsTunnelController.committedCredentialMetadata
                    browserLoginState = ReadReceiptsTunnelController.browserLoginState
                    browserAccountId = ReadReceiptsTunnelController.browserAccountId
                    browserTunnels = ReadReceiptsTunnelController.browserExistingTunnels
                    val authority = when (val decision =
                        ReadReceiptsTunnelController.browserMetadataRebindDecision
                    ) {
                        BrowserMetadataRebindDecision.Keep -> null
                        is BrowserMetadataRebindDecision.Replace -> decision.metadata
                    }
                    if (
                        tunnelModeInput == ReadReceiptsTunnelMode.BROWSER_LOGIN &&
                        authority != null && authority != hydratedBrowserAuthority
                    ) {
                        automaticPortInput = false
                        builtInPortInput = authority.fixedOriginPort.toString()
                        hostnameInput = authority.canonicalHostname
                        manualBrowserHostname = authority.canonicalHostname
                        selectedBrowserTunnelId = authority.tunnelId
                        selectedConfiguredHostname = browserTunnels
                            .firstOrNull { it.id == authority.tunnelId }
                            ?.hostnames
                            ?.firstOrNull { "https://$it" == authority.canonicalHostname }
                            ?.let { "https://$it" }
                        hydratedBrowserAuthority = authority
                    }
                    delay(500)
                }
            }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_read_receipts_name)) },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            modifier = Modifier.clickable {
                                if (modeInput != ReadReceiptsServerMode.THIRD_PARTY) {
                                    configurationTransactionOwnership.supersede()
                                }
                                modeInput = ReadReceiptsServerMode.THIRD_PARTY
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = modeInput == ReadReceiptsServerMode.THIRD_PARTY,
                                    onClick = null,
                                )
                            },
                            supportingContent = {
                                Text(stringResource(R.string.read_receipts_third_party_description))
                            },
                            content = {
                                Text(stringResource(R.string.read_receipts_third_party_server))
                            },
                        )

                        ListItem(
                            modifier = Modifier.clickable {
                                if (modeInput != ReadReceiptsServerMode.BUILT_IN) {
                                    configurationTransactionOwnership.supersede()
                                }
                                modeInput = ReadReceiptsServerMode.BUILT_IN
                            },
                            trailingContent = {
                                RadioButton(
                                    selected = modeInput == ReadReceiptsServerMode.BUILT_IN,
                                    onClick = null,
                                )
                            },
                            supportingContent = {
                                Text(stringResource(R.string.read_receipts_built_in_description))
                            },
                            content = {
                                Text(stringResource(R.string.read_receipts_built_in_server))
                            },
                        )

                        when (modeInput) {
                            ReadReceiptsServerMode.THIRD_PARTY -> {
                                TextField(
                                    value = serverInput,
                                    onValueChange = { serverInput = it },
                                    label = {
                                        Text(stringResource(R.string.read_receipts_https_server_url))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = {
                                        serverTestJob?.cancel()
                                        serverTestJob = testThirdPartyEndpoint(
                                            context = context,
                                            value = serverInput,
                                            scope = featureScope ?: dialogScope,
                                            isCurrentDialog = {
                                                settingsDialogGeneration.get() == dialogGeneration
                                            },
                                        )
                                    },
                                ) { Text(stringResource(R.string.read_receipts_test_connection)) }
                            }

                            ReadReceiptsServerMode.BUILT_IN -> {
                                ListItem(
                                    modifier = Modifier.clickable {
                                        automaticLifecycleInput = !automaticLifecycleInput
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = automaticLifecycleInput,
                                            onCheckedChange = null,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_automatic_lifecycle_description,
                                            ),
                                        )
                                    },
                                    content = {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_automatic_lifecycle,
                                            ),
                                        )
                                    },
                                )

                                ListItem(
                                    modifier = Modifier.clickable {
                                        if (tunnelModeInput != ReadReceiptsTunnelMode.QUICK) {
                                            configurationTransactionOwnership.supersede()
                                        }
                                        tunnelModeInput = ReadReceiptsTunnelMode.QUICK
                                    },
                                    trailingContent = {
                                        RadioButton(
                                            selected = tunnelModeInput == ReadReceiptsTunnelMode.QUICK,
                                            onClick = null,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_quick_tunnel_description,
                                            ),
                                        )
                                    },
                                    content = {
                                        Text(stringResource(R.string.read_receipts_quick_tunnel))
                                    },
                                )
                                ListItem(
                                    modifier = Modifier.clickable {
                                        if (tunnelModeInput != ReadReceiptsTunnelMode.TOKEN) {
                                            configurationTransactionOwnership.supersede()
                                        }
                                        tunnelModeInput = ReadReceiptsTunnelMode.TOKEN
                                    },
                                    trailingContent = {
                                        RadioButton(
                                            selected = tunnelModeInput == ReadReceiptsTunnelMode.TOKEN,
                                            onClick = null,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_tunnel_token_description,
                                            ),
                                        )
                                    },
                                    content = {
                                        Text(stringResource(R.string.read_receipts_tunnel_token))
                                    },
                                )
                                ListItem(
                                    modifier = Modifier.clickable {
                                        if (
                                            tunnelModeInput !=
                                            ReadReceiptsTunnelMode.BROWSER_LOGIN
                                        ) {
                                            configurationTransactionOwnership.supersede()
                                        }
                                        tunnelModeInput = ReadReceiptsTunnelMode.BROWSER_LOGIN
                                        automaticPortInput = false
                                    },
                                    trailingContent = {
                                        RadioButton(
                                            selected = tunnelModeInput ==
                                                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                                            onClick = null,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_browser_login_description,
                                            ),
                                        )
                                    },
                                    content = {
                                        Text(stringResource(R.string.read_receipts_browser_login))
                                    },
                                )

                                ListItem(
                                    modifier = Modifier.clickable(
                                        enabled = tunnelModeInput !=
                                            ReadReceiptsTunnelMode.BROWSER_LOGIN,
                                    ) {
                                        automaticPortInput = !automaticPortInput
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = automaticPortInput,
                                            onCheckedChange = null,
                                            enabled = tunnelModeInput !=
                                                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            stringResource(
                                                if (
                                                tunnelModeInput ==
                                                ReadReceiptsTunnelMode.BROWSER_LOGIN
                                                ) {
                                                    R.string.read_receipts_browser_fixed_port_description
                                                } else {
                                                    R.string.read_receipts_automatic_port_description
                                                },
                                            ),
                                        )
                                    },
                                    content = {
                                        Text(stringResource(R.string.read_receipts_automatic_port))
                                    },
                                )

                                if (
                                    tunnelModeInput in setOf(
                                        ReadReceiptsTunnelMode.TOKEN,
                                        ReadReceiptsTunnelMode.BROWSER_LOGIN,
                                    ) &&
                                    automaticPortInput
                                ) {
                                    Text(stringResource(R.string.read_receipts_fixed_port_warning))
                                }

                                if (!automaticPortInput) {
                                    TextField(
                                        value = builtInPortInput,
                                        onValueChange = {
                                            builtInPortInput = it.filter(Char::isDigit)
                                        },
                                        label = {
                                            Text(stringResource(R.string.read_receipts_loopback_port))
                                        },
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                        ),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }

                                if (tunnelModeInput == ReadReceiptsTunnelMode.TOKEN) {
                                    val tokenCredentialSaved =
                                        committedCredentialMetadata?.source ==
                                            TunnelCredentialSource.TOKEN
                                    TextField(
                                        value = tokenInput,
                                        onValueChange = { tokenInput = it },
                                        label = {
                                            Text(
                                                stringResource(
                                                    if (tokenCredentialSaved) {
                                                        R.string.read_receipts_tunnel_token_saved
                                                    } else {
                                                        R.string.read_receipts_tunnel_token
                                                    },
                                                ),
                                            )
                                        },
                                        visualTransformation = if (revealToken) {
                                            VisualTransformation.None
                                        } else {
                                            PasswordVisualTransformation()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        TextButton(onClick = { revealToken = !revealToken }) {
                                            Text(
                                                stringResource(
                                                    if (revealToken) {
                                                        R.string.read_receipts_hide_token
                                                    } else {
                                                        R.string.read_receipts_show_token
                                                    },
                                                ),
                                            )
                                        }
                                        if (tokenCredentialSaved) {
                                            TextButton(
                                                onClick = {
                                                    tokenInput = ""
                                                    ReadReceiptsTunnelController.deleteCredential()
                                                },
                                            ) {
                                                Text(
                                                    stringResource(
                                                        R.string.read_receipts_delete_saved_token,
                                                    ),
                                                )
                                            }
                                        }
                                    }
                                    TextField(
                                        value = hostnameInput,
                                        onValueChange = { hostnameInput = it },
                                        label = {
                                            Text(stringResource(R.string.read_receipts_public_hostname))
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text(stringResource(R.string.read_receipts_cloudflare_ownership))
                                }

                                if (tunnelModeInput == ReadReceiptsTunnelMode.BROWSER_LOGIN) {
                                    val loginStateText = stringResource(
                                        browserLoginState.state.browserLoginLabelRes,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.read_receipts_cloudflare_login_status,
                                            loginStateText,
                                        ),
                                    )
                                    if (ReadReceiptsTunnelController.browserLoginRestartRequired) {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_browser_login_session_lost,
                                            ),
                                        )
                                    }
                                    if (browserAccountId.isNotEmpty()) {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_account_id,
                                                browserAccountId,
                                            ),
                                        )
                                    }
                                    browserLoginState.error?.let { error ->
                                        val errorCode = ReadReceiptsTunnelErrorCode.entries
                                            .firstOrNull { it.name == error }
                                            ?: ReadReceiptsTunnelErrorCode.UNEXPECTED_FAILURE
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_login_error,
                                                stringResource(errorCode.messageRes),
                                            ),
                                        )
                                    }
                                    browserActionError?.let { error ->
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_operation_error,
                                                error.resolve(),
                                            ),
                                        )
                                    }
                                    if (browserCommitPending) {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_browser_commit_pending,
                                            ),
                                        )
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Button(
                                            enabled = !browserOperationActive &&
                                                !connectionTransactionActive,
                                            onClick = {
                                                browserOperationActive = true
                                                browserActionError = null
                                                dialogScope.launch {
                                                    runCatching {
                                                        ReadReceiptsTunnelController
                                                            .beginBrowserLogin()
                                                    }.fold(
                                                        onSuccess = { browserLoginState = it },
                                                        onFailure = {
                                                            browserActionError =
                                                                ReadReceiptsUiText.from(
                                                                    it,
                                                                    R.string.read_receipts_browser_login_start_failed,
                                                                )
                                                        },
                                                    )
                                                    browserOperationActive = false
                                                }
                                            },
                                        ) {
                                            Text(
                                                stringResource(
                                                    if (
                                                        browserLoginState.state ==
                                                        ReadReceiptsTunnelState.FAILED ||
                                                        ReadReceiptsTunnelController
                                                            .browserLoginRestartRequired
                                                    ) {
                                                        R.string.read_receipts_retry_login
                                                    } else {
                                                        R.string.read_receipts_login
                                                    },
                                                ),
                                            )
                                        }
                                        Button(
                                            enabled = browserLoginState.state ==
                                                ReadReceiptsTunnelState.CONNECTED &&
                                                !browserOperationActive &&
                                                !connectionTransactionActive,
                                            onClick = {
                                                browserOperationActive = true
                                                browserActionError = null
                                                dialogScope.launch {
                                                    runCatching {
                                                        ReadReceiptsTunnelController
                                                            .listExistingTunnels()
                                                    }.fold(
                                                        onSuccess = { browserTunnels = it },
                                                        onFailure = {
                                                            browserActionError =
                                                                ReadReceiptsUiText.from(
                                                                    it,
                                                                    R.string.read_receipts_tunnel_list_refresh_failed,
                                                                )
                                                        },
                                                    )
                                                    browserOperationActive = false
                                                }
                                            },
                                        ) { Text(stringResource(R.string.read_receipts_refresh)) }
                                    }

                                    val authorizationUrl = browserLoginState.authorizationUrl
                                    if (authorizationUrl != null) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Button(
                                                onClick = {
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent(
                                                                Intent.ACTION_VIEW,
                                                                Uri.parse(authorizationUrl),
                                                            ),
                                                        )
                                                    }.onFailure {
                                                        browserActionError = ReadReceiptsUiText.Resource(
                                                            R.string.read_receipts_authorization_open_failed,
                                                        )
                                                    }
                                                },
                                            ) {
                                                Text(
                                                    stringResource(
                                                        R.string.read_receipts_open_authorization_page,
                                                    ),
                                                )
                                            }
                                            TextButton(
                                                onClick = {
                                                    copyToClipboard(context, authorizationUrl)
                                                    showToast(
                                                        context,
                                                        context.localizedChatString(
                                                            R.string.read_receipts_authorization_link_copied,
                                                        ),
                                                    )
                                                },
                                            ) {
                                                Text(
                                                    stringResource(
                                                        R.string.read_receipts_copy_authorization_link,
                                                    ),
                                                )
                                            }
                                        }
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        TextButton(
                                            enabled = browserLoginState.state ==
                                                ReadReceiptsTunnelState.STARTING &&
                                                !browserOperationActive &&
                                                !connectionTransactionActive,
                                            onClick = {
                                                browserOperationActive = true
                                                browserActionError = null
                                                dialogScope.launch {
                                                    ReadReceiptsTunnelController
                                                        .cancelBrowserLogin()
                                                        .onFailure {
                                                            browserActionError =
                                                                ReadReceiptsUiText.from(
                                                                    it,
                                                                    R.string.read_receipts_cancel_login_failed,
                                                                )
                                                        }
                                                    browserOperationActive = false
                                                }
                                            },
                                        ) {
                                            Text(
                                                stringResource(
                                                    R.string.read_receipts_cancel_login,
                                                ),
                                            )
                                        }
                                        TextButton(
                                            enabled = browserLoginState.state ==
                                                ReadReceiptsTunnelState.CONNECTED &&
                                                !browserOperationActive &&
                                                !connectionTransactionActive,
                                            onClick = {
                                                browserOperationActive = true
                                                browserActionError = null
                                                dialogScope.launch {
                                                    ReadReceiptsTunnelController
                                                        .logoutBrowserLogin()
                                                        .onFailure {
                                                            browserActionError =
                                                                ReadReceiptsUiText.from(
                                                                    it,
                                                                    R.string.read_receipts_logout_failed,
                                                                )
                                                        }
                                                    browserOperationActive = false
                                                }
                                            },
                                        ) {
                                            Text(stringResource(R.string.read_receipts_logout))
                                        }
                                    }

                                    if (
                                        browserLoginState.state ==
                                        ReadReceiptsTunnelState.CONNECTED &&
                                        browserTunnels.isEmpty()
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_tunnel_list_empty,
                                            ),
                                        )
                                    }
                                    LazyColumn(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 320.dp),
                                    ) {
                                        items(browserTunnels, key = ExistingTunnel::id) { tunnel ->
                                            ListItem(
                                                modifier = Modifier.clickable {
                                                    selectedBrowserTunnelId = tunnel.id
                                                    selectedConfiguredHostname = tunnel.hostnames
                                                        .firstOrNull()
                                                        ?.let { "https://$it" }
                                                },
                                                trailingContent = {
                                                    RadioButton(
                                                        selected =
                                                            selectedBrowserTunnelId == tunnel.id,
                                                        onClick = null,
                                                    )
                                                },
                                                supportingContent = {
                                                    Text(
                                                        stringResource(
                                                            R.string.read_receipts_tunnel_id,
                                                            tunnel.id,
                                                        ),
                                                    )
                                                },
                                                content = { Text(tunnel.name) },
                                            )
                                        }
                                    }

                                    val selectedTunnel = browserTunnels.firstOrNull {
                                        it.id == selectedBrowserTunnelId
                                    }
                                    if (selectedTunnel != null) {
                                        Text(
                                            stringResource(R.string.read_receipts_public_hostname),
                                        )
                                        LazyColumn(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 240.dp),
                                        ) {
                                            items(selectedTunnel.hostnames, key = { it }) { hostname ->
                                                val root = "https://$hostname"
                                                ListItem(
                                                    modifier = Modifier.clickable {
                                                        selectedConfiguredHostname = root
                                                    },
                                                    trailingContent = {
                                                        RadioButton(
                                                            selected =
                                                                selectedConfiguredHostname == root,
                                                            onClick = null,
                                                        )
                                                    },
                                                    content = { Text(hostname) },
                                                )
                                            }
                                        }
                                        ListItem(
                                            modifier = Modifier.clickable {
                                                selectedConfiguredHostname = null
                                            },
                                            trailingContent = {
                                                RadioButton(
                                                    selected = selectedConfiguredHostname == null,
                                                    onClick = null,
                                                )
                                            },
                                            supportingContent = {
                                                Text(
                                                    stringResource(
                                                        R.string.read_receipts_manual_hostname_description,
                                                    ),
                                                )
                                            },
                                            content = {
                                                Text(
                                                    stringResource(
                                                        R.string.read_receipts_manual_hostname,
                                                    ),
                                                )
                                            },
                                        )
                                        TextField(
                                            value = manualBrowserHostname,
                                            onValueChange = {
                                                manualBrowserHostname = it
                                                selectedConfiguredHostname = null
                                            },
                                            label = {
                                                Text(
                                                    stringResource(
                                                        R.string.read_receipts_public_hostname,
                                                    ),
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Button(
                                            enabled = browserLoginState.state ==
                                                ReadReceiptsTunnelState.CONNECTED &&
                                                !browserOperationActive &&
                                                !connectionTransactionActive,
                                            onClick = {
                                                val port = builtInPortInput.toIntOrNull()
                                                    ?.takeIf { it in 1..65535 }
                                                    ?: run {
                                                        browserActionError =
                                                            ReadReceiptsUiText.Resource(
                                                                R.string.read_receipts_invalid_loopback_port,
                                                            )
                                                        return@Button
                                                    }
                                                val hostname =
                                                    selectedConfiguredHostname
                                                        ?: manualBrowserHostname
                                                val canonicalHostname =
                                                    ReadReceiptsTunnelService
                                                        .canonicalPublicRoot(hostname)
                                                        ?: run {
                                                            browserActionError =
                                                                ReadReceiptsUiText.Resource(
                                                                    R.string.read_receipts_invalid_public_hostname,
                                                                )
                                                            return@Button
                                                        }
                                                val candidate = configuration().copy(
                                                    mode = ReadReceiptsServerMode.BUILT_IN,
                                                    automaticPort = false,
                                                    builtInPort = port,
                                                    tunnelMode =
                                                        ReadReceiptsTunnelMode.BROWSER_LOGIN.name,
                                                    hostname = canonicalHostname,
                                                    selectedAccountId = browserAccountId,
                                                    selectedAccountName = "",
                                                    selectedTunnelId = selectedTunnel.id,
                                                    selectedTunnelName = selectedTunnel.name,
                                                )
                                                browserActionError = null
                                                browserCommitPending = false
                                                val transactionOwner =
                                                    connectionTransactionOwnership.acquire()
                                                applyAndSelectBrowserStack(
                                                    candidate = candidate,
                                                    onCommitPending = {
                                                        browserCommitPending = true
                                                    },
                                                ) { terminal ->
                                                    transactionOwner.finish(
                                                        terminal = terminal,
                                                        onCompletedSuccess = {
                                                            browserCommitPending = false
                                                            originStatus =
                                                                originController.snapshot()
                                                            val reconciled = configuration()
                                                            hostnameInput = reconciled.hostname
                                                            manualBrowserHostname =
                                                                reconciled.hostname
                                                            selectedBrowserTunnelId =
                                                                reconciled.selectedTunnelId
                                                            showToast(
                                                                context,
                                                                context.localizedChatString(
                                                                    R.string.read_receipts_browser_tunnel_connected,
                                                                ),
                                                            )
                                                        },
                                                        onCompletedFailure = { error ->
                                                            browserCommitPending = false
                                                            originStatus =
                                                                originController.snapshot()
                                                            val actionError = ReadReceiptsUiText.from(
                                                                error,
                                                                R.string.read_receipts_browser_tunnel_connection_failed,
                                                            )
                                                            browserActionError = actionError
                                                            showToast(
                                                                context,
                                                                context.localizedChatString(
                                                                    R.string.read_receipts_connection_failed,
                                                                    actionError.resolve(context),
                                                                ),
                                                            )
                                                        },
                                                        onSuperseded = {
                                                            browserCommitPending = false
                                                            originStatus =
                                                                originController.snapshot()
                                                            browserActionError =
                                                                ReadReceiptsUiText.Resource(
                                                                    R.string.read_receipts_connection_superseded,
                                                                )
                                                        },
                                                    )
                                                }
                                            },
                                        ) {
                                            Text(
                                                stringResource(
                                                    R.string.read_receipts_select_and_verify,
                                                ),
                                            )
                                        }
                                    }
                                }

                                val stateText = stringResource(originStatus.state.labelRes)
                                Text(
                                    stringResource(
                                        R.string.read_receipts_built_in_status,
                                        stateText,
                                    ),
                                )
                                Text(
                                    stringResource(
                                        R.string.read_receipts_local_address,
                                        if (
                                            originStatus.state == ReadReceiptsRuntimeState.RUNNING &&
                                            originStatus.port != null
                                        ) {
                                            "http://127.0.0.1:${originStatus.port}"
                                        } else {
                                            stringResource(R.string.read_receipts_not_ready)
                                        },
                                    ),
                                )
                                val database = NativeReadReceiptsServerController.databaseFile()
                                Text(
                                    stringResource(
                                        R.string.read_receipts_database_path,
                                        database.absolutePath,
                                    ),
                                )
                                Text(
                                    stringResource(
                                        R.string.read_receipts_database_size,
                                        if (database.isFile) database.length() else 0L,
                                    ),
                                )
                                if (originStatus.error != null) {
                                    Text(
                                        stringResource(
                                            R.string.read_receipts_error_prefix,
                                            stringResource(
                                                R.string.read_receipts_built_in_server_error,
                                            ),
                                        ),
                                    )
                                }
                                val tunnelStateText = stringResource(tunnelStatus.state.labelRes)
                                Text(
                                    stringResource(
                                        R.string.read_receipts_public_tunnel_status,
                                        tunnelStateText,
                                    ),
                                )
                                tunnelStatus.errorCode?.let { errorCode ->
                                    Text(
                                        stringResource(
                                            R.string.read_receipts_tunnel_error,
                                            stringResource(errorCode.messageRes),
                                        ),
                                    )
                                }
                                if (tunnelStatus.needsNotificationSettings) {
                                    Button(
                                        onClick = {
                                            ReadReceiptsTunnelController.openNotificationSettings(context)
                                                .onFailure {
                                                    showToast(
                                                        context,
                                                        context.localizedChatString(
                                                            R.string.read_receipts_notification_settings_failed,
                                                        ),
                                                    )
                                                }
                                        },
                                    ) {
                                        Text(
                                            stringResource(
                                                R.string.read_receipts_open_notification_settings,
                                            ),
                                        )
                                    }
                                }
                                val verifiedUrl = tunnelStatus.publicUrl
                                    ?.takeIf { tunnelStatus.state == ReadReceiptsTunnelState.CONNECTED }
                                if (verifiedUrl != null) {
                                    Text(
                                        stringResource(
                                            R.string.read_receipts_verified_public_url,
                                            verifiedUrl,
                                        ),
                                    )
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Button(onClick = {
                                            copyToClipboard(context, verifiedUrl)
                                            showToast(
                                                context,
                                                context.localizedChatString(
                                                    R.string.read_receipts_public_url_copied,
                                                ),
                                            )
                                        }) {
                                            Text(stringResource(R.string.read_receipts_copy_url))
                                        }
                                        Button(onClick = {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, verifiedUrl)
                                            }
                                            context.startActivity(
                                                Intent.createChooser(
                                                    intent,
                                                    context.localizedChatString(
                                                        R.string.read_receipts_share_public_url,
                                                    ),
                                                ),
                                            )
                                        }) {
                                            Text(stringResource(R.string.read_receipts_share_url))
                                        }
                                    }
                                }

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    if (tunnelModeInput != ReadReceiptsTunnelMode.BROWSER_LOGIN) {
                                        Button(
                                            enabled = tunnelStatus.state in setOf(
                                                ReadReceiptsTunnelState.STOPPED,
                                                ReadReceiptsTunnelState.FAILED,
                                                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                                            ) && !connectionTransactionActive,
                                            onClick = {
                                            val port = if (automaticPortInput) {
                                                initialConfiguration.builtInPort
                                            } else {
                                                builtInPortInput.toIntOrNull()
                                                    ?.takeIf { it in 1..65535 }
                                                    ?: run {
                                                        showToast(
                                                            context,
                                                            context.localizedChatString(
                                                                R.string.read_receipts_invalid_loopback_port,
                                                            ),
                                                        )
                                                        return@Button
                                                    }
                                            }
                                            if (
                                                tunnelModeInput == ReadReceiptsTunnelMode.TOKEN &&
                                                automaticPortInput
                                            ) {
                                                showToast(
                                                    context,
                                                    context.localizedChatString(
                                                        R.string.read_receipts_token_requires_fixed_port,
                                                    ),
                                                )
                                                return@Button
                                            }
                                            val canonicalHostname = if (
                                                tunnelModeInput == ReadReceiptsTunnelMode.TOKEN
                                            ) {
                                                ReadReceiptsTunnelService.canonicalPublicRoot(hostnameInput)
                                                    ?: run {
                                                        showToast(
                                                            context,
                                                            context.localizedChatString(
                                                                R.string.read_receipts_invalid_public_hostname,
                                                            ),
                                                        )
                                                        return@Button
                                                    }
                                            } else {
                                                hostnameInput
                                            }
                                            val candidate = configuration().copy(
                                                mode = ReadReceiptsServerMode.BUILT_IN,
                                                automaticPort = automaticPortInput,
                                                builtInPort = port,
                                                tunnelMode = tunnelModeInput.name,
                                                hostname = canonicalHostname,
                                            )
                                            val transactionOwner =
                                                connectionTransactionOwnership.acquire()
                                            applyAndStartBuiltInStack(
                                                candidate,
                                                tokenInput.takeIf(String::isNotBlank),
                                            ) { terminal ->
                                                transactionOwner.finish(
                                                    terminal = terminal,
                                                    onCompletedSuccess = {
                                                        tokenInput = ""
                                                        originStatus = originController.snapshot()
                                                        showToast(
                                                            context,
                                                            context.localizedChatString(
                                                                R.string.read_receipts_tunnel_start_submitted,
                                                            ),
                                                        )
                                                    },
                                                    onCompletedFailure = { error ->
                                                        originStatus = originController.snapshot()
                                                        showToast(
                                                            context,
                                                            context.localizedChatString(
                                                                R.string.read_receipts_connection_failed,
                                                                ReadReceiptsUiText.from(
                                                                    error,
                                                                    R.string.read_receipts_unknown_error,
                                                                ).resolve(context),
                                                            ),
                                                        )
                                                    },
                                                    onSuperseded = {
                                                        originStatus = originController.snapshot()
                                                        showToast(
                                                            context,
                                                            context.localizedChatString(
                                                                R.string.read_receipts_connection_superseded,
                                                            ),
                                                        )
                                                    },
                                                )
                                            }
                                            },
                                        ) {
                                            Text(
                                                stringResource(
                                                    R.string.read_receipts_verify_and_connect,
                                                ),
                                            )
                                        }
                                    } else {
                                        Button(
                                            enabled = tunnelStatus.state in setOf(
                                                ReadReceiptsTunnelState.STOPPED,
                                                ReadReceiptsTunnelState.FAILED,
                                                ReadReceiptsTunnelState.NEEDS_USER_ACTION,
                                            ) && !connectionTransactionActive,
                                            onClick = {
                                                val authoritative =
                                                    authoritativeBrowserConfiguration(configuration())
                                                        ?: run {
                                                            browserActionError =
                                                                ReadReceiptsUiText.Resource(
                                                                    R.string.read_receipts_authoritative_config_pending,
                                                                )
                                                            return@Button
                                                        }
                                                browserActionError = null
                                                val transactionOwner =
                                                    connectionTransactionOwnership.acquire()
                                                applyAndStartBuiltInStack(
                                                    candidate = authoritative,
                                                    token = null,
                                                ) { terminal ->
                                                    transactionOwner.finish(
                                                        terminal = terminal,
                                                        onCompletedSuccess = {
                                                            originStatus =
                                                                originController.snapshot()
                                                            showToast(
                                                                context,
                                                                context.localizedChatString(
                                                                    R.string.read_receipts_browser_reconnect_submitted,
                                                                ),
                                                            )
                                                        },
                                                        onCompletedFailure = { error ->
                                                            originStatus =
                                                                originController.snapshot()
                                                            val actionError = ReadReceiptsUiText.from(
                                                                error,
                                                                R.string.read_receipts_browser_reconnect_failed,
                                                            )
                                                            browserActionError = actionError
                                                            showToast(
                                                                context,
                                                                context.localizedChatString(
                                                                    R.string.read_receipts_reconnect_failed,
                                                                    actionError.resolve(context),
                                                                ),
                                                            )
                                                        },
                                                        onSuperseded = {
                                                            originStatus =
                                                                originController.snapshot()
                                                            browserActionError =
                                                                ReadReceiptsUiText.Resource(
                                                                    R.string.read_receipts_reconnect_superseded,
                                                                )
                                                        },
                                                    )
                                                }
                                            },
                                        ) {
                                            Text(
                                                stringResource(
                                                    R.string.read_receipts_reconnect_saved,
                                                ),
                                            )
                                        }
                                    }
                                    Button(
                                        enabled = !connectionTransactionActive && (
                                            tunnelStatus.state !in setOf(
                                                ReadReceiptsTunnelState.STOPPED,
                                                ReadReceiptsTunnelState.STOPPING,
                                            ) || originStatus.state !in setOf(
                                                ReadReceiptsRuntimeState.STOPPED,
                                                ReadReceiptsRuntimeState.STOPPING,
                                            )
                                        ),
                                        onClick = {
                                            stopBuiltInStack { terminal ->
                                                when (terminal) {
                                                    is OriginRequestTerminal.Completed -> {
                                                        originStatus = originController.snapshot()
                                                        showToast(
                                                            context,
                                                            terminal.result.fold(
                                                                onSuccess = {
                                                                    context.localizedChatString(
                                                                        R.string.read_receipts_stack_stopped,
                                                                    )
                                                                },
                                                                onFailure = { error ->
                                                                    ReadReceiptsUiText.from(
                                                                        error,
                                                                        R.string.read_receipts_disconnect_failed,
                                                                    ).resolve(context)
                                                                },
                                                            ),
                                                        )
                                                    }

                                                    OriginRequestTerminal.Superseded -> {
                                                        originStatus = originController.snapshot()
                                                        showToast(
                                                            context,
                                                            context.localizedChatString(
                                                                R.string.read_receipts_disconnect_superseded,
                                                            ),
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                    ) { Text(stringResource(R.string.read_receipts_disconnect)) }
                                }
                            }
                        }

                        TextField(
                            value = prefixInput,
                            onValueChange = { prefixInput = it },
                            label = {
                                Text(stringResource(R.string.chat_read_receipts_prefix))
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextField(
                            value = intervalInput,
                            onValueChange = { intervalInput = it.filter { ch -> ch.isDigit() } },
                            label = {
                                Text(stringResource(R.string.chat_read_receipts_poll_interval))
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        runtimeError?.let { error ->
                            Text(
                                context.localizedChatString(
                                    R.string.read_receipts_recent_error,
                                    error.message(context),
                                ),
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(
                        enabled = !connectionTransactionActive,
                        onClick = {
                        val normalizedThirdParty = if (
                            modeInput == ReadReceiptsServerMode.THIRD_PARTY
                        ) {
                            normalizedHttpsEndpoint(serverInput) ?: run {
                                showToast(
                                    context,
                                    context.localizedChatString(
                                        R.string.read_receipts_invalid_third_party_https,
                                    ),
                                )
                                return@Button
                            }
                        } else {
                            initialConfiguration.thirdPartyUrl
                        }

                        val interval = intervalInput.toIntOrNull()
                        if (interval == null || interval <= 0) {
                            showToast(
                                context,
                                context.localizedChatString(
                                    R.string.chat_read_receipts_invalid_interval,
                                ),
                            )
                            return@Button
                        }

                        val configuredPort = if (
                            modeInput != ReadReceiptsServerMode.BUILT_IN || automaticPortInput
                        ) {
                            initialConfiguration.builtInPort
                        } else {
                            builtInPortInput.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                                showToast(
                                    context,
                                    context.localizedChatString(
                                        R.string.read_receipts_invalid_loopback_port,
                                    ),
                                )
                                return@Button
                            }
                        }
                        if (
                            modeInput == ReadReceiptsServerMode.BUILT_IN &&
                            tunnelModeInput in setOf(
                                ReadReceiptsTunnelMode.TOKEN,
                                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                            ) &&
                            automaticPortInput
                        ) {
                            showToast(
                                context,
                                context.localizedChatString(
                                    R.string.read_receipts_tunnel_mode_requires_fixed_port,
                                ),
                            )
                            return@Button
                        }
                        var normalizedHostname = if (
                            modeInput == ReadReceiptsServerMode.BUILT_IN &&
                            tunnelModeInput in setOf(
                                ReadReceiptsTunnelMode.TOKEN,
                                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                            )
                        ) {
                            val input = if (
                                tunnelModeInput == ReadReceiptsTunnelMode.BROWSER_LOGIN
                            ) {
                                selectedConfiguredHostname ?: manualBrowserHostname
                            } else {
                                hostnameInput
                            }
                            ReadReceiptsTunnelService.canonicalPublicRoot(input) ?: run {
                                showToast(
                                    context,
                                    context.localizedChatString(
                                        R.string.read_receipts_invalid_public_hostname,
                                    ),
                                )
                                return@Button
                            }
                        } else {
                            hostnameInput
                        }

                        val oldConfiguration = configuration()
                        val originWasActive = originController.status() in setOf(
                            ReadReceiptsRuntimeState.STARTING,
                            ReadReceiptsRuntimeState.RUNNING,
                            ReadReceiptsRuntimeState.STOPPING,
                        )

                        val authoritativeBrowser = if (
                            modeInput == ReadReceiptsServerMode.BUILT_IN &&
                            tunnelModeInput == ReadReceiptsTunnelMode.BROWSER_LOGIN
                        ) {
                            authoritativeBrowserConfiguration(
                                base = oldConfiguration,
                                expectedTunnelId = selectedBrowserTunnelId,
                                expectedHostname = normalizedHostname,
                                expectedPort = configuredPort,
                            ) ?: run {
                                showToast(
                                    context,
                                    context.localizedChatString(
                                        R.string.read_receipts_select_browser_tunnel_first,
                                    ),
                                )
                                return@Button
                            }
                        } else {
                            oldConfiguration
                        }
                        if (tunnelModeInput == ReadReceiptsTunnelMode.BROWSER_LOGIN) {
                            normalizedHostname = authoritativeBrowser.hostname
                        }
                        val candidate = authoritativeBrowser.copy(
                            mode = modeInput,
                            thirdPartyUrl = normalizedThirdParty,
                            prefix = prefixInput,
                            pollIntervalSecs = interval,
                            automaticPort = if (
                                tunnelModeInput == ReadReceiptsTunnelMode.BROWSER_LOGIN
                            ) false else automaticPortInput,
                            builtInPort = if (
                                tunnelModeInput == ReadReceiptsTunnelMode.BROWSER_LOGIN
                            ) authoritativeBrowser.builtInPort else configuredPort,
                            automaticLifecycle = automaticLifecycleInput,
                            tunnelMode = tunnelModeInput.name,
                            hostname = normalizedHostname,
                        )

                        fun finishSuccessfulSave() {
                            if (prefixInput.isEmpty()) {
                                showToast(
                                    context,
                                    context.localizedChatString(
                                        R.string.chat_read_receipts_empty_prefix_warning,
                                    ),
                                )
                            }
                            onDismiss()
                        }

                        fun runRuntimeSave(
                            start: ((OriginRequestTerminal<Unit>) -> Unit) -> Unit,
                        ) {
                            val transactionOwner = connectionTransactionOwnership.acquire()
                            start { terminal ->
                                transactionOwner.finish(
                                    terminal = terminal,
                                    onCompletedSuccess = { finishSuccessfulSave() },
                                    onCompletedFailure = { error ->
                                        originStatus = originController.snapshot()
                                        showToast(
                                            context,
                                            context.localizedChatString(
                                                R.string.read_receipts_save_failed,
                                                ReadReceiptsUiText.from(
                                                    error,
                                                    R.string.read_receipts_unknown_error,
                                                ).resolve(context),
                                            ),
                                        )
                                    },
                                    onSuperseded = {
                                        originStatus = originController.snapshot()
                                        showToast(
                                            context,
                                            context.localizedChatString(
                                                R.string.read_receipts_save_superseded,
                                            ),
                                        )
                                    },
                                )
                            }
                        }

                        when (
                            readReceiptsConfigurationSaveAction(
                                previous = oldConfiguration,
                                candidate = candidate,
                                originWasActive = originWasActive,
                                featureActive = isActive,
                            )
                        ) {
                            ReadReceiptsConfigurationSaveAction.COMMIT -> {
                                saveConfiguration(candidate)
                                finishSuccessfulSave()
                            }

                            ReadReceiptsConfigurationSaveAction.STOP_THEN_COMMIT -> {
                                runRuntimeSave { complete ->
                                    applyConfigurationAfterStoppingStack(candidate, complete)
                                }
                            }

                            ReadReceiptsConfigurationSaveAction.TRANSACTIONAL_START,
                            ReadReceiptsConfigurationSaveAction.TRANSACTIONAL_REPLACE,
                            -> {
                                runRuntimeSave { complete ->
                                    applyAndStartBuiltInStack(
                                        candidate,
                                        token = null,
                                        onFinished = complete,
                                    )
                                }
                            }
                        }
                        },
                    ) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }
}
