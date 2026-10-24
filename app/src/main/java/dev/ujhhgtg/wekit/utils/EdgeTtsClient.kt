package dev.ujhhgtg.wekit.utils

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSocketException
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.io.path.outputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 逆向自开源项目 edge-tts (https://github.com/rany2/edge-tts) 的免费云端 TTS: 直接连接 Microsoft Edge 内置语音合成用的
 * WebSocket 接口,不需要 API Key, 服务端直接吐出 mp3 音频流。
 *
 * 协议细节参考了原库的 drm.py (Sec-MS-GEC 生成算法) 和 communicate.py (消息格式)。
 *
 * 已实现的健壮性处理:
 * - 长文本按 4096 字节分片、多轮请求后把音频顺序拼接;
 * - 收到 403 时,按服务器返回的 Date 头校正本地时钟偏差后重试一次。
 *
 * 未实现 (用不到): WordBoundary/SentenceBoundary 字幕时间戳元数据解析 (已在 speech.config 中禁用)。
 */
object EdgeTtsClient : AutoCloseable {

    private const val TAG = "EdgeTTS"
    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"

    // FIXME: remember to bump this version
    // 需要和 Edge 浏览器版本保持接近, edge-tts 原库会跟着 Chromium 版本定期更新, 过期太久可能导致服务端返回 403
    private const val CHROMIUM_FULL_VERSION = "150.0.4078.48"

    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
    private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"

    // 用来读取服务器时间 (Date 头) 以校正时钟偏差; 任意响应 (含 4xx) 都会带 Date 头。
    private const val VOICE_LIST_URL =
        "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/voices/list" +
            "?trustedclienttoken=$TRUSTED_CLIENT_TOKEN"

    // Windows 文件时间纪元 (1601-01-01) 相对 Unix 纪元 (1970-01-01) 的秒数偏移
    private const val WIN_EPOCH_SECONDS = 11_644_473_600L

    // 单次请求 SSML 文本内容的最大字节数 (原库常量), 超过则分片。
    private const val MAX_CHUNK_BYTES = 4096

    // 本地时钟相对服务器的偏差 (秒), 由 403 处理逻辑累加校正。原库 DRM.clock_skew_seconds。
    @Volatile private var clockSkewSeconds: Double = 0.0

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
            " (KHTML, like Gecko) Chrome/${CHROMIUM_FULL_VERSION.substringBefore('.')}.0.0.0" +
            " Safari/537.36 Edg/${CHROMIUM_FULL_VERSION.substringBefore('.')}.0.0.0"

    private val httpClient =
        HttpClient(CIO) {
            install(WebSockets)
        }

    /** 文本 -> mp3。长文本会按 4096 字节切成多段, 逐段建立 WebSocket 请求, 把每段收到的音频二进制帧顺序写入同一个文件, 拼起来就是完整 mp3。 */
    suspend fun synthesizeToMp3(
        text: String,
        outputMp3: Path,
        voice: String = "zh-CN-XiaoxiaoNeural",
        rate: String = "+0%",
        volume: String = "+0%",
        pitch: String = "+0Hz",
    ): Result<Path> = runCatching {
        WeLogger.d(TAG, "synthesis start codePoints=${text.codePointCount(0, text.length)}")
        withContext(Dispatchers.IO) {
            // 先清洗掉服务端不支持的控制字符, 再做 XML 转义, 最后按字节长度分片。
            // 顺序必须是"转义后再分片", 这样分片逻辑才能避免切断 &amp; 这类实体。
            val escaped = escapeXml(removeIncompatibleCharacters(text))
            val chunks = splitByByteLength(escaped.toByteArray(Charsets.UTF_8), MAX_CHUNK_BYTES)
            WeLogger.d(
                TAG,
                "synthesis prepared chunks=${chunks.size} thread=${Thread.currentThread().name}",
            )

            var audioReceived = false
            outputMp3.outputStream().use { output ->
                for (chunk in chunks) {
                    val chunkText = String(chunk, Charsets.UTF_8)
                    // 每段独立处理 403 重试, 与原库 Communicate.stream 的逐段循环一致。
                    if (synthesizeChunk(chunkText, voice, rate, volume, pitch, output)) {
                        audioReceived = true
                    }
                }
            }
            check(audioReceived) { "未收到任何音频数据, 可能是 Sec-MS-GEC 校验失败或网络问题" }
            WeLogger.i(TAG, "synthesis completed chunks=${chunks.size}")
            outputMp3
        }
    }
        .onFailure { error ->
            WeLogger.e(TAG, "synthesis failed", error)
        }

    /**
     * 合成单个分片。返回是否收到过音频数据。 若首次握手因 403 失败, 会按服务器 Date 头校正时钟偏差后重试一次 (对齐原库
     * handle_client_response_error)。
     */
    private suspend fun synthesizeChunk(
        chunkText: String,
        voice: String,
        rate: String,
        volume: String,
        pitch: String,
        output: java.io.OutputStream,
    ): Boolean {
        return try {
            streamChunk(chunkText, voice, rate, volume, pitch, output)
        } catch (e: WebSocketException) {
            // Ktor 在握手响应非 101 时抛 WebSocketException, message 形如
            // "Handshake exception, expected status code 101 but was 403"。
            // 这里只对 403 做时钟校正重试, 其它情况原样抛出。
            if (!e.isHandshake403()) throw e
            WeLogger.w(
                TAG,
                "WebSocket handshake returned 403; correcting clock skew and retrying",
                e,
            )
            correctClockSkewFromServer()
            streamChunk(chunkText, voice, rate, volume, pitch, output)
        }
    }

    /** 建立一次 WebSocket 连接, 发送配置 + SSML, 把音频帧写入 output。返回是否收到音频。 */
    private suspend fun streamChunk(
        chunkText: String,
        voice: String,
        rate: String,
        volume: String,
        pitch: String,
        output: java.io.OutputStream,
    ): Boolean {
        val requestId = UUID.randomUUID().toString().replace("-", "")
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val url =
            WSS_URL +
                "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
                "&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=${generateSecMsGec()}" +
                "&Sec-MS-GEC-Version=1-$CHROMIUM_FULL_VERSION"

        var audioReceived = false
        httpClient.webSocket(
            urlString = url,
            request = {
                header(HttpHeaders.Pragma, "no-cache")
                header(HttpHeaders.CacheControl, "no-cache")
                header(HttpHeaders.Origin, ORIGIN)
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Cookie, "muid=${generateMuid()};")
            },
        ) {
            WeLogger.d(TAG, "WebSocket connected")
            send(Frame.Text(buildSpeechConfigMessage()))
            send(Frame.Text(buildSsmlMessage(requestId, voice, rate, volume, pitch, chunkText)))

            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val headerBlock = frame.readText().substringBefore("\r\n\r\n")
                        if (parseHeaderBlock(headerBlock)["Path"] == "turn.end") break
                    }

                    is Frame.Binary -> {
                        val data = frame.data
                        if (data.size < 2) continue
                        // 二进制帧格式: [2字节大端长度][头部文本][音频数据]
                        val headerLength =
                            data[0].toInt() and 0xFF shl 8 or (data[1].toInt() and 0xFF)
                        // 帧被截断/损坏时长度字段会越界, 直接跳过, 否则 String(...) 会抛越界异常。
                        if (headerLength !in 0..(data.size - 2)) {
                            WeLogger.w(
                                TAG,
                                "skipping malformed binary frame: headerLength=$headerLength size=${data.size}",
                            )
                            continue
                        }
                        val headers =
                            parseHeaderBlock(
                                String(data, 2, headerLength, Charsets.UTF_8),
                            )
                        if (headers["Path"] == "audio") {
                            val audioStart = 2 + headerLength
                            if (audioStart < data.size) {
                                output.write(data, audioStart, data.size - audioStart)
                                audioReceived = true
                            }
                        }
                    }

                    else -> Unit
                }
            }
        }
        return audioReceived
    }

    private fun WebSocketException.isHandshake403(): Boolean = message?.contains("was 403") == true

    /**
     * 复刻 edge-tts 的 drm.py: 拿一个普通 HTTPS 请求的响应 Date 头当作服务器时间, 累加校正本地时钟偏差 clockSkewSeconds。之后
     * generateSecMsGec 会带上这个偏差。 与原库不同: Ktor 的 WebSocketException 不携带握手响应头, 所以这里单独发一个请求取 Date。
     */
    private suspend fun correctClockSkewFromServer() {
        val response: HttpResponse =
            httpClient.head(VOICE_LIST_URL) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }
        val serverDate =
            response.headers[HttpHeaders.Date]
                ?: throw IllegalStateException("服务器响应缺少 Date 头, 无法校正时钟偏差")
        val serverEpochSeconds =
            parseRfc1123Date(serverDate)
                ?: throw IllegalStateException("无法解析服务器 Date 头: $serverDate")
        val clientEpochSeconds = System.currentTimeMillis() / 1000.0 + clockSkewSeconds
        clockSkewSeconds += serverEpochSeconds - clientEpochSeconds
        WeLogger.i(TAG, "clock skew corrected")
    }

    /** 解析 RFC 1123 (RFC 2616) 日期字符串为 Unix 秒。例: "Wed, 21 Oct 2015 07:28:00 GMT"。 */
    private fun parseRfc1123Date(date: String): Double? =
        try {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("GMT")
            sdf.parse(date)?.time?.div(1000.0)
        } catch (_: java.text.ParseException) {
            null
        }

    /** 复刻 edge-tts 的 drm.py: DRM.generate_sec_ms_gec(), 带时钟偏差校正。 */
    private fun generateSecMsGec(): String {
        // 秒级 Unix 时间 + 时钟偏差, 再切到 Windows 文件时间纪元。
        var ticks =
            (System.currentTimeMillis() / 1000.0 + clockSkewSeconds).toLong() + WIN_EPOCH_SECONDS
        ticks -= ticks % 300 // 向下取整到最近的 5 分钟
        val windowsTicks = ticks * 10_000_000L // 秒 -> 100ns (Windows 文件时间单位)
        val toHash = "$windowsTicks$TRUSTED_CLIENT_TOKEN"
        val digest =
            MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun buildSpeechConfigMessage(): String =
        "X-Timestamp:${jsDateString()}\r\n" +
            "Content-Type:application/json; charset=utf-8\r\n" +
            "Path:speech.config\r\n\r\n" +
            """{"context":{"synthesis":{"audio":{"metadataoptions":{"sentenceBoundaryEnabled":"false","wordBoundaryEnabled":"false"},"outputFormat":"audio-24khz-48kbitrate-mono-mp3"}}}}"""

    /** 注意: chunkText 已在调用方完成 XML 转义, 这里不再重复转义。 */
    private fun buildSsmlMessage(
        requestId: String,
        voice: String,
        rate: String,
        volume: String,
        pitch: String,
        chunkText: String,
    ): String {
        val ssml =
            "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                "<voice name='$voice'><prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
                chunkText +
                "</prosody></voice></speak>"
        return "X-RequestId:$requestId\r\n" +
            "Content-Type:application/ssml+xml\r\n" +
            "X-Timestamp:${jsDateString()}Z\r\n" +
            "Path:ssml\r\n\r\n" +
            ssml
    }

    private fun jsDateString(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return "${sdf.format(Date())} GMT+0000 (Coordinated Universal Time)"
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /**
     * 复刻 edge-tts 的 remove_incompatible_characters: 服务端不接受部分控制字符 (最典型的是垂直制表符, 常见于 OCR 出来的 PDF),
     * 统一替换成空格。
     */
    private fun removeIncompatibleCharacters(text: String): String =
        buildString(text.length) {
            for (c in text) {
                val code = c.code
                if (code in 0..8 || code in 11..12 || code in 14..31) append(' ') else append(c)
            }
        }

    /**
     * 复刻 edge-tts 的 split_text_by_byte_length: 把 UTF-8 字节按不超过 byteLength 分片, 优先在换行/空格处断开, 否则退到合法的
     * UTF-8 边界, 并避免切断 &amp; 这类 XML 实体。
     *
     * 修正了原库的一处逻辑错误: 当限长内既无换行也无空格时 (中文长文本的常见情形), 原库把整段 text 传给 UTF-8 边界查找, 导致返回整段长度而永远不分片; 这里改为只在
     * text[0, byteLength] 范围内查找边界。
     */
    private fun splitByByteLength(text: ByteArray, byteLength: Int): List<ByteArray> {
        require(byteLength > 0) { "byteLength must be greater than 0" }
        val result = mutableListOf<ByteArray>()
        var remaining = text
        while (remaining.size > byteLength) {
            var splitAt = findLastNewlineOrSpace(remaining, byteLength)
            if (splitAt <= 0) {
                // 限长内无换行/空格, 或唯一的空白恰好在第 0 字节 (断在 0 处不会有任何进展):
                // 在 [0, byteLength] 内退到合法 UTF-8 边界。
                splitAt = findSafeUtf8SplitPoint(remaining, byteLength)
            }
            splitAt = adjustSplitPointForXmlEntity(remaining, splitAt)
            check(splitAt > 0) {
                "分片失败: byteLength 太小, 或 '&' 附近的文本结构 / UTF-8 编码异常"
            }
            val chunk = remaining.copyOfRange(0, splitAt).trimAscii()
            if (chunk.isNotEmpty()) result.add(chunk)
            // 断点处的空白留在了下一段开头, 先去掉, 否则下一轮的 findLastNewlineOrSpace 又会返回 0。
            remaining = remaining.copyOfRange(splitAt, remaining.size).trimAsciiStart()
        }
        val last = remaining.trimAscii()
        if (last.isNotEmpty()) result.add(last)
        // 极端情况下 (整段都是空白) 也要保证至少走一次请求逻辑由上层的 audioReceived 兜底,
        // 这里若为空直接返回原文一份, 交给服务端处理。
        return if (result.isEmpty()) listOf(text) else result
    }

    /** 在 [0, limit) 内找最靠右的换行, 没有再找空格。返回索引, 找不到返回 -1。 */
    private fun findLastNewlineOrSpace(text: ByteArray, limit: Int): Int {
        val bound = minOf(limit, text.size)
        for (i in bound - 1 downTo 0) if (text[i] == '\n'.code.toByte()) return i
        for (i in bound - 1 downTo 0) if (text[i] == ' '.code.toByte()) return i
        return -1
    }

    /** 在 [0, limit] 内找最大的、使 text[0, n) 为合法 UTF-8 的 n。 */
    private fun findSafeUtf8SplitPoint(text: ByteArray, limit: Int): Int {
        var splitAt = minOf(limit, text.size)
        while (splitAt > 0) {
            // text[0, splitAt) 为完整合法 UTF-8 时即可断开; 否则末尾切断了多字节字符, 往前退。
            if (isValidUtf8Prefix(text, splitAt)) return splitAt
            splitAt--
        }
        return splitAt
    }

    /** 判断 text[0, len) 是否为完整合法的 UTF-8 (末尾不含被截断的多字节字符)。 */
    private fun isValidUtf8Prefix(text: ByteArray, len: Int): Boolean {
        var i = 0
        while (i < len) {
            val b = text[i].toInt() and 0xFF
            val seqLen =
                when {
                    b and 0x80 == 0x00 -> 1
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> return false
                }
            if (i + seqLen > len) return false
            for (j in 1 until seqLen) {
                if (text[i + j].toInt() and 0xC0 != 0x80) return false
            }
            i += seqLen
        }
        return true
    }

    /** 若 splitAt 落在未闭合的 XML 实体 (形如 &amp;) 中间, 把断点回退到 '&' 之前。 */
    private fun adjustSplitPointForXmlEntity(text: ByteArray, splitAt: Int): Int {
        var at = splitAt
        while (at > 0) {
            val amp = lastIndexOf(text, '&'.code.toByte(), at)
            if (amp < 0) break
            // '&' 与断点之间存在 ';' 说明实体已闭合, 可安全在原断点断开。
            if (indexOf(text, ';'.code.toByte(), amp, at) != -1) break
            at = amp // 实体未闭合, 断点回退到 '&'。
        }
        return at
    }

    private fun lastIndexOf(text: ByteArray, target: Byte, endExclusive: Int): Int {
        for (i in endExclusive - 1 downTo 0) if (text[i] == target) return i
        return -1
    }

    private fun indexOf(text: ByteArray, target: Byte, start: Int, endExclusive: Int): Int {
        for (i in start until endExclusive) if (text[i] == target) return i
        return -1
    }

    private fun isAsciiWhitespace(b: Byte): Boolean =
        b == ' '.code.toByte() ||
            b == '\t'.code.toByte() ||
            b == '\n'.code.toByte() ||
            b == '\r'.code.toByte()

    /** 去掉两端 ASCII 空白 (空格/制表符/换行/回车), 对齐原库 bytes.strip()。 */
    private fun ByteArray.trimAscii(): ByteArray {
        var start = 0
        var end = size
        while (start < end && isAsciiWhitespace(this[start])) start++
        while (end > start && isAsciiWhitespace(this[end - 1])) end--
        return copyOfRange(start, end)
    }

    /** 只去掉开头的 ASCII 空白, 对齐原库 bytes.lstrip()。 */
    private fun ByteArray.trimAsciiStart(): ByteArray {
        var start = 0
        while (start < size && isAsciiWhitespace(this[start])) start++
        return if (start == 0) this else copyOfRange(start, size)
    }

    private fun parseHeaderBlock(block: String): Map<String, String> =
        block
            .lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
            }
            .toMap()

    override fun close() = httpClient.close()
}
