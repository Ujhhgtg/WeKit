package dev.ujhhgtg.wekit.features.api.net

import dev.ujhhgtg.wekit.features.api.net.abc.IWePacketInterceptor
import java.util.concurrent.CopyOnWriteArrayList

object WePacketManager {
    private val listeners = CopyOnWriteArrayList<IWePacketInterceptor>()

    fun addInterceptor(interceptor: IWePacketInterceptor) = listeners.addIfAbsent(interceptor)

    fun removeInterceptor(interceptor: IWePacketInterceptor) = listeners.remove(interceptor)

    fun hasInterceptors(): Boolean = listeners.isNotEmpty()

    fun handleRequestTamper(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        for (listener in listeners) {
            val tampered = listener.onRequest(uri, cgiId, reqBytes)
            if (tampered != null) return tampered
        }
        return null
    }

    fun handleResponseTamper(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        for (listener in listeners) {
            val tampered = listener.onResponse(uri, cgiId, respBytes)
            if (tampered != null) return tampered
        }
        return null
    }
}
