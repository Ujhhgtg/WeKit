package dev.ujhhgtg.wekit.dexkit

import android.content.pm.ApplicationInfo
import androidx.annotation.Keep
import dev.ujhhgtg.wekit.utils.HostInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read

/** Selection policy used by [DexSqlBridge.resolve]. */
data class ResolveOptions(
    val resultIndex: Int = 0,
    val allowMultiple: Boolean = false,
    val allowFailure: Boolean = false,
) {
    init {
        require(resultIndex >= 0) { "resultIndex must be non-negative" }
    }
}

/** Owned, allocation-safe representation of one DexSQL query result. */
@Keep
data class DexSqlQueryResult(
    val columns: List<String>,
    val rows: List<List<String>>,
)

/** A native operation reported an error in its JSON envelope. */
class DexSqlException(message: String) : IllegalStateException(message)

/** Native returned malformed or incomplete JSON. */
class DexSqlProtocolException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Kotlin owner for one native DexSQL dataset.
 *
 * A bridge may be shared by callers. Queries use the read side of a lifecycle lock, while
 * [close] takes the write side and therefore waits for in-flight calls before releasing the
 * native handle. Closing is idempotent; every other operation after close fails loudly.
 * Prefer `DexSqlBridge.open(...).use { ... }` so the native dataset is always released.
 */
@Keep
class DexSqlBridge private constructor(handle: Long) : AutoCloseable {
    private val lifecycleLock = ReentrantReadWriteLock()
    private var nativeHandle = handle

    /** Execute a read-only SQL statement and decode its structured result. */
    fun query(sql: String): DexSqlQueryResult = withHandle { handle ->
        decodeQuery(DexSqlNative.query(handle, sql))
    }

    /** Resolve one value from the first result column; an allowed empty result returns null. */
    fun resolve(sql: String, options: ResolveOptions = ResolveOptions()): String? =
        withHandle { handle ->
            decodeResolve(
                DexSqlNative.resolve(
                    handle = handle,
                    sql = sql,
                    resultIndex = options.resultIndex,
                    allowMultiple = options.allowMultiple,
                    allowFailure = options.allowFailure,
                ),
            )
        }

    /** Exception-free convenience form for callers that already use Kotlin [Result]. */
    fun queryResult(sql: String): Result<DexSqlQueryResult> = runCatching { query(sql) }

    /** Exception-free convenience form for callers that already use Kotlin [Result]. */
    fun resolveResult(
        sql: String,
        options: ResolveOptions = ResolveOptions(),
    ): Result<String?> = runCatching { resolve(sql, options) }

    override fun close() {
        val writeLock = lifecycleLock.writeLock()
        writeLock.lock()
        try {
            val handle = nativeHandle
            if (handle != 0L) {
                nativeHandle = 0L
                DexSqlNative.close(handle)
            }
        } finally {
            writeLock.unlock()
        }
    }

    private inline fun <T> withHandle(action: (Long) -> T): T = lifecycleLock.read {
        val handle = nativeHandle
        check(handle != 0L) { "DexSQL bridge is closed" }
        action(handle)
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** Open one or more DEX/APK/JAR/ZIP paths. */
        fun open(paths: Iterable<String>): DexSqlBridge {
            val inputs = paths
                .filter(String::isNotEmpty)
                .distinct()
            require(inputs.isNotEmpty()) { "DexSQL requires at least one input path" }
            val handle = DexSqlNative.open(inputs.toTypedArray())
            if (handle == 0L) throw DexSqlException("DexSQL native open failed")
            return DexSqlBridge(handle)
        }

        /** Open the host APK and every installed split APK in ApplicationInfo order. */
        fun openHost(): DexSqlBridge = openHost(HostInfo.appInfo)

        /** Open an explicitly supplied host ApplicationInfo (useful for tests and repackagers). */
        fun openHost(applicationInfo: ApplicationInfo): DexSqlBridge =
            open(hostApkPaths(applicationInfo))

        /** Collect sourceDir followed by splitSourceDirs, preserving order and removing duplicates. */
        fun hostApkPaths(applicationInfo: ApplicationInfo): List<String> = buildList {
            add(applicationInfo.sourceDir)
            applicationInfo.splitSourceDirs.orEmpty().forEach(::add)
        }
            .filter(String::isNotEmpty)
            .distinct()

        private fun decodeQuery(raw: String): DexSqlQueryResult {
            val response = decodeEnvelope(raw, "query")
            if (response.columns == null || response.rows == null) {
                throw DexSqlProtocolException("DexSQL query response omitted columns or rows")
            }
            return DexSqlQueryResult(response.columns, response.rows)
        }

        private fun decodeResolve(raw: String): String? {
            val response = decodeEnvelope(raw, "resolve")
            return response.value
        }

        private fun decodeEnvelope(raw: String, operation: String): NativeResponse {
            val response = try {
                json.decodeFromString<NativeResponse>(raw)
            } catch (error: Throwable) {
                throw DexSqlProtocolException(
                    "DexSQL $operation returned malformed JSON",
                    error,
                )
            }
            if (!response.ok) {
                val error = response.error
                throw DexSqlException(
                    error?.message ?: error?.kind ?: "DexSQL $operation failed"
                )
            }
            return response
        }
    }

    @Serializable
    private data class NativeResponse(
        val ok: Boolean,
        val columns: List<String>? = null,
        val rows: List<List<String>>? = null,
        val value: String? = null,
        val error: NativeError? = null,
    )

    @Serializable
    private data class NativeError(
        val kind: String? = null,
        val message: String? = null,
    )
}
