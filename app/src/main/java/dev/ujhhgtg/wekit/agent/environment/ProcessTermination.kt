package dev.ujhhgtg.wekit.agent.environment

import android.os.Process as AndroidProcess
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal object ProcessTermination {
    fun terminateTree(process: Process, rootPid: Int?) {
        if (rootPid == null) {
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            return
        }
        val parentOf = readParents()
        descendants(rootPid, parentOf).forEach { pid ->
            runCatching { AndroidProcess.killProcess(pid) }
        }
        runCatching { AndroidProcess.killProcess(rootPid) }
        process.destroy()
        if (!runCatching { process.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) {
            process.destroyForcibly()
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    private fun readParents(): Map<Int, Int> = buildMap {
        runCatching {
            Files.list(Path.of("/proc")).use { entries ->
                entries.filter { it.fileName.toString().all(Char::isDigit) }.forEach { pidPath ->
                    val pid = pidPath.fileName.toString().toInt()
                    val fields = Files.readString(pidPath.resolve("stat")).substringAfterLast(") ").split(' ')
                    if (fields.size > 1) put(pid, fields[1].toInt())
                }
            }
        }
    }

    internal fun descendants(rootPid: Int, parentOf: Map<Int, Int>): List<Int> {
        val children = parentOf.entries.groupBy({ it.value }, { it.key })
        return buildList {
            fun visit(pid: Int) {
                children[pid].orEmpty().forEach { child ->
                    visit(child)
                    add(child)
                }
            }
            visit(rootPid)
        }
    }
}
