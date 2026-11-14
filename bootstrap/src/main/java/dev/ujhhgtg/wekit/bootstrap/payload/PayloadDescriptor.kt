package dev.ujhhgtg.wekit.bootstrap.payload

/** Constructed only after the release envelope signature and strict JSON have been verified. */
data class PayloadDescriptor(
    val payloadId: String,
    val entryClass: String,
    val apkSha256: String,
    val apkSize: Long,
    val cipherSha256: String,
    val cipherSize: Long,
    val noncePrefix: ByteArray,
    val chunkCount: Int,
    val chunkSize: Int = CHUNK_SIZE,
) {
    init {
        require(payloadId.isNotEmpty() && payloadId.length <= 128 &&
            payloadId.none { it == '\r' || it == '\n' || it == '\u0000' })
        require(entryClass.isNotBlank() && entryClass.length <= 512)
        require(apkSha256.matches(SHA256) && cipherSha256.matches(SHA256))
        require(apkSize in 1..MAX_APK_SIZE)
        require(chunkSize == CHUNK_SIZE)
        require(chunkCount.toLong() == (apkSize + chunkSize - 1) / chunkSize)
        require(cipherSize == apkSize + TAG_SIZE * chunkCount.toLong())
        require(noncePrefix.size == 8)
    }

    companion object {
        const val CHUNK_SIZE = 1024 * 1024
        const val TAG_SIZE = 16
        const val MAX_APK_SIZE = 128L * 1024 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}
