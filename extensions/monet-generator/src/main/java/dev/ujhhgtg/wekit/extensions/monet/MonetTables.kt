package dev.ujhhgtg.wekit.extensions.monet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class MonetColorRule(
    @SerialName("l") val light: String,
    @SerialName("n") val night: String,
    @SerialName("v") val expectedValue: String? = null,
)

@Serializable
internal data class MonetVersionTable(
    @SerialName("colors") val colors: Map<String, MonetColorRule> = emptyMap(),
)

@Serializable
internal data class MonetTables(
    @SerialName("versions") val versions: Map<String, MonetVersionTable> = emptyMap(),
    @SerialName("generic") val generic: Map<String, MonetColorRule> = emptyMap(),
    @SerialName("brandByValue") val brandByValue: Map<String, MonetColorRule> = emptyMap(),
    @SerialName("surfByPair") val surfByPair: Map<String, MonetColorRule> = emptyMap(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(payloadDir: File): MonetTables = json.decodeFromString<MonetTables>(
            payloadDir.resolve("monet_tables.json").readText(),
        )
    }
}
