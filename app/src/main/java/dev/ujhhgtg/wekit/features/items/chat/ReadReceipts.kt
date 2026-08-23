package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Receipt_long
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
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
import kotlin.time.Duration.Companion.milliseconds

internal fun readReceiptNetworkFailureCategory(failure: Throwable): String = when (failure) {
    is SocketTimeoutException -> "timeout"
    is UnknownHostException -> "dns"
    is SSLException -> "tls"
    is ConnectException -> "connect"
    is IOException -> "io"
    else -> "response"
}

internal class ReadReceiptsLocalFailure(
    @StringRes val messageRes: Int,
    vararg val formatArgs: Any,
) : IllegalStateException()

internal sealed interface ReadReceiptRuntimeError {
    fun message(context: Context): String

    class Resource(
        @StringRes private val id: Int,
        private vararg val formatArgs: Any,
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

internal sealed interface ReadReceiptsUiText {
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

object ReadReceipts : ClickableFeature(),
    WeChatMessageViewApi.ICreateViewListener,
    WeChatMessageViewApi.IMessageViewLifecycleListener {

    override val technicalId = "已读追踪"
    override val nameRes = R.string.feature_read_receipts_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_read_receipts_description

    private const val TAG = "ReadReceipts"

    /** 被动模式: 拦截所有文本发送, 一律替换为已读回执消息 */
    internal const val MODE_PASSIVE = 0

    /** 主动模式 (加号菜单): 长按发送按钮, 通过输入栏菜单主动发送 */
    internal const val MODE_ACTIVE_MENU = 1

    /** 主动模式 (触发前缀): 以触发前缀开头的文本替换为已读回执消息 */
    internal const val MODE_ACTIVE_PREFIX = 2

    // ── Preferences ─────────────────────────────────────────────────────────
    private var serializedConfiguration by prefOption("read_receipts_configuration", "")
    internal var sendMode by prefOption("read_receipts_send_mode", MODE_ACTIVE_MENU)
    internal var triggerPrefix by prefOption("read_receipts_trigger_prefix", "#rr")
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
    internal var runtimeError: ReadReceiptRuntimeError? = null

    internal fun originStatus(): ReadReceiptsStatus = originController.snapshot()

    internal fun originActive(): Boolean = originController.status().let {
        it != ReadReceiptsRuntimeState.STOPPED && it != ReadReceiptsRuntimeState.FAILED
    }

    private val originController = NativeReadReceiptsServerController()
    private val originScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val originGeneration = AtomicLong()
    private val originLifecycleMutex = Mutex()
    private val builtInStopCallbacks = CoalescedOriginCallbacks<Unit>()
    private val configurationTransactionOwnership = ConfigurationTransactionOwnership()

    private data class OriginRequest(
        val generation: Long,
        val port: Int?,
        val forceRestart: Boolean,
    )

    internal fun configuration(): ReadReceiptsConfiguration {
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

    internal fun saveConfiguration(value: ReadReceiptsConfiguration) {
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

    private fun normalizedEndpoint(value: String): String? {
        return normalizeThirdPartyReadReceiptEndpoint(value)
    }

    private fun verifiedTunnelEndpoint(): String? =
        ReadReceiptsTunnelController.verifiedEndpoint()

    private fun resolveBackend(): Pair<ResolvedBackend?, ReadReceiptRuntimeError?> {
        val configuration = configuration()
        return when (configuration.mode) {
            ReadReceiptsServerMode.THIRD_PARTY -> {
                val endpoint = normalizedEndpoint(configuration.thirdPartyUrl)
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

    /** Starts the fixed/automatic origin before handing its actual port to one tunnel candidate. */
    private fun startBuiltInStack(
        configuration: ReadReceiptsConfiguration,
        token: String? = null,
        onFinished: ((OriginRequestTerminal<Unit>) -> Unit)? = null,
    ) {
        if (configuration.mode != ReadReceiptsServerMode.BUILT_IN) {
            onFinished?.invoke(
                OriginRequestTerminal.Completed(
                    Result.failure(
                        ReadReceiptsLocalFailure(
                            R.string.read_receipts_built_in_mode_required,
                        ),
                    ),
                ),
            )
            return
        }
        val mode = configuration.tunnelMode()
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
        val mode = configuration.tunnelMode()
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

    internal fun authoritativeBrowserConfiguration(
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
        if (current.tunnelMode() != ReadReceiptsTunnelMode.BROWSER_LOGIN) return null

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
            if (attempts % BROWSER_METADATA_RECONCILE_ATTEMPTS == 0) {
                ReadReceiptsTunnelController.refresh()
            }
            if (!owner.isCurrent()) return OriginRequestTerminal.Superseded
            val authoritative = authoritativeBrowserConfiguration(
                base = base,
                expectedTunnelId = expectedTunnelId,
                expectedHostname = expectedHostname,
                expectedPort = expectedPort,
                requireVerifiedEndpoint = requireVerifiedEndpoint,
            )?.let {
                OriginRequestTerminal.Completed(Result.success(it))
            }
            if (authoritative != null) return authoritative
            attempts++
            delay(BROWSER_METADATA_RECONCILE_DELAY_MILLIS.milliseconds)
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
                        onFinished(reconciled ?: OriginRequestTerminal.Superseded)
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
        val candidateMode = candidate.tunnelMode()
        val canonicalCandidate = if (
            candidateMode in setOf(
                ReadReceiptsTunnelMode.TOKEN,
                ReadReceiptsTunnelMode.BROWSER_LOGIN,
            )
        ) {
            val canonicalHostname = ReadReceiptsTunnelHostnames.canonicalPublicRoot(candidate.hostname)
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

    internal fun applyAndStartBuiltInStack(
        candidate: ReadReceiptsConfiguration,
        token: String?,
        onFinished: (OriginRequestTerminal<Unit>) -> Unit,
    ) {
        if (candidate.mode != ReadReceiptsServerMode.BUILT_IN) {
            onFinished(
                OriginRequestTerminal.Completed(
                    Result.failure(
                        ReadReceiptsLocalFailure(R.string.read_receipts_built_in_mode_required),
                    ),
                ),
            )
            return
        }
        if (candidate.tunnelMode() == ReadReceiptsTunnelMode.BROWSER_LOGIN) {
            onFinished(
                OriginRequestTerminal.Completed(
                    Result.failure(
                        ReadReceiptsLocalFailure(
                            R.string.read_receipts_select_browser_tunnel_first,
                        ),
                    ),
                ),
            )
            return
        }
        applyAndStartVerifiedBuiltInStack(candidate, token, onFinished)
    }

    private fun applyAndStartVerifiedBuiltInStack(
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

    internal fun reconnectAuthoritativeBrowserStack(
        base: ReadReceiptsConfiguration,
        onFinished: (OriginRequestTerminal<Unit>) -> Unit,
    ) {
        val candidate = authoritativeBrowserConfiguration(base) ?: run {
            onFinished(
                OriginRequestTerminal.Completed(
                    Result.failure(
                        ReadReceiptsLocalFailure(
                            R.string.read_receipts_authoritative_config_pending,
                        ),
                    ),
                ),
            )
            return
        }
        applyAndStartVerifiedBuiltInStack(candidate, null, onFinished)
    }

    private suspend fun awaitTunnelCandidateVerification(
        owner: ConfigurationTransactionOwner,
        candidate: ReadReceiptsConfiguration,
    ): OriginRequestTerminal<Unit> {
        val expectedEndpoint = when (candidate.tunnelMode()) {
            ReadReceiptsTunnelMode.QUICK -> null
            ReadReceiptsTunnelMode.TOKEN,
            ReadReceiptsTunnelMode.BROWSER_LOGIN,
            -> candidate.hostname
        }
        val terminal = withTimeoutOrNull(TUNNEL_CANDIDATE_VERIFY_TIMEOUT_MILLIS.milliseconds) {
            var attempts = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!owner.isCurrent()) return@withTimeoutOrNull OriginRequestTerminal.Superseded
                if (attempts % BROWSER_METADATA_RECONCILE_ATTEMPTS == 0) {
                    ReadReceiptsTunnelController.refresh()
                }
                val status = ReadReceiptsTunnelController.status
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
                delay(BROWSER_METADATA_RECONCILE_DELAY_MILLIS.milliseconds)
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

    internal fun applyAndSelectBrowserStack(
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

    internal fun applyConfigurationAfterStoppingStack(
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

    internal fun stopBuiltInStack(
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

    internal fun disconnectBuiltInStack(
        onFinished: (OriginRequestTerminal<Unit>) -> Unit,
    ) {
        val owner = configurationTransactionOwnership.acquire()
        stopBuiltInStack { terminal ->
            if (!owner.finishIfCurrent()) {
                onFinished(OriginRequestTerminal.Superseded)
                return@stopBuiltInStack
            }
            onFinished(terminal)
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
    ): OriginRequest = OriginRequest(
        generation = originGeneration.incrementAndGet(),
        port = port,
        forceRestart = forceRestart,
    ).also {
        lastBuiltInState = desiredState.name
    }

    private fun submitOriginRequest(
        request: OriginRequest,
        onTerminal: ((OriginRequestTerminal<Int?>) -> Unit)? = null,
    ) {
        originScope.launch {
            val execution = OriginRequestExecution<Int?, ReadReceiptsStatus>(
                isCurrent = { request.isCurrent() },
                lifecycleMutex = originLifecycleMutex,
            )
            val terminal = execution.execute(
                reconcile = { reconcileOrigin(request) },
                snapshot = originController::snapshot,
                publish = { result, status ->
                    if (!request.isCurrent()) return@execute false
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
                },
            )
            if (onTerminal != null) {
                onTerminal(if (request.isCurrent()) terminal else OriginRequestTerminal.Superseded)
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
        ORIGIN_STOP_TIMEOUT_MILLIS.milliseconds,
    ) {
        while (true) {
            val status = originController.snapshot()
            if (!request.isCurrent()) return@withTimeoutOrNull null
            when (status.state) {
                ReadReceiptsRuntimeState.STOPPED,
                ReadReceiptsRuntimeState.FAILED,
                -> return@withTimeoutOrNull status.state

                else -> {
                    delay(50.milliseconds)
                    if (!request.isCurrent()) return@withTimeoutOrNull null
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ReadReceiptsRuntimeState.FAILED
    }

    private suspend fun awaitOriginStartSettlement(
        request: OriginRequest,
    ): ReadReceiptsStatus? = withTimeoutOrNull(ORIGIN_STOP_TIMEOUT_MILLIS.milliseconds) {
        while (true) {
            val status = originController.snapshot()
            if (!request.isCurrent()) return@withTimeoutOrNull null
            if (status.state != ReadReceiptsRuntimeState.STARTING) {
                return@withTimeoutOrNull status
            }
            delay(50.milliseconds)
            if (!request.isCurrent()) return@withTimeoutOrNull null
        }
        @Suppress("UNREACHABLE_CODE")
        ReadReceiptsStatus(ReadReceiptsRuntimeState.FAILED)
    }

    private fun OriginRequest.isCurrent(): Boolean =
        originGeneration.get() == generation

    // 主动模式 (加号菜单): 仅在模拟点击发送按钮的同步流程内置位
    private var pendingMenuSend = false

    private val provider = WeChatInputBarMenuApi.IActionItemsProvider {
        if (sendMode != MODE_ACTIVE_MENU) return@IActionItemsProvider emptyList()

        listOf(
            WeChatInputBarMenuApi.ActionItem(
                id = "send_read_receipt_message",
                icon = MaterialSymbols.Outlined.Receipt_long,
                label = localizedChatString(R.string.read_receipts_menu_label),
                onClick = { context, chatFooter ->
                    if (chatFooter.lastText.isEmpty()) {
                        showToast(
                            context,
                            context.localizedChatString(R.string.read_receipts_input_empty),
                        )
                        return@ActionItem
                    }

                    // 走用户点击发送键时的原生路径, 由 methodSendMessage 的
                    // hookBefore 拦截并替换为已读回执消息 (同步消费标记)
                    pendingMenuSend = true
                    try {
                        WeChatInputBarMenuApi.performSend(chatFooter)
                    } finally {
                        pendingMenuSend = false
                    }
                }
            )
        )
    }

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
                    val reconciled = reconcileActiveBrowserConfiguration()
                    if (reconciled != null) {
                        if (requestedBuiltInPort(reconciled) != requestedBuiltInPort(configuration)) {
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
                        return@launch
                    }
                    delay(BROWSER_METADATA_RECONCILE_DELAY_MILLIS.milliseconds)
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
            if (text.isEmpty()) return@hookBefore
            val actualText = when (sendMode) {
                MODE_ACTIVE_PREFIX -> {
                    if (!text.startsWith(triggerPrefix)) return@hookBefore
                    text.removePrefix(triggerPrefix)
                }

                // 主动模式 (加号菜单): 仅放行由菜单项触发的发送流程, 其余正常发送
                MODE_ACTIVE_MENU -> {
                    if (!pendingMenuSend) return@hookBefore
                    text
                }

                // 被动模式: 所有文本一律替换
                else -> text
            }
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
                        runtimeError = registrationError
                        showToast(
                            chatFooter.context,
                            chatFooter.context.localizedChatString(
                                R.string.read_receipts_error_prefix,
                                registrationError.message(chatFooter.context),
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

        WeChatInputBarMenuApi.addProvider(provider)
        WeChatMessageViewApi.addListener(this)
        WeChatMessageViewApi.addLifecycleListener(this)
    }

    override fun onDisable() {
        WeChatInputBarMenuApi.removeProvider(provider)
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
        val generation = (timeTV.getTag(READ_RECEIPTS_BINDING_GENERATION_TAG) as? Long ?: 0L) + 1
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
                        backoffs[it.key()]?.nextAttemptAtMillis ?: 0L <= now
                    }
                    if (due.isNotEmpty()) {
                        pollRecords(due)
                        continue
                    }

                    val nextAttempt = activeRecords.minOf {
                        backoffs[it.key()]?.nextAttemptAtMillis ?: now
                    }
                    withTimeoutOrNull((nextAttempt - now).coerceAtLeast(1L).milliseconds) {
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

    // Settings activity support

    internal fun testThirdPartyEndpoint(
        value: String,
        scope: CoroutineScope,
        onResult: (Result<Unit>) -> Unit,
    ): Job? {
        val endpoint = normalizedEndpoint(value) ?: return null
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
                onResult(result)
                result.exceptionOrNull()?.let {
                    WeLogger.w(
                        TAG,
                        "server connection failed (${readReceiptNetworkFailureCategory(it)})",
                    )
                }
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        context.startActivity(Intent(context, ReadReceiptsSettingsActivity::class.java))
    }
}
