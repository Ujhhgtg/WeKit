package dev.ujhhgtg.wekit.agent.environment

import dev.ujhhgtg.wekit.utils.fs.asPath
import android.os.Process as AndroidProcess
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.listDirectoryEntries

object ProcessTermination {
    const val TERM_GRACE_MILLIS = 500L

    suspend fun drain(process: OwnedProcessHandle) = process.terminateGroup(TERM_GRACE_MILLIS)

    fun terminateTree(process: Process, rootPid: Int?) {
        if (rootPid != null) {
            val parentOf = readParents()
            descendants(rootPid, parentOf).forEach { pid -> runCatching { AndroidProcess.killProcess(pid) } }
            runCatching { AndroidProcess.killProcess(rootPid) }
        }
        process.destroy()
        if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    private fun readParents(): Map<Int, Int> = buildMap {
        runCatching {
            "/proc".asPath.listDirectoryEntries()
                .filter { it.fileName.toString().all(Char::isDigit) }
                .forEach { pidPath ->
                    val pid = pidPath.fileName.toString().toInt()
                    val fields = pidPath.resolve("stat").readText().substringAfterLast(") ").split(' ')
                    if (fields.size > 1) put(pid, fields[1].toInt())
                }
        }
    }

    fun descendants(rootPid: Int, parentOf: Map<Int, Int>): List<Int> {
        val children = parentOf.entries.groupBy({ it.value }, { it.key })
        return buildList {
            fun visit(pid: Int) {
                children[pid].orEmpty().forEach { child -> visit(child); add(child) }
            }
            visit(rootPid)
        }
    }
}
