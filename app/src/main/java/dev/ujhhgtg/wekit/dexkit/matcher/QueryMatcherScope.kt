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
        val sql = methodCandidateSql(spec, packages) ?: BROAD_METHODS
        return query.descriptors(sql).asSequence()
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
        val sql = classCandidateSql(spec, packages) ?: BROAD_CLASSES
        return query.descriptors(sql).asSequence()
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
        val sql = fieldCandidateSql(spec, packages) ?: BROAD_FIELDS
        return query.descriptors(sql).asSequence()
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

private fun methodCandidateSql(spec: MethodSpec, packages: List<String>): String? {
    // Exact descriptor equality is a cheap, fully-superset candidate filter.
    spec.descriptor?.let { descriptor ->
        return "SELECT DISTINCT m.descriptor AS method FROM methods m WHERE m.descriptor = " +
            sqlLiteral(descriptor) + " ORDER BY m.descriptor;"
    }
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

private fun classCandidateSql(spec: ClassSpec, packages: List<String>): String? {
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

private fun fieldCandidateSql(spec: FieldSpec, packages: List<String>): String? {
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
