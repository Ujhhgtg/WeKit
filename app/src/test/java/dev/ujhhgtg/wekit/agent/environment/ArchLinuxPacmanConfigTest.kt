package dev.ujhhgtg.wekit.agent.environment

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ArchLinuxPacmanConfigTest {
    @Test
    fun `adds DisableSandbox to pacman options`() {
        val config = "[options]\nRootDir = /\n\n[core]\n"

        assertEquals(
            "[options]\nDisableSandbox\nRootDir = /\n\n[core]\n",
            ArchLinuxInstanceInstaller.disablePacmanSandbox(config),
        )
    }

    @Test
    fun `does not duplicate DisableSandbox`() {
        val config = "[options]\nDisableSandbox\nRootDir = /\n"

        assertEquals(config, ArchLinuxInstanceInstaller.disablePacmanSandbox(config))
    }

    @Test
    fun `requires pacman options section`() {
        assertThrows(IllegalArgumentException::class.java) {
            ArchLinuxInstanceInstaller.disablePacmanSandbox("[core]\n")
        }
    }
}
