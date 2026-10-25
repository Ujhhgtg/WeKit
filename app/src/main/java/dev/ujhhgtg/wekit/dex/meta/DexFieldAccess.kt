package dev.ujhhgtg.wekit.dex.meta

import kotlinx.serialization.json.JsonObject

/** DexKit-compatible access direction of one field usage. */
enum class FieldUsingType {
    Read,
    Write;

    val isRead: Boolean
        get() = this == Read

    val isWrite: Boolean
        get() = this == Write
}

/** One field access of a method: the accessed field plus read/write direction. */
class DexFieldAccess internal constructor(val field: DexFieldInfo, val usingType: FieldUsingType) {
    override fun toString(): String = usingType.name + " " + field.descriptor

    internal companion object {
        fun parse(json: JsonObject, metadata: DexMetadata): DexFieldAccess =
            DexFieldAccess(
                field = DexFieldInfo(json.requiredObject("field"), metadata),
                // Engine contract is lowercase "read"/"write"; the DexKit oracle snapshot uses
                // capitalized "Read"/"Write", so accept both.
                usingType =
                    when (val value = json.requiredString("using_type").lowercase()) {
                        "read" -> FieldUsingType.Read
                        "write" -> FieldUsingType.Write
                        else -> error("Unknown DexSQL field using_type: " + value)
                    },
            )
    }
}
