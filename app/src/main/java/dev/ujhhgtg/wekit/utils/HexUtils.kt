package dev.ujhhgtg.wekit.utils

/** 将十六进制字符串转换为字节数组 移植自 Hutool HexUtil, 去除了冗余依赖 */
fun hexToBytes(src: String?): ByteArray? {
    if (src.isNullOrEmpty()) return null

    // 1. 清洗空白字符
    val cleaned = src.filterNot { isBlankChar(it.code) }
    if (cleaned.isEmpty()) return null

    // 2. 处理奇数长度
    var finalHex = cleaned
    if ((finalHex.length and 0x01) != 0) {
        finalHex = "0$finalHex"
    }

    val len = finalHex.length
    val out = ByteArray(len shr 1)

    // 3. 每两个字符转换一个字节
    for (i in 0 until (len shr 1)) {
        val f = (toDigit(finalHex[i * 2], i * 2) shl 4) or toDigit(finalHex[i * 2 + 1], i * 2 + 1)
        out[i] = (f and 0xFF).toByte()
    }

    return out
}

private fun toDigit(ch: Char, index: Int): Int {
    val digit = Character.digit(ch, 16)
    if (digit < 0) {
        throw IllegalArgumentException("Illegal hexadecimal character $ch at index $index")
    }
    return digit
}

private fun isBlankChar(c: Int): Boolean {
    return Character.isWhitespace(c) ||
        Character.isSpaceChar(c) ||
        c == 0xFEFF ||
        c == 0x202A ||
        c == 0x0000 ||
        c == 0x3164 ||
        c == 0x2800 ||
        c == 0x200C ||
        c == 0x180E
}
