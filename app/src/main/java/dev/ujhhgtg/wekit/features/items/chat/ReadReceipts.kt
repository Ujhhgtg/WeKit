package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.io.IOException
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

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

@Feature(name = "已读追踪", categories = ["聊天"], description = "追踪文本消息已读人数, 并在自己发送的消息上实时显示\"已读 x 人\"")
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
    private const val MAX_ENDPOINT_CHARS = 2048
    private const val ORIGIN_STOP_TIMEOUT_MILLIS = 10_000L
    private const val BROWSER_METADATA_RECONCILE_ATTEMPTS = 50
    private const val BROWSER_METADATA_RECONCILE_DELAY_MILLIS = 100L

    private data class ResolvedBackend(
        val backend: ReadReceiptBackend,
        val requestEndpoint: String,
        val pixelEndpoint: String,
        val recordEndpoint: String,
    )

    @Volatile
    private var runtimeError: String? = null

    private val originController = NativeReadReceiptsServerController()
    private val originScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val originGeneration = AtomicLong()
    private val originLifecycleMutex = Mutex()
    private val originRequestBoundary = OriginRequestBoundary()
    private val builtInStopCallbacks = CoalescedOriginCallbacks<Unit>()
    private val configurationTransactionOwnership = ConfigurationTransactionOwnership()

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
    ): String? {
        val bodyJson = buildJsonObject {
            put("wxId", wxId)
            put("content", content)
            put("createTime", createTime)
        }.toString()
        if (bodyJson.toByteArray(Charsets.UTF_8).size > MAX_REGISTRATION_BODY_BYTES) {
            return "注册请求过大"
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
                    WeLogger.w(TAG, "register request failed", e)
                    continuation.resumeIfActive("注册请求失败: ${e.message ?: e.javaClass.simpleName}")
                }

                override fun onResponse(call: Call, response: Response) {
                    registrationCalls -= call
                    response.use {
                        if (it.isSuccessful) {
                            continuation.resumeIfActive(null)
                        } else {
                            WeLogger.w(TAG, "register failed: HTTP ${it.code}")
                            continuation.resumeIfActive("注册失败: HTTP ${it.code}")
                        }
                    }
                }
            })
        }
    }

    private fun <T> kotlinx.coroutines.CancellableContinuation<T>.resumeIfActive(value: T) {
        if (isActive) resume(value)
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
            WeLogger.w(TAG, "invalid count endpoint", it)
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (!call.isCanceled()) WeLogger.w(TAG, "count request failed", e)
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
        val normalized = value.trimEnd('/')
        if (
            normalized.length > MAX_ENDPOINT_CHARS || normalized != normalized.trim() ||
            normalized.any(Char::isWhitespace)
        ) {
            return null
        }
        val url = normalized.toHttpUrlOrNull() ?: return null
        if (
            url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) {
            return null
        }
        return normalized
    }

    private fun verifiedTunnelEndpoint(): String? =
        ReadReceiptsTunnelController.verifiedEndpoint()

    private fun resolveBackend(): Pair<ResolvedBackend?, String?> {
        val configuration = configuration()
        return when (configuration.mode) {
            ReadReceiptsServerMode.THIRD_PARTY -> {
                val endpoint = normalizedHttpsEndpoint(configuration.thirdPartyUrl)
                    ?: return null to "第三方服务器必须是有效的 HTTPS 地址"
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
                    return null to "内置服务器未运行"
                }
                val publicEndpoint = verifiedTunnelEndpoint()
                    ?: return null to "Cloudflare Tunnel 公网健康检查尚未通过"
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
            val label = if (mode == ReadReceiptsTunnelMode.TOKEN) "Token" else "浏览器登录"
            onFinished?.invoke(
                OriginRequestTerminal.Completed(
                    Result.failure(
                        IllegalArgumentException(
                            "$label 模式必须使用固定回环端口, 并在 Cloudflare 控制台将路由指向该端口",
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
                                IllegalArgumentException(
                                    "Token 与浏览器登录模式需要根路径 HTTPS 主机名",
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
                    Result.failure(IllegalArgumentException("请选择有效的 Cloudflare Tunnel")),
                ),
            )
            return
        }
        val previousWasActive = originController.status() in setOf(
            ReadReceiptsRuntimeState.STARTING,
            ReadReceiptsRuntimeState.RUNNING,
            ReadReceiptsRuntimeState.STOPPING,
        )
        val needsReplacement = previousWasActive && (
            previous.mode != ReadReceiptsServerMode.BUILT_IN ||
                requestedBuiltInPort(previous) != requestedBuiltInPort(canonicalCandidate) ||
                tunnelRuntimeChanged(
                    tunnelMode(previous),
                    previous.hostname,
                    candidateMode,
                    canonicalCandidate.hostname,
                )
        )

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
                        if (previousWasActive) {
                            startBuiltInStack(previous) { restartTerminal ->
                                when (restartTerminal) {
                                    is OriginRequestTerminal.Completed -> {
                                        if (owner.finishIfCurrent()) {
                                            onFinished(
                                                OriginRequestTerminal.Completed(
                                                    Result.failure(error),
                                                ),
                                            )
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
        starter = { canonicalCandidate, _, complete ->
            startBuiltInStack(canonicalCandidate, token) { terminal ->
                when (terminal) {
                    is OriginRequestTerminal.Completed -> complete(
                        OriginRequestTerminal.Completed(
                            terminal.result.fold(
                                onSuccess = {
                                    Result.success(canonicalCandidate)
                                },
                                onFailure = { Result.failure(it) },
                            ),
                        ),
                    )
                    OriginRequestTerminal.Superseded -> complete(OriginRequestTerminal.Superseded)
                }
            }
        },
        onFinished = onFinished,
    )

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

    private fun stopBuiltInStack(
        onFinished: ((OriginRequestTerminal<Unit>) -> Unit)? = null,
    ) {
        if (!builtInStopCallbacks.register(onFinished)) return
        ReadReceiptsTunnelController.stop {
            stopOriginTracked { generation, terminal ->
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
                                runtimeError = error.message ?: error.javaClass.simpleName
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
                Result.failure(IllegalStateException("内置服务器未能及时停止"))
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
                        IllegalStateException("内置服务器未能及时停止, 配置尚未应用"),
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
                            IllegalStateException("内置服务器未能切换到指定端口"),
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
                    Result.failure(IllegalStateException("内置服务器未能及时完成启动")),
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
                    Result.failure(IllegalStateException("内置服务器未能及时停止")),
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
        val result = originController.startBuiltIn(requestedPort).map { it as Int? }
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
                runtimeError = endpointError!!
                showToast(chatFooter.context, "错误: $endpointError")
                return@hookBefore
            }

            val actualText = text.removePrefix(configuration.prefix)
            val selfWxId = WeApi.selfWxId
            if (
                selfWxId.toByteArray(Charsets.UTF_8).size > MAX_WX_ID_BYTES ||
                actualText.toByteArray(Charsets.UTF_8).size > MAX_CONTENT_BYTES
            ) {
                runtimeError = "发送者标识或消息内容过长"
                showToast(chatFooter.context, "错误: 发送者标识或消息内容过长")
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
                        showToast(chatFooter.context, "错误: $registrationError")
                    }
                    return@launch
                }

                withContext(Dispatchers.Main.immediate) {
                    coroutineContext.ensureActive()
                    if (!ReadReceipts.isActive) return@withContext
                    if (!WeMessageApi.sendXmlAppMsg(target, xml)) {
                        runtimeError = "消息发送失败"
                        showToast(chatFooter.context, "错误: 消息发送失败")
                        return@withContext
                    }
                    insertRecord(record)
                    runtimeError = null
                    if (chatFooter.lastText == text) chatFooter.lastText = ""
                    showToast(chatFooter.context, "已发送附带已读追踪的消息")
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
            clearReceiptState(receiptView.view)
            MessageTimeEnhancements.renderMessageTime(
                message,
                receiptView.view,
                readReceiptCount = null,
            )
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
        val hadReceipt = timeTV.getTag(READ_RECEIPTS_MESSAGE_ID_TAG) != null
        timeTV.setTag(READ_RECEIPTS_MESSAGE_ID_TAG, null)
        timeTV.setTag(READ_RECEIPTS_COUNT_TAG, null)
        if (hadReceipt && !MessageTimeEnhancements.isActive) {
            timeTV.text = timeTV.text.toString().substringBefore(READ_RECEIPTS_SUFFIX)
        }
    }

    private fun stampAndRender(
        message: MessageInfo,
        timeTV: TextView,
        record: ReadReceiptRecord,
    ) {
        val count = counts[record.key()]
        timeTV.setTag(READ_RECEIPTS_MESSAGE_ID_TAG, message.id)
        timeTV.setTag(READ_RECEIPTS_COUNT_TAG, ReadReceiptCountState(count))
        MessageTimeEnhancements.renderMessageTime(
            message,
            timeTV,
            forceVisible = true,
            readReceiptCount = count,
        )
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
                receiptView.view.setTag(READ_RECEIPTS_COUNT_TAG, ReadReceiptCountState(count))
                MessageTimeEnhancements.renderMessageTime(
                    target.message,
                    receiptView.view,
                    forceVisible = true,
                    readReceiptCount = count,
                )
            }
        }
    }

    // ── Settings dialog ─────────────────────────────────────────────────────────

    private fun testThirdPartyEndpoint(context: ComponentActivity, value: String) {
        val endpoint = normalizedHttpsEndpoint(value)
        if (endpoint == null) {
            showToast(context, "错误: 第三方服务器必须是有效的 HTTPS 地址")
            return
        }
        originScope.launch {
            val request = Request.Builder()
                .url("$endpoint/count?wxId=wekit-health-check&id=${"0".repeat(64)}")
                .get()
                .build()
            val result = runCatching {
                httpClient.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                }
            }
            withContext(Dispatchers.Main.immediate) {
                showToast(
                    context,
                    result.fold(
                        onSuccess = { "服务器连接成功" },
                        onFailure = { "服务器连接失败: ${it.message ?: it.javaClass.simpleName}" },
                    ),
                )
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val initialConfiguration = remember { configuration() }
            val dialogScope = rememberCoroutineScope()
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
            var browserActionError by remember { mutableStateOf<String?>(null) }
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
                title = { Text("已读追踪") },
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
                            supportingContent = { Text("使用自行部署的 HTTPS 服务") },
                            content = { Text("第三方服务器") },
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
                            supportingContent = { Text("在微信进程中运行仅限回环访问的服务器") },
                            content = { Text("内置服务器") },
                        )

                        when (modeInput) {
                            ReadReceiptsServerMode.THIRD_PARTY -> {
                                TextField(
                                    value = serverInput,
                                    onValueChange = { serverInput = it },
                                    label = { Text("HTTPS 服务器地址") },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    onClick = { testThirdPartyEndpoint(context, serverInput) },
                                ) { Text("测试连接") }
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
                                        Text("功能启用时准备内置服务器; 前台隧道仍需一次可见点击")
                                    },
                                    content = { Text("自动管理服务器和隧道") },
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
                                    supportingContent = { Text("临时 trycloudflare.com 地址, 仅适合测试") },
                                    content = { Text("Quick Tunnel") },
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
                                    supportingContent = { Text("使用控制台已配置的远程管理隧道") },
                                    content = { Text("Tunnel token") },
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
                                        Text("登录 Cloudflare 并选择已有 Tunnel；不会修改隧道、DNS 或 ingress")
                                    },
                                    content = { Text("Browser Login") },
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
                                            if (
                                                tunnelModeInput ==
                                                ReadReceiptsTunnelMode.BROWSER_LOGIN
                                            ) {
                                                "Browser Login 必须使用与已配置 ingress 一致的固定端口"
                                            } else {
                                                "由系统选择空闲的回环端口"
                                            },
                                        )
                                    },
                                    content = { Text("自动选择端口") },
                                )

                                if (
                                    tunnelModeInput in setOf(
                                        ReadReceiptsTunnelMode.TOKEN,
                                        ReadReceiptsTunnelMode.BROWSER_LOGIN,
                                    ) &&
                                    automaticPortInput
                                ) {
                                    Text("此模式需要固定端口, 控制台 Public Hostname 的服务地址必须指向同一 127.0.0.1 端口")
                                }

                                if (!automaticPortInput) {
                                    TextField(
                                        value = builtInPortInput,
                                        onValueChange = {
                                            builtInPortInput = it.filter(Char::isDigit)
                                        },
                                        label = { Text("回环端口") },
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
                                                if (tokenCredentialSaved) {
                                                    "Tunnel token（已保存）"
                                                } else {
                                                    "Tunnel token"
                                                },
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
                                            Text(if (revealToken) "隐藏" else "显示")
                                        }
                                        if (tokenCredentialSaved) {
                                            TextButton(
                                                onClick = {
                                                    tokenInput = ""
                                                    ReadReceiptsTunnelController.deleteCredential()
                                                },
                                            ) { Text("删除已保存 token") }
                                        }
                                    }
                                    TextField(
                                        value = hostnameInput,
                                        onValueChange = { hostnameInput = it },
                                        label = { Text("HTTPS 公网主机名") },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Text("WeKit 不会创建或修改隧道、DNS、主机名或 ingress")
                                }

                                if (tunnelModeInput == ReadReceiptsTunnelMode.BROWSER_LOGIN) {
                                    val loginStateText = when (browserLoginState.state) {
                                        ReadReceiptsTunnelState.STOPPED -> "未登录"
                                        ReadReceiptsTunnelState.STARTING -> "等待浏览器授权"
                                        ReadReceiptsTunnelState.CONNECTED -> "已授权"
                                        ReadReceiptsTunnelState.FAILED -> "登录失效，需要重试"
                                        ReadReceiptsTunnelState.RECONNECTING -> "状态恢复中"
                                        ReadReceiptsTunnelState.NEEDS_USER_ACTION -> "需要用户操作"
                                        ReadReceiptsTunnelState.STOPPING -> "正在取消"
                                    }
                                    Text("Cloudflare 登录状态: $loginStateText")
                                    if (ReadReceiptsTunnelController.browserLoginRestartRequired) {
                                        Text("登录会话已丢失，请重新登录")
                                    }
                                    if (browserAccountId.isNotEmpty()) {
                                        Text("Account ID: $browserAccountId")
                                    }
                                    if (browserLoginState.error != null) {
                                        Text("登录错误: ${browserLoginState.error}")
                                    }
                                    if (browserActionError != null) {
                                        Text("操作错误: $browserActionError")
                                    }
                                    if (browserCommitPending) {
                                        Text("服务已提交并验证 Tunnel，正在等待权威配置同步；同步完成前不会报告成功")
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
                                                            browserActionError = it.message
                                                                ?: "无法启动浏览器登录"
                                                        },
                                                    )
                                                    browserOperationActive = false
                                                }
                                            },
                                        ) {
                                            Text(
                                                if (
                                                    browserLoginState.state ==
                                                    ReadReceiptsTunnelState.FAILED ||
                                                    ReadReceiptsTunnelController
                                                        .browserLoginRestartRequired
                                                ) {
                                                    "重试登录"
                                                } else {
                                                    "登录"
                                                },
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
                                                            browserActionError = it.message
                                                                ?: "无法刷新 Tunnel 列表"
                                                        },
                                                    )
                                                    browserOperationActive = false
                                                }
                                            },
                                        ) { Text("刷新") }
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
                                                        browserActionError =
                                                            "无法打开浏览器，请复制授权链接后手动打开"
                                                    }
                                                },
                                            ) { Text("打开授权页面") }
                                            TextButton(
                                                onClick = {
                                                    copyToClipboard(context, authorizationUrl)
                                                    showToast(context, "授权链接已复制")
                                                },
                                            ) { Text("复制授权链接") }
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
                                                            browserActionError = it.message
                                                                ?: "无法取消登录"
                                                        }
                                                    browserOperationActive = false
                                                }
                                            },
                                        ) { Text("取消登录") }
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
                                                            browserActionError = it.message
                                                                ?: "无法退出登录"
                                                        }
                                                    browserOperationActive = false
                                                }
                                            },
                                        ) { Text("退出登录") }
                                    }

                                    if (
                                        browserLoginState.state ==
                                        ReadReceiptsTunnelState.CONNECTED &&
                                        browserTunnels.isEmpty()
                                    ) {
                                        Text("尚未加载 Tunnel；点击“刷新”读取当前账号的已有 Tunnel")
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
                                                    Text("Tunnel ID: ${tunnel.id}")
                                                },
                                                content = { Text(tunnel.name) },
                                            )
                                        }
                                    }

                                    val selectedTunnel = browserTunnels.firstOrNull {
                                        it.id == selectedBrowserTunnelId
                                    }
                                    if (selectedTunnel != null) {
                                        Text("Public Hostname")
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
                                                Text("用于本地配置 Tunnel，或选择未出现在远程 ingress 中的主机名")
                                            },
                                            content = { Text("手动输入主机名") },
                                        )
                                        TextField(
                                            value = manualBrowserHostname,
                                            onValueChange = {
                                                manualBrowserHostname = it
                                                selectedConfiguredHostname = null
                                            },
                                            label = { Text("HTTPS 公网主机名") },
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
                                                            "回环端口必须在 1 到 65535 之间"
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
                                                                "请输入根路径 HTTPS 主机名"
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
                                                                "Browser Tunnel 已验证并连接",
                                                            )
                                                        },
                                                        onCompletedFailure = { error ->
                                                            browserCommitPending = false
                                                            originStatus =
                                                                originController.snapshot()
                                                            browserActionError = error.message
                                                                ?: "Browser Tunnel 连接失败"
                                                            showToast(
                                                                context,
                                                                "连接失败: $browserActionError",
                                                            )
                                                        },
                                                        onSuperseded = {
                                                            browserCommitPending = false
                                                            originStatus =
                                                                originController.snapshot()
                                                            browserActionError =
                                                                "连接请求已被新请求取代"
                                                        },
                                                    )
                                                }
                                            },
                                        ) { Text("选择并验证连接") }
                                    }
                                }

                                val stateText = when (originStatus.state) {
                                    ReadReceiptsRuntimeState.STOPPED -> "已停止"
                                    ReadReceiptsRuntimeState.STARTING -> "启动中"
                                    ReadReceiptsRuntimeState.RUNNING -> "运行中"
                                    ReadReceiptsRuntimeState.STOPPING -> "停止中"
                                    ReadReceiptsRuntimeState.FAILED -> "失败"
                                }
                                Text("内置服务器状态: $stateText")
                                Text(
                                    "本地地址: " + if (
                                        originStatus.state == ReadReceiptsRuntimeState.RUNNING &&
                                        originStatus.port != null
                                    ) {
                                        "http://127.0.0.1:${originStatus.port}"
                                    } else {
                                        "尚未就绪"
                                    },
                                )
                                val database = NativeReadReceiptsServerController.databaseFile()
                                Text("数据库: ${database.absolutePath}")
                                Text("数据库大小: ${if (database.isFile) database.length() else 0} 字节")
                                if (originStatus.error != null) {
                                    Text("错误: ${originStatus.error}")
                                }
                                val tunnelStateText = when (tunnelStatus.state) {
                                    ReadReceiptsTunnelState.STOPPED -> "已停止"
                                    ReadReceiptsTunnelState.STARTING -> "启动中"
                                    ReadReceiptsTunnelState.CONNECTED -> "已连接并通过公网健康检查"
                                    ReadReceiptsTunnelState.RECONNECTING -> "连接或健康检查恢复中"
                                    ReadReceiptsTunnelState.NEEDS_USER_ACTION -> "需要用户操作"
                                    ReadReceiptsTunnelState.FAILED -> "失败"
                                    ReadReceiptsTunnelState.STOPPING -> "停止中"
                                }
                                Text("公网隧道: $tunnelStateText")
                                if (tunnelStatus.error != null) Text("隧道错误: ${tunnelStatus.error}")
                                if (tunnelStatus.needsNotificationSettings) {
                                    Button(
                                        onClick = {
                                            ReadReceiptsTunnelController.openNotificationSettings(context)
                                                .onFailure {
                                                    showToast(context, "无法打开 WeKit 通知设置")
                                                }
                                        },
                                    ) { Text("打开 WeKit 通知设置") }
                                }
                                val verifiedUrl = tunnelStatus.publicUrl
                                    ?.takeIf { tunnelStatus.state == ReadReceiptsTunnelState.CONNECTED }
                                if (verifiedUrl != null) {
                                    Text("已验证公网地址: $verifiedUrl")
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Button(onClick = {
                                            copyToClipboard(context, verifiedUrl)
                                            showToast(context, "公网地址已复制")
                                        }) { Text("复制地址") }
                                        Button(onClick = {
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, verifiedUrl)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "分享公网地址"))
                                        }) { Text("分享地址") }
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
                                                        showToast(context, "错误: 回环端口必须在 1 到 65535 之间")
                                                        return@Button
                                                    }
                                            }
                                            if (
                                                tunnelModeInput == ReadReceiptsTunnelMode.TOKEN &&
                                                automaticPortInput
                                            ) {
                                                showToast(context, "错误: Token 模式必须关闭自动端口")
                                                return@Button
                                            }
                                            val canonicalHostname = if (
                                                tunnelModeInput == ReadReceiptsTunnelMode.TOKEN
                                            ) {
                                                ReadReceiptsTunnelService.canonicalPublicRoot(hostnameInput)
                                                    ?: run {
                                                        showToast(context, "错误: 请输入根路径 HTTPS 主机名")
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
                                                        showToast(context, "隧道启动请求已提交")
                                                    },
                                                    onCompletedFailure = { error ->
                                                        originStatus = originController.snapshot()
                                                        showToast(
                                                            context,
                                                            "连接失败: ${error.message}",
                                                        )
                                                    },
                                                    onSuperseded = {
                                                        originStatus = originController.snapshot()
                                                        showToast(context, "连接请求已被新请求取代")
                                                    },
                                                )
                                            }
                                            },
                                        ) { Text("验证并连接") }
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
                                                                "尚未取得已保存 Browser Tunnel 的权威配置，请稍后重试"
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
                                                                "已使用保存的 Browser Tunnel 发起重连",
                                                            )
                                                        },
                                                        onCompletedFailure = { error ->
                                                            originStatus =
                                                                originController.snapshot()
                                                            browserActionError = error.message
                                                                ?: "Browser Tunnel 重连失败"
                                                            showToast(
                                                                context,
                                                                "重连失败: $browserActionError",
                                                            )
                                                        },
                                                        onSuperseded = {
                                                            originStatus =
                                                                originController.snapshot()
                                                            browserActionError =
                                                                "重连请求已被新请求取代"
                                                        },
                                                    )
                                                }
                                            },
                                        ) { Text("使用已保存配置重连") }
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
                                                                    "隧道与内置服务器已停止"
                                                                },
                                                                onFailure = { error ->
                                                                    error.message!!
                                                                },
                                                            ),
                                                        )
                                                    }

                                                    OriginRequestTerminal.Superseded -> {
                                                        originStatus = originController.snapshot()
                                                        showToast(context, "断开请求已被新请求取代")
                                                    }
                                                }
                                            }
                                        },
                                    ) { Text("断开") }
                                }
                            }
                        }

                        TextField(
                            value = prefixInput,
                            onValueChange = { prefixInput = it },
                            label = { Text("触发前缀") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        TextField(
                            value = intervalInput,
                            onValueChange = { intervalInput = it.filter { ch -> ch.isDigit() } },
                            label = { Text("轮询间隔 (秒)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (runtimeError != null) {
                            Text("最近错误: $runtimeError")
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(
                        enabled = !connectionTransactionActive,
                        onClick = {
                        val normalizedThirdParty = if (
                            modeInput == ReadReceiptsServerMode.THIRD_PARTY
                        ) {
                            normalizedHttpsEndpoint(serverInput) ?: run {
                                showToast(context, "错误: 第三方服务器必须是有效的 HTTPS 地址")
                                return@Button
                            }
                        } else {
                            initialConfiguration.thirdPartyUrl
                        }

                        val interval = intervalInput.toIntOrNull()
                        if (interval == null || interval <= 0) {
                            showToast(context, "错误: 轮询间隔格式不正确!")
                            return@Button
                        }

                        val configuredPort = if (
                            modeInput != ReadReceiptsServerMode.BUILT_IN || automaticPortInput
                        ) {
                            initialConfiguration.builtInPort
                        } else {
                            builtInPortInput.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                                showToast(context, "错误: 回环端口必须在 1 到 65535 之间")
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
                            showToast(context, "错误: 当前隧道模式必须使用固定回环端口")
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
                                showToast(context, "错误: 请输入根路径 HTTPS 主机名")
                                return@Button
                            }
                        } else {
                            hostnameInput
                        }

                        val oldConfiguration = configuration()
                        val oldRequestedPort = requestedBuiltInPort(oldConfiguration)
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
                                showToast(context, "错误: 请先选择并验证一个 Browser Tunnel")
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
                        // The versioned snapshot is one MMKV value; no legacy configuration key is
                        // written after the complete selected-mode candidate has been validated.
                        saveConfiguration(candidate)

                        val newRequestedPort = requestedBuiltInPort(candidate)
                        when {
                            modeInput == ReadReceiptsServerMode.THIRD_PARTY && originWasActive -> {
                                stopBuiltInStack()
                            }

                            modeInput == ReadReceiptsServerMode.BUILT_IN && originWasActive &&
                                (oldConfiguration.mode != modeInput ||
                                    oldRequestedPort != newRequestedPort ||
                                    tunnelRuntimeChanged(
                                        tunnelMode(oldConfiguration),
                                        oldConfiguration.hostname,
                                        tunnelMode(candidate),
                                        candidate.hostname,
                                    )) -> {
                                stopBuiltInStack()
                            }

                            modeInput == ReadReceiptsServerMode.BUILT_IN && !originWasActive &&
                                isActive && candidate.automaticLifecycle -> {
                                startOrigin(newRequestedPort) { terminal ->
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
                        }

                        if (prefixInput.isEmpty()) {
                            showToast(context, "警告: 「触发前缀」为空, 所有文本消息将启用已读追踪!")
                        }

                            onDismiss()
                        },
                    ) { Text("确定") }
                })
        }
    }
}
