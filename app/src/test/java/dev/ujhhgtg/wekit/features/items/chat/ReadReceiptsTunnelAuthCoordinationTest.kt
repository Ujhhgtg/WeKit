package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadReceiptsTunnelAuthCoordinationTest {
    @Test
    fun `public tunnel model canonicalizes bounded native input`() {
        val mutableHostnames = mutableListOf("Tunnel.Example.COM.", "api.example.com")
        val tunnel = ExistingTunnel.create(
            id = "550E8400-E29B-41D4-A716-446655440000",
            name = "  production  ",
            hostnames = mutableHostnames,
        )

        assertNotNull(tunnel)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", tunnel!!.id)
        assertEquals("production", tunnel.name)
        assertEquals(listOf("tunnel.example.com", "api.example.com"), tunnel.hostnames)
        mutableHostnames.clear()
        assertEquals(listOf("tunnel.example.com", "api.example.com"), tunnel.hostnames)
        assertThrows(UnsupportedOperationException::class.java) {
            (tunnel.hostnames as MutableList<String>).add("mutated.example.com")
        }
        assertNull(
            ExistingTunnel.create(
                id = tunnel.id,
                name = tunnel.name,
                hostnames = listOf("same.example.com", "SAME.EXAMPLE.COM"),
            ),
        )
        assertNull(ExistingTunnel.create("1-1-1-1-1", "short UUID", emptyList()))
        assertFalse(tunnel.toString().contains("service", ignoreCase = true))
    }

    @Test
    fun `version two credential payload round trips without secret toString leakage`() {
        val runToken = "secret-run-token"
        val payload = browserPayload(runToken)

        val encoded = TunnelCredentialPayloadCodec.encode(payload)
        val decoded = TunnelCredentialPayloadCodec.decode(encoded)

        assertTrue(decoded is TunnelCredentialDecode.Decoded)
        decoded as TunnelCredentialDecode.Decoded
        assertFalse(decoded.migratedLegacy)
        assertTrue(decoded.payload.runToken.contentEquals(runToken))
        assertEquals(TunnelCredentialSource.BROWSER_LOGIN, decoded.payload.source)
        assertEquals("https://tunnel.example.com", decoded.payload.canonicalHostname)
        assertEquals(18443, decoded.payload.fixedOriginPort)
        assertFalse(payload.toString().contains(runToken))
        assertTrue(payload.toString().contains("[redacted]"))
        assertTrue(encoded.toString(Charsets.UTF_8).contains("\"version\":2"))
        val jsonRead = StrictJsonReader.read(encoded.toString(Charsets.UTF_8))
        assertTrue(jsonRead is StrictJsonRead.Parsed)
        assertFalse(jsonRead.toString().contains(runToken))
        assertTrue(jsonRead.toString().contains("[redacted]"))
    }

    @Test
    fun `legacy raw token migrates only when plaintext is not JSON`() {
        val legacyToken = "legacy-secret-token"
        val migrated = TunnelCredentialPayloadCodec.decode(legacyToken.toByteArray())

        assertTrue(migrated is TunnelCredentialDecode.Decoded)
        migrated as TunnelCredentialDecode.Decoded
        assertTrue(migrated.migratedLegacy)
        assertEquals(TunnelCredentialSource.TOKEN, migrated.payload.source)
        assertTrue(migrated.payload.runToken.contentEquals(legacyToken))
        assertEquals("", migrated.payload.accountId)
        assertEquals("", migrated.payload.tunnelId)
        assertEquals("", migrated.payload.tunnelName)
        assertEquals("", migrated.payload.canonicalHostname)
        assertEquals(0, migrated.payload.fixedOriginPort)

        assertEquals(
            TunnelCredentialDecode.Invalid,
            TunnelCredentialPayloadCodec.decode("{not-json-secret".toByteArray()),
        )
        assertEquals(
            TunnelCredentialDecode.Invalid,
            TunnelCredentialPayloadCodec.decode(
                """{"version":1,"runToken":"must-not-fallback"}""".toByteArray(),
            ),
        )
        listOf("[]", "\"secret\"", "123", "true", "null").forEach { validJson ->
            assertEquals(
                TunnelCredentialDecode.Invalid,
                TunnelCredentialPayloadCodec.decode(validJson.toByteArray()),
            )
        }
    }

    @Test
    fun `strict codec rejects unknown missing malformed and non UTF8 payloads`() {
        val valid = TunnelCredentialPayloadCodec.encode(browserPayload("strict-token"))
            .toString(Charsets.UTF_8)
        val unknownKey = valid.dropLast(1) + ",\"extra\":true}"
        val missingField = valid.replace(Regex(",\"tunnelName\":\"[^\"]*\""), "")
        val unknownSource = valid.replace("BROWSER_LOGIN", "UNKNOWN")

        listOf(unknownKey, missingField, unknownSource).forEach { plaintext ->
            assertEquals(
                TunnelCredentialDecode.Invalid,
                TunnelCredentialPayloadCodec.decode(plaintext.toByteArray()),
            )
        }
        assertEquals(
            TunnelCredentialDecode.Invalid,
            TunnelCredentialPayloadCodec.decode(byteArrayOf(0xc3.toByte(), 0x28)),
        )
    }

    @Test
    fun `strict codec rejects semantic duplicate top level keys`() {
        val valid = TunnelCredentialPayloadCodec.encode(browserPayload("duplicate-token"))
            .toString(Charsets.UTF_8)
        val duplicateVersion = valid.replaceFirst(
            "\"version\":2",
            "\"version\":2,\"version\":2",
        )
        val duplicateEscapedRunToken = valid.replaceFirst(
            "\"runToken\":\"duplicate-token\"",
            """"runToken":"text with fake \"version\":2 and {[]} value","\u0072unToken":"replacement"""",
        )
        assertTrue(duplicateEscapedRunToken.contains("\\u0072unToken"))

        listOf(duplicateVersion, duplicateEscapedRunToken).forEach { duplicate ->
            assertEquals(
                TunnelCredentialDecode.Invalid,
                TunnelCredentialPayloadCodec.decode(duplicate.toByteArray()),
            )
        }
    }

    @Test
    fun `strict reader distinguishes legacy text from invalid or bounded JSON`() {
        val nestedDuplicate = """[{"key":1,"\u006bey":2}]"""
        val tooDeepArray = "[".repeat(StrictJsonReader.MAX_DEPTH + 1) + "0" +
            "]".repeat(StrictJsonReader.MAX_DEPTH + 1)
        val tooDeepObject = "{\"key\":".repeat(StrictJsonReader.MAX_DEPTH + 1) + "0" +
            "}".repeat(StrictJsonReader.MAX_DEPTH + 1)
        val maximumDepth = "[".repeat(StrictJsonReader.MAX_DEPTH) + "0" +
            "]".repeat(StrictJsonReader.MAX_DEPTH)

        listOf(nestedDuplicate, tooDeepArray, tooDeepObject).forEach { invalidJson ->
            assertEquals(StrictJsonRead.InvalidJson, StrictJsonReader.read(invalidJson))
            assertEquals(
                TunnelCredentialDecode.Invalid,
                TunnelCredentialPayloadCodec.decode(invalidJson.toByteArray()),
            )
        }
        assertTrue(StrictJsonReader.read(maximumDepth) is StrictJsonRead.Parsed)
        val deepBracketsInsideString = "\"${"[".repeat(StrictJsonReader.MAX_DEPTH + 10)}\""
        assertTrue(StrictJsonReader.read(deepBracketsInsideString) is StrictJsonRead.Parsed)
        assertEquals(StrictJsonRead.NotJson, StrictJsonReader.read("01"))
        assertTrue(StrictJsonReader.read("123") is StrictJsonRead.Parsed)

        val numericLegacy = TunnelCredentialPayloadCodec.decode("01".toByteArray())
        assertTrue(numericLegacy is TunnelCredentialDecode.Decoded)
        numericLegacy as TunnelCredentialDecode.Decoded
        assertTrue(numericLegacy.migratedLegacy)
        assertTrue(numericLegacy.payload.runToken.contentEquals("01"))
        assertEquals(
            TunnelCredentialDecode.Invalid,
            TunnelCredentialPayloadCodec.decode("123".toByteArray()),
        )
    }

    @Test
    fun `strict reader scans RFC numbers without changing numeric legacy classification`() {
        listOf("0", "-0", "1", "10", "-1", "0.1", "1e10", "1E+10", "-1.2e-3").forEach {
            assertTrue(StrictJsonReader.read(it) is StrictJsonRead.Parsed, it)
        }
        listOf("01", "-01", "1.", "1e", "1e+", "+1", ".1", "123abc").forEach {
            assertEquals(StrictJsonRead.NotJson, StrictJsonReader.read(it), it)
            val decoded = TunnelCredentialPayloadCodec.decode(it.toByteArray())
            assertTrue(decoded is TunnelCredentialDecode.Decoded, it)
            decoded as TunnelCredentialDecode.Decoded
            assertTrue(decoded.migratedLegacy, it)
            assertTrue(decoded.payload.runToken.contentEquals(it), it)
        }
    }

    @Test
    fun `credential payload rejects incomplete browser metadata and oversized fields`() {
        assertNull(
            TunnelCredentialPayload.create(
                runToken = "token",
                source = TunnelCredentialSource.BROWSER_LOGIN,
                accountId = "account_1",
                tunnelId = "not-a-uuid",
                tunnelName = "production",
                canonicalHostname = "https://tunnel.example.com",
                fixedOriginPort = 18443,
            ),
        )
        assertNull(
            TunnelCredentialPayload.create(
                runToken = "token",
                source = TunnelCredentialSource.BROWSER_LOGIN,
                accountId = "account_1",
                tunnelId = "1-1-1-1-1",
                tunnelName = "production",
                canonicalHostname = "https://tunnel.example.com",
                fixedOriginPort = 18443,
            ),
        )
        assertNull(
            TunnelCredentialPayload.create(
                runToken = "界".repeat(TunnelCredentialPayload.MAX_RUN_TOKEN_BYTES / 2 + 1),
                source = TunnelCredentialSource.TOKEN,
            ),
        )
        listOf(" token", "token ", "token\n").forEach { invalidLegacy ->
            assertEquals(
                TunnelCredentialDecode.Invalid,
                TunnelCredentialPayloadCodec.decode(invalidLegacy.toByteArray()),
            )
        }
        assertEquals(
            TunnelCredentialDecode.Invalid,
            TunnelCredentialPayloadCodec.decode(ByteArray(TunnelCredentialPayloadCodec.MAX_BYTES + 1)),
        )
    }

    @Test
    fun `browser unattended startup requires browser source exact canonical hostname and fixed port`() {
        val payload = browserPayload("startup-token")

        assertEquals(
            TunnelCredentialStartupDecision.START,
            decideCredentialStartup(
                payload,
                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                "https://tunnel.example.com",
                18443,
            ),
        )
        assertEquals(
            TunnelCredentialStartupDecision.NEEDS_USER_ACTION,
            decideCredentialStartup(
                payload,
                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                "https://TUNNEL.example.com/",
                18443,
            ),
        )
        assertEquals(
            TunnelCredentialStartupDecision.NEEDS_USER_ACTION,
            decideCredentialStartup(
                payload,
                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                "https://other.example.com",
                18443,
            ),
        )
        assertEquals(
            TunnelCredentialStartupDecision.NEEDS_USER_ACTION,
            decideCredentialStartup(
                payload,
                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                "https://tunnel.example.com",
                18444,
            ),
        )
        assertEquals(
            TunnelCredentialStartupDecision.NEEDS_USER_ACTION,
            decideCredentialStartup(
                payload,
                ReadReceiptsTunnelMode.TOKEN,
                "https://tunnel.example.com",
                18443,
            ),
        )
    }

    @Test
    fun `token startup does not apply browser metadata matching`() {
        val legacy = TunnelCredentialPayload.create(
            runToken = "legacy-startup-token",
            source = TunnelCredentialSource.TOKEN,
        )!!
        val versionedToken = TunnelCredentialPayload.create(
            runToken = "versioned-startup-token",
            source = TunnelCredentialSource.TOKEN,
            canonicalHostname = "https://token.example.com",
            fixedOriginPort = 18080,
        )!!

        assertEquals(
            TunnelCredentialStartupDecision.START,
            decideCredentialStartup(
                legacy,
                ReadReceiptsTunnelMode.TOKEN,
                "https://different.example.com",
                65535,
            ),
        )
        assertEquals(
            TunnelCredentialStartupDecision.START,
            decideCredentialStartup(
                versionedToken,
                ReadReceiptsTunnelMode.TOKEN,
                "https://different.example.com",
                1,
            ),
        )
        assertEquals(
            TunnelCredentialStartupDecision.NEEDS_USER_ACTION,
            decideCredentialStartup(
                versionedToken,
                ReadReceiptsTunnelMode.BROWSER_LOGIN,
                "https://token.example.com",
                18080,
            ),
        )
    }

    private fun browserPayload(runToken: String): TunnelCredentialPayload =
        TunnelCredentialPayload.create(
            runToken = runToken,
            source = TunnelCredentialSource.BROWSER_LOGIN,
            accountId = "account_1",
            tunnelId = "550E8400-E29B-41D4-A716-446655440000",
            tunnelName = "production",
            canonicalHostname = "https://Tunnel.Example.COM/",
            fixedOriginPort = 18443,
        )!!
}
