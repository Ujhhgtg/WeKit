package dev.ujhhgtg.wekit.bootstrap.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class WireJsonTest {
    private fun report(errorCode: String? = null, errorMessage: String? = null) = InitializationReport(
        requestId = "request",
        installationId = "installation",
        processNonce = "nonce",
        initializationId = "initialization",
        stage = "INITIALIZATION",
        result = if (errorCode == null) "LOADED" else "FAILED",
        selfWxId = "wxid",
        selfAlias = null,
        identityReadState = "ALIAS_UNAVAILABLE",
        errorCode = errorCode,
        errorMessage = errorMessage,
    )

    @Test
    fun `failed reports include bounded diagnostics`() {
        val body = WireJson.body(report("PAYLOAD_LOAD_EACCES", "open failed: EACCES (Permission denied)"))

        assertEquals("PAYLOAD_LOAD_EACCES", body.string("errorCode"))
        assertEquals("open failed: EACCES (Permission denied)", body.string("errorMessage"))
    }

    @Test
    fun `successful reports omit absent diagnostics`() {
        val body = WireJson.body(report())

        assertFalse(body.containsKey("errorCode"))
        assertFalse(body.containsKey("errorMessage"))
    }
}
