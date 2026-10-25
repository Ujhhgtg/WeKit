package dev.ujhhgtg.wekit.dex

import dev.ujhhgtg.wekit.dex.meta.DexMetadataKind
import dev.ujhhgtg.wekit.dex.meta.DexMetadataProvider
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Metadata provider backed by one open DexSQL session. */
class DexSqlMetadataProvider(private val bridge: DexSqlBridge) : DexMetadataProvider {
    override fun query(kind: DexMetadataKind, descriptor: String): JsonObject? =
        bridge.metadata(kind.key, descriptor)

    /** Opt-in caller list for a method descriptor (may be very large). */
    fun callers(descriptor: String): JsonElement? = bridge.metadataCallers(descriptor)
}
