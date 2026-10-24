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
import dev.ujhhgtg.wekit.data.KvStore
import dev.ujhhgtg.wekit.data.KvStore.prefOption
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.milliseconds

fun readReceiptNetworkFailureCategory(failure: Throwable): String = when (failure) {
    is SocketTimeoutException -> "timeout"
    is UnknownHostException -> "dns"
    is SSLException -> "tls"
    is ConnectException -> "connect"
    is IOException -> "io"
    else -> "response"
}

class ReadReceiptError(
    @StringRes private val id: Int,
    private vararg val formatArgs: Any,
) {
    fun message(context: Context): String = context.localizedChatString(id, *formatArgs)
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
    const val MODE_PASSIVE = 0

    /** 主动模式 (加号菜单): 长按发送按钮, 通过输入栏菜单主动发送 */
    const val MODE_ACTIVE_MENU = 1

    /** 主动模式 (触发前缀): 以触发前缀开头的文本替换为已读回执消息 */
    const val MODE_ACTIVE_PREFIX = 2

    // ── Preferences ─────────────────────────────────────────────────────────
    private var serializedConfiguration by prefOption("read_receipts_configuration", "")
    var sendMode by prefOption("read_receipts_send_mode", MODE_ACTIVE_MENU)
    var triggerPrefix by prefOption("read_receipts_trigger_prefix", "#rr")
    private var serializedRecords by prefOption("read_receipts_records", emptySet())

    private val configurationLock = Any()

    @Volatile
    private var loadedConfiguration: ReadReceiptsConfiguration? = null

    private const val RECORD_RETENTION_MILLIS = 180L * 24 * 60 * 60 * 1000
    private const val MAX_POLL_WORKERS = 4
    private const val MAX_FAILURE_BACKOFF_MILLIS = 5L * 60 * 1000
    private const val MAX_WX_ID_BYTES = 128
    private const val MAX_CONTENT_BYTES = 16 * 1024
    private const val MAX_REGISTRATION_BODY_BYTES = 20 * 1024

    fun configuration(): ReadReceiptsConfiguration {
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

    fun saveConfiguration(value: ReadReceiptsConfiguration) {
        val encoded = ReadReceiptsConfigurationCodec.encode(value)
        val canonical = ReadReceiptsConfigurationCodec.decode(encoded)!!
        synchronized(configurationLock) {
            serializedConfiguration = encoded
            loadedConfiguration = canonical
        }
    }

    private fun migrateLegacyConfiguration(): ReadReceiptsConfiguration {
        return ReadReceiptsConfiguration(
            thirdPartyUrl = normalizeThirdPartyReadReceiptEndpoint(
                KvStore.getStringOrDef("read_receipts_third_party_url", ""),
            ) ?: "",
            pollIntervalSecs = KvStore.getIntOrDef("read_receipts_poll_interval", 5)
                .takeIf { it > 0 } ?: 5,
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
    ): ReadReceiptError? {
        val bodyJson = buildJsonObject {
            put("wxId", wxId)
            put("content", content)
            put("createTime", createTime)
        }.toString()
        if (bodyJson.toByteArray(Charsets.UTF_8).size > MAX_REGISTRATION_BODY_BYTES) {
            return ReadReceiptError(
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
                        ReadReceiptError(R.string.read_receipts_registration_failed),
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
                                ReadReceiptError(
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
        val endpoint = record.endpoint
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

    /** Last successful count, isolated by historical server endpoint. */
    private val counts = ConcurrentHashMap<RecordKey, Int>()

    /** Attached message-root views and the exact tracked generation currently occupying each row. */
    private val activeViews = Collections.synchronizedMap(WeakHashMap<View, ActiveBinding>())
    private val backoffs = ConcurrentHashMap<RecordKey, PollBackoff>()
    private val pollWake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var featureScope: CoroutineScope? = null

    @Volatile
    private var pollJob: Job? = null

    private fun ReadReceiptRecord.key() = RecordKey(id, wxId, endpoint)

    private fun loadRecords() {
        val decoded = buildList {
            for (value in serializedRecords) {
                val record = ReadReceiptRecordCodec.decode(value)
                if (record == null) {
                    WeLogger.w(TAG, "discarding unsupported or malformed persisted read-receipt record")
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

            val endpoint = normalizeThirdPartyReadReceiptEndpoint(configuration().thirdPartyUrl)
            if (endpoint == null) {
                val error = ReadReceiptError(R.string.chat_read_receipts_server_missing)
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
                val error = ReadReceiptError(
                    R.string.read_receipts_sender_or_content_too_large,
                )
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

            val pixelUrl = "$endpoint/pixel?wxId=$selfWxId&amp;id=$id"

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
                endpoint = endpoint,
                createdAtMillis = createTime,
            )
            featureScope!!.launch {
                val registrationError = registerMessage(
                    endpoint,
                    selfWxId,
                    actualText,
                    createTime,
                )
                if (registrationError != null) {
                    withContext(Dispatchers.Main.immediate) {
                        if (!ReadReceipts.isActive) return@withContext
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
                        val error = ReadReceiptError(R.string.read_receipts_send_failed)
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

    fun testThirdPartyEndpoint(
        value: String,
        scope: CoroutineScope,
        onResult: (Result<Unit>) -> Unit,
    ): Job? {
        val endpoint = normalizeThirdPartyReadReceiptEndpoint(value) ?: return null
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
