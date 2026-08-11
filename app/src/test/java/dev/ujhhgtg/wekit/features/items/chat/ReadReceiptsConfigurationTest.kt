package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReadReceiptsConfigurationTest {

    @Test
    fun `active automatic built in runtime changes use transactional replacement`() {
        val quickPrevious = builtInConfiguration(
            tunnelMode = ReadReceiptsTunnelMode.QUICK,
            port = 3000,
        )
        val tokenPrevious = builtInConfiguration(
            tunnelMode = ReadReceiptsTunnelMode.TOKEN,
            port = 3000,
            hostname = "https://old.example.com",
        )
        val browserPrevious = builtInConfiguration(
            tunnelMode = ReadReceiptsTunnelMode.BROWSER_LOGIN,
            port = 3000,
            hostname = "https://browser.example.com",
            selectedTunnelId = "11111111-1111-4111-8111-111111111111",
        )
        val cases = listOf(
            quickPrevious to quickPrevious.copy(builtInPort = 4000),
            tokenPrevious to tokenPrevious.copy(hostname = "https://new.example.com"),
            browserPrevious to browserPrevious.copy(
                selectedTunnelId = "22222222-2222-4222-8222-222222222222",
            ),
        )

        for ((previous, candidate) in cases) {
            assertEquals(
                ReadReceiptsConfigurationSaveAction.TRANSACTIONAL_REPLACE,
                readReceiptsConfigurationSaveAction(
                    previous = previous,
                    candidate = candidate,
                    originWasActive = true,
                    featureActive = true,
                ),
                candidate.tunnelMode,
            )
        }
    }

    @Test
    fun `active manual runtime change stops stale stack without restart`() {
        val previous = builtInConfiguration(
            tunnelMode = ReadReceiptsTunnelMode.QUICK,
            port = 3000,
        )
        val candidate = previous.copy(
            builtInPort = 4000,
            automaticLifecycle = false,
        )

        assertEquals(
            ReadReceiptsConfigurationSaveAction.STOP_THEN_COMMIT,
            readReceiptsConfigurationSaveAction(
                previous = previous,
                candidate = candidate,
                originWasActive = true,
                featureActive = true,
            ),
        )
    }

    @Test
    fun `inactive automatic built in configuration starts transactionally`() {
        val configuration = builtInConfiguration(
            tunnelMode = ReadReceiptsTunnelMode.QUICK,
            port = 3000,
        )

        assertEquals(
            ReadReceiptsConfigurationSaveAction.TRANSACTIONAL_START,
            readReceiptsConfigurationSaveAction(
                previous = ReadReceiptsConfiguration(),
                candidate = configuration,
                originWasActive = false,
                featureActive = true,
            ),
        )
    }

    @Test
    fun `round trips a third party configuration`() {
        val configuration = ReadReceiptsConfiguration(
            mode = ReadReceiptsServerMode.THIRD_PARTY,
            thirdPartyUrl = "https://receipts.example",
            prefix = "#read ",
            pollIntervalSecs = 7,
            automaticPort = true,
            builtInPort = 3000,
            automaticLifecycle = false,
            tunnelMode = "QUICK",
            hostname = "",
            selectedAccountId = "",
            selectedAccountName = "",
            selectedTunnelId = "",
            selectedTunnelName = "",
        )

        assertEquals(
            configuration,
            ReadReceiptsConfigurationCodec.decode(
                ReadReceiptsConfigurationCodec.encode(configuration),
            ),
        )
    }

    @Test
    fun `canonicalizes a valid third party configuration endpoint`() {
        val submitted = ReadReceiptsConfiguration(
            mode = ReadReceiptsServerMode.THIRD_PARTY,
            thirdPartyUrl = "HTTPS://Example.COM/receipts/",
        )

        assertEquals(
            submitted.copy(thirdPartyUrl = "https://example.com/receipts"),
            ReadReceiptsConfigurationCodec.decode(
                ReadReceiptsConfigurationCodec.encode(submitted),
            ),
        )
    }

    @Test
    fun `preserves every non secret configuration field`() {
        val configuration = ReadReceiptsConfiguration(
            mode = ReadReceiptsServerMode.BUILT_IN,
            thirdPartyUrl = "https://fallback.example/base",
            prefix = "",
            pollIntervalSecs = 11,
            automaticPort = false,
            builtInPort = 43123,
            automaticLifecycle = true,
            tunnelMode = "AUTHENTICATED",
            hostname = "https://receipts.example.com",
            selectedAccountId = "account-id",
            selectedAccountName = "Account Name",
            selectedTunnelId = "tunnel-id",
            selectedTunnelName = "Tunnel Name",
        )

        assertEquals(
            configuration,
            ReadReceiptsConfigurationCodec.decode(
                ReadReceiptsConfigurationCodec.encode(configuration),
            ),
        )
    }

    @Test
    fun `round trips legacy endpoint strings verbatim`() {
        val configuration = ReadReceiptsConfiguration(
            mode = ReadReceiptsServerMode.BUILT_IN,
            thirdPartyUrl = "http://inactive.example/legacy/",
            hostname = "HTTPS://例子.测试/路径/",
        )

        assertEquals(
            configuration,
            ReadReceiptsConfigurationCodec.decode(
                ReadReceiptsConfigurationCodec.encode(configuration),
            ),
        )
    }

    @Test
    fun `rejects unsupported and malformed snapshots`() {
        assertNull(ReadReceiptsConfigurationCodec.decode("{\"version\":99}"))
        assertNull(ReadReceiptsConfigurationCodec.decode("not json"))
        assertNull(
            ReadReceiptsConfigurationCodec.decode(
                "{\"version\":1,\"mode\":\"UNKNOWN\"}",
            ),
        )
    }

    private fun builtInConfiguration(
        tunnelMode: ReadReceiptsTunnelMode,
        port: Int,
        hostname: String = "",
        selectedTunnelId: String = "",
    ) = ReadReceiptsConfiguration(
        mode = ReadReceiptsServerMode.BUILT_IN,
        automaticPort = false,
        builtInPort = port,
        automaticLifecycle = true,
        tunnelMode = tunnelMode.name,
        hostname = hostname,
        selectedTunnelId = selectedTunnelId,
    )
}
