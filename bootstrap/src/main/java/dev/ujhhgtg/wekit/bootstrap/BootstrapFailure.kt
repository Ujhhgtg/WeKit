package dev.ujhhgtg.wekit.bootstrap

import android.content.Context
import java.io.File

/**
 * Explains a failed startup in terms a user or a report reader can act on.
 *
 * ART renders a stack overflow as `stack size 8188KB` — only the overflowing thread's stack size —
 * so the raw message names neither the recursion nor a way forward. The recorded stack trace does:
 * an overflow keeps re-entering the same few frames, and that cycle is what [cycleFrames] extracts.
 */
object BootstrapFailure {
    /** Frames inspected from the top of the trace; an overflow only repeats its innermost frames. */
    private const val SCAN_DEPTH = 256

    /** Longest cycle reported; anything longer is a run of the same call chain. */
    private const val MAX_CYCLE = 32

    /** A cycle has to repeat this often before the stack is considered to be looping there. */
    private const val MIN_REPEATS = 3

    /** Leading frames that can sit above the loop, depending on where the throw happened. */
    private const val MAX_ENTRY_FRAMES = 8

    /** User-facing text for a failed initialization; ART error messages alone are not actionable. */
    fun loadFailure(context: Context, bootstrapApk: File, error: Throwable): String =
        if (error is StackOverflowError) {
            BootstrapResources.getString(
                context, bootstrapApk, R.string.wekit_auth_load_stack_overflow,
                recursionLocation(error),
            )
        } else {
            BootstrapResources.getString(context, bootstrapApk, R.string.wekit_auth_load_failure,
                error.message ?: error.javaClass.simpleName)
        }

    /** Frames the overflowed stack loops through, e.g. `A.b -> C.d`; empty when it does not loop. */
    fun cycleFrames(error: StackOverflowError): List<String> {
        val frames = error.stackTrace
        val scanned = minOf(frames.size, SCAN_DEPTH)
        for (entry in 0..minOf(MAX_ENTRY_FRAMES, scanned / 2)) {
            for (cycle in 1..MAX_CYCLE) {
                if (!loops(frames, entry, cycle, scanned)) continue
                return (0 until cycle).map { frameName(frames[entry + it]) }
            }
        }
        return emptyList()
    }

    /** One logcat line that names the overflow site, including the repeating frames. */
    fun describeOverflow(error: StackOverflowError): String {
        val frames = error.stackTrace
        val cycle = cycleFrames(error)
        val innermost =
            frames.take(3).joinToString(" <- ") { "${it.className}.${it.methodName}" }
        return buildString {
            append("stack overflow after ${frames.size} frames")
            if (cycle.isNotEmpty()) {
                append("; loop ${cycle.joinToString(" -> ")} -> ${cycle.first()}")
            }
            append("; innermost ${innermost.ifEmpty { "<no trace>" }}")
        }
    }

    private fun loops(
        frames: Array<StackTraceElement>,
        entry: Int,
        cycle: Int,
        scanned: Int,
    ): Boolean {
        val compared = cycle * MIN_REPEATS
        if (entry + compared + cycle > scanned) return false
        for (offset in 0 until compared) {
            if (frames[entry + offset] != frames[entry + offset + cycle]) return false
        }
        return true
    }

    private fun recursionLocation(error: StackOverflowError): String {
        val cycle = cycleFrames(error)
        if (cycle.isNotEmpty()) return cycle.joinToString(" -> ")
        // Still better than ART's message: at least name where the stack ran out.
        val frame = error.stackTrace.firstOrNull() ?: return error.message.orEmpty()
        return "${frame.className}.${frame.methodName}"
    }

    private fun frameName(frame: StackTraceElement): String =
        "${frame.className.substringAfterLast('.')}.${frame.methodName}"
}
