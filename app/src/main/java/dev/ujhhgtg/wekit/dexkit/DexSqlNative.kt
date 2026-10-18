package dev.ujhhgtg.wekit.dexkit

import androidx.annotation.Keep

/**
 * The small JNI surface exported by libwekit_native for the embedded DexSQL engine.
 *
 * Native failures are returned in the operation's JSON envelope. The only operation that cannot
 * return an envelope is [open], so a zero handle means that opening failed. Keeping this object
 * free of Kotlin logic also keeps the JNI names stable when the bridge implementation changes.
 *
 * JNI symbols for this object are:
 * `Java_dev_ujhhgtg_wekit_dexkit_DexSqlNative_{open,query,resolve,close}`.
 */
@Keep
object DexSqlNative {
    external fun open(paths: Array<String>): Long

    /** Returns an `{ok:true,...}` or `{ok:false,error}` JSON envelope. */
    external fun query(handle: Long, sql: String): String

    /** Returns an `{ok:true,...}` or `{ok:false,error}` JSON envelope. */
    external fun resolve(
        handle: Long,
        sql: String,
        resultIndex: Int,
        allowMultiple: Boolean,
        allowFailure: Boolean,
    ): String

    external fun close(handle: Long)
}
