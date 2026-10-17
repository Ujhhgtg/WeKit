package dev.ujhhgtg.wekit.bootstrap.payload

import android.annotation.SuppressLint
import android.content.Context
import java.io.File

/** Its JNI library belongs to the bootstrap loader, never to the business loader. */
object LoaderNatives {
    private var initialized = false

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    @Synchronized
    fun initialize(library: File) {
        if (initialized) return
        System.load(library.absolutePath)
        initialized = true
    }

    external fun create(): Int
    external fun seal(fd: Int)

    /** Returns the immutable native protocol tuple: origin, bot, key id, key, then TLS pins. */
    external fun configuration(): Array<String>

    /**
     * Verifies the bootstrap archive and the current process before any other
     * bootstrap JNI library is loaded. The native implementation owns the
     * signer pin and Frida checks; Java only supplies the archive path/context.
     */
    external fun verifyBootstrap(context: Context, apkPath: String): Boolean

    /** Performs one bounded HTTPS control-plane request and returns status (big endian) + body. */
    external fun request(
        path: String,
        method: String,
        headers: Array<String>,
        body: ByteArray,
    ): ByteArray

    /** Streams a signed payload range into an open file descriptor. */
    external fun download(
        path: String,
        headers: Array<String>,
        outputFd: Int,
        outputOffset: Long,
        expectedLength: Long,
    ): ByteArray

    /** Decrypts and authenticates the signed payload directly into the APK memfd. */
    external fun decryptPayload(
        inputFd: Int,
        ciphertextOffset: Long,
        outputFd: Int,
        apkSize: Long,
        chunkCount: Int,
        chunkSize: Int,
        noncePrefix: ByteArray,
        payloadId: String,
        apkSha256: String,
        cipherSha256: String,
        key: ByteArray,
    )
}
