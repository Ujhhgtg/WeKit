package dev.ujhhgtg.wekit.bootstrap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Base64

class BootstrapConfigTest {
    /** server/deploy/config.ts still reads this file, so it must not drift from the Kotlin constants. */
    @Test
    fun `hardcoded config matches the JSON the server deploy reads`() {
        val json = Json.parseToJsonElement(File("src/main/assets/remote-loader.json").readText()).jsonObject
        val config = BootstrapConfig.DEFAULT
        assertEquals(json.getValue("apiOrigin").jsonPrimitive.content, config.apiOrigin)
        assertEquals(json.getValue("telegramBotUsername").jsonPrimitive.content, config.telegramBotUsername)
        val expected = json.getValue("releasePublicKeys").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val actual = config.releaseKeys.mapValues { Base64.getUrlEncoder().withoutPadding().encodeToString(it.value.encoded) }
        assertEquals(expected, actual)
    }
}
