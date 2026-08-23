package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReadReceiptsTunnelControllerAuthCoordinationTest {
    @Test
    fun `only a complete authoritative browser snapshot proposes metadata replacement`() {
        val browser = CommittedTunnelCredentialMetadata(
            source = TunnelCredentialSource.BROWSER_LOGIN,
            accountId = "account_1",
            tunnelId = "550e8400-e29b-41d4-a716-446655440000",
            tunnelName = "Primary",
            canonicalHostname = "https://tunnel.example.com",
            fixedOriginPort = 18443,
        )
        val token = browser.copy(
            source = TunnelCredentialSource.TOKEN,
            accountId = "",
            tunnelId = "",
            tunnelName = "",
        )
        val invalidBrowser = browser.copy(
            canonicalHostname = "https://TUNNEL.example.com",
            fixedOriginPort = 0,
        )

        assertEquals(
            BrowserMetadataRebindDecision.Keep,
            controllerSnapshot(metadataLoading = true, metadata = browser)
                .browserMetadataRebindDecision(),
        )
        assertEquals(
            BrowserMetadataRebindDecision.Keep,
            controllerSnapshot(metadata = null).browserMetadataRebindDecision(),
        )
        assertEquals(
            BrowserMetadataRebindDecision.Keep,
            controllerSnapshot(metadata = token).browserMetadataRebindDecision(),
        )
        assertEquals(
            BrowserMetadataRebindDecision.Keep,
            controllerSnapshot(metadata = invalidBrowser).browserMetadataRebindDecision(),
        )
        assertEquals(
            BrowserMetadataRebindDecision.Replace(
                CommittedBrowserTunnelMetadata(
                    accountId = "account_1",
                    tunnelId = "550e8400-e29b-41d4-a716-446655440000",
                    tunnelName = "Primary",
                    canonicalHostname = "https://tunnel.example.com",
                    fixedOriginPort = 18443,
                ),
            ),
            controllerSnapshot(metadata = browser).browserMetadataRebindDecision(),
        )
    }

    @Test
    fun `snapshot rejects structurally inconsistent login authority`() {
        assertThrows(IllegalArgumentException::class.java) {
            controllerSnapshot(
                authGeneration = 0,
                loginState = CloudflareLoginState(null, ReadReceiptsTunnelState.STARTING, null),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            controllerSnapshot(authGeneration = 41, restartRequired = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            controllerSnapshot(accountId = "account_1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            controllerSnapshot(
                restartRequired = true,
                loginState = CloudflareLoginState(null, ReadReceiptsTunnelState.FAILED, "failed"),
            )
        }
    }

    @Test
    fun `accepted snapshot owns an immutable tunnel list`() {
        val source = mutableListOf(
            checkNotNull(
                ExistingTunnel.create(
                    "550e8400-e29b-41d4-a716-446655440000",
                    "Primary",
                    listOf("tunnel.example.com"),
                ),
            ),
        )
        val snapshot = controllerSnapshot(
            authGeneration = 42,
            loginState = CloudflareLoginState(
                AUTHORIZATION_URL,
                ReadReceiptsTunnelState.CONNECTED,
                null,
            ),
            accountId = "account_1",
            tunnels = source,
        )

        source.clear()

        assertEquals(1, snapshot.tunnels.size)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (snapshot.tunnels as MutableList<ExistingTunnel>).clear()
        }
    }

    private fun controllerSnapshot(
        revision: Long = 1,
        authGeneration: Long = 0,
        restartRequired: Boolean = false,
        loginState: CloudflareLoginState = CloudflareLoginState(
            null,
            ReadReceiptsTunnelState.STOPPED,
            null,
        ),
        accountId: String = "",
        tunnels: List<ExistingTunnel> = emptyList(),
        metadataLoading: Boolean = false,
        metadata: CommittedTunnelCredentialMetadata? = null,
    ): ControllerAuthSnapshot = ControllerAuthSnapshot(
        revision = revision,
        authGeneration = authGeneration,
        restartRequired = restartRequired,
        loginState = loginState,
        accountId = accountId,
        tunnels = tunnels,
        metadataLoading = metadataLoading,
        committedMetadata = metadata,
    )

    private companion object {
        const val AUTHORIZATION_URL =
            "https://dash.cloudflare.com/argotunnel?callback=" +
                "https%3A%2F%2Flogin.cloudflareaccess.org%2F" +
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa%3D"
    }
}
