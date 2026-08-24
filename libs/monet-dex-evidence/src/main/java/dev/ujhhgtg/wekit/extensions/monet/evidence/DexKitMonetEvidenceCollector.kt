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
        val methodDescriptorsByResourceId = methodsByCandidate.mapKeys { (candidate) ->
            candidate.resourceId
        }.mapValues { (_, methods) ->
            methods.map(MethodData::descriptor)
        }

        return methodsByCandidate.map { (candidate, methods) ->
            MonetResourceDexEvidence(
                resourceId = candidate.resourceId,
                methods = methods.map { method ->
                    normalizeMethodEvidence(
                        descriptor = method.descriptor,
                        strings = method.usingStrings,
                        invokes = method.invokes.map(::invokedMethodShape),
                        resourceIds = neighboringResourceIdsForMethod(
                            candidateResourceId = candidate.resourceId,
                            methodDescriptor = method.descriptor,
                            methodDescriptorsByResourceId = methodDescriptorsByResourceId,
                        ),
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

internal fun neighboringResourceIdsForMethod(
    candidateResourceId: Int,
    methodDescriptor: String,
    methodDescriptorsByResourceId: Map<Int, List<String>>,
): List<Int> = methodDescriptorsByResourceId
    .asSequence()
    .filter { (resourceId, descriptors) ->
        resourceId != candidateResourceId && methodDescriptor in descriptors
    }
    .map { (resourceId) -> resourceId }
    .distinct()
    .sorted()
    .toList()

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

private fun invokedMethodShape(method: MethodData): String = normalizeInvokedMethodShape(
    owner = method.declaredClassName,
    name = method.name,
    paramTypeNames = method.paramTypeNames,
    returnTypeName = method.returnTypeName,
)

internal fun normalizeInvokedMethodShape(
    owner: String,
    name: String,
    paramTypeNames: List<String>,
    returnTypeName: String,
): String {
    val params = paramTypeNames.joinToString(",", transform = ::normalizedTypeShape)
    val returnType = normalizedTypeShape(returnTypeName)
    return if (isStablePublicOwner(owner)) {
        "$owner#$name($params):$returnType"
    } else {
        "($params):$returnType"
    }
}

private fun normalizedTypeShape(typeName: String): String = when {
    typeName.endsWith("[]") -> normalizedTypeShape(typeName.removeSuffix("[]")) + "[]"
    typeName in PRIMITIVE_TYPE_NAMES -> typeName
    isStablePublicOwner(typeName) -> typeName
    else -> "object"
}

private fun isStablePublicOwner(typeName: String): Boolean {
    if (PLATFORM_API_PREFIXES.any(typeName::startsWith)) return true
    if (!typeName.startsWith(WECHAT_OPEN_SDK_PREFIX)) return false

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

private val PLATFORM_API_PREFIXES = listOf(
    "android.",
    "java.",
    "javax.",
)

private const val WECHAT_OPEN_SDK_PREFIX = "com.tencent.mm.opensdk."
