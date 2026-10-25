package dev.ujhhgtg.wekit.utils

/**
 * Microsoft Edge 云端 TTS 的 `Sec-MS-GEC` v2 token 生成器。
 *
 * 移植自 edge-tts >= 7.3.0 的 `edge_tts/drm.py`。旧版 v1 token 只有一个 64 位十六进制 SHA-256 摘要, 服务端现在会直接以 403
 * 拒绝握手; v2 需要发送 `<16 位十六进制 CS64>-<64 位十六进制 SHA-256>` 形式的 token, 并把 `Sec-MS-GEC-Version` 的版本方案前缀改成
 * `2-` (见 [EdgeTtsClient])。
 *
 * 时间基准是 Windows 文件时间 (100ns ticks, 1601-01-01 起), 向下取整到最近的 5 分钟窗口; 计算时把 "ticks + trusted client
 * token" 同时喂给一个魔改过的 SHA-256 (替换了初始状态 / 消息调度 / 一个轮常量) 和一个 CS64 混合摘要。算法与上游逐位一致, 由 `EdgeTtsDrmTest`
 * 里的固定向量保证。
 */
object EdgeTtsDrm {
    // Windows 文件时间纪元 (1601-01-01) 相对 Unix 纪元 (1970-01-01) 的秒数偏移。
    private const val WIN_EPOCH_SECONDS = 11_644_473_600.0

    // token 的有效期窗口 (秒); 原库 ticks -= ticks % 300。
    private const val TOKEN_WINDOW_SECONDS = 300.0

    // Windows 文件时间每 100ns 一个 tick; 先乘 1e7 再格式化, 与原库 S_TO_NS / 100 一致。
    private const val TICKS_PER_SECOND = 10_000_000.0

    // 标准 SHA-256 的 8 个初始哈希字 (FIPS 180-4)。
    private val SHA256_INITIAL =
        intArrayOf(
            0x6A09E667,
            0xBB67AE85.toInt(),
            0x3C6EF372,
            0xA54FF53A.toInt(),
            0x510E527F,
            0x9B05688C.toInt(),
            0x1F83D9AB,
            0x5BE0CD19,
        )

    // 魔改 SHA-256 的初始状态 (上游以硬编码 midstate 替换了标准 IV)。
    private val SHA256_STATE =
        intArrayOf(
            0xFD9FBEA2.toInt(),
            0x91243CE7.toInt(),
            0xBE499094.toInt(),
            0xCD2E1E0A.toInt(),
            0x6C006F49,
            0x04BD35AB,
            0xFEB0A7E4.toInt(),
            0x03EFFBD2,
        )

    // 消息调度前 14 个字也是硬编码的, 用来预热第一个 block 的 W[16..29]。
    private val SHA256_SCHEDULE =
        intArrayOf(
            0xEB24BF9E.toInt(),
            0x1558691F,
            0xA0C09EE2.toInt(),
            0x0CFA32BA,
            0xA622EE8C.toInt(),
            0xD1DD9491.toInt(),
            0xC0DD25A0.toInt(),
            0xF9E70AB6.toInt(),
            0xF0C31573.toInt(),
            0x7058CE4E,
            0x7188E606,
            0x00E5AB33,
            0xD258B060.toInt(),
            0x72766564,
        )

    // 标准 SHA-256 轮常量 K (FIPS 180-4)。
    private val SHA256_ROUND_CONSTANTS =
        intArrayOf(
            0x428A2F98,
            0x71374491,
            0xB5C0FBCF.toInt(),
            0xE9B5DBA5.toInt(),
            0x3956C25B,
            0x59F111F1,
            0x923F82A4.toInt(),
            0xAB1C5ED5.toInt(),
            0xD807AA98.toInt(),
            0x12835B01,
            0x243185BE,
            0x550C7DC3,
            0x72BE5D74,
            0x80DEB1FE.toInt(),
            0x9BDC06A7.toInt(),
            0xC19BF174.toInt(),
            0xE49B69C1.toInt(),
            0xEFBE4786.toInt(),
            0x0FC19DC6,
            0x240CA1CC,
            0x2DE92C6F,
            0x4A7484AA,
            0x5CB0A9DC,
            0x76F988DA,
            0x983E5152.toInt(),
            0xA831C66D.toInt(),
            0xB00327C8.toInt(),
            0xBF597FC7.toInt(),
            0xC6E00BF3.toInt(),
            0xD5A79147.toInt(),
            0x06CA6351,
            0x14292967,
            0x27B70A85,
            0x2E1B2138,
            0x4D2C6DFC,
            0x53380D13,
            0x650A7354,
            0x766A0ABB,
            0x81C2C92E.toInt(),
            0x92722C85.toInt(),
            0xA2BFE8A1.toInt(),
            0xA81A664B.toInt(),
            0xC24B8B70.toInt(),
            0xC76C51A3.toInt(),
            0xD192E819.toInt(),
            0xD6990624.toInt(),
            0xF40E3585.toInt(),
            0x106AA070,
            0x19A4C116,
            0x1E376C08,
            0x2748774C,
            0x34B0BCB5,
            0x391C0CB3,
            0x4ED8AA4A,
            0x5B9CCA4F,
            0x682E6FF3,
            0x748F82EE,
            0x78A5636F,
            0x84C87814.toInt(),
            0x8CC70208.toInt(),
            0x90BEFFFA.toInt(),
            0xA4506CEB.toInt(),
            0xBEF9A3F7.toInt(),
            0xC67178F2.toInt(),
        )

    // 第一 block 的第 14 轮改用这个常量, 并注入专门的初始状态。
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7.toInt()

    // 消息调度预热的两个调整值, 分别在第 16 / 第 29 个字上异或。
    private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80
    private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0

    // CS64 混合摘要的 14 组系数与轮偏移。
    private val CS64_COEFFICIENTS =
        intArrayOf(
            0xE82E91B1.toInt(),
            0x44DE24C6,
            0x3BEB65BC,
            0xC89C5933.toInt(),
            0x8D1EC211.toInt(),
            0x314EF5D5,
            0xE92491B1.toInt(),
            0xF0A5B9D5.toInt(),
            0x06064CF3,
            0xDA79FA60.toInt(),
            0xBED20560.toInt(),
            0x89F9C9F8.toInt(),
            0x35FA38C6,
            0xCB49E3C6.toInt(),
        )

    private val CS64_OFFSETS =
        intArrayOf(
            0xB32700D0.toInt(),
            0x88DFF51D.toInt(),
            0x29514038,
            0xB328CBDF.toInt(),
            0xD405B4D5.toInt(),
            0x26E55F3C,
            0x607801C6,
            0x82B2C1FB.toInt(),
            0xFA51A3DA.toInt(),
            0x207CC464,
            0x3C3C74F3,
            0xD2D14B61.toInt(),
            0xBB04A2C1.toInt(),
            0xC5626167.toInt(),
        )

    private const val CS64_FACTOR = 0x82893933.toInt()
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1A.toInt()
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000

    // 本地时钟相对服务器的偏差 (秒), 由 403 处理逻辑累加校正。对应原库 DRM.clock_skew_seconds。
    @Volatile private var clockSkewSeconds: Double = 0.0

    /** 累加本地时钟相对服务器的偏差 (秒), 对齐原库 `DRM.adj_clock_skew_seconds`。 */
    fun adjustClockSkewSeconds(skewSeconds: Double) {
        clockSkewSeconds += skewSeconds
    }

    /** 当前 Unix 时间 (秒, 带时钟偏差校正), 对齐原库 `DRM.get_unix_timestamp`。 */
    fun getUnixTimestamp(): Double = System.currentTimeMillis() / 1000.0 + clockSkewSeconds

    /**
     * 生成 v2 `Sec-MS-GEC` token: `<16 位 CS64>-<64 位 SHA-256>`, 对齐原库 `generate_sec_ms_gec_v2`。
     *
     * @param trustedClientToken 固定可信 token; WeKit 用 [EdgeTtsClient.TRUSTED_CLIENT_TOKEN]。
     * @param unixTimestamp 仅测试用: 指定时间戳替代当前时间。
     */
    fun generateSecMsGec(trustedClientToken: String, unixTimestamp: Double? = null): String {
        var ticks = (unixTimestamp ?: getUnixTimestamp()) + WIN_EPOCH_SECONDS
        ticks -= ticks % TOKEN_WINDOW_SECONDS
        // 原库此处是 f"{ticks:.0f}"; 乘 1e7 后数值已超过 2^53, double 必然是整数, 直接截断结果相同。
        val data =
            "${(ticks * TICKS_PER_SECOND).toLong()}$trustedClientToken"
                .toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val cs64 = cs64Digest(data, digest)
        return "${hexLeWord(cs64[0])}${hexLeWord(cs64[1])}-${digest.toHexUpper()}"
    }

    /** 复刻原库 `_sha256_digest`: 前 7 字节决定定制 SHA-256 的初始状态, 其余字节按魔改调度压缩。 */
    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "token salt must contain at least seven bytes" }
        val length = 57 + data.size
        val tail =
            ByteArray(
                data.size - 7 + 1 + Math.floorMod(55 - length, 64) + 8,
            )
        data.copyInto(tail, destinationOffset = 0, startIndex = 7, endIndex = data.size)
        tail[data.size - 7] = 0x80.toByte()
        val bitLength = length.toLong() * 8
        for (index in 0 until 8) {
            tail[tail.size - 8 + index] = (bitLength ushr (7 - index) * 8).toByte()
        }

        var state = sha256Init(data)
        val words = IntArray(64)
        for (blockStart in tail.indices step 64) {
            for (index in 0 until 16) words[index] = tail.readBigEndianInt(blockStart + index * 4)
            expandSha256Schedule(words)
            val working = sha256Rounds(state, words)
            state = IntArray(8) { state[it] + working[it] }
        }

        val digest = ByteArray(32)
        for (index in 0 until 8) {
            val word = state[index]
            digest[index * 4] = (word ushr 24).toByte()
            digest[index * 4 + 1] = (word ushr 16).toByte()
            digest[index * 4 + 2] = (word ushr 8).toByte()
            digest[index * 4 + 3] = word.toByte()
        }
        return digest
    }

    /** 复刻原库 `_sha256_init`: 用硬编码调度表预热第一个 block, 并从第 14 轮开始压缩。 */
    private fun sha256Init(data: ByteArray): IntArray {
        val words = IntArray(64)
        words[14] =
            data[0].toInt() and 0xFF shl 16 or
                (data[1].toInt() and 0xFF shl 8) or
                (data[2].toInt() and 0xFF)
        words[15] =
            data[3].toInt() and 0xFF shl 24 or
                (data[4].toInt() and 0xFF shl 16) or
                (data[5].toInt() and 0xFF shl 8) or
                (data[6].toInt() and 0xFF)

        for (index in 16..29) {
            var value = SHA256_SCHEDULE[index - 16]
            val sigma = sigma1(words[index - 2])
            value += if (index == 16) sigma xor SHA256_SIGMA1_ADJUSTMENT else sigma
            if (index - 7 >= 14) value += words[index - 7]
            if (index - 15 >= 14) {
                val sigma = sigma0(words[index - 15])
                value += sigma xor SHA256_SIGMA0_ADJUSTMENT
            }
            words[index] = value
        }
        words[30] =
            sigma1(words[28]) + words[23] + sigma0(words[15]) + words[14] + SHA256_ADJUSTMENT -
                SHA256_ROUND_CONSTANTS[14]
        expandSha256Schedule(words, firstWord = 31)

        val working = sha256Rounds(SHA256_STATE, words, firstRound = 14, blockConstants = true)
        return IntArray(8) { SHA256_INITIAL[it] + working[it] }
    }

    /** 标准 SHA-256 轮函数; `firstRound` / `blockConstants` 用于从定制 midstate 续算。 */
    private fun sha256Rounds(
        state: IntArray,
        words: IntArray,
        firstRound: Int = 0,
        blockConstants: Boolean = false,
    ): IntArray {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]
        for (index in firstRound until 64) {
            val constant =
                if (blockConstants && index == 14) SHA256_ADJUSTMENT
                else SHA256_ROUND_CONSTANTS[index]
            val sum1 =
                Integer.rotateRight(e, 6) xor
                    Integer.rotateRight(e, 11) xor
                    Integer.rotateRight(e, 25)
            val choice = e and f xor (e.inv() and g)
            val temp1 = h + sum1 + choice + constant + words[index]
            val sum0 =
                Integer.rotateRight(a, 2) xor
                    Integer.rotateRight(a, 13) xor
                    Integer.rotateRight(a, 22)
            val majority = a and b xor (a and c) xor (b and c)
            val temp2 = sum0 + majority
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    /** 标准 SHA-256 消息调度扩展 (原库 `_expand_sha256_schedule`)。 */
    private fun expandSha256Schedule(words: IntArray, firstWord: Int = 16) {
        for (index in firstWord until 64) {
            words[index] =
                words[index - 16] +
                    sigma0(words[index - 15]) +
                    words[index - 7] +
                    sigma1(words[index - 2])
        }
    }

    /** SHA-256 小写 sigma0 (standard: ROTR 7 ^ ROTR 18 ^ SHR 3)。 */
    private fun sigma0(value: Int): Int =
        Integer.rotateRight(value, 7) xor Integer.rotateRight(value, 18) xor (value ushr 3)

    /** SHA-256 小写 sigma1 (standard: ROTR 17 ^ ROTR 19 ^ SHR 10)。 */
    private fun sigma1(value: Int): Int =
        Integer.rotateRight(value, 17) xor Integer.rotateRight(value, 19) xor (value ushr 10)

    /** 复刻原库 `_cs64_digest`: 用 SHA-256 摘要的前两个字作 key 做一轮混合。 */
    private fun cs64Digest(data: ByteArray, digest: ByteArray): IntArray {
        val keyA = digest.readLittleEndianInt(0) or 1
        val keyB = digest.readLittleEndianInt(4) or 1
        val factorA = keyA * CS64_FACTOR
        val factorB = keyB * CS64_FACTOR

        // 和原库一致: 先放 14 个固定项, 再追加 (0x00 + data) 按 little-endian 4 字节切出的每个字。
        val words = wordsLe4Even(byteArrayOf(0) + data)
        val termCount = CS64_COEFFICIENTS.size + words.size
        val terms = IntArray(termCount)
        val offsets = IntArray(termCount)
        for (index in CS64_COEFFICIENTS.indices) {
            terms[index] = CS64_COEFFICIENTS[index] * if (index % 2 == 0) factorA else factorB
            offsets[index] = CS64_OFFSETS[index]
        }
        for (index in words.indices) {
            val target = CS64_COEFFICIENTS.size + index
            var term = words[index] * if (index % 2 == 0) keyA else keyB
            var offset =
                rotateLeft16(words[index]) *
                        if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt()
            if (index == 0) {
                term += CS64_ADJUSTMENT0 * factorA
                offset += CS64_ADJUSTMENT1
            }
            terms[target] = term
            offsets[target] = offset
        }

        var swapAcc = 0
        var swapLast = 0
        var state = 0
        var acc = 0
        var index = 0
        while (index < termCount) {
            val x = terms[index]
            val y = terms[index + 1]

            var t = rotateLeft16(offsets[index] + x)
            var q = t * 0x76F50F34
            swapAcc += rotateLeft16(q) * 0xC3EFEA97.toInt() + q
            t = rotateLeft16(offsets[index + 1] + y)
            q = t * 0x1881CE3C.toInt()
            swapLast = rotateLeft16(q) * 0x35BD1EC9.toInt() + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rotateLeft16(state) * 0xCF98B111.toInt()
            state = rotateLeft16(state) * 0x87085B9F.toInt()
            state = rotateLeft16(state) * 0x12CEB96D
            state = rotateLeft16(state) * 0x257E1D83
            acc += state
            t = state * keyB + y
            t = rotateLeft16(t) * 0xA27416F5.toInt()
            t = rotateLeft16(t) * 0xD38396FF.toInt()
            state = rotateLeft16(rotateLeft16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state

            index += 2
        }
        return intArrayOf(swapLast xor state, swapAcc xor acc)
    }

    /** 按 little-endian 切成 4 字节字, 零填充到 4 字节对齐; 字数为奇数时补一个 0 (原库 `_words_le4_even`)。 */
    private fun wordsLe4Even(data: ByteArray): IntArray {
        if (data.isEmpty()) return IntArray(0)
        val wordCount = (data.size + 3) / 4
        val paddedCount = if (wordCount % 2 == 0) wordCount else wordCount + 1
        return IntArray(paddedCount) { index ->
            var word = 0
            for (byteIndex in 0 until 4) {
                val position = index * 4 + byteIndex
                if (position < data.size)
                    word = word or (data[position].toInt() and 0xFF shl byteIndex * 8)
            }
            word
        }
    }

    /** 32 位循环左移 16 位 (原库 `_rol16`)。 */
    private fun rotateLeft16(value: Int): Int = value shl 16 or (value ushr 16)

    private fun ByteArray.readBigEndianInt(offset: Int): Int =
        this[offset].toInt() and 0xFF shl 24 or
            (this[offset + 1].toInt() and 0xFF shl 16) or
            (this[offset + 2].toInt() and 0xFF shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private fun ByteArray.readLittleEndianInt(offset: Int): Int =
        this[offset].toInt() and 0xFF or
            (this[offset + 1].toInt() and 0xFF shl 8) or
            (this[offset + 2].toInt() and 0xFF shl 16) or
            (this[offset + 3].toInt() and 0xFF shl 24)

    private fun ByteArray.toHexUpper(): String =
        buildString(size * 2) { for (byte in this@toHexUpper) appendHexByte(byte.toInt() and 0xFF) }

    /** 32 位字的 little-endian 字节序十六进制 (对应原库 struct.pack("<I", word).hex())。 */
    private fun hexLeWord(word: Int): String =
        buildString(8) {
            for (byteIndex in 0 until 4) appendHexByte(word ushr byteIndex * 8 and 0xFF)
        }

    private fun StringBuilder.appendHexByte(value: Int) {
        append(HEX_DIGITS[value ushr 4 and 0xF])
        append(HEX_DIGITS[value and 0xF])
    }

    private const val HEX_DIGITS = "0123456789ABCDEF"
}
