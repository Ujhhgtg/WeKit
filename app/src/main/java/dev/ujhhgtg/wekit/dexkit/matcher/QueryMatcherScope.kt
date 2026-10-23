package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import dev.ujhhgtg.wekit.dexkit.sql.SqlClassQuery
import dev.ujhhgtg.wekit.dexkit.sql.SqlFieldQuery
import dev.ujhhgtg.wekit.dexkit.sql.SqlMethodQuery
import dev.ujhhgtg.wekit.dexkit.sql.SqlStringMatchMode
import dev.ujhhgtg.wekit.dexkit.sql.sqlClass
import dev.ujhhgtg.wekit.dexkit.sql.sqlField
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod

/** Executes one generated candidate query and returns the descriptors from its first column. */
fun interface DexDescriptorQuery {
    fun descriptors(sql: String): List<String>
}

/**
 * Query-backed matcher scope: generates a fast canonical candidate query from the cheap parts of
 * the spec, runs it through [query], then evaluates the full DexKit matcher semantics over the
 * DexSQL metadata API. Deterministic broad fallbacks are used when the spec has no cheap predicate.
 *
 * Candidate generation is conservative: every generated condition is a necessary condition of a
 * true match (structural T5/T6 constraints use raw class/member joins), so the evaluator is the
 * authority. anyOf specs run the union of cheap child candidates; allOf specs use the first cheap
 * child (both directions are supersets). Caller/field-access relations are lazy metadata lookups.
 *
 * The scope is executed lazily during Dex resolution (the caller supplies metadata/query from the
 * active [dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext] session).
 */
class QueryMatcherScope(
    val metadata: DexMetadata,
    private val query: DexDescriptorQuery,
    val relations: DexMatcherRelations? = null,
) {
    private val evaluator = DexMatcherEvaluator(metadata, relations)

    fun findMethods(
        spec: MethodSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexMethodInfo> {
        val descriptors = queryDescriptors(methodCandidateSqls(spec, packages), BROAD_METHODS)
        return descriptors.asSequence()
            // Methods declared by array classes (e.g. "[Lx;->clone()...") are never valid WeKit
            // targets and the native metadata API rejects their declaring descriptor.
            .filterNot { it.startsWith("[") }
            .mapNotNull { metadata.methodInfo(it) }
            .filter { matchesPackages(it.declaredClassName, packages, excludePackages) }
            .filter { evaluator.matchesMethod(it, spec) }
            .toList()
    }

    fun findClasses(
        spec: ClassSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexClassInfo> {
        val descriptors = queryDescriptors(classCandidateSqls(spec, packages), BROAD_CLASSES)
        return descriptors.asSequence()
            .filterNot { it.startsWith("[") }
            .mapNotNull { metadata.classInfo(it) }
            .filter { matchesPackages(it.name, packages, excludePackages) }
            .filter { evaluator.matchesClass(it, spec) }
            .toList()
    }

    fun findFields(
        spec: FieldSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexFieldInfo> {
        val descriptors = queryDescriptors(fieldCandidateSqls(spec, packages), BROAD_FIELDS)
        return descriptors.asSequence()
            .filterNot { it.startsWith("[") }
            .mapNotNull { metadata.fieldInfo(it) }
            .filter { matchesPackages(it.declaredClassName, packages, excludePackages) }
            .filter { evaluator.matchesField(it, spec) }
            .toList()
    }

    /** Single-result helper: null when nothing matched; multiple matches are an ambiguity error. */
    fun uniqueMethod(
        spec: MethodSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): String? = uniqueDescriptor(findMethods(spec, packages, excludePackages), "method") { it.descriptor }

    /** Single-result helper: null when nothing matched; multiple matches are an ambiguity error. */
    fun uniqueClass(
        spec: ClassSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): String? = uniqueDescriptor(findClasses(spec, packages, excludePackages), "class") { it.descriptor }

    /** Single-result helper: null when nothing matched; multiple matches are an ambiguity error. */
    fun uniqueField(
        spec: FieldSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): String? = uniqueDescriptor(findFields(spec, packages, excludePackages), "field") { it.descriptor }

    private fun queryDescriptors(candidateSqls: List<String>?, broadSql: String): List<String> =
        (candidateSqls ?: listOf(broadSql)).flatMap { query.descriptors(it) }.distinct()

    private fun <T> uniqueDescriptor(matches: List<T>, kind: String, descriptor: (T) -> String): String? =
        when (matches.size) {
            0 -> null
            1 -> descriptor(matches.single())
            else -> error(
                "Query matcher: " + matches.size + " " + kind + "s matched, expected one: " +
                    matches.joinToString(",") { descriptor(it) },
            )
        }
}

// ---------------------------------------------------------------------------
// Candidate query generation
// ---------------------------------------------------------------------------

private const val BROAD_METHODS =
    "SELECT DISTINCT m.descriptor AS method FROM methods m ORDER BY m.descriptor;"
private const val BROAD_CLASSES =
    "SELECT DISTINCT c.descriptor AS descriptor FROM classes c ORDER BY c.descriptor;"
private const val BROAD_FIELDS =
    "SELECT DISTINCT f.descriptor AS descriptor FROM fields f ORDER BY f.descriptor;"

private fun methodCandidateSqls(spec: MethodSpec, packages: List<String>): List<String>? {
    methodOwnCandidateSql(spec, packages)?.let { return listOf(it) }
    // allOf children are all required: any child's superset narrows the candidate set.
    for (child in spec.allOf) {
        methodOwnCandidateSql(child, emptyList())?.let { return listOf(it) }
    }
    // anyOf children: their union is a superset only when every child has a candidate filter.
    if (spec.anyOf.isNotEmpty()) {
        val childSqls = spec.anyOf.map { methodOwnCandidateSql(it, emptyList()) }
        if (childSqls.all { it != null }) return childSqls.filterNotNull().distinct()
    }
    return null
}

private fun methodOwnCandidateSql(spec: MethodSpec, packages: List<String>): String? {
    // Exact descriptor equality is a cheap, fully-superset candidate filter.
    spec.descriptor?.let { descriptor ->
        return "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.descriptor = " +
            sqlLiteral(descriptor) + " ORDER BY m.descriptor;"
    }
    if (needsRawMethodCandidate(spec)) return rawMethodCandidateSql(spec, packages)
    return dslMethodCandidateSql(spec, packages)
}

private fun needsRawMethodCandidate(spec: MethodSpec): Boolean =
    (spec.declaredClass != null && !classSpecIsNameOnly(spec.declaredClass)) ||
        (spec.returnType != null && !classSpecIsNameOnly(spec.returnType))

private fun rawMethodCandidateSql(spec: MethodSpec, packages: List<String>): String? {
    val parts = CandidateQueryParts()
    addMethodCandidateParts(spec, "m", parts)
    packageCandidateCondition("m.class_descriptor", packages)?.let { parts.where += it }
    if (parts.isEmpty()) return null
    return "SELECT DISTINCT m.descriptor AS method FROM methods m" + joinsSql(parts) +
        " WHERE " + parts.where.joinToString(" AND ") + " ORDER BY m.descriptor;"
}

private fun dslMethodCandidateSql(spec: MethodSpec, packages: List<String>): String? {
    val steps = mutableListOf<(SqlMethodQuery) -> Unit>()
    spec.name?.takeIf { it.match == NameMatch.Equals }?.let { nameSpec ->
        steps += { query -> query.name = nameSpec.value }
    }
    spec.modifiers?.let { mask ->
        steps += { query -> query.modifiers = mask }
    }
    spec.declaredClass?.takeIf(::classSpecIsNameOnly)?.className?.let { nameSpec ->
        steps += { query -> query.className(nameSpec.match.toSqlMode(), nameSpec.value) }
    }
    spec.returnType?.takeIf(::classSpecIsNameOnly)?.className?.takeIf {
        it.match != NameMatch.EndsWith
    }?.let { nameSpec ->
        steps += { query -> query.returnType(nameSpec.match.toSqlMode(), nameSpec.value) }
    }
    spec.paramCount?.let { range ->
        steps += { query -> query.paramCount(range.first, range.last) }
    }
    spec.paramTypes?.takeIf { types -> types.all { it == null || it.match == NameMatch.Equals } }?.let { types ->
        val values = types.map { it?.value }.toTypedArray()
        steps += { query -> query.paramTypes(*values) }
    }
    spec.usingStrings.forEach { stringSpec ->
        steps += { query ->
            when (stringSpec.match) {
                NameMatch.Equals -> query.usingEqStrings(stringSpec.value)
                NameMatch.StartsWith -> query.usingPrefixStrings(stringSpec.value)
                NameMatch.Contains -> query.usingStrings(stringSpec.value)
                NameMatch.EndsWith -> query.usingSuffixStrings(stringSpec.value)
            }
        }
    }
    if (packages.isNotEmpty()) {
        val pack = packages.toTypedArray()
        steps += { query -> query.searchPackages(*pack) }
    }
    if (steps.isEmpty()) return null
    return sqlMethod { steps.forEach { step -> step(this) } }.toSql()
}

private fun classCandidateSqls(spec: ClassSpec, packages: List<String>): List<String>? {
    dslClassCandidateSql(spec, packages)?.let { return listOf(it) }
    rawClassCandidateSql(spec, packages)?.let { return listOf(it) }
    return null
}

private fun needsRawClassCandidate(spec: ClassSpec): Boolean =
    spec.superClass != null ||
        spec.interfaces.isNotEmpty() ||
        spec.fields.isNotEmpty() ||
        spec.methods.isNotEmpty()

private fun dslClassCandidateSql(spec: ClassSpec, packages: List<String>): String? {
    if (needsRawClassCandidate(spec)) return null
    val steps = mutableListOf<(SqlClassQuery) -> Unit>()
    spec.className?.let { nameSpec ->
        steps += { query -> query.className(nameSpec.match.toSqlMode(), nameSpec.value) }
    }
    spec.usingStrings.forEach { stringSpec ->
        steps += { query ->
            when (stringSpec.match) {
                NameMatch.Equals -> query.usingEqStrings(stringSpec.value)
                NameMatch.StartsWith -> query.usingPrefixStrings(stringSpec.value)
                NameMatch.Contains -> query.usingStrings(stringSpec.value)
                NameMatch.EndsWith -> query.usingSuffixStrings(stringSpec.value)
            }
        }
    }
    if (packages.isNotEmpty()) {
        val pack = packages.toTypedArray()
        steps += { query -> query.searchPackages(*pack) }
    }
    if (steps.isEmpty()) return null
    return sqlClass { steps.forEach { step -> step(this) } }.toSql()
}

private fun rawClassCandidateSql(spec: ClassSpec, packages: List<String>): String? {
    val parts = CandidateQueryParts()
    addClassCandidateParts(spec, "c", parts)
    packageCandidateCondition("c.descriptor", packages)?.let { parts.where += it }
    if (parts.isEmpty()) return null
    return "SELECT DISTINCT c.descriptor AS descriptor FROM classes c" + joinsSql(parts) +
        " WHERE " + parts.where.joinToString(" AND ") + " ORDER BY c.descriptor;"
}

private fun fieldCandidateSqls(spec: FieldSpec, packages: List<String>): List<String>? {
    dslFieldCandidateSql(spec, packages)?.let { return listOf(it) }
    rawFieldCandidateSql(spec, packages)?.let { return listOf(it) }
    return null
}

private fun needsRawFieldCandidate(spec: FieldSpec): Boolean =
    (spec.declaredClass != null && !classSpecIsNameOnly(spec.declaredClass)) ||
        (spec.type != null && !classSpecIsNameOnly(spec.type)) ||
        spec.readMethods.isNotEmpty() ||
        spec.writeMethods.isNotEmpty()

private fun dslFieldCandidateSql(spec: FieldSpec, packages: List<String>): String? {
    if (needsRawFieldCandidate(spec)) return null
    val steps = mutableListOf<(SqlFieldQuery) -> Unit>()
    spec.name?.takeIf { it.match == NameMatch.Equals }?.let { nameSpec ->
        steps += { query -> query.name = nameSpec.value }
    }
    spec.declaredClass?.takeIf(::classSpecIsNameOnly)?.className
        ?.takeIf { it.match == NameMatch.Equals }?.let { nameSpec ->
            steps += { query -> query.declaredClass(nameSpec.value) }
        }
    spec.type?.takeIf(::classSpecIsNameOnly)?.className
        ?.takeIf { it.match == NameMatch.Equals }?.let { nameSpec ->
            steps += { query -> query.type(nameSpec.value) }
        }
    if (packages.isNotEmpty()) {
        val pack = packages.toTypedArray()
        steps += { query -> query.searchPackages(*pack) }
    }
    if (steps.isEmpty()) return null
    return sqlField { steps.forEach { step -> step(this) } }.toSql()
}

private fun rawFieldCandidateSql(spec: FieldSpec, packages: List<String>): String? {
    val parts = CandidateQueryParts()
    addFieldCandidateParts(spec, "f", parts)
    packageCandidateCondition("f.class_descriptor", packages)?.let { parts.where += it }
    if (parts.isEmpty()) return null
    return "SELECT DISTINCT f.descriptor AS descriptor FROM fields f" + joinsSql(parts) +
        " WHERE " + parts.where.joinToString(" AND ") + " ORDER BY f.descriptor;"
}

private fun joinsSql(parts: CandidateQueryParts): String =
    if (parts.joins.isEmpty()) "" else " " + parts.joins.joinToString(" ")

private fun sqlLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

private fun classSpecIsNameOnly(spec: ClassSpec): Boolean =
    spec.className != null &&
        spec.superClass == null &&
        spec.interfaces.isEmpty() &&
        spec.fields.isEmpty() &&
        spec.methods.isEmpty() &&
        spec.fieldCount == null &&
        spec.methodCount == null &&
        spec.interfaceCount == null &&
        spec.modifiers == null &&
        spec.usingStrings.isEmpty()

private fun NameMatch.toSqlMode(): SqlStringMatchMode = when (this) {
    NameMatch.Equals -> SqlStringMatchMode.Equals
    NameMatch.StartsWith -> SqlStringMatchMode.StartsWith
    NameMatch.Contains -> SqlStringMatchMode.Contains
    NameMatch.EndsWith -> SqlStringMatchMode.EndsWith
}

private fun matchesPackages(
    javaName: String,
    packages: List<String>,
    excludePackages: List<String>,
): Boolean {
    if (packages.isNotEmpty() && packages.none { javaName == it || javaName.startsWith(it + ".") }) {
        return false
    }
    if (excludePackages.any { javaName == it || javaName.startsWith(it + ".") }) {
        return false
    }
    return true
}
