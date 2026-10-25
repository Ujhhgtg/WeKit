package dev.ujhhgtg.wekit.dex

import dev.ujhhgtg.wekit.dex.meta.DexClassInfo
import dev.ujhhgtg.wekit.dex.meta.DexMetadata
import dev.ujhhgtg.wekit.dex.meta.DexMethodInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import dev.ujhhgtg.wekit.utils.reflection.descriptorToConstructor
import dev.ujhhgtg.wekit.utils.reflection.descriptorToMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.time.Duration.Companion.seconds

/**
 * One open runtime DexSQL session over the host APK.
 *
 * This is the DexKit-free replacement for the shared DexKit bridge that scripts, Monet evidence
 * collection and feature-flag probing used at runtime. The session owns an immutable dataset:
 * [bridge] executes SQL, [metadata] reads indexed class/method/field documents, and
 * `dev.ujhhgtg.wekit.dexkit.matcher.DexMatcherSession` (created from [bridge] + [metadata]) runs
 * the same query matcher the resolve phase uses.
 */
class DexSqlRuntime internal constructor(val bridge: DexSqlBridge) : AutoCloseable {
    val metadata: DexMetadata = DexMetadata(DexSqlMetadataProvider(bridge))

    override fun close() = bridge.close()
}

private object DexSqlHolder {
    private const val TAG = "DexSqlHolder"
    private val IDLE_TIMEOUT = 30.seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var runtime: DexSqlRuntime? = null
    private var activeLeaseCount = 0
    private var idleCloseJob: Job? = null
    private var idleCloseGeneration = 0L

    fun acquire(): DexSqlRuntime =
        synchronized(lock) {
            cancelIdleCloseLocked()

            val current = runtime
            val acquired =
                if (current != null && current.bridge.isOpen) {
                    current
                } else {
                    // Repackaging frameworks such as NPatch may expose a loader-only outer APK
                    // through sourceDir.
                    DexSqlRuntime(DexSqlBridge.openHost()).also { runtime = it }
                }

            activeLeaseCount++
            acquired
        }

    fun release() =
        synchronized(lock) {
            check(activeLeaseCount > 0) { "DexSQL lease released without a matching acquire" }

            activeLeaseCount--
            if (activeLeaseCount == 0) {
                scheduleIdleCloseLocked()
            }
        }

    private fun cancelIdleCloseLocked() {
        idleCloseGeneration++
        idleCloseJob?.cancel()
        idleCloseJob = null
    }

    private fun scheduleIdleCloseLocked() {
        check(idleCloseJob == null)
        val generation = ++idleCloseGeneration

        idleCloseJob = scope.launch {
            delay(IDLE_TIMEOUT)
            synchronized(lock) {
                if (generation != idleCloseGeneration || activeLeaseCount != 0) {
                    return@synchronized
                }

                val idleRuntime = runtime
                runtime = null
                idleCloseJob = null

                if (idleRuntime != null) {
                    WeLogger.d(TAG, "idle timeout reached, closing DexSQL")
                    idleRuntime.close()
                }
            }
        }
    }
}

/** Keeps the shared runtime session open for the entire [block]. */
fun <T> withDexSql(block: (DexSqlRuntime) -> T): T {
    val session = DexSqlHolder.acquire()
    return try {
        block(session)
    } finally {
        DexSqlHolder.release()
    }
}

/** Reflection handle for a resolved method (hook-time only). */
inline val DexMethodInfo.asMethod: Method
    get() = descriptor.descriptorToMethod(ClassLoaders.HOST)

/** Reflection handle for a resolved constructor (hook-time only). */
inline val DexMethodInfo.asConstructor: Constructor<*>
    get() = descriptor.descriptorToConstructor(ClassLoaders.HOST)

/** Reflection handle for a resolved class (hook-time only). */
inline val DexClassInfo.asClass: Class<*>
    get() = ClassLoaders.HOST.loadClass(name)
