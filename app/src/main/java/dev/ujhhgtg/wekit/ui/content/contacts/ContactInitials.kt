package dev.ujhhgtg.wekit.ui.content.contacts

import android.icu.text.Transliterator
import android.os.Build
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps a display name to its index letter (`A`–`Z`, or `#`), transliterating Han characters to
 * pinyin on API 29+.
 *
 * 分组字母缓存 (首字符 -> A-Z / #)。ICU 的 Transliterator 很慢, 而分组会在每次搜索按键时对整个列表 重跑一遍; 不同的首字符数量远小于联系人数量,
 * 按首字符缓存后主线程基本只是查表。
 */
class ContactInitials {
    private val transliterator: Transliterator? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Transliterator.getInstance("Han-Latin; Any-Latin; Latin-ASCII")
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    private val cache = ConcurrentHashMap<Char, String>()

    fun initialOf(displayName: String): String {
        val name = displayName.trim()
        if (name.isEmpty()) return "#"
        val firstChar = name.first()
        cache[firstChar]?.let {
            return it
        }

        val upper = firstChar.uppercaseChar()
        val initial =
            if (upper in 'A'..'Z') {
                upper.toString()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && transliterator != null) {
                // ICU Transliterator 不是线程安全的, 预热协程与主线程可能同时进来。
                val pinyin =
                    synchronized(transliterator) {
                        transliterator.transliterate(firstChar.toString())
                    }
                val c = pinyin.firstOrNull()?.uppercaseChar() ?: '#'
                if (c in 'A'..'Z') c.toString() else "#"
            } else {
                "#"
            }
        cache[firstChar] = initial
        return initial
    }
}
