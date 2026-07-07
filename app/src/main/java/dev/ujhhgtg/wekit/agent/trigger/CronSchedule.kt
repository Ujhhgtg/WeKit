package dev.ujhhgtg.wekit.agent.trigger

import java.util.Calendar

/**
 * A minimal standard 5-field cron evaluator (minute hour day-of-month month day-of-week) in the
 * device's local timezone. Supports the star wildcard, single values, a-b ranges, step syntax
 * (slash-n), and comma lists of those. Day-of-week is 0..6 (Sunday=0), with 7 also accepted as Sunday.
 *
 * This is intentionally tiny (no seconds field, no macro shortcuts, no L/W/hash specifiers): triggers
 * only need "next fire at or after now". Returns null on an unparseable expression so the scheduler
 * can disable a malformed trigger rather than crash.
 */
object CronSchedule {

    /** [isStar] is true only when the field spec was literally "*", which drives cron's dom/dow rule. */
    private data class Field(val allowed: BooleanArray, val isStar: Boolean)

    private class Parsed(
        val minute: Field,
        val hour: Field,
        val dom: Field,
        val month: Field,     // 1..12 stored at index 1..12 (index 0 unused)
        val dow: Field,       // 0..6, Sunday = 0
    )

    /**
     * Computes the next fire time strictly after [afterMillis], or null if the expression is invalid
     * or no match is found within a one-shot 4-year search window.
     */
    fun nextAfter(cronExpr: String, afterMillis: Long): Long? {
        val parsed = parse(cronExpr) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = afterMillis
            // Advance to the start of the next whole minute (cron granularity is 1 minute).
            add(Calendar.MINUTE, 1)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // 4 years of minutes is a safe upper bound (covers Feb-29-only expressions).
        val limit = cal.timeInMillis + 4L * 366 * 24 * 60 * 60 * 1000
        while (cal.timeInMillis <= limit) {
            if (matches(parsed, cal)) return cal.timeInMillis
            cal.add(Calendar.MINUTE, 1)
        }
        return null
    }

    private fun matches(p: Parsed, cal: Calendar): Boolean {
        val minute = cal.get(Calendar.MINUTE)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dom = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1                 // Calendar months are 0-based
        val dow = (cal.get(Calendar.DAY_OF_WEEK) - 1)           // Calendar: Sunday=1 -> 0

        if (!p.minute.allowed[minute]) return false
        if (!p.hour.allowed[hour]) return false
        if (!p.month.allowed[month]) return false

        // Standard cron rule: if BOTH dom and dow are restricted (not "*"), a match on EITHER is
        // enough; if only one is restricted, only that one must match.
        val domRestricted = !p.dom.isStar
        val dowRestricted = !p.dow.isStar
        val domOk = p.dom.allowed[dom]
        val dowOk = p.dow.allowed[dow]
        return when {
            domRestricted && dowRestricted -> domOk || dowOk
            domRestricted -> domOk
            dowRestricted -> dowOk
            else -> true
        }
    }

    private fun parse(expr: String): Parsed? {
        val parts = expr.trim().split(Regex("\\s+"))
        if (parts.size != 5) return null
        val minute = field(parts[0], 0, 59) ?: return null
        val hour = field(parts[1], 0, 23) ?: return null
        val dom = field(parts[2], 1, 31) ?: return null
        val month = field(parts[3], 1, 12) ?: return null
        val dow = fieldDow(parts[4]) ?: return null
        return Parsed(minute, hour, dom, month, dow)
    }

    /**
     * Parses one field into a [Field]. [min]..[max] is the valid value range; the backing array is
     * sized [max]+1 so callers can index directly by the natural value (e.g. month 1..12).
     */
    private fun field(spec: String, min: Int, max: Int): Field? {
        val allowed = BooleanArray(max + 1)
        for (token in spec.split(',')) {
            if (!applyToken(token, min, max, allowed)) return null
        }
        return Field(allowed, isStar = spec.trim() == "*")
    }

    /** Day-of-week: accept 0..7 (both 0 and 7 = Sunday), normalized into a 0..6 array. */
    private fun fieldDow(spec: String): Field? {
        val allowed = BooleanArray(7)
        for (token in spec.split(',')) {
            if (!applyToken(token, 0, 7, allowed, normalize7to0 = true)) return null
        }
        return Field(allowed, isStar = spec.trim() == "*")
    }

    private fun applyToken(
        token: String,
        min: Int,
        max: Int,
        allowed: BooleanArray,
        normalize7to0: Boolean = false,
    ): Boolean {
        var rangePart = token
        var step = 1
        val slash = token.indexOf('/')
        if (slash >= 0) {
            step = token.substring(slash + 1).toIntOrNull()?.takeIf { it > 0 } ?: return false
            rangePart = token.substring(0, slash)
        }
        val (lo, hi) = when {
            rangePart == "*" -> min to max
            rangePart.contains('-') -> {
                val (a, b) = rangePart.split('-', limit = 2)
                val ai = a.toIntOrNull() ?: return false
                val bi = b.toIntOrNull() ?: return false
                ai to bi
            }
            else -> {
                val v = rangePart.toIntOrNull() ?: return false
                v to v
            }
        }
        if (lo < min || hi > max || lo > hi) return false
        var v = lo
        while (v <= hi) {
            val idx = if (normalize7to0 && v == 7) 0 else v
            if (idx in allowed.indices) allowed[idx] = true
            v += step
        }
        return true
    }
}
