package dev.ujhhgtg.wekit.extensions

internal fun validateExtensionPacks(packs: List<ExtensionPack>): List<ExtensionPack> {
    packs.forEach { pack ->
        require(pack.id.isNotEmpty()) {
            "Extension pack ${pack.javaClass.name} has an empty ID"
        }
    }
    packs.groupBy(ExtensionPack::id)
        .filterValues { it.size > 1 }
        .forEach { (id, duplicates) ->
            require(false) {
                "Duplicate extension pack ID '$id': ${duplicates.joinToString { it.javaClass.name }}"
            }
        }
    return packs
}
