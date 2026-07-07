package nuke.data.cipher

object NativeCrypto {
    @JvmStatic
    external fun nativeAttestModule(bArr: ByteArray): Boolean

    @JvmStatic
    external fun nativeConfDecrypt(str: String): String

    @JvmStatic
    external fun nativeConfEncrypt(str: String): String

    @JvmStatic
    external fun nativeConfKeyName(str: String): String

    @JvmStatic
    external fun nativeDecryptJsonBytes(
        str: String,
        str2: String,
        str3: String,
        str4: String,
        str5: String
    ): ByteArray

    @JvmStatic
    external fun nativeEncryptJsonBytes(bArr: ByteArray?, str: String?): Array<String>

    @JvmStatic
    external fun nativeIsConfigured(): Boolean

    @JvmStatic
    external fun nativeKid(): String

    @JvmStatic
    external fun nativeSelfTest(): Boolean

    @JvmStatic
    external fun nativeSignClientPayload(str: String): String
}
