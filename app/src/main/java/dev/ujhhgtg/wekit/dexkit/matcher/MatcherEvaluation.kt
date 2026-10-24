package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.DexSqlException
import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
    /**
     * Query-backed class-level usingStrings check (class descriptor + one StringSpec). Avoiding the
     * metadata method documents here is essential: the engine's method-document builder rejects
     * method refs whose declaring class is an array type (e.g. array.clone call sites), which would
     * otherwise surface as a DexSqlException during a pure matcher evaluation.
     */
    private val classUsingString: ((DexClassInfo, StringSpec) -> Boolean)? = null,
) {

    fun matchesClass(info: DexClassInfo, spec: ClassSpec): Boolean {
        spec.className?.let { if (!nameSpecMatchesJavaName(it, info.name)) return false }
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
        // Array-class member refs (e.g. "[Lx;->clone()...") and any other malformed member
        // descriptors are not real class members; their metadata documents cannot be resolved.
        // Skip them instead of querying the native API with an array/primitive descriptor.
        val realMethods = info.methods.filter { isRealMethodDescriptor(it.descriptor) }
        if (!injectiveMatch(spec.methods, realMethods) { s, c -> matchesMethod(c, s) }) return false
        // Class-level string semantics cover every real method's string constants. If the strict
        // member filter selected nothing (defensive: never silently drop class-level strings), fall
        // back to the weaker array-ref filter; classMethodUsingStrings ignores unreadable documents.
        val stringSourceMethods = if (realMethods.isNotEmpty()) {
            realMethods
        } else {
            info.methods.filterNot { it.descriptor.startsWith("[") }
        }
        for (stringSpec in spec.usingStrings) {
            val matched = classUsingString?.invoke(info, stringSpec)
                ?: stringSourceMethods.any { method ->
                    classMethodUsingStrings(method).any { stringSpec.matches(it) }
                }
            if (!matched) return false
        }
        return true
    }

    /**
     * String constants of one class member. The top-level method document is authoritative and
     * carries using_strings inline; shallow class-member refs may need the provider round-trip for
     * their relations, which is surfaced through the same full-document read. A native method
     * document failure (array-class method refs) is treated as "no strings" instead of aborting the
     * whole class evaluation.
     */
    private fun classMethodUsingStrings(method: DexMethodInfo): List<String> {
        val full = try {
            metadata.methodInfo(method.descriptor)
        } catch (e: DexSqlException) {
            null
        }
        if (full != null) {
            val inline = full.rawJson["using_strings"] as? JsonArray
            if (inline != null) {
                return inline.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            }
            val resolved = try {
                full.usingStrings
            } catch (e: DexSqlException) {
                emptyList()
            }
            if (resolved.isNotEmpty()) return resolved
        }
        return try {
            method.usingStrings
        } catch (e: DexSqlException) {
            emptyList()
        }
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
                    if (type != null && !typeSpecMatchesJavaName(type, info.paramTypeNames[index])) {
                        return false
                    }
                }
            }
        }
        spec.declaredClass?.let { classSpec ->
            if (!matchesClassReference(info.declaredClass, info.declaredClassName, classSpec)) return false
        }
        spec.returnType?.let { typeSpec ->
            // Never resolve class metadata for primitive/array return types: the meta layer's
            // name->descriptor conversion is guarded here, but array-typed member relations must
            // not be touched at all.
            val rawName = info.returnTypeName
            if (isPrimitiveOrArrayTypeName(rawName)) {
                if (!matchesPrimitiveOrArrayType(rawName, typeSpec)) return false
            } else if (!matchesClassReference(info.returnType, rawName, typeSpec)) {
                return false
            }
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
            if (!matchesFieldType(info.typeName, typeSpec)) return false
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
        val raw = numbersJson(info)["using_numbers"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
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
     * Relation-bearing JSON for a method. Shallow class-member refs only carry scalars, so the full
     * top-level document is fetched on demand (same as DexMethodInfo.relationsJson), but a native
     * method-document failure (array-class method refs inside invokes) must not abort resolution:
     * treat the relations as unavailable instead.
     */
    private fun numbersJson(info: DexMethodInfo): kotlinx.serialization.json.JsonObject {
        val shallow = info.rawJson
        if (shallow.containsKey("using_numbers") ||
            shallow.containsKey("invokes") ||
            shallow.containsKey("using_fields") ||
            shallow.containsKey("using_strings")
        ) {
            return shallow
        }
        return try {
            metadata.methodInfo(info.descriptor)?.rawJson ?: shallow
        } catch (e: DexSqlException) {
            shallow
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

    /**
     * Field type matching. Primitive/array Java type names ("int", "com.x.C[]") have no dataset
     * class metadata; they are compared by name only and can never satisfy structural ClassSpec
     * constraints. This avoids converting array Java names back to descriptors (which would produce
     * malformed descriptors like "Lcom/x/C[];" and crash the native metadata API).
     */
    private fun matchesFieldType(typeName: String, spec: ClassSpec): Boolean {
        val javaName = javaTypeNameOf(typeName)
        if (isPrimitiveOrArrayTypeName(javaName)) {
            return matchesPrimitiveOrArrayType(javaName, spec)
        }
        return matchesClassReference(metadata.classInfoByName(javaName), javaName, spec)
    }

    /**
     * Array/primitive type matching: no class metadata exists, so only an explicitly compatible
     * name-only matcher matches (exact Java-name/descriptor equivalence). Mode matchers
     * (Contains/StartsWith/EndsWith) and structural constraints never match arrays/primitives.
     */
    private fun matchesPrimitiveOrArrayType(actualName: String, spec: ClassSpec): Boolean {
        val nameSpec = spec.className ?: return false
        if (nameSpec.match != NameMatch.Equals) return false
        if (javaTypeNameOf(actualName) != javaTypeNameOf(nameSpec.value)) return false
        return spec.superClass == null &&
            spec.interfaces.isEmpty() &&
            spec.fields.isEmpty() &&
            spec.methods.isEmpty() &&
            spec.fieldCount == null &&
            spec.methodCount == null &&
            spec.interfaceCount == null &&
            spec.modifiers == null &&
            spec.usingStrings.isEmpty()
    }

    private fun matchesClassReference(
        resolved: DexClassInfo?,
        fallbackName: String,
        spec: ClassSpec,
    ): Boolean {
        if (resolved != null) return matchesClass(resolved, spec)
        // External/non-dataset class: only the name is available (mirrors DexKit's name-only path).
        val nameSpec = spec.className ?: return false
        return nameSpecMatchesJavaName(nameSpec, fallbackName) &&
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
