package dev.ujhhgtg.wekit.agent.environment

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SshBackendTest {
    @Test
    fun `reverse forward closes once when post-acquisition construction fails`() {
        var closes = 0
        val forward = SshReverseForward(23456) { closes++ }

        assertThrows(NoSuchElementException::class.java) {
            runBlocking {
                withSshReverseForward(forward) {
                    mapOf("WEAGENT_BRIDGE_PORT" to "12345").getValue("WEAGENT_BRIDGE_TOKEN")
                }
            }
        }

        assertEquals(1, closes)
    }

    @Test
    fun `reverse forward remains open for normal execution lifetime`() = runBlocking {
        var closes = 0
        val forward = SshReverseForward(23456) { closes++ }

        val result = withSshReverseForward(forward) {
            assertEquals(0, closes)
            "complete"
        }

        assertEquals("complete", result)
        assertEquals(1, closes)
    }
}
