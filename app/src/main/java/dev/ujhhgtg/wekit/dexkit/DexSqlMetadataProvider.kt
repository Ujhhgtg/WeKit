package dev.ujhhgtg.wekit.dexkit

import dev.ujhhgtg.wekit.dexkit.meta.DexMetadataKind
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadataProvider
import kotlinx.serialization.json.JsonObject

/** Metadata provider backed by one open DexSQL session. */
class DexSqlMetadataProvider(private val bridge: DexSqlBridge) : DexMetadataProvider {
    override fun query(kind: DexMetadataKind, descriptor: String): JsonObject? =
        bridge.metadata(kind.key, descriptor)
}
