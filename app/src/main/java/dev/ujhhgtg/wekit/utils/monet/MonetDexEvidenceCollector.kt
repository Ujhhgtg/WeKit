package dev.ujhhgtg.wekit.utils.monet

import dev.ujhhgtg.wekit.dexkit.DexSqlBridge
import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.matcher.DexMatcherSession
import dev.ujhhgtg.wekit.dexkit.matcher.FieldSpec
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameSpec
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import dev.ujhhgtg.wekit.dexkit.meta.FieldUsingType
import dev.ujhhgtg.wekit.dexkit.withDexSql

object MonetDexEvidenceCollector {
    fun collect(candidates: List<MonetDexCandidate>): List<MonetResourceDexEvidence> =
        withDexSql { runtime ->
            collect(runtime.bridge, candidates)
        }

    /** Test/embedded entry point over an already-open bridge; the caller owns [bridge]. */
    fun collect(
        bridge: DexSqlBridge,
        candidates: List<MonetDexCandidate>,
    ): List<MonetResourceDexEvidence> =
        DexMatcherSession(bridge).use { session -> collect(session, candidates) }

    private fun collect(
        session: DexMatcherSession,
        candidates: List<MonetDexCandidate>,
    ): List<MonetResourceDexEvidence> {
        val candidateKeys = candidates.associateBy { it.type to it.name }
        val fieldReaders = candidates.map(MonetDexCandidate::type).distinct().flatMap { resourceType ->
            session.findFields(
                FieldSpec(
                    declaredClass = ClassSpec(
                        className = NameSpec("com.tencent.mm.R\$$resourceType"),
                    ),
                    type = ClassSpec(className = NameSpec("int")),
                ),
            ).mapNotNull { field ->
                candidateKeys[resourceType to field.name]?.let { candidate ->
                    candidate to session.fieldAccessMethods(field.descriptor, "get")
                }
            }
        }.toMap()
        val methods = candidates.associateWith { candidate ->
            val direct = session.findMethods(
                MethodSpec(usingNumbers = listOf<Number>(candidate.resourceId)),
            )
            (direct + fieldReaders[candidate].orEmpty())
                .distinctBy { it.descriptor }
                .sortedBy { it.descriptor }
        }
        val descriptors = methods.mapValues { (_, users) -> users.map { it.descriptor }.toSet() }
        return methods.map { (candidate, users) ->
            MonetResourceDexEvidence(
                candidate.resourceId,
                users.map { method ->
                    MonetMethodDexEvidence(
                        descriptor = method.descriptor,
                        ownerPackage = method.declaredClassName.substringBeforeLast('.', ""),
                        methodShape = "(" + method.paramTypeNames.joinToString(",", transform = ::typeShape) + "):" + typeShape(method.returnTypeName),
                        stableStrings = (sequenceOf(method) + session.callers(method.descriptor).asSequence())
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

    private fun invokeShape(method: DexMethodInfo): String {
        val params = method.paramTypeNames.joinToString(",", transform = ::typeShape)
        val owner = method.declaredClassName.takeIf(::stableOwner)?.plus("#") ?: ""
        return owner + method.name + "(" + params + "):" + typeShape(method.returnTypeName)
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
