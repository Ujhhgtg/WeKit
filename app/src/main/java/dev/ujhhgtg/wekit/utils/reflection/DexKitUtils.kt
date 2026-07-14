package dev.ujhhgtg.wekit.utils.reflection

import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import kotlin.time.Duration.Companion.seconds

inline val MethodData.asMethod get() = getMethodInstance(ClassLoaders.HOST)

inline val ClassData.asClass get() = getInstance(ClassLoaders.HOST)

inline val MethodData.asConstructor get() = getConstructorInstance(ClassLoaders.HOST)

private object DexKitHolder {

    private val IDLE_TIMEOUT = 30.seconds

    // SupervisorJob so a cancelled timer job never takes down the whole scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var bridge: DexKitBridge? = null
    private var idleJob: Job? = null

    fun get(): DexKitBridge {
        runBlocking {
            mutex.withLock {
                if (bridge == null) {
                    bridge = DexKitBridge.create(HostInfo.appInfo.sourceDir)
                }
                idleJob?.cancel()
                idleJob = scope.launch {
                    delay(IDLE_TIMEOUT)
                    mutex.withLock {
                        WeLogger.d("DexKitHolder", "timeout reached, closing DexKit")
                        bridge?.close()
                        bridge = null
                        idleJob = null
                    }
                }
            }
        }
        return bridge!!
    }
}

val DexKit: DexKitBridge get() = DexKitHolder.get()
