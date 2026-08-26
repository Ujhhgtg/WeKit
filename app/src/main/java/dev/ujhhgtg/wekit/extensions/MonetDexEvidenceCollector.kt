package dev.ujhhgtg.wekit.extensions

import dev.ujhhgtg.wekit.extensions.monet.api.MonetDexCandidate
import dev.ujhhgtg.wekit.extensions.monet.api.MonetFieldAccess
import dev.ujhhgtg.wekit.extensions.monet.api.MonetFieldAccessEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetMethodDexEvidence
import dev.ujhhgtg.wekit.extensions.monet.api.MonetResourceDexEvidence
import dev.ujhhgtg.wekit.utils.reflection.withDexKit
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData

internal object MonetDexEvidenceCollector {
    fun collect(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence> = withDexKit { bridge ->
        collect(bridge, candidates)
    }

    internal fun collect(
        bridge: org.luckypray.dexkit.DexKitBridge,
        candidates: List<MonetDexCandidate>,
    ): List<MonetResourceDexEvidence> {
        val candidateKeys = candidates.associateBy { it.type to it.name }
        val fieldReaders = candidates.map(MonetDexCandidate::type).distinct().flatMap { resourceType ->
            bridge.findField {
                matcher {
                    declaredClass = "com.tencent.mm.R\$$resourceType"
                    type = "int"
                }
            }.mapNotNull { field ->
                candidateKeys[resourceType to field.name]?.let { candidate -> candidate to field.readers }
            }
        }.toMap()
        val methods = candidates.associateWith { candidate ->
            val direct = bridge.findMethod { matcher { usingNumbers(candidate.resourceId) } }
            (direct + fieldReaders[candidate].orEmpty())
                .distinctBy(MethodData::descriptor)
                .sortedBy(MethodData::descriptor)
        }
        val descriptors = methods.mapValues { (_, users) -> users.map(MethodData::descriptor).toSet() }
        return methods.map { (candidate, users) ->
            MonetResourceDexEvidence(
                candidate.resourceId,
                users.map { method ->
                    MonetMethodDexEvidence(
                        descriptor = method.descriptor,
                        ownerPackage = method.declaredClassName.substringBeforeLast('.', ""),
                        methodShape = "(${method.paramTypeNames.joinToString(",", transform = ::typeShape)}):${typeShape(method.returnTypeName)}",
                        stableStrings = (sequenceOf(method) + method.callers.asSequence())
                            .flatMap { it.usingStrings.asSequence() }.distinct().sorted().toList(),
                        invokedMethodShapes = method.invokes.map(::invokeShape).distinct().sorted(),
                        neighboringResourceIds = descriptors.filter { (other, users) ->
                            other != candidate && method.descriptor in users
                        }.keys.map(MonetDexCandidate::resourceId).sorted(),
                        fieldAccesses = method.usingFields.map { field ->
                            MonetFieldAccessEvidence(
                                field.field.descriptor,
                                if (field.usingType == FieldUsingType.Read) {
                                    MonetFieldAccess.READ
                                } else {
                                    MonetFieldAccess.WRITE
                                },
                            )
                        }.distinct().sortedBy(MonetFieldAccessEvidence::descriptor),
                    )
                },
            )
        }
    }

    private fun invokeShape(method: MethodData): String {
        val params = method.paramTypeNames.joinToString(",", transform = ::typeShape)
        val owner = method.declaredClassName.takeIf(::stableOwner)?.plus("#") ?: ""
        return "$owner${method.name}($params):${typeShape(method.returnTypeName)}"
    }

    private fun typeShape(type: String): String = when {
        type.endsWith("[]") -> typeShape(type.removeSuffix("[]")) + "[]"
        type in primitiveTypes || stableOwner(type) -> type
        else -> "object"
    }

    private fun stableOwner(type: String): Boolean = stablePrefixes.any(type::startsWith)

    private val stablePrefixes = listOf("android.", "java.", "javax.", "com.tencent.mm.opensdk.")
    private val primitiveTypes = setOf("boolean", "byte", "char", "double", "float", "int", "long", "short", "void")
}
