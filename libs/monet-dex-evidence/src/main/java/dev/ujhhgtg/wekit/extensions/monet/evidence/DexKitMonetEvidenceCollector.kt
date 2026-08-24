package dev.ujhhgtg.wekit.extensions.monet.evidence

import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetFieldAccess
import dev.ujhhgtg.wekit.extensions.monet.api.MonetFieldAccessEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetMethodDexEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetResourceDexEvidence
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData

object DexKitMonetEvidenceCollector {
    fun collect(
        bridge: DexKitBridge,
        candidates: List<MonetDexCandidate>,
    ): List<MonetResourceDexEvidence> {
        require(candidates.map(MonetDexCandidate::resourceId).distinct().size == candidates.size) {
            "Monet Dex candidates must have unique resource IDs"
        }

        val methodsByCandidate = candidates
            .sortedBy(MonetDexCandidate::resourceId)
            .associateWith { candidate -> collectCandidateMethods(bridge, candidate) }
        val resourceIdsByMethod = buildMap<String, Set<Int>> {
            methodsByCandidate.forEach { (candidate, methods) ->
                methods.forEach { method ->
                    put(
                        method.descriptor,
                        get(method.descriptor).orEmpty() + candidate.resourceId,
                    )
                }
            }
        }

        return methodsByCandidate.map { (candidate, methods) ->
            MonetResourceDexEvidence(
                resourceId = candidate.resourceId,
                methods = methods.map { method ->
                    normalizeMethodEvidence(
                        descriptor = method.descriptor,
                        strings = method.usingStrings,
                        invokes = method.invokes.map(::invokedMethodShape),
                        resourceIds = resourceIdsByMethod
                            .getValue(method.descriptor)
                            .filterNot { it == candidate.resourceId },
                        fields = method.usingFields.map { usingField ->
                            MonetFieldAccessEvidence(
                                descriptor = usingField.field.descriptor,
                                access = when (usingField.usingType) {
                                    FieldUsingType.Read -> MonetFieldAccess.READ
                                    FieldUsingType.Write -> MonetFieldAccess.WRITE
                                },
                            )
                        },
                    )
                },
            )
        }
    }

    private fun collectCandidateMethods(
        bridge: DexKitBridge,
        candidate: MonetDexCandidate,
    ): List<MethodData> {
        val inlineUsers = bridge.findMethod {
            matcher {
                usingNumbers(candidate.resourceId)
            }
        }
        val fieldReaders = bridge.findField {
            matcher {
                declaredClass = "com.tencent.mm.R\$${candidate.type}"
                name = candidate.name
                type = "int"
            }
        }.flatMap { field -> field.readers }

        return (inlineUsers + fieldReaders)
            .distinctBy(MethodData::descriptor)
            .sortedBy(MethodData::descriptor)
    }
}

internal fun normalizeMethodEvidence(
    descriptor: String,
    strings: List<String>,
    invokes: List<String>,
    resourceIds: List<Int>,
    fields: List<MonetFieldAccessEvidence>,
): MonetMethodDexEvidence = MonetMethodDexEvidence(
    descriptor = descriptor,
    stableStrings = strings.distinct().sorted(),
    invokedMethodShapes = invokes.distinct().sorted(),
    neighboringResourceIds = resourceIds.distinct().sorted(),
    fieldAccesses = fields.distinct().sortedWith(
        compareBy(MonetFieldAccessEvidence::descriptor, MonetFieldAccessEvidence::access),
    ),
)

private fun invokedMethodShape(method: MethodData): String {
    val params = method.paramTypeNames.joinToString(",", transform = ::normalizedTypeShape)
    val returnType = normalizedTypeShape(method.returnTypeName)
    return if (hasStablePublicOwner(method.declaredClassName)) {
        "${method.declaredClassName}#${method.name}($params):$returnType"
    } else {
        "($params):$returnType"
    }
}

private fun normalizedTypeShape(typeName: String): String = when {
    typeName.endsWith("[]") -> normalizedTypeShape(typeName.removeSuffix("[]")) + "[]"
    typeName in PRIMITIVE_TYPE_NAMES -> typeName
    hasStablePublicOwner(typeName) -> typeName
    else -> "object"
}

private fun hasStablePublicOwner(typeName: String): Boolean {
    if (PUBLIC_API_PREFIXES.any(typeName::startsWith)) return true
    if (!typeName.startsWith("com.tencent.mm.")) return false

    val outerClassName = typeName.substringAfterLast('.').substringBefore('$')
    return outerClassName.firstOrNull()?.isUpperCase() == true
}

private val PRIMITIVE_TYPE_NAMES = setOf(
    "boolean",
    "byte",
    "char",
    "double",
    "float",
    "int",
    "long",
    "short",
    "void",
)

private val PUBLIC_API_PREFIXES = listOf(
    "android.",
    "androidx.",
    "java.",
    "javax.",
    "kotlin.",
    "kotlinx.",
)
