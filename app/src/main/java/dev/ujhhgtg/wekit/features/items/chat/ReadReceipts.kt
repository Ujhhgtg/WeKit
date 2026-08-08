package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private const val ORIGIN_STOP_TIMEOUT_MILLIS = 10_000L

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
    private val originMetadataLock = Any()

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
        val body = buildJsonObject {
            put("wxId", wxId)
            put("content", content)
            put("createTime", createTime)
        }.toString().toRequestBody(jsonMediaType)
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
        if (normalized != normalized.trim() || normalized.any(Char::isWhitespace)) return null
        val url = normalized.toHttpUrlOrNull() ?: return null
        if (url.scheme != "https") return null
        return normalized
    }

    /** Task 9 will return a URL here only after its independently-owned tunnel is healthy. */
    private fun verifiedTunnelEndpoint(): String? = null

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
                if (normalizedHttpsEndpoint(configuration.hostname) == null) {
                    return null to "Cloudflare Tunnel 公网地址未就绪"
                }

                val publicEndpoint = verifiedTunnelEndpoint()
                    ?: return null to "Cloudflare Tunnel 状态暂不可验证, 请等待公网隧道功能接入"
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

    private fun startOrigin(
        requestedPort: Int,
        onFinished: ((Result<Int>) -> Unit)? = null,
    ) {
        val request = newOriginRequest(
            port = requestedPort,
            forceRestart = false,
            desiredState = ReadReceiptsRuntimeState.STARTING,
        )
        submitOriginRequest(request) { result ->
            onFinished?.invoke(result.map { it!! })
        }
    }

    private fun stopOrigin(onFinished: ((Result<Unit>) -> Unit)? = null) {
        val request = newOriginRequest(
            port = null,
            forceRestart = false,
            desiredState = ReadReceiptsRuntimeState.STOPPING,
        )
        submitOriginRequest(request) { result ->
            onFinished?.invoke(result.map { Unit })
        }
    }

    private fun restartOrigin(requestedPort: Int) {
        val request = newOriginRequest(
            port = requestedPort,
            forceRestart = true,
            desiredState = ReadReceiptsRuntimeState.STOPPING,
        )
        submitOriginRequest(request)
    }

    private fun newOriginRequest(
        port: Int?,
        forceRestart: Boolean,
        desiredState: ReadReceiptsRuntimeState,
    ): OriginRequest = synchronized(originMetadataLock) {
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
        onFinished: ((Result<Int?>) -> Unit)? = null,
    ) {
        originScope.launch {
            if (originGeneration.get() != request.generation) return@launch
            val result = originLifecycleMutex.withLock {
                if (originGeneration.get() != request.generation) return@withLock null
                reconcileOrigin(request)
            } ?: return@launch
            if (originGeneration.get() != request.generation) return@launch

            val status = originController.snapshot()
            if (originGeneration.get() != request.generation) return@launch
            val published = synchronized(originMetadataLock) {
                if (originGeneration.get() != request.generation) {
                    false
                } else {
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
            }
            if (!published) return@launch
            if (onFinished != null) {
                withContext(Dispatchers.Main.immediate) {
                    if (originGeneration.get() == request.generation) onFinished(result)
                }
            }
        }
    }

    /** Runs under [originLifecycleMutex]; null means a newer desired generation superseded it. */
    private suspend fun reconcileOrigin(request: OriginRequest): Result<Int?>? {
        if (!request.isCurrent()) return null
        val requestedPort = request.port
        if (requestedPort == null) {
            val terminal = stopOriginAndAwait(request)
            if (!request.isCurrent()) return null
            return if (terminal == ReadReceiptsRuntimeState.STOPPED) {
                Result.success(null)
            } else {
                Result.failure(IllegalStateException("内置服务器未能及时停止"))
            }
        }

        val status = originController.snapshot()
        if (!request.isCurrent()) return null
        if (request.forceRestart) {
            if (stopOriginAndAwait(request) == null) {
                if (!request.isCurrent()) return null
                return Result.failure(IllegalStateException("内置服务器未能及时停止, 配置尚未应用"))
            }
            if (!request.isCurrent()) return null
            return startOriginNative(request, requestedPort)
        }

        return startOriginFromStatus(request, requestedPort, status)
    }

    private suspend fun startOriginFromStatus(
        request: OriginRequest,
        requestedPort: Int,
        status: ReadReceiptsStatus,
    ): Result<Int?>? = when (status.state) {
        ReadReceiptsRuntimeState.RUNNING -> Result.success(status.port!!)
        ReadReceiptsRuntimeState.STARTING -> {
            val settled = awaitOriginStartSettlement(request)
            if (!request.isCurrent()) return null
            if (settled == null) {
                Result.failure(IllegalStateException("内置服务器未能及时完成启动"))
            } else {
                startOriginFromStatus(request, requestedPort, settled)
            }
        }

        ReadReceiptsRuntimeState.STOPPING -> {
            val terminal = awaitOriginTerminal(request)
            if (!request.isCurrent()) return null
            if (terminal == null) {
                Result.failure(IllegalStateException("内置服务器未能及时停止"))
            } else {
                startOriginNative(request, requestedPort)
            }
        }

        ReadReceiptsRuntimeState.STOPPED,
        ReadReceiptsRuntimeState.FAILED,
        -> startOriginNative(request, requestedPort)
    }

    private fun startOriginNative(request: OriginRequest, requestedPort: Int): Result<Int?>? {
        if (!request.isCurrent()) return null
        val result = originController.startBuiltIn(requestedPort).map { it as Int? }
        return if (request.isCurrent()) result else null
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
            // Task 9 must start the tunnel only after this origin callback succeeds.
            startOrigin(requestedBuiltInPort(configuration))
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
            // Task 9 must stop its tunnel leg first, then call this origin stop.
            stopOrigin()
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
            var originStatus by remember { mutableStateOf(originController.snapshot()) }

            LaunchedEffect(Unit) {
                while (true) {
                    originStatus = withContext(Dispatchers.IO) { originController.snapshot() }
                    delay(500)
                }
            }

            AlertDialogContent(
                title = { Text("已读追踪") },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
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
                                        Text("功能启用时启动内置服务器; 公网隧道将在后续版本接入")
                                    },
                                    content = { Text("自动管理服务器和隧道") },
                                )

                                ListItem(
                                    modifier = Modifier.clickable {
                                        automaticPortInput = !automaticPortInput
                                    },
                                    trailingContent = {
                                        Switch(
                                            checked = automaticPortInput,
                                            onCheckedChange = null,
                                        )
                                    },
                                    supportingContent = { Text("由系统选择空闲的回环端口") },
                                    content = { Text("自动选择端口") },
                                )

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
                                Text("公网隧道: 尚未接入, 当前不能发送内置模式追踪消息")

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Button(
                                        enabled = originStatus.state == ReadReceiptsRuntimeState.STOPPED ||
                                            originStatus.state == ReadReceiptsRuntimeState.FAILED,
                                        onClick = {
                                            startOrigin(requestedBuiltInPort()) { result ->
                                                originStatus = originController.snapshot()
                                                showToast(
                                                    context,
                                                    result.fold(
                                                        onSuccess = { "内置服务器已启动: 127.0.0.1:$it" },
                                                        onFailure = {
                                                            "内置服务器启动失败: ${it.message}"
                                                        },
                                                    ),
                                                )
                                            }
                                        },
                                    ) { Text("启动") }
                                    Button(
                                        enabled = originStatus.state == ReadReceiptsRuntimeState.STARTING ||
                                            originStatus.state == ReadReceiptsRuntimeState.RUNNING,
                                        onClick = {
                                            stopOrigin { result ->
                                                originStatus = originController.snapshot()
                                                showToast(
                                                    context,
                                                    if (result.isSuccess) {
                                                        "内置服务器已停止"
                                                    } else {
                                                        result.exceptionOrNull()!!.message!!
                                                    },
                                                )
                                            }
                                        },
                                    ) { Text("停止") }
                                }
                                Text("手动控制使用上次保存的端口配置")
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
                    Button(onClick = {
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

                        val oldConfiguration = configuration()
                        val oldRequestedPort = requestedBuiltInPort(oldConfiguration)
                        val originWasActive = originController.status() in setOf(
                            ReadReceiptsRuntimeState.STARTING,
                            ReadReceiptsRuntimeState.RUNNING,
                            ReadReceiptsRuntimeState.STOPPING,
                        )

                        val candidate = oldConfiguration.copy(
                            mode = modeInput,
                            thirdPartyUrl = normalizedThirdParty,
                            prefix = prefixInput,
                            pollIntervalSecs = interval,
                            automaticPort = automaticPortInput,
                            builtInPort = configuredPort,
                            automaticLifecycle = automaticLifecycleInput,
                        )
                        // The versioned snapshot is one MMKV value; no legacy configuration key is
                        // written after the complete selected-mode candidate has been validated.
                        saveConfiguration(candidate)

                        val newRequestedPort = requestedBuiltInPort(candidate)
                        when {
                            modeInput == ReadReceiptsServerMode.THIRD_PARTY && originWasActive -> {
                                stopOrigin()
                            }

                            modeInput == ReadReceiptsServerMode.BUILT_IN && originWasActive &&
                                (oldConfiguration.mode != modeInput ||
                                    oldRequestedPort != newRequestedPort) -> {
                                restartOrigin(newRequestedPort)
                            }

                            modeInput == ReadReceiptsServerMode.BUILT_IN && !originWasActive &&
                                isActive && candidate.automaticLifecycle -> {
                                startOrigin(newRequestedPort)
                            }
                        }

                        if (prefixInput.isEmpty()) {
                            showToast(context, "警告: 「触发前缀」为空, 所有文本消息将启用已读追踪!")
                        }

                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }
}
