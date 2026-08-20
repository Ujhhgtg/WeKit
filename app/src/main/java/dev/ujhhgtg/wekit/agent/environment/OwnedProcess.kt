package dev.ujhhgtg.wekit.agent.environment

import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

internal interface OwnedProcessHandle : AutoCloseable {
    val outputStream: OutputStream
    val inputStream: InputStream
    val errorStream: InputStream
    fun pollExit(): Int?
    suspend fun terminateGroup(graceMillis: Long = ProcessTermination.TERM_GRACE_MILLIS)
}

internal typealias OwnedProcessStarter = (List<String>, Map<String, String>, String) -> OwnedProcessHandle

internal class OwnedProcess private constructor(
    handle: Long,
    val pid: Int,
    val pgid: Int,
    private val stdinDescriptor: ParcelFileDescriptor,
    private val stdoutDescriptor: ParcelFileDescriptor,
    private val stderrDescriptor: ParcelFileDescriptor,
) : OwnedProcessHandle {
    private val handle = AtomicLong(handle)
    override val outputStream: OutputStream = ParcelFileDescriptor.AutoCloseOutputStream(stdinDescriptor)
    override val inputStream: InputStream = ParcelFileDescriptor.AutoCloseInputStream(stdoutDescriptor)
    override val errorStream: InputStream = ParcelFileDescriptor.AutoCloseInputStream(stderrDescriptor)

    override fun pollExit(): Int? = when (val result = Native.pollExit(requireHandle())) {
        Native.RUNNING -> null
        Native.ERROR -> error("owned process wait failed")
        else -> result
    }

    override suspend fun terminateGroup(graceMillis: Long) {
        check(Native.terminateGroup(requireHandle(), graceMillis)) { "owned process group termination failed" }
    }

    override fun close() {
        runCatching { outputStream.close() }
        runCatching { inputStream.close() }
        runCatching { errorStream.close() }
        handle.getAndSet(0).takeIf { it != 0L }?.let(Native::close)
    }

    private fun requireHandle(): Long = handle.get().also { check(it != 0L) { "owned process is closed" } }

    companion object {
        fun start(argv: List<String>, environment: Map<String, String>, cwd: String): OwnedProcess {
            require(argv.isNotEmpty())
            val values = Native.start(
                argv.toTypedArray(),
                environment.map { (key, value) -> "$key=$value" }.toTypedArray(),
                cwd,
            ) ?: error("failed to start owned process")
            check(values.size == 6)
            return OwnedProcess(
                values[0], values[1].toInt(), values[2].toInt(),
                ParcelFileDescriptor.adoptFd(values[3].toInt()),
                ParcelFileDescriptor.adoptFd(values[4].toInt()),
                ParcelFileDescriptor.adoptFd(values[5].toInt()),
            )
        }
    }

    private object Native {
        init { try { System.loadLibrary("wekit_native") } catch (_: UnsatisfiedLinkError) { } }
        external fun start(argv: Array<String>, environment: Array<String>, cwd: String): LongArray?
        external fun pollExit(handle: Long): Int
        external fun terminateGroup(handle: Long, graceMillis: Long): Boolean
        external fun close(handle: Long)
        const val ERROR = Int.MIN_VALUE
        const val RUNNING = Int.MIN_VALUE + 1
    }
}
