package dev.ujhhgtg.wekit.features.items.chat

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReadReceiptsTunnelNativeParserTest {
    @Test
    fun `login status maps every native state without exposing raw JSON`() {
        val waiting = assertPresent(
            ReadReceiptsTunnelNativeParser.parseLoginStatus(
                statusJson(state = "WAITING", authorizationUrl = AUTHORIZATION_URL),
            ),
        )
        assertEquals(7, waiting.generation)
        assertEquals(ReadReceiptsTunnelState.STARTING, waiting.loginState.state)
        assertEquals(AUTHORIZATION_URL, waiting.loginState.authorizationUrl)

        val authorized = assertPresent(
            ReadReceiptsTunnelNativeParser.parseLoginStatus(
                statusJson(
                    state = "AUTHORIZED",
                    authorizationUrl = AUTHORIZATION_URL,
                    accountId = "account_1",
                    selectedTunnelId = TUNNEL_ID,
                    selectedHostname = PUBLIC_ROOT,
                ),
            ),
        )
        assertEquals(ReadReceiptsTunnelState.CONNECTED, authorized.loginState.state)
        assertEquals("account_1", authorized.accountId)
        assertEquals(TUNNEL_ID, authorized.selectedTunnelId)
        assertEquals(PUBLIC_ROOT, authorized.selectedHostname)

        val failureMessage = "界".repeat(128)
        val failed = assertPresent(
            ReadReceiptsTunnelNativeParser.parseLoginStatus(
                statusJson(
                    state = "FAILED",
                    authorizationUrl = AUTHORIZATION_URL,
                    error = failureMessage,
                ),
            ),
        )
        assertEquals(ReadReceiptsTunnelState.FAILED, failed.loginState.state)
        assertEquals(failureMessage, failed.loginState.error)

        val stopped = assertPresent(
            ReadReceiptsTunnelNativeParser.parseLoginStatus(statusJson(state = "STOPPED")),
        )
        assertEquals(ReadReceiptsTunnelState.STOPPED, stopped.loginState.state)
        assertNull(stopped.loginState.authorizationUrl)
        assertNull(stopped.loginState.error)
    }

    @Test
    fun `login status requires exact complete duplicate free schema and positive generation`() {
        val valid = statusJson(state = "WAITING", authorizationUrl = AUTHORIZATION_URL)
        val unknown = valid.dropLast(1) + ",\"unknown\":true}"
        val missing = valid.replace(Regex(",\"accountId\":\"[^\"]*\""), "")
        val duplicate = valid.replaceFirst(
            "\"state\":\"WAITING\"",
            "\"state\":\"WAITING\",\"\\u0073tate\":\"WAITING\"",
        )

        listOf(unknown, missing, duplicate, "$valid trailing", "[]", "true").forEach {
            assertNull(ReadReceiptsTunnelNativeParser.parseLoginStatus(it))
        }
        assertNull(
            ReadReceiptsTunnelNativeParser.parseLoginStatus(
                statusJson(generation = 0, state = "STOPPED"),
            ),
        )
        assertNull(
            ReadReceiptsTunnelNativeParser.parseLoginStatus(
                statusJson(generation = -1, state = "STOPPED"),
            ),
        )
    }

    @Test
    fun `login status pins Cloudflare authorization and callback URLs`() {
        val invalidAuthorizationUrls = listOf(
            "http://dash.cloudflare.com/argotunnel?callback=$ENCODED_CALLBACK",
            "https://dash.cloudflare.com:444/argotunnel?callback=$ENCODED_CALLBACK",
            "https://user@dash.cloudflare.com/argotunnel?callback=$ENCODED_CALLBACK",
            "https://dash.cloudflare.com/other?callback=$ENCODED_CALLBACK",
            "https://dash.cloudflare.com/argotunnel?callback=$ENCODED_CALLBACK#fragment",
            "https://example.com/argotunnel?callback=$ENCODED_CALLBACK",
            "https://dash.cloudflare.com/argotunnel",
            "https://dash.cloudflare.com/argotunnel?callback=$ENCODED_CALLBACK&callback=$ENCODED_CALLBACK",
            "https://dash.cloudflare.com/argotunnel?callback=$ENCODED_CALLBACK&extra=true",
            "https://dash.cloudflare.com/argotunnel?callback=http%3A%2F%2Flogin.cloudflareaccess.org%2Fnamespace",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Flogin.cloudflareaccess.org%3A444%2Fnamespace",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Fuser%40login.cloudflareaccess.org%2Fnamespace",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Fexample.com%2Fnamespace",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Flogin.cloudflareaccess.org%2F",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Flogin.cloudflareaccess.org%2Fa%2Fb",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Flogin.cloudflareaccess.org%2F${"a".repeat(20)}%252F${"b".repeat(22)}%3D",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Flogin.cloudflareaccess.org%2F${"a".repeat(42)}%252e%3D",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Flogin.cloudflareaccess.org%2Fbad%2F%252e%252e%2F${"a".repeat(43)}%3D",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Flogin.cloudflareaccess.org%2Fnamespace%3Fx%3D1",
            "https://dash.cloudflare.com/argotunnel?callback=https%3A%2F%2Flogin.cloudflareaccess.org%2Fnamespace%23fragment",
        )

        invalidAuthorizationUrls.forEach { authorizationUrl ->
            assertNull(
                ReadReceiptsTunnelNativeParser.parseLoginStatus(
                    statusJson(state = "WAITING", authorizationUrl = authorizationUrl),
                ),
                authorizationUrl,
            )
        }
    }

    @Test
    fun `login status enforces state account error and selection invariants`() {
        val invalid = listOf(
            statusJson(state = "UNKNOWN"),
            statusJson(state = "WAITING"),
            statusJson(state = "WAITING", authorizationUrl = AUTHORIZATION_URL, accountId = "account"),
            statusJson(state = "AUTHORIZED", authorizationUrl = AUTHORIZATION_URL),
            statusJson(
                state = "AUTHORIZED",
                authorizationUrl = AUTHORIZATION_URL,
                accountId = "bad account",
            ),
            statusJson(
                state = "AUTHORIZED",
                authorizationUrl = AUTHORIZATION_URL,
                accountId = "a".repeat(33),
            ),
            statusJson(
                state = "AUTHORIZED",
                authorizationUrl = AUTHORIZATION_URL,
                accountId = "account",
                error = "unexpected",
            ),
            statusJson(state = "FAILED", authorizationUrl = AUTHORIZATION_URL),
            statusJson(
                state = "FAILED",
                authorizationUrl = AUTHORIZATION_URL,
                error = "x".repeat(257),
            ),
            statusJson(
                state = "FAILED",
                authorizationUrl = AUTHORIZATION_URL,
                error = "bad\nerror",
            ),
            statusJson(state = "STOPPED", authorizationUrl = AUTHORIZATION_URL),
            statusJson(
                state = "AUTHORIZED",
                authorizationUrl = AUTHORIZATION_URL,
                accountId = "account",
                selectedTunnelId = TUNNEL_ID,
            ),
            statusJson(
                state = "AUTHORIZED",
                authorizationUrl = AUTHORIZATION_URL,
                accountId = "account",
                selectedTunnelId = TUNNEL_ID.uppercase(),
                selectedHostname = PUBLIC_ROOT,
            ),
            statusJson(
                state = "AUTHORIZED",
                authorizationUrl = AUTHORIZATION_URL,
                accountId = "account",
                selectedTunnelId = TUNNEL_ID,
                selectedHostname = "$PUBLIC_ROOT/",
            ),
        )

        invalid.forEach { assertNull(ReadReceiptsTunnelNativeParser.parseLoginStatus(it), it) }
    }

    @Test
    fun `tunnel list parses bounded public DTOs and native error`() {
        val parsed = assertPresent(
            ReadReceiptsTunnelNativeParser.parseTunnelList(
                listJson(
                    tunnels = listOf(
                        tunnelJson(TUNNEL_ID, "production", listOf("tunnel.example.com")),
                    ),
                ),
            ),
        )
        assertEquals(9, parsed.generation)
        assertNull(parsed.error)
        assertEquals(1, parsed.tunnels.size)
        assertEquals(TUNNEL_ID, parsed.tunnels.single().id)
        assertEquals("production", parsed.tunnels.single().name)
        assertEquals(listOf("tunnel.example.com"), parsed.tunnels.single().hostnames)
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException::class.java) {
            (parsed.tunnels as MutableList<ExistingTunnel>).clear()
        }

        val failed = assertPresent(
            ReadReceiptsTunnelNativeParser.parseTunnelList(
                listJson(tunnels = emptyList(), error = "listing failed"),
            ),
        )
        assertEquals("listing failed", failed.error)
        assertEquals(emptyList<ExistingTunnel>(), failed.tunnels)
    }

    @Test
    fun `tunnel list rejects unknown native service duplicate keys and noncanonical values`() {
        val validTunnel = tunnelJson(TUNNEL_ID, "production", listOf("tunnel.example.com"))
        val valid = listJson(listOf(validTunnel))
        val nestedDuplicate = valid.replaceFirst(
            "\"id\":\"$TUNNEL_ID\"",
            "\"id\":\"$TUNNEL_ID\",\"\\u0069d\":\"$TUNNEL_ID\"",
        )
        val withService = listJson(
            listOf(validTunnel.dropLast(1) + ",\"service\":\"secret-token\"}"),
        )
        val uppercaseId = listJson(
            listOf(tunnelJson(TUNNEL_ID.uppercase(), "production", listOf("tunnel.example.com"))),
        )
        val noncanonicalHostname = listJson(
            listOf(tunnelJson(TUNNEL_ID, "production", listOf("Tunnel.Example.COM"))),
        )
        val duplicateHostname = listJson(
            listOf(tunnelJson(TUNNEL_ID, "production", listOf("same.example.com", "same.example.com"))),
        )

        listOf(
            nestedDuplicate,
            withService,
            uppercaseId,
            noncanonicalHostname,
            duplicateHostname,
            valid.dropLast(1) + ",\"unknown\":true}",
            valid.replace("\"generation\":9", "\"generation\":0"),
            listJson(listOf(tunnelJson(TUNNEL_ID, " production ", emptyList()))),
            listJson(listOf(tunnelJson(TUNNEL_ID, "界".repeat(43), emptyList()))),
            listJson(emptyList(), error = "bad\nerror"),
            listJson(listOf(validTunnel), error = "unexpected"),
        ).forEach { assertNull(ReadReceiptsTunnelNativeParser.parseTunnelList(it), it) }
    }

    @Test
    fun `native JSON and list counts are bounded`() {
        val tooManyTunnels = List(101) { index ->
            tunnelJson(
                "550e8400-e29b-41d4-a716-${index.toString().padStart(12, '0')}",
                "tunnel-$index",
                emptyList(),
            )
        }
        val tooManyHostnames = List(101) { "host-$it.example.com" }

        assertNull(ReadReceiptsTunnelNativeParser.parseTunnelList(listJson(tooManyTunnels)))
        assertNull(
            ReadReceiptsTunnelNativeParser.parseTunnelList(
                listJson(listOf(tunnelJson(TUNNEL_ID, "production", tooManyHostnames))),
            ),
        )
        assertNull(
            ReadReceiptsTunnelNativeParser.parseLoginStatus(
                " ".repeat(ReadReceiptsTunnelNativeParser.MAX_JSON_BYTES + 1),
            ),
        )
        val tooDeep = "[".repeat(StrictJsonReader.MAX_DEPTH + 1) + "0" +
            "]".repeat(StrictJsonReader.MAX_DEPTH + 1)
        assertNull(ReadReceiptsTunnelNativeParser.parseLoginStatus(tooDeep))
        assertNull(ReadReceiptsTunnelNativeParser.parseTunnelList(tooDeep))
    }

    private fun statusJson(
        generation: Long = 7,
        state: String,
        authorizationUrl: String = "",
        accountId: String = "",
        error: String = "",
        selectedTunnelId: String = "",
        selectedHostname: String = "",
    ): String = buildJsonObject {
        put("generation", generation)
        put("authorizationUrl", authorizationUrl)
        put("state", state)
        put("accountId", accountId)
        put("error", error)
        put("selectedTunnelId", selectedTunnelId)
        put("selectedHostname", selectedHostname)
    }.toString()

    private fun listJson(tunnels: List<String>, error: String? = null): String {
        val tunnelsJson = tunnels.joinToString(prefix = "[", postfix = "]")
        return if (error == null) {
            """{"generation":9,"tunnels":$tunnelsJson}"""
        } else {
            """{"generation":9,"tunnels":$tunnelsJson,"error":${jsonString(error)}}"""
        }
    }

    private fun tunnelJson(id: String, name: String, hostnames: List<String>): String =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("hostnames", buildJsonArray { hostnames.forEach { add(JsonPrimitive(it)) } })
        }.toString()

    private fun jsonString(value: String): String =
        buildJsonArray { add(JsonPrimitive(value)) }.toString().let {
        it.substring(1, it.length - 1)
    }

    private fun <T : Any> assertPresent(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    private companion object {
        const val TUNNEL_ID = "550e8400-e29b-41d4-a716-446655440000"
        const val PUBLIC_ROOT = "https://tunnel.example.com"
        const val ENCODED_CALLBACK =
            "https%3A%2F%2Flogin.cloudflareaccess.org%2Faaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa%3D"
        const val AUTHORIZATION_URL =
            "https://dash.cloudflare.com/argotunnel?callback=$ENCODED_CALLBACK"
    }
}
