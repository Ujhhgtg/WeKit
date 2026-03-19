package moe.ouom.wekit.hooks.api.net

import moe.ouom.wekit.constants.PreferenceKeys
import moe.ouom.wekit.hooks.api.net.abc.IWePacketInterceptor
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.utils.logging.WeLogger
import java.util.concurrent.CopyOnWriteArrayList

object WePacketManager {

    private val listeners = CopyOnWriteArrayList<IWePacketInterceptor>()

    fun addInterceptor(interceptor: IWePacketInterceptor) = listeners.addIfAbsent(interceptor)

    fun removeInterceptor(interceptor: IWePacketInterceptor) = listeners.remove(interceptor)

    internal fun handleRequestTamper(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        if (WePrefs.getBoolOrFalse(PreferenceKeys.VERBOSE_LOG)) {
            val data = WeProtoData()
            data.fromBytes(reqBytes)
            WeLogger.logChunkedI(
                "WePacketInterceptor.Request",
                "Request: $uri, CGI=$cgiId, LEN=${reqBytes.size}, Data=${data.toJsonObject()}, Stack=${WeLogger.getStackTraceString()}"
            )
        }

        for (listener in listeners) {
            val tampered = listener.onRequest(uri, cgiId, reqBytes)
            if (tampered != null) return tampered
        }
        return null
    }

    internal fun handleResponseTamper(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (WePrefs.getBoolOrFalse(PreferenceKeys.VERBOSE_LOG)) {
            val data = WeProtoData()
            data.fromBytes(respBytes)
            WeLogger.logChunkedI(
                "WePacketInterceptor.Response",
                "Received: $uri, CGI=$cgiId, LEN=${respBytes.size}, Data=${data.toJsonObject()}"
            )
        }
        for (listener in listeners) {
            val tampered = listener.onResponse(uri, cgiId, respBytes)
            if (tampered != null) return tampered
        }
        return null
    }
}