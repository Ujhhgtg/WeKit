package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo

/**
 * Pure evaluator for [ClassSpec]/[MethodSpec]/[FieldSpec] over the DexSQL metadata API, mirroring
 * the remaining DexKit matcher semantics used by WeKit:
 *
 * - nested method/field/interface lists are injective (each spec is satisfied by a distinct member);
 * - count ranges apply to the actual member list size;
 * - class-level usingStrings is existential over all strings of the class's methods;
 * - modifiers use Contains semantics: (modifiers and mask) == mask;
 * - strings are case-sensitive.
 *
 * Candidate pre-filtering is the caller's job ([QueryMatcherScope]); this evaluator is the
 * authoritative matcher.
 */
internal class DexMatcherEvaluator(
    private val metadata: DexMetadata,
    private val relations: DexMatcherRelations? = null,
) {

    fun matchesClass(info: DexClassInfo, spec: ClassSpec): Boolean {
        spec.className?.let { if (!it.matches(info.name)) return false }
        if (isShallow(info)) {
            // External classes have no dataset metadata: DexKit only matches their names; any
            // structural/count/member constraint cannot be satisfied.
            return spec.className != null &&
                spec.superClass == null &&
                spec.interfaces.isEmpty() &&
                spec.fields.isEmpty() &&
                spec.methods.isEmpty() &&
                spec.fieldCount == null &&
                spec.methodCount == null &&
                spec.interfaceCount == null &&
                spec.modifiers == null &&
                spec.usingStrings.isEmpty()
        }
        spec.modifiers?.let { if ((info.modifiers and it) != it) return false }
        spec.interfaceCount?.let { if (info.interfaces.size !in it) return false }
        spec.methodCount?.let { if (info.methods.size !in it) return false }
        spec.fieldCount?.let { if (info.fields.size !in it) return false }
        spec.superClass?.let { superSpec ->
            val superInfo = info.superClass ?: return false
            if (!matchesClass(superInfo, superSpec)) return false
        }
        if (!injectiveMatch(spec.interfaces, info.interfaces) { s, c -> matchesClass(c, s) }) return false
        if (!injectiveMatch(spec.fields, info.fields) { s, c -> matchesField(c, s) }) return false
        if (!injectiveMatch(spec.methods, info.methods) { s, c -> matchesMethod(c, s) }) return false
        for (stringSpec in spec.usingStrings) {
            if (info.methods.none { method -> method.usingStrings.any { stringSpec.matches(it) } }) {
                return false
            }
        }
        return true
    }

    fun matchesMethod(info: DexMethodInfo, spec: MethodSpec): Boolean {
        spec.descriptor?.let { if (info.descriptor != it) return false }
        spec.name?.let { if (!it.matches(info.name)) return false }
        spec.modifiers?.let { if ((info.modifiers and it) != it) return false }
        spec.paramCount?.let { if (info.paramCount !in it) return false }
        spec.paramTypes?.let { types ->
            if (types.isEmpty()) {
                if (info.paramCount != 0) return false
            } else {
                if (types.size != info.paramCount) return false
                types.forEachIndexed { index, type ->
                    if (type != null && !type.matches(info.paramTypeNames[index])) return false
                }
            }
        }
        spec.declaredClass?.let { classSpec ->
            if (!matchesClassReference(info.declaredClass, info.declaredClassName, classSpec)) return false
        }
        spec.returnType?.let { typeSpec ->
            if (!matchesClassReference(info.returnType, info.returnTypeName, typeSpec)) return false
        }
        for (stringSpec in spec.usingStrings) {
            if (info.usingStrings.none { stringSpec.matches(it) }) return false
        }
        if (spec.usingNumbers.isNotEmpty()) {
            val candidateNumbers = usingNumbers(info)
            if (!injectiveMatch(spec.usingNumbers, candidateNumbers) { expected, actual ->
                    matchesNumber(expected, actual)
                }
            ) return false
        }
        if (!injectiveMatch(spec.usingFields, info.usingFields) { fieldSpec, access ->
                (fieldSpec.usingType == null || fieldSpec.usingType == access.usingType) &&
                    matchesField(access.field, fieldSpec.field)
            }
        ) return false
        if (!injectiveMatch(spec.invokeMethods, info.invokes) { s, c -> matchesMethod(c, s) }) return false
        if (spec.callerMethods.isNotEmpty()) {
            val callers = (relations ?: missingRelations("callerMethods")).callers(info.descriptor)
            if (!injectiveMatch(spec.callerMethods, callers) { s, c -> matchesMethod(c, s) }) return false
        }
        for (child in spec.allOf) if (!matchesMethod(info, child)) return false
        if (spec.anyOf.isNotEmpty() && spec.anyOf.none { matchesMethod(info, it) }) return false
        return true
    }

    fun matchesField(info: DexFieldInfo, spec: FieldSpec): Boolean {
        spec.name?.let { if (!it.matches(info.name)) return false }
        spec.modifiers?.let { if ((info.modifiers and it) != it) return false }
        spec.type?.let { typeSpec ->
            if (!matchesClassReference(metadata.classInfoByName(info.typeName), info.typeName, typeSpec)) {
                return false
            }
        }
        spec.declaredClass?.let { classSpec ->
            if (!matchesClassReference(info.declaredClass, info.declaredClassName, classSpec)) return false
        }
        val resolvedRelations = if (spec.readMethods.isNotEmpty() || spec.writeMethods.isNotEmpty()) {
            relations ?: missingRelations("field read/write methods")
        } else {
            null
        }
        if (spec.readMethods.isNotEmpty()) {
            val methods = resolvedRelations!!.fieldAccessMethods(info.descriptor, "get")
            if (!injectiveMatch(spec.readMethods, methods) { s, c -> matchesMethod(c, s) }) return false
        }
        if (spec.writeMethods.isNotEmpty()) {
            val methods = resolvedRelations!!.fieldAccessMethods(info.descriptor, "put")
            if (!injectiveMatch(spec.writeMethods, methods) { s, c -> matchesMethod(c, s) }) return false
        }
        return true
    }

    private fun missingRelations(feature: String): Nothing = throw UnsupportedOperationException(
        "Query matcher " + feature + " require DexMatcherRelations (none configured)",
    )

    /** Parsed DexKit usingNumbers operand: engine collapses DEX literal labels to long/double. */
    private data class CandidateNumber(val longValue: Long?, val doubleValue: Double?)

    private fun usingNumbers(info: DexMethodInfo): List<CandidateNumber> {
        val raw = info.rawJson["using_numbers"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return raw.mapNotNull { element ->
            val entry = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val kind = (entry["kind"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val value = (entry["value"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            when (kind) {
                "long" -> value.toLongOrNull()?.let { CandidateNumber(it, null) }
                "double" -> value.toDoubleOrNull()?.let { CandidateNumber(null, it) }
                else -> null
            }
        }
    }

    /**
     * DexKit numeric operand matching: integer matchers compare long values exactly, floating
     * matchers compare double values (NaN never matches; tiny epsilon for constant folding).
     */
    private fun matchesNumber(expected: Number, actual: CandidateNumber): Boolean {
        return if (expected is Float || expected is Double) {
            val expectedDouble = expected.toDouble()
            val actualDouble = actual.doubleValue ?: return false
            actualDouble == expectedDouble ||
                kotlin.math.abs(actualDouble - expectedDouble) < 1e-6
        } else {
            actual.longValue != null && actual.longValue == expected.toLong()
        }
    }

    private fun matchesClassReference(
        resolved: DexClassInfo?,
        fallbackName: String,
        spec: ClassSpec,
    ): Boolean {
        if (resolved != null) return matchesClass(resolved, spec)
        // External/non-dataset class: only the name is available (mirrors DexKit's name-only path).
        val nameSpec = spec.className ?: return false
        return nameSpec.matches(fallbackName) &&
            spec.superClass == null &&
            spec.interfaces.isEmpty() &&
            spec.fields.isEmpty() &&
            spec.methods.isEmpty() &&
            spec.fieldCount == null &&
            spec.methodCount == null &&
            spec.interfaceCount == null &&
            spec.modifiers == null &&
            spec.usingStrings.isEmpty()
    }

    /** True when this instance is the shallow fallback produced for a non-dataset class. */
    private fun isShallow(info: DexClassInfo): Boolean =
        metadata.classInfo(info.descriptor) == null
}

/**
 * Maximum bipartite matching: every spec must be satisfied by a distinct candidate. Mirrors
 * DexKit's Hungarian-based list matcher semantics (including the injective nested-method rule).
 */
internal fun <S, C> injectiveMatch(
    specs: List<S>,
    candidates: List<C>,
    matches: (S, C) -> Boolean,
): Boolean {
    if (specs.isEmpty()) return true
    if (specs.size > candidates.size) return false
    val candidateOwner = IntArray(candidates.size) { -1 }

    fun assign(specIndex: Int, visited: BooleanArray): Boolean {
        for (candidateIndex in candidates.indices) {
            if (visited[candidateIndex] || !matches(specs[specIndex], candidates[candidateIndex])) continue
            visited[candidateIndex] = true
            val owner = candidateOwner[candidateIndex]
            if (owner == -1 || assign(owner, visited)) {
                candidateOwner[candidateIndex] = specIndex
                return true
            }
        }
        return false
    }

    for (specIndex in specs.indices) {
        if (!assign(specIndex, BooleanArray(candidates.size))) return false
    }
    return true
}
