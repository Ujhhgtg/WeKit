package dev.ujhhgtg.wekit.agent.model.local

import androidx.annotation.Keep
import java.io.FileOutputStream
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Minimal app_process entry point for the isolated local llama server. */
@Keep
object LlamaServerProcess {

    @JvmStatic
    fun main(args: Array<String>) {
        val inheritedStatusFd = args.getOrNull(1)?.toIntOrNull()
        try {
            require(args.size == 7) { "expected 7 arguments, got ${args.size}" }
            require(args[0] == "1") { "unsupported schema: ${args[0]}" }
            val statusFd = requireNotNull(inheritedStatusFd) { "invalid status fd: ${args[1]}" }
            require(statusFd >= 0) { "status fd must be non-negative" }
            val nativeLibraryPath = args[2]
            val modelPath = args[3]
            val nCtx = args[4].toInt()
            val backend = args[5]
            val configJson = args[6]

            System.load(nativeLibraryPath)
            val exitCode = LlamaServerNative.runServerProcess(
                modelPath,
                nCtx,
                backend,
                configJson,
                statusFd,
            )
            exitProcess(exitCode)
        } catch (error: Throwable) {
            if (inheritedStatusFd != null && inheritedStatusFd >= 0) {
                val line = JsonObject(
                    mapOf(
                        "type" to JsonPrimitive("error"),
                        "msg" to JsonPrimitive(error.message ?: error.javaClass.name),
                    )
                ).toString() + "\n"
                try {
                    FileOutputStream("/proc/self/fd/$inheritedStatusFd").use { output ->
                        output.write(line.toByteArray(Charsets.UTF_8))
                    }
                } finally {
                    exitProcess(1)
                }
            }
            exitProcess(1)
        }
    }
}
