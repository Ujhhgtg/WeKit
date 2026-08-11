package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadReceiptsTunnelControllerAuthCoordinationTest {
    @Test
    fun `terminal requires one matching ACK from the binder that owns the sent request`() {
        val queue = ControllerAuthOperationQueue()
        val key = AuthOperationKey(10, 1)
        val binder = Any()
        val terminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()

        assertTrue(queue.replaceGeneration(10))
        assertTrue(queue.enqueue(key, AuthOperationKind.LIST, terminals::add))
        assertTrue(queue.markSent(key, AuthOperationKind.LIST, binder))
        assertFalse(
            queue.complete(
                key,
                AuthOperationKind.LIST,
                binder,
                AuthOperationTerminal.Completed(emptyList()),
            ),
        )
        assertFalse(queue.acknowledge(key, AuthOperationKind.SELECT, binder))
        assertFalse(queue.acknowledge(key, AuthOperationKind.LIST, Any()))
        assertTrue(queue.acknowledge(key, AuthOperationKind.LIST, binder))
        assertFalse(queue.acknowledge(key, AuthOperationKind.LIST, binder))
        assertTrue(
            queue.complete(
                key,
                AuthOperationKind.LIST,
                binder,
                AuthOperationTerminal.Completed(emptyList()),
            ),
        )
        assertFalse(
            queue.complete(
                key,
                AuthOperationKind.LIST,
                binder,
                AuthOperationTerminal.Failed("late duplicate"),
            ),
        )
        assertEquals(1, terminals.size)
        assertTrue(terminals.single() is AuthOperationTerminal.Completed)
    }

    @Test
    fun `binder death terminates only its sent slots while unsent and independent slots survive`() {
        val queue = ControllerAuthOperationQueue()
        val deadBinder = Any()
        val replacementBinder = Any()
        val listKey = AuthOperationKey(20, 1)
        val selectKey = AuthOperationKey(20, 2)
        val listTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        val selectTerminals = mutableListOf<AuthOperationTerminal<Unit>>()

        assertTrue(queue.replaceGeneration(20))
        assertTrue(queue.enqueue(listKey, AuthOperationKind.LIST, listTerminals::add))
        assertTrue(queue.enqueue(selectKey, AuthOperationKind.SELECT, selectTerminals::add))
        assertTrue(queue.markSent(listKey, AuthOperationKind.LIST, deadBinder))

        assertEquals(1, queue.binderDied(deadBinder, "认证服务连接已断开"))
        assertEquals(
            listOf(AuthOperationTerminal.Failed("认证服务连接已断开")),
            listTerminals,
        )
        assertTrue(selectTerminals.isEmpty())
        assertEquals(setOf(selectKey), queue.unsentKeys())

        assertTrue(queue.markSent(selectKey, AuthOperationKind.SELECT, replacementBinder))
        assertTrue(queue.acknowledge(selectKey, AuthOperationKind.SELECT, replacementBinder))
        assertTrue(queue.cancel(selectKey, AuthOperationKind.SELECT))
        assertFalse(queue.cancel(selectKey, AuthOperationKind.SELECT))
        assertEquals(listOf(AuthOperationTerminal.Cancelled), selectTerminals)

        val oldBegin = AuthOperationKey(21, 1)
        val oldBeginTerminals = mutableListOf<AuthOperationTerminal<CloudflareLoginState>>()
        assertTrue(queue.replaceGeneration(21))
        assertTrue(queue.enqueue(oldBegin, AuthOperationKind.BEGIN, oldBeginTerminals::add))
        assertTrue(queue.replaceGeneration(22))
        assertEquals(listOf(AuthOperationTerminal.Superseded), oldBeginTerminals)
        assertTrue(queue.unsentKeys().isEmpty())
    }

    @Test
    fun `snapshot revision is binder scoped while restart preserves the expected auth generation`() {
        val tracker = ControllerAuthSnapshotTracker()
        val firstBinder = Any()
        val replacementBinder = Any()

        assertTrue(tracker.expectBegin(30))
        assertTrue(
            tracker.accept(
                firstBinder,
                controllerSnapshot(revision = 7, authGeneration = 0, restartRequired = true),
            ),
        )
        assertEquals(30, tracker.lastSeenAuthGeneration())
        assertFalse(
            tracker.accept(
                firstBinder,
                controllerSnapshot(revision = 7, authGeneration = 30),
            ),
        )
        assertTrue(
            tracker.accept(
                replacementBinder,
                controllerSnapshot(revision = 1, authGeneration = 0, restartRequired = true),
            ),
        )
        assertEquals(30, tracker.lastSeenAuthGeneration())

        assertTrue(tracker.clearExpectation(30))
        assertEquals(0, tracker.lastSeenAuthGeneration())
        assertFalse(tracker.clearExpectation(30))

        assertTrue(
            tracker.accept(
                replacementBinder,
                controllerSnapshot(revision = 2, authGeneration = 31),
            ),
        )
        assertEquals(31, tracker.lastSeenAuthGeneration())
    }

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
