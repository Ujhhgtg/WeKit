package dev.ujhhgtg.wekit.bootstrap.auth

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger

class WireProtocolTest {
    // Public fixed signature from the Python / Node WebCrypto / real workerd interoperability probe.
    private val raw = "456645bfbbd0a4bab8459b0859f3ad076fe00b000b1564dfe8da3b31f4ce938167bc190bfd53c10d8d676f08b2531c3b40f298d79fb7f9fe2d9d3d143ad32bee".unhex()
    private val der = "30440220456645bfbbd0a4bab8459b0859f3ad076fe00b000b1564dfe8da3b31f4ce9381022067bc190bfd53c10d8d676f08b2531c3b40f298d79fb7f9fe2d9d3d143ad32bee".unhex()

    @Test
    fun `converts the externally verified signature without changing either integer`() {
        assertArrayEquals(raw, WireProtocol.derToRaw(der))
        assertArrayEquals(der, WireProtocol.rawToDer(raw))
    }

    @Test
    fun `handles canonical positive sign padding and the smallest valid integers`() {
        val large = "c9dd84905fb2ef64eeb47ad29c9918f5400ba7fa93048bb79d97ab33e194954dd01d33e808be02ee2e3c69adf1f119b445602ada0862650409a759d3500121fa".unhex()
        val largeDer = "3046022100c9dd84905fb2ef64eeb47ad29c9918f5400ba7fa93048bb79d97ab33e194954d022100d01d33e808be02ee2e3c69adf1f119b445602ada0862650409a759d3500121fa".unhex()
        assertArrayEquals(largeDer, WireProtocol.rawToDer(large))
        assertArrayEquals(large, WireProtocol.derToRaw(largeDer))
        val small = ByteArray(64).apply { this[31] = 1; this[63] = 1 }
        assertArrayEquals("3006020101020101".unhex(), WireProtocol.rawToDer(small))
        assertArrayEquals(small, WireProtocol.derToRaw("3006020101020101".unhex()))
    }

    @Test
    fun `rejects malformed noncanonical DER rather than guessing signature encoding`() {
        for (bad in listOf(
            "300702020001020101", // nonminimal leading zero
            "3006020180020101", // negative integer
            "3006020100020101", // zero r
            "3006020101020100", // zero s
            "300602010102010100", // trailing byte
            "308106020101020101", // unnecessary long-form length
            "3006020101030101", // wrong integer tag
            "3006020201020101", // truncated / inconsistent integer length
        )) {
            assertThrows(IllegalArgumentException::class.java) { WireProtocol.derToRaw(bad.unhex()) }
        }
    }

    @Test
    fun `rejects zero and curve-order P1363 values and incorrect widths`() {
        val order = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
            .toByteArray().takeLast(32).toByteArray()
        val one = ByteArray(32).apply { this[31] = 1 }
        for (bad in listOf(ByteArray(64), ByteArray(63), ByteArray(65), order + one, one + order)) {
            assertThrows(IllegalArgumentException::class.java) { WireProtocol.rawToDer(bad) }
        }
    }

    @Test
    fun `request canonical bytes match the frozen cross-runtime vector including empty grant line`() {
        val body = """{"requestId":"request-test-1","requestedPayloadId":null}""".toByteArray()
        val canonical = WireProtocol.requestBytes("POST", "/v1/initializations", body,
            WireProtocol.encode(ByteArray(32) { (it + 0x20).toByte() }), "challenge-test-1",
            WireProtocol.encode(ByteArray(32) { (it + 0x40).toByte() }), "request-test-1",
            WireProtocol.encode(ByteArray(32) { (it + 0x60).toByte() }))
        assertEquals("4d042aeed03d4171a8c5dbbaec3c47e5a71300a4e3c4c9e95cb7a34cbea644f8", WireProtocol.sha256(canonical))
    }

    @Test
    fun `Base64url rejects padding bad alphabet width and noncanonical unused bits`() {
        for (bad in listOf("AA=", "AA+", "AA/", "A", "AB")) {
            assertThrows(IllegalArgumentException::class.java) { WireProtocol.decode(bad) }
        }
        assertThrows(IllegalArgumentException::class.java) { WireProtocol.decode("AA", 32) }
        assertArrayEquals(byteArrayOf(0), WireProtocol.decode("AA", 1))
    }

    private fun String.unhex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
