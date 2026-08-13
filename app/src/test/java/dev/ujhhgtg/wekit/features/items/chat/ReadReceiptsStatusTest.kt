package dev.ujhhgtg.wekit.features.items.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadReceiptsStatusTest {

    @Test
    fun `accepts exact native status wire states`() {
        val valid = mapOf(
            "{\"state\":\"stopped\",\"port\":null,\"error\":null}" to
                ReadReceiptsStatus(ReadReceiptsRuntimeState.STOPPED),
            "{\"state\":\"starting\",\"port\":null,\"error\":null}" to
                ReadReceiptsStatus(ReadReceiptsRuntimeState.STARTING),
            "{\"state\":\"running\",\"port\":43123,\"error\":null}" to
                ReadReceiptsStatus(ReadReceiptsRuntimeState.RUNNING, port = 43123),
            "{\"state\":\"stopping\",\"port\":null,\"error\":null}" to
                ReadReceiptsStatus(ReadReceiptsRuntimeState.STOPPING),
            "{\"state\":\"stopping\",\"port\":43123,\"error\":null}" to
                ReadReceiptsStatus(ReadReceiptsRuntimeState.STOPPING, port = 43123),
            "{\"state\":\"failed\",\"port\":null,\"error\":\"bind failed\"}" to
                ReadReceiptsStatus(ReadReceiptsRuntimeState.FAILED, error = "bind failed"),
        )

        valid.forEach { (wire, expected) ->
            assertEquals(expected, ReadReceiptsStatus.parse(wire).getOrNull(), wire)
        }
    }

    @Test
    fun `rejects ports and errors outside their exact states`() {
        val invalid = listOf(
            "{\"state\":\"stopped\",\"port\":1,\"error\":null}",
            "{\"state\":\"stopped\",\"port\":null,\"error\":\"error\"}",
            "{\"state\":\"starting\",\"port\":1,\"error\":null}",
            "{\"state\":\"starting\",\"port\":null,\"error\":\"error\"}",
            "{\"state\":\"running\",\"port\":null,\"error\":null}",
            "{\"state\":\"running\",\"port\":0,\"error\":null}",
            "{\"state\":\"running\",\"port\":\"1\",\"error\":null}",
            "{\"state\":\"running\",\"port\":1,\"error\":\"error\"}",
            "{\"state\":\"stopping\",\"port\":0,\"error\":null}",
            "{\"state\":\"stopping\",\"port\":null,\"error\":\"error\"}",
            "{\"state\":\"failed\",\"port\":1,\"error\":\"error\"}",
            "{\"state\":\"failed\",\"port\":null,\"error\":null}",
            "{\"state\":\"failed\",\"port\":null,\"error\":123}",
            "{\"state\":\"failed\",\"port\":null,\"error\":\"\"}",
            "{\"state\":\"failed\",\"port\":null,\"error\":\"${"x".repeat(257)}\"}",
        )

        invalid.forEach { wire ->
            assertTrue(ReadReceiptsStatus.parse(wire).isFailure, wire)
        }
    }
}
