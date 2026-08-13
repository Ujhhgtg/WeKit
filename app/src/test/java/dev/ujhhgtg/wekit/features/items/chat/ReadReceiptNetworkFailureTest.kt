package dev.ujhhgtg.wekit.features.items.chat

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ReadReceiptNetworkFailureTest {
    @Test
    fun `failure categories are fixed and never include exception messages`() {
        val secret = "tunnel-secret-must-not-escape"
        val cases =
            listOf(
                SocketTimeoutException(secret) to "timeout",
                UnknownHostException(secret) to "dns",
                SSLException(secret) to "tls",
                ConnectException(secret) to "connect",
                IOException(secret) to "io",
                IllegalStateException(secret) to "response",
            )

        for ((failure, expected) in cases) {
            val category = readReceiptNetworkFailureCategory(failure)
            assertEquals(expected, category)
            assertFalse(category.contains(secret))
            assert(category.toByteArray(Charsets.UTF_8).size <= 16)
        }
    }
}
