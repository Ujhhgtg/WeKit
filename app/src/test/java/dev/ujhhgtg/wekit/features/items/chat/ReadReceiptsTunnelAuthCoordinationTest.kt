package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ReadReceiptsTunnelAuthCoordinationTest {
    @Test
    fun `new auth generation supersedes every old operation exactly once`() {
        val registry = AuthOperationRegistry()
        val listTerminals = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        val selectTerminals = mutableListOf<AuthOperationTerminal<Unit>>()

        assertTrue(registry.replaceGeneration(10))
        assertTrue(
            registry.register(AuthOperationKey(10, 1), AuthOperationKind.LIST, listTerminals::add),
        )
        assertTrue(
            registry.register(AuthOperationKey(10, 2), AuthOperationKind.SELECT, selectTerminals::add),
        )

        assertTrue(registry.replaceGeneration(11))
        assertEquals(listOf(AuthOperationTerminal.Superseded), listTerminals)
        assertEquals(listOf(AuthOperationTerminal.Superseded), selectTerminals)
        assertFalse(
            registry.complete(
                AuthOperationKey(10, 1),
                AuthOperationKind.LIST,
                AuthOperationTerminal.Completed(emptyList<ExistingTunnel>()),
            ),
        )
        assertFalse(registry.replaceGeneration(9))
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun `cancel timeout duplicate and late old terminals deliver once`() {
        val registry = AuthOperationRegistry()
        registry.replaceGeneration(20)
        val cancelled = mutableListOf<AuthOperationTerminal<Unit>>()
        val timedOut = mutableListOf<AuthOperationTerminal<CloudflareLoginState>>()
        val failed = mutableListOf<AuthOperationTerminal<Unit>>()
        val cancelKey = AuthOperationKey(20, 1)
        val timeoutKey = AuthOperationKey(20, 2)
        val failureKey = AuthOperationKey(20, 3)
        registry.register(cancelKey, AuthOperationKind.CANCEL, cancelled::add)
        registry.register(timeoutKey, AuthOperationKind.BEGIN, timedOut::add)
        registry.register(failureKey, AuthOperationKind.LOGOUT, failed::add)

        assertTrue(registry.cancel(cancelKey, AuthOperationKind.CANCEL))
        assertFalse(registry.cancel(cancelKey, AuthOperationKind.CANCEL))
        assertTrue(registry.timeout(timeoutKey, AuthOperationKind.BEGIN))
        assertFalse(
            registry.complete(
                timeoutKey,
                AuthOperationKind.BEGIN,
                AuthOperationTerminal.Completed(stoppedLoginState()),
            ),
        )
        assertTrue(
            registry.complete(
                failureKey,
                AuthOperationKind.LOGOUT,
                AuthOperationTerminal.Failed("Cloudflare request failed"),
            ),
        )
        assertFalse(
            registry.complete(
                failureKey,
                AuthOperationKind.LOGOUT,
                AuthOperationTerminal.Failed("late duplicate"),
            ),
        )

        assertEquals(listOf(AuthOperationTerminal.Cancelled), cancelled)
        assertEquals(listOf(AuthOperationTerminal.TimedOut), timedOut)
        assertEquals(
            listOf(AuthOperationTerminal.Failed("Cloudflare request failed")),
            failed,
        )
    }

    @Test
    fun `list and select have independent concurrent slots`() {
        val registry = AuthOperationRegistry()
        registry.replaceGeneration(30)
        val listed = mutableListOf<AuthOperationTerminal<List<ExistingTunnel>>>()
        val selected = mutableListOf<AuthOperationTerminal<Unit>>()
        val listKey = AuthOperationKey(30, 41)
        val selectKey = AuthOperationKey(30, 42)

        registry.register(listKey, AuthOperationKind.LIST, listed::add)
        registry.register(selectKey, AuthOperationKind.SELECT, selected::add)
        assertEquals(setOf(listKey, selectKey), registry.pendingKeys())

        assertTrue(
            registry.complete(
                selectKey,
                AuthOperationKind.SELECT,
                AuthOperationTerminal.Completed(Unit),
            ),
        )
        assertEquals(setOf(listKey), registry.pendingKeys())
        assertTrue(
            registry.complete(
                listKey,
                AuthOperationKind.LIST,
                AuthOperationTerminal.Completed(emptyList<ExistingTunnel>()),
            ),
        )
        assertTrue(listed.single() is AuthOperationTerminal.Completed)
        assertTrue(selected.single() is AuthOperationTerminal.Completed)
    }

    @Test
    fun `replacement and terminal callbacks run outside registry lock and may reenter`() {
        val registry = AuthOperationRegistry()
        registry.replaceGeneration(40)
        val callbackCompleted = CountDownLatch(1)
        val nestedRegistered = CountDownLatch(1)
        val key = AuthOperationKey(40, 1)
        registry.register<Unit>(key, AuthOperationKind.SELECT) {
            val nested = thread {
                registry.register<Unit>(AuthOperationKey(41, 2), AuthOperationKind.LOGOUT) {}
                nestedRegistered.countDown()
            }
            assertTrue(nestedRegistered.await(1, TimeUnit.SECONDS))
            nested.join()
            callbackCompleted.countDown()
        }

        assertTrue(registry.replaceGeneration(41))
        assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS))
        assertEquals(setOf(AuthOperationKey(41, 2)), registry.pendingKeys())
    }

    @Test
    fun `one superseded callback failure does not skip remaining terminals`() {
        val registry = AuthOperationRegistry()
        registry.replaceGeneration(45)
        val laterTerminalCount = AtomicInteger()
        registry.register<Unit>(AuthOperationKey(45, 1), AuthOperationKind.SELECT) {
            error("callback failed")
        }
        registry.register<Unit>(AuthOperationKey(45, 2), AuthOperationKind.SELECT) {
            laterTerminalCount.incrementAndGet()
        }

        assertThrows(IllegalStateException::class.java) {
            registry.replaceGeneration(46)
        }
        assertEquals(1, laterTerminalCount.get())
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun `shared callback failure instance cannot interrupt later terminal delivery`() {
        val registry = AuthOperationRegistry()
        registry.replaceGeneration(46)
        val sharedFailure = IllegalStateException("shared callback failure")
        val thirdCallbackCount = AtomicInteger()
        registry.register<Unit>(AuthOperationKey(46, 1), AuthOperationKind.SELECT) {
            throw sharedFailure
        }
        registry.register<Unit>(AuthOperationKey(46, 2), AuthOperationKind.SELECT) {
            throw sharedFailure
        }
        registry.register<Unit>(AuthOperationKey(46, 3), AuthOperationKind.SELECT) {
            thirdCallbackCount.incrementAndGet()
        }

        assertSame(
            sharedFailure,
            assertThrows(IllegalStateException::class.java) {
                registry.replaceGeneration(47)
            },
        )
        assertEquals(1, thirdCallbackCount.get())
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun `duplicate key is rejected without transferring old terminal ownership`() {
        val registry = AuthOperationRegistry()
        registry.replaceGeneration(47)
        val key = AuthOperationKey(47, 1)
        val oldTerminals = mutableListOf<AuthOperationTerminal<Unit>>()
        val newTerminals = mutableListOf<AuthOperationTerminal<Unit>>()
        assertTrue(registry.register(key, AuthOperationKind.SELECT, oldTerminals::add))

        assertFalse(registry.register(key, AuthOperationKind.SELECT, newTerminals::add))
        assertEquals(listOf(AuthOperationTerminal.Superseded), newTerminals)
        assertFalse(
            registry.complete(
                key,
                AuthOperationKind.LOGOUT,
                AuthOperationTerminal.Completed(Unit),
            ),
        )
        assertTrue(
            registry.complete(
                key,
                AuthOperationKind.SELECT,
                AuthOperationTerminal.Completed(Unit),
            ),
        )
        assertTrue(oldTerminals.single() is AuthOperationTerminal.Completed)
        assertEquals(1, newTerminals.size)
    }

    @Test
    fun `stale registration receives superseded without entering the registry`() {
        val registry = AuthOperationRegistry()
        registry.replaceGeneration(51)
        val terminals = mutableListOf<AuthOperationTerminal<Unit>>()

        assertFalse(
            registry.register(AuthOperationKey(50, 1), AuthOperationKind.SELECT, terminals::add),
        )
        assertEquals(listOf(AuthOperationTerminal.Superseded), terminals)
        assertEquals(0, registry.pendingCount())
    }

    @Test
    fun `complete timeout and generation replacement races still deliver one terminal`() {
        repeat(50) { iteration ->
            val registry = AuthOperationRegistry()
            registry.replaceGeneration(60)
            val key = AuthOperationKey(60, iteration.toLong() + 1)
            val terminalCount = AtomicInteger()
            val winners = AtomicInteger()
            val ready = CountDownLatch(2)
            val race = CountDownLatch(1)
            registry.register<Unit>(key, AuthOperationKind.SELECT) { terminalCount.incrementAndGet() }
            val complete = thread {
                ready.countDown()
                race.await()
                if (
                    registry.complete(
                        key,
                        AuthOperationKind.SELECT,
                        AuthOperationTerminal.Completed(Unit),
                    )
                ) {
                    winners.incrementAndGet()
                }
            }
            val timeout = thread {
                ready.countDown()
                race.await()
                if (registry.timeout(key, AuthOperationKind.SELECT)) winners.incrementAndGet()
            }
            ready.await()
            race.countDown()
            complete.join()
            timeout.join()
            assertEquals(1, winners.get())
            assertEquals(1, terminalCount.get())
        }

        repeat(50) { iteration ->
            val registry = AuthOperationRegistry()
            registry.replaceGeneration(70)
            val key = AuthOperationKey(70, iteration.toLong() + 1)
            val terminals = ConcurrentLinkedQueue<AuthOperationTerminal<Unit>>()
            val ready = CountDownLatch(2)
            val race = CountDownLatch(1)
            registry.register(key, AuthOperationKind.SELECT, terminals::add)
            val replace = thread {
                ready.countDown()
                race.await()
                registry.replaceGeneration(71)
            }
            val complete = thread {
                ready.countDown()
                race.await()
                registry.complete(
                    key,
                    AuthOperationKind.SELECT,
                    AuthOperationTerminal.Completed(Unit),
                )
            }
            ready.await()
            race.countDown()
            replace.join()
            complete.join()
            assertEquals(1, terminals.size)
            assertTrue(
                terminals.single() == AuthOperationTerminal.Superseded ||
                    terminals.single() is AuthOperationTerminal.Completed,
            )
        }
    }

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
    fun `browser unattended startup requires exact canonical hostname and fixed port`() {
        val payload = browserPayload("startup-token")

        assertEquals(
            TunnelCredentialStartupDecision.START,
            decideCredentialStartup(payload, "https://TUNNEL.example.com/", 18443),
        )
        assertEquals(
            TunnelCredentialStartupDecision.NEEDS_USER_ACTION,
            decideCredentialStartup(payload, "https://other.example.com", 18443),
        )
        assertEquals(
            TunnelCredentialStartupDecision.NEEDS_USER_ACTION,
            decideCredentialStartup(payload, "https://tunnel.example.com", 18444),
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
            decideCredentialStartup(legacy, "https://different.example.com", 65535),
        )
        assertEquals(
            TunnelCredentialStartupDecision.START,
            decideCredentialStartup(versionedToken, "https://different.example.com", 1),
        )
    }

    @Test
    fun `failed selection or health preserves prior payload and creates no candidate`() {
        val prior = browserPayload("prior-token")
        val candidateCalls = AtomicInteger()
        val candidate = {
            candidateCalls.incrementAndGet()
            browserPayload("replacement-token")
        }

        val selectionFailure = decideBrowserCredentialCommit(
            prior,
            selectionSucceeded = false,
            publicHealthVerified = true,
            candidateFactory = candidate,
        )
        val healthFailure = decideBrowserCredentialCommit(
            prior,
            selectionSucceeded = true,
            publicHealthVerified = false,
            candidateFactory = candidate,
        )

        assertTrue(selectionFailure is BrowserCredentialCommitDecision.Preserve)
        assertTrue(healthFailure is BrowserCredentialCommitDecision.Preserve)
        assertSame(prior, (selectionFailure as BrowserCredentialCommitDecision.Preserve).authoritative)
        assertSame(prior, (healthFailure as BrowserCredentialCommitDecision.Preserve).authoritative)
        assertEquals(0, candidateCalls.get())
    }

    @Test
    fun `verified success creates exactly one commit candidate`() {
        val prior = browserPayload("prior-success-token")
        val candidateCalls = AtomicInteger()

        val decision = decideBrowserCredentialCommit(
            prior,
            selectionSucceeded = true,
            publicHealthVerified = true,
        ) {
            candidateCalls.incrementAndGet()
            browserPayload("new-success-token")
        }

        assertTrue(decision is BrowserCredentialCommitDecision.CommitCandidate)
        assertEquals(1, candidateCalls.get())
        assertEquals(
            "550e8400-e29b-41d4-a716-446655440000",
            (decision as BrowserCredentialCommitDecision.CommitCandidate).payload.tunnelId,
        )
    }

    @Test
    fun `register rebind updates only authoritative complete browser metadata`() {
        val current = CommittedBrowserTunnelMetadata(
            accountId = "old-account",
            tunnelId = "123e4567-e89b-12d3-a456-426614174000",
            tunnelName = "old",
            canonicalHostname = "https://old.example.com",
            fixedOriginPort = 18080,
        )
        val payload = browserPayload("register-token")

        val replacement = decideBrowserMetadataRebind(
            TunnelCredentialSnapshot.Authoritative(payload),
        )
        assertTrue(replacement is BrowserMetadataRebindDecision.Replace)
        replacement as BrowserMetadataRebindDecision.Replace
        assertEquals(payload.accountId, replacement.metadata.accountId)
        assertEquals(payload.tunnelId, replacement.metadata.tunnelId)
        assertEquals(payload.tunnelName, replacement.metadata.tunnelName)
        assertEquals(payload.canonicalHostname, replacement.metadata.canonicalHostname)
        assertEquals(payload.fixedOriginPort, replacement.metadata.fixedOriginPort)

        listOf(
            TunnelCredentialSnapshot.Stale,
            TunnelCredentialSnapshot.Invalid,
            TunnelCredentialSnapshot.None,
            TunnelCredentialSnapshot.Authoritative(
                TunnelCredentialPayload.create(
                    runToken = "token-source",
                    source = TunnelCredentialSource.TOKEN,
                )!!,
            ),
        ).forEach { snapshot ->
            assertSame(
                current,
                applyBrowserMetadataRebind(current, decideBrowserMetadataRebind(snapshot)),
            )
        }
        assertEquals(
            replacement.metadata,
            applyBrowserMetadataRebind(current, replacement),
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

    private fun stoppedLoginState(): CloudflareLoginState =
        CloudflareLoginState(
            authorizationUrl = null,
            state = ReadReceiptsTunnelState.STOPPED,
            error = null,
        )
}
