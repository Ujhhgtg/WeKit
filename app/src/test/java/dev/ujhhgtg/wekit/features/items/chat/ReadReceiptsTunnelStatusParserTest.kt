package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReadReceiptsTunnelStatusParserTest {

    @Test
    fun `notification rejection decodes its semantic error code`() {
        val decoded = decodeReadReceiptsTunnelStatus(statusWire(
            state = "NEEDS_USER_ACTION",
            errorCode = "NOTIFICATIONS_DISABLED",
            needsNotificationSettings = true,
        ))!!

        assertEquals(41L, decoded.generation)
        assertEquals(ReadReceiptsTunnelState.NEEDS_USER_ACTION, decoded.status.state)
        assertEquals(
            ReadReceiptsTunnelErrorCode.NOTIFICATIONS_DISABLED,
            decoded.status.errorCode,
        )
        assertEquals(true, decoded.status.needsNotificationSettings)
    }

    @Test
    fun `connected and stopped statuses have no error code`() {
        val connected = decodeReadReceiptsTunnelStatus(statusWire(
            state = "CONNECTED",
            publicUrl = "https://receipts.example.com",
        ))!!.status
        val stopped = decodeReadReceiptsTunnelStatus(statusWire(state = "STOPPED"))!!.status

        assertNull(connected.errorCode)
        assertNull(stopped.errorCode)
    }

    @Test
    fun `unknown wire error code is rejected`() {
        assertNull(decodeReadReceiptsTunnelStatus(statusWire(
            state = "FAILED",
            errorCode = "TRANSLATED_OR_UNKNOWN_FAILURE",
        )))
    }

    @Test
    fun `status parser requires exact keys and value types`() {
        assertNull(
            decodeReadReceiptsTunnelStatus(
                statusWire(state = "FAILED", errorCode = "UNEXPECTED_FAILURE") +
                    ("unexpected" to true),
            ),
        )
        assertNull(decodeReadReceiptsTunnelStatus(
            statusWire(state = "FAILED", errorCode = "UNEXPECTED_FAILURE") -
                ReadReceiptsTunnelProtocol.KEY_PUBLIC_URL,
        ))
        assertNull(decodeReadReceiptsTunnelStatus(
            statusWire(state = "FAILED", errorCode = "UNEXPECTED_FAILURE").toMutableMap().apply {
                put(ReadReceiptsTunnelProtocol.KEY_GENERATION, "41")
            },
        ))
    }

    @Test
    fun `healthy terminal states reject a smuggled error code`() {
        assertNull(decodeReadReceiptsTunnelStatus(statusWire(
            state = "CONNECTED",
            publicUrl = "https://receipts.example.com",
            errorCode = "UNEXPECTED_FAILURE",
        )))
        assertNull(decodeReadReceiptsTunnelStatus(statusWire(
            state = "STOPPED",
            errorCode = "UNEXPECTED_FAILURE",
        )))
    }

    private fun statusWire(
        state: String,
        publicUrl: String? = null,
        errorCode: String? = null,
        needsNotificationSettings: Boolean = false,
    ): Map<String, Any?> = mapOf(
        ReadReceiptsTunnelProtocol.KEY_GENERATION to 41L,
        ReadReceiptsTunnelProtocol.KEY_STATE to state,
        ReadReceiptsTunnelProtocol.KEY_PUBLIC_URL to publicUrl,
        ReadReceiptsTunnelProtocol.KEY_ERROR_CODE to errorCode,
        ReadReceiptsTunnelProtocol.KEY_CREDENTIAL_EXISTS to true,
        ReadReceiptsTunnelProtocol.KEY_NEEDS_NOTIFICATION_SETTINGS to
            needsNotificationSettings,
        ReadReceiptsTunnelProtocol.KEY_CLIENT_NONCE to "0123456789abcdef0123456789abcdef",
    )
}
