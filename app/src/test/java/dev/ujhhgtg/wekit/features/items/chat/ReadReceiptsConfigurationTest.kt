package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ReadReceiptsConfigurationTest {

    @Test
    fun `round trips a third party configuration`() {
        val configuration =
            ReadReceiptsConfiguration(
                thirdPartyUrl = "https://receipts.example",
                pollIntervalSecs = 7,
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
        val submitted =
            ReadReceiptsConfiguration(
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
    fun `rejects invalid active third party endpoint on encode and decode`() {
        val valid =
            ReadReceiptsConfiguration(
                thirdPartyUrl = "https://receipts.example",
            )
        val encoded = ReadReceiptsConfigurationCodec.encode(valid)
        val invalidEndpoints =
            listOf(
                "https://user@receipts.example",
                "https://receipts.example?token=secret",
                " https://receipts.example",
                "https://receipts.example" + "/".repeat(2048),
            )

        invalidEndpoints.forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                ReadReceiptsConfigurationCodec.encode(valid.copy(thirdPartyUrl = endpoint))
            }
            assertNull(
                ReadReceiptsConfigurationCodec.decode(
                    encoded.replace("https://receipts.example", endpoint),
                ),
                endpoint,
            )
        }
    }

    @Test
    fun `allows exact empty third party endpoint as unconfigured default`() {
        val configuration = ReadReceiptsConfiguration()

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
                "{\"version\":1,\"thirdPartyUrl\":\"\",\"pollIntervalSecs\":5}",
            ),
        )
    }
}
