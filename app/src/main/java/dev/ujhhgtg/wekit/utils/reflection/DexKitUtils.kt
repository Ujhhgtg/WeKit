package dev.ujhhgtg.wekit.utils.reflection

import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import kotlin.time.Duration.Companion.seconds

inline val MethodData.asMethod get() = getMethodInstance(ClassLoaders.HOST)

inline val ClassData.asClass get() = getInstance(ClassLoaders.HOST)

inline val MethodData.asConstructor get() = getConstructorInstance(ClassLoaders.HOST)

private object DexKitHolder {

    private const val TAG = "DexKitHolder"
    private val IDLE_TIMEOUT = 30.seconds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private var bridge: DexKitBridge? = null
    private var activeLeaseCount = 0
    private var idleCloseJob: Job? = null
    private var idleCloseGeneration = 0L

    fun acquire(): DexKitBridge = synchronized(lock) {
        cancelIdleCloseLocked()

        val current = bridge
        val acquired = if (current?.isValid == true) {
            current
        } else {
            // Repackaging frameworks such as NPatch may expose a loader-only outer APK through sourceDir.
            DexKitBridge.create(ClassLoaders.HOST, true).also { bridge = it }
        }

        activeLeaseCount++
        acquired
    }

    fun release() = synchronized(lock) {
        check(activeLeaseCount > 0) { "DexKit lease released without a matching acquire" }

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

                val idleBridge = bridge
                bridge = null
                idleCloseJob = null

                if (idleBridge != null) {
                    WeLogger.d(TAG, "idle timeout reached, closing DexKit")
                    idleBridge.close()
                }
            }
        }
    }
}

/**
 * Keeps the shared bridge valid for the entire [block]. DexKit result objects must be consumed
 * inside the block because some of them retain the bridge for follow-up queries.
 */
fun <T> withDexKit(block: (DexKitBridge) -> T): T {
    val dexKit = DexKitHolder.acquire()
    return try {
        block(dexKit)
    } finally {
        DexKitHolder.release()
    }
}

/** Suspending counterpart of [withDexKit]. The lease remains active across suspension points. */
suspend fun <T> withDexKitSuspending(block: suspend (DexKitBridge) -> T): T {
    val dexKit = DexKitHolder.acquire()
    return try {
        block(dexKit)
    } finally {
        DexKitHolder.release()
    }
}
