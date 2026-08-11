package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.utils.HostInfo
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface ReadReceiptsServerController {
    fun startBuiltIn(port: Int, connectorAuthenticator: String): Result<Int>

    fun stopBuiltIn()

    fun status(): ReadReceiptsRuntimeState
}

internal class NativeReadReceiptsServerController : ReadReceiptsServerController {
    private val generation = AtomicLong()
    private val lastStatus = AtomicReference(ReadReceiptsStatus(ReadReceiptsRuntimeState.STOPPED))

    override fun startBuiltIn(port: Int, connectorAuthenticator: String): Result<Int> {
        val currentGeneration = generation.incrementAndGet()
        updateStatus(currentGeneration, ReadReceiptsStatus(ReadReceiptsRuntimeState.STARTING))

        val result = runCatching {
            require(port in 0..65535) { "server port must be between 0 and 65535" }
            require(connectorAuthenticator.length == 32) { "invalid connector authenticator" }
            val database = databaseFile()
            check(database.parentFile!!.isDirectory || database.parentFile!!.mkdirs()) {
                "无法创建内置服务器数据库目录"
            }
            ReadReceiptsNative.startServer(database.absolutePath, port, connectorAuthenticator)
                ?.let(::error)
            val status = nativeStatus()
            check(status.state == ReadReceiptsRuntimeState.RUNNING && status.port != null) {
                status.error ?: "内置服务器启动后未进入运行状态"
            }
            status.port
        }

        val terminal = result.fold(
            onSuccess = { ReadReceiptsStatus(ReadReceiptsRuntimeState.RUNNING, port = it) },
            onFailure = {
                ReadReceiptsStatus(
                    ReadReceiptsRuntimeState.FAILED,
                    error = it.message ?: it.javaClass.simpleName,
                )
            },
        )
        updateStatus(currentGeneration, terminal)
        return result
    }

    override fun stopBuiltIn() {
        val currentGeneration = generation.incrementAndGet()
        val previous = lastStatus.get()
        updateStatus(
            currentGeneration,
            ReadReceiptsStatus(ReadReceiptsRuntimeState.STOPPING, port = previous.port),
        )
        ReadReceiptsNative.stopServer()
        refreshStatus(currentGeneration)
    }

    override fun status(): ReadReceiptsRuntimeState = snapshot().state

    internal fun snapshot(): ReadReceiptsStatus {
        val currentGeneration = generation.get()
        return refreshStatus(currentGeneration)
    }

    private fun refreshStatus(expectedGeneration: Long): ReadReceiptsStatus {
        val status = runCatching(::nativeStatus).getOrElse {
            ReadReceiptsStatus(
                ReadReceiptsRuntimeState.FAILED,
                error = STATUS_READ_ERROR,
            )
        }
        updateStatus(expectedGeneration, status)
        return if (generation.get() == expectedGeneration) status else lastStatus.get()
    }

    private fun nativeStatus(): ReadReceiptsStatus = ReadReceiptsStatus
        .parse(ReadReceiptsNative.serverStatus())
        .getOrElse { error(STATUS_READ_ERROR) }

    private fun updateStatus(expectedGeneration: Long, status: ReadReceiptsStatus) {
        if (generation.get() == expectedGeneration) lastStatus.set(status)
    }

    internal companion object {
        private const val STATUS_READ_ERROR = "无法读取内置服务器状态"

        fun databaseFile(): File = File(
            HostInfo.application.filesDir,
            "wekit-read-receipts/read_receipts.db",
        )
    }
}
