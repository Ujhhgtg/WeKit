package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReadReceiptsConfigurationTest {

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
    fun `rejects unsupported and malformed snapshots`() {
        assertNull(ReadReceiptsConfigurationCodec.decode("{\"version\":99}"))
        assertNull(ReadReceiptsConfigurationCodec.decode("not json"))
        assertNull(
            ReadReceiptsConfigurationCodec.decode(
                "{\"version\":1,\"mode\":\"UNKNOWN\"}",
            ),
        )
    }
}
