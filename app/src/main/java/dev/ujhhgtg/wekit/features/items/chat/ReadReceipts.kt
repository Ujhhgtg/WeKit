package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageViewApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashSet
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@Feature(name = "已读追踪", categories = ["聊天"], description = "追踪文本消息已读人数, 并在自己发送的消息上实时显示\"已读 x 人\"")
object ReadReceipts : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "ReadReceipts"

    // ── Preferences ─────────────────────────────────────────────────────────
    private var prefix by prefOption("read_receipts_prefix", "#")
    private var server by prefOption("read_receipts_server", "")
    private var pollIntervalSecs by prefOption("read_receipts_poll_interval", 5)

    /** Normalized server base URL with any trailing slash removed. */
    private val serverBase: String get() = server.trimEnd('/')

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

    /**
     * Resolves the current text in the chat input bar.
     *
     * Since WeChat 8.0.77 the `ChatFooter.lastText` getter can return an incomplete
     * value (e.g. only the bare trigger prefix `#` instead of `#雷猴`), which caused
     * empty `content` to be registered on the server. To work around that, we prefer
     * reading the real input EditText — the field exposed by the input-bar API is the
     * field whose type is an interface declaring `addTextChangedListener` (fl5.i) —
     * and fall back to `lastText` only when the real input can't be read.
     */
    private fun resolveInputText(chatFooter: ChatFooter): String {
        val fromRealInput = runCatching {
            val input = chatFooter.reflekt().firstField {
                type { clazz ->
                    clazz.isInterface && clazz.declaredMethods.any { it.name == "addTextChangedListener" }
                }
            }.get()
            (input as? android.widget.EditText)?.text?.toString()
        }.getOrNull()
        if (!fromRealInput.isNullOrBlank()) return fromRealInput
        return chatFooter.lastText
    }

    /**
     * Resolves this device's own WeChat nickname, so a read report can tell the
     * server exactly WHO opened a probing message (reader wxid + nickname).
     * WeChat stores the self contact row in `rcontact` with username = the value
     * in `userinfo` id 2 (the logged-in wxid).
     */
    private fun resolveSelfNickname(): String {
        return try {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT nickname FROM rcontact WHERE username = (SELECT value FROM userinfo WHERE id = 2) LIMIT 1",
                null
            )
            cursor.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else ""
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "resolveSelfNickname failed", e)
            ""
        }
    }

    /** Recently reported message ids (bounded), so a single message is only reported once. */
    private val reportedReads = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * Fired when THIS device renders an INCOMING message that carries one of our
     * tracking pixels — i.e. the receiver is looking at the probing message.
     * Reports the reader's own wxid + nickname to the server, so each probed IP
     * can be labelled with WHO read it (this also covers group chats, where the
     * pixel URL alone can only name the room).
     */
    private fun reportRead(senderWxId: String, id: String) {
        if (serverBase.isEmpty()) return
        if (!reportedReads.add(id)) return
        if (reportedReads.size > 200) {
            synchronized(reportedReads) {
                val it = reportedReads.iterator()
                if (it.hasNext()) { it.next(); it.remove() }
            }
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val body = buildJsonObject {
                    put("msgId", id)
                    put("senderWxId", senderWxId)
                    put("readerWxId", WeApi.selfWxId)
                    put("readerNickname", resolveSelfNickname())
                }.toString().toRequestBody(jsonMediaType)
                val request = Request.Builder().url("$serverBase/read-report").post(body).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) WeLogger.w(TAG, "read-report failed: HTTP ${resp.code}")
                }
            }.onFailure { WeLogger.w(TAG, "read-report request failed", it) }
        }
    }

    /** Fire-and-forget registration of the plaintext content so the server can match reads to it. */
    private fun registerMessage(wxId: String, content: String, createTime: Long, talker: String = "", chatName: String = "") {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val body = buildJsonObject {
                    put("wxId", wxId)
                    put("content", content)
                    put("createTime", createTime)
                    put("talker", talker)
                    put("chatName", chatName)
                }.toString().toRequestBody(jsonMediaType)
                val request = Request.Builder().url("$serverBase/register").post(body).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) WeLogger.w(TAG, "register failed: HTTP ${resp.code}")
                }
            }.onFailure { WeLogger.w(TAG, "register request failed", it) }
        }
    }

    /**
     * Resolves a human-readable name for the conversation the tracked message is
     * sent into, so the dashboard can label each message with where it came from:
     * - direct chat: remark name (备注) if set, otherwise the contact nickname;
     * - group chat: the group name (WeChat stores it as the room contact nickname).
     * Returns "" when the talker is blank or the contact row is missing.
     */
    private fun resolveChatName(talker: String): String {
        if (talker.isBlank()) return ""
        return try {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT nickname, conRemark FROM rcontact WHERE username = ? LIMIT 1",
                arrayOf<Any>(talker)
            )
            cursor.use { c ->
                if (!c.moveToFirst()) return@use ""
                val nickname = if (!c.isNull(0)) c.getString(0) else ""
                val remark = if (!c.isNull(1)) c.getString(1) else ""
                remark.ifBlank { nickname }
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "resolveChatName failed for $talker", e)
            ""
        }
    }

    /** Queries the distinct-IP read count for a (wxId, id) pair. Returns null on any failure. */
    private fun fetchCount(wxId: String, id: String): Int? {
        return runCatching {
            val url = "$serverBase/count?wxId=$wxId&id=$id"
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body.string()
                DefaultJson.parseToJsonElement(text).jsonObject["count"]?.jsonPrimitive?.content?.toIntOrNull()
            }
        }.getOrNull()
    }

    // ── Live "已读 x 人" state ─────────────────────────────────────────────────

    /**
     * Integer tag key stamped onto a tracked message's [TextView] so an in-flight poll can detect
     * that the view was recycled to a different message before posting its update.
     * In the 0x7E… range to avoid collisions with Android R.id values (0x7F…).
     */
    private const val VIEW_TAG_ID = 0x7E000002

    /** Marker prefixing the injected read-count text, so we can strip a stale suffix before re-appending. */
    private const val COUNT_MARKER = "​ | 已读 "

    /** msgId → distinct-IP read count, last known. Drives instant render on (re)bind. */
    private val counts = ConcurrentHashMap<String, Int>()

    /** Currently bound tracked timeTVs → their msgId. Weak so recycled views are collected. */
    private val activeViews = Collections.synchronizedMap(WeakHashMap<TextView, TrackedRef>())

    private data class TrackedRef(val wxId: String, val id: String)

    @Volatile
    private var pollJob: Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatInputBarMenuApi.methodSendMessage.hookBefore(100) {
            val chatFooter = thisObject!!.reflekt().firstField {
                type = ChatFooter::class
            }.get()!! as ChatFooter

            val text = resolveInputText(chatFooter)
            if (!text.startsWith(prefix)) return@hookBefore

            if (serverBase.isEmpty()) {
                showToast(chatFooter.context, "错误: 已读追踪未设置服务器!")
                return@hookBefore
            }

            val actualText = text.removePrefix(prefix)

            // Only the bare prefix (e.g. "#" with nothing after it) would produce
            // an empty tracked message; refuse it instead of registering empty
            // content that shows up as a blank row in the dashboard.
            if (actualText.isBlank()) {
                showToast(chatFooter.context, "已读追踪: 请输入要追踪的文字内容")
                return@hookBefore
            }

            val selfWxId = WeApi.selfWxId
            // Assigned now (epoch millis) so two identical-text messages get distinct ids.
            val createTime = System.currentTimeMillis()
            val id = computeId(selfWxId, actualText, createTime)

            // Which chat is this being sent into? Drives the 私聊/群聊 badge and the
            // conversation name shown on the dashboard.
            val talker = WeCurrentConversationApi.value
            val chatName = resolveChatName(talker)

            // Record the plaintext content server-side (idempotent); the id is derived locally so
            // polling never depends on this call succeeding.
            registerMessage(selfWxId, actualText, createTime, talker, chatName)

            // The pixel URL also carries the conversation context (talker = the chat id /
            // peer wxid, chatName = human-readable conversation name). The server stores
            // these on each read so the dashboard can label every probed IP with the
            // conversation it was read in. Names are URL-encoded so CJK/special chars
            // survive inside the XML-embedded URL.
            //
            // NOTE: the URL is embedded into an XML app message, so every `&` MUST be
            // written as `&amp;` — a bare `&` makes the XML invalid and WeChat silently
            // drops the message (that was the "message blocked" bug).
            val pixelUrl = buildString {
                append("$serverBase/pixel?wxId=$selfWxId&amp;id=$id")
                if (talker.isNotBlank()) {
                    append("&amp;talker=").append(URLEncoder.encode(talker, "UTF-8"))
                }
                if (chatName.isNotBlank()) {
                    append("&amp;chatName=").append(URLEncoder.encode(chatName, "UTF-8"))
                }
            }

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

            WeMessageApi.sendXmlAppMsg(target, xml)
            showToast(chatFooter.context, "已发送附带已读追踪的消息")

            chatFooter.lastText = ""

            result = null
        }

        WeChatMessageViewApi.addListener(this)
        startPolling()
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        pollJob?.cancel()
        pollJob = null
        activeViews.clear()
        counts.clear()
    }

    // ── View listener: detect tracked self-messages and render the count ───────

    /** Pulls `wxId` and `id` out of an embedded `/pixel?wxId=..&id=..` URL, tolerating `&`/`&amp;`. */
    private val pixelParamRegex =
        Regex("""/pixel\?wxId=([^&"<\s]+)(?:&amp;|&)id=([0-9a-fA-F]+)""")

    override fun onCreateView(param: HookParam, view: View) {
        val msgInfo = WeChatMessageViewApi.getMsgInfoFromParam(param)
        val content = runCatching { msgInfo.content }.getOrNull() ?: return
        val match = pixelParamRegex.find(content) ?: return
        val (wxId, id) = match.destructured

        // Incoming message that carries one of our pixels → I (this device) am the
        // reader: report my wxid + nickname so the dashboard can label the probed
        // IP with WHO read it. Only outgoing messages render the live count.
        if (msgInfo.isSend == 0) {
            reportRead(wxId, id)
            return
        }

        val tag = view.tag ?: return
        val timeTV = tag.reflekt()
            .firstField { name = "timeTV"; superclass() }
            .get() as? TextView? ?: return

        timeTV.setTag(VIEW_TAG_ID, id)
        activeViews[timeTV] = TrackedRef(wxId, id)

        // Instant render from cache; the poll loop keeps it fresh.
        counts[id]?.let { applyCount(timeTV, id, it) }
    }

    /** Appends/refreshes the " · 已读 x 人" suffix on [timeTV], coexisting with MessageTimeEnhancements. */
    @SuppressLint("SetTextI18n")
    private fun applyCount(timeTV: TextView, id: String, count: Int) {
        if (timeTV.getTag(VIEW_TAG_ID) != id) return
        val base = (timeTV.text ?: "").toString().substringBefore(COUNT_MARKER)
        timeTV.text = "$base$COUNT_MARKER$count 人"
        timeTV.visibility = View.VISIBLE
    }

    // ── Poll loop ──────────────────────────────────────────────────────────────

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                // Snapshot the distinct (wxId, id) pairs currently on screen.
                val refs: Set<TrackedRef> = synchronized(activeViews) { HashSet(activeViews.values) }
                for (ref in refs) {
                    val count = fetchCount(ref.wxId, ref.id) ?: continue
                    val prev = counts.put(ref.id, count)
                    if (prev != count) {
                        // Refresh every on-screen view bound to this id.
                        val targets = synchronized(activeViews) {
                            activeViews.entries.filter { it.value.id == ref.id }.map { it.key }
                        }
                        for (tv in targets) mainHandler.post { applyCount(tv, ref.id, count) }
                    }
                }
                delay((pollIntervalSecs.coerceAtLeast(1) * 1000L).milliseconds)
            }
        }
    }

    // ── Settings dialog ─────────────────────────────────────────────────────────

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var serverInput by remember { mutableStateOf(server) }
            var prefixInput by remember { mutableStateOf(prefix) }
            var intervalInput by remember { mutableStateOf(pollIntervalSecs.toString()) }

            AlertDialogContent(
                title = { Text("已读追踪") },
                text = {
                    DefaultColumn {
                        TextField(
                            value = serverInput,
                            onValueChange = { serverInput = it },
                            label = { Text("服务器") },
                            modifier = Modifier.fillMaxWidth()
                        )
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
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        if (serverInput.isBlank()) {
                            showToast(context, "错误: 未设置服务器!")
                            return@Button
                        }
                        server = serverInput

                        if (prefixInput.isEmpty()) {
                            showToast(context, "警告: 「触发前缀」为空, 所有文本消息将启用已读追踪!")
                        }
                        prefix = prefixInput

                        val interval = intervalInput.toIntOrNull()
                        if (interval == null || interval <= 0) {
                            showToast(context, "错误: 轮询间隔格式不正确!")
                            return@Button
                        }
                        pollIntervalSecs = interval

                        onDismiss()
                    }) { Text("确定") }
                })
        }
    }
}

