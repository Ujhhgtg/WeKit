package dev.ujhhgtg.wekit.features.items.scripting_python.runtime

import dalvik.system.InMemoryDexClassLoader
import android.os.Build
import dev.ujhhgtg.wekit.extensions.MountedPythonRuntime
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.extensions.PythonRuntimeArchive
import dev.ujhhgtg.wekit.extensions.PythonRuntimePack
import dev.ujhhgtg.wekit.loader.utils.InjectionHandle
import dev.ujhhgtg.wekit.loader.utils.HybridClassLoader
import dev.ujhhgtg.wekit.loader.utils.ResourcesInjector
import dev.ujhhgtg.wekit.python.api.PythonPluginHost
import dev.ujhhgtg.wekit.python.api.PythonRuntimeApi
import dev.ujhhgtg.wekit.python.api.PythonRuntimeBackend
import dev.ujhhgtg.wekit.python.api.PythonRuntimeConfig
import dev.ujhhgtg.wekit.python.api.PythonRuntimeStartupException
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.zip.ZipFile

enum class PythonRuntimeState { NOT_STARTED, MOUNTING, STARTING, STARTED, FAILED }

data class PythonRuntimeStatus(
    val state: PythonRuntimeState = PythonRuntimeState.NOT_STARTED,
    val version: String? = null,
    val phase: String? = null,
    val library: String? = null,
    val error: Throwable? = null,
)

class PythonRuntimeMissingException : IllegalStateException("Python runtime extension is not installed")

object PythonRuntimeLimits {
    val SYNC_HOOK_BUDGET_MS = BuildConfig.PYTHON_SYNC_HOOK_BUDGET_MS
    val TASK_DRAIN_TIMEOUT_MS = BuildConfig.PYTHON_TASK_DRAIN_TIMEOUT_MS
    val MAX_MANIFEST_BYTES = BuildConfig.PYTHON_MAX_MANIFEST_BYTES
    val MAX_PLUGIN_FILE_BYTES = BuildConfig.PYTHON_MAX_PLUGIN_FILE_BYTES
}

object PythonRuntimeLoader {
    private const val TAG = "PythonRuntimeLoader"
    private val lock = Any()
    private val mutableStatus = MutableStateFlow(PythonRuntimeStatus())
    val status: StateFlow<PythonRuntimeStatus> = mutableStatus

    @Volatile
    private var backend: PythonRuntimeBackend? = null
    private var startup: CompletableFuture<PythonRuntimeBackend>? = null
    private var injectionHandle: InjectionHandle? = null
    private var runtimeClassLoader: ClassLoader? = null
    private var dexBuffers: Array<ByteBuffer>? = null

    fun ensureStarted(host: PythonPluginHost): PythonRuntimeBackend {
        backend?.let { return it }
        var owner = false
        val pending = synchronized(lock) {
            backend?.let { return it }
            if (mutableStatus.value.state == PythonRuntimeState.FAILED) {
                throw checkNotNull(mutableStatus.value.error)
            }
            startup ?: CompletableFuture<PythonRuntimeBackend>().also {
                startup = it
                owner = true
            }
        }
        if (owner) {
            try {
                pending.complete(start(host))
            } catch (missing: PythonRuntimeMissingException) {
                synchronized(lock) {
                    startup = null
                    mutableStatus.value = PythonRuntimeStatus()
                }
                pending.completeExceptionally(missing)
            } catch (error: Throwable) {
                val cause = unwrap(error)
                synchronized(lock) {
                    mutableStatus.value = mutableStatus.value.copy(
                        state = PythonRuntimeState.FAILED,
                        phase = (cause as? PythonRuntimeStartupException)?.phase
                            ?: mutableStatus.value.phase,
                        library = (cause as? PythonRuntimeStartupException)?.library,
                        error = cause,
                    )
                }
                WeLogger.e(
                    TAG,
                    "state=FAILED version=${mutableStatus.value.version} phase=${mutableStatus.value.phase}",
                    cause,
                )
                pending.completeExceptionally(cause)
            }
        }
        return try {
            pending.get()
        } catch (error: ExecutionException) {
            throw unwrap(error)
        }
    }

    fun startedBackend(): PythonRuntimeBackend? = backend

    fun syncHookBudgetMs(): Long =
        PythonRuntimeLimits.SYNC_HOOK_BUDGET_MS

    fun <T> withLookupClassLoader(block: () -> T): T {
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = ClassLoaders.HYBRID
        return try {
            block()
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private fun start(host: PythonPluginHost): PythonRuntimeBackend {
        val mounted = PythonRuntimePack.selectForMount() ?: throw PythonRuntimeMissingException()
        publish(PythonRuntimeState.MOUNTING, mounted, "assets")
        val resources = HostInfo.application.resources
        injectionHandle = ResourcesInjector.injectApk(
            resources,
            mounted.runtimeApk,
            mounted.manifest.sha256,
        )
        resources.assets.open("chaquopy/build.json").use { it.read() }

        publish(PythonRuntimeState.MOUNTING, mounted, "dex")
        val loader = createClassLoader(mounted)
        runtimeClassLoader = loader
        HybridClassLoader.additionalLoaders.addIfAbsent(loader)
        val entrypoint = loader.loadClass(PythonRuntimeApi.ENTRYPOINT_CLASS)

        publish(PythonRuntimeState.STARTING, mounted, "bootstrap")
        val config = PythonRuntimeConfig(
            application = HostInfo.application,
            runtimeApk = mounted.runtimeApk,
            nativeDirectory = mounted.nativeDirectory,
            sdkRoot = mounted.sdkDirectory,
            lookupClassLoader = ClassLoaders.HYBRID,
            syncHookBudgetMs = PythonRuntimeLimits.SYNC_HOOK_BUDGET_MS,
            taskDrainTimeoutMs = PythonRuntimeLimits.TASK_DRAIN_TIMEOUT_MS,
            maxManifestBytes = PythonRuntimeLimits.MAX_MANIFEST_BYTES,
            maxPluginFileBytes = PythonRuntimeLimits.MAX_PLUGIN_FILE_BYTES,
        )
        val bootstrap = entrypoint.getMethod(
            "bootstrap",
            Int::class.javaPrimitiveType,
            PythonRuntimeConfig::class.java,
            PythonPluginHost::class.java,
        )
        val instance = bootstrap.invoke(null, PythonRuntimeApi.API_VERSION, config, host)
        require(instance is PythonRuntimeBackend) { "Python runtime entrypoint returned an incompatible backend" }
        require(instance.javaClass.classLoader === loader) { "Python runtime backend was loaded by the wrong ClassLoader" }
        instance.start(config)
        synchronized(lock) {
            backend = instance
            mutableStatus.value = PythonRuntimeStatus(PythonRuntimeState.STARTED, mounted.manifest.version)
        }
        WeLogger.i(TAG, "state=STARTED version=${mounted.manifest.version}")
        return instance
    }

    private fun createClassLoader(mounted: MountedPythonRuntime): ClassLoader {
        val contents = PythonRuntimeArchive.inspect(mounted.runtimeApk, mounted.manifest.meta)
        val buffers = ZipFile(mounted.runtimeApk).use { zip ->
            contents.dexEntries.map { name ->
                val bytes = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                ByteBuffer.wrap(bytes)
            }.toTypedArray()
        }
        dexBuffers = buffers
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            InMemoryDexClassLoader(buffers, mounted.nativeDirectory.absolutePath, ClassLoaders.MODULE)
        } else {
            InMemoryDexClassLoader(buffers, ClassLoaders.MODULE)
        }
    }

    private fun publish(state: PythonRuntimeState, mounted: MountedPythonRuntime, phase: String) {
        mutableStatus.value = PythonRuntimeStatus(state, mounted.manifest.version, phase)
        WeLogger.i(TAG, "state=$state version=${mounted.manifest.version} phase=$phase")
    }

    private fun unwrap(error: Throwable): Throwable = when (error) {
        is InvocationTargetException -> error.targetException
        is ExecutionException -> error.cause ?: error
        else -> error
    }
}
