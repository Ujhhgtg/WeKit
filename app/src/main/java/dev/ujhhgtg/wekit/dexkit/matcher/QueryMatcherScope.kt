package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.meta.DexClassInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexFieldInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMethodInfo
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadata
import dev.ujhhgtg.wekit.dexkit.meta.DexMetadataKind
import dev.ujhhgtg.wekit.dexkit.meta.dottedClassNameToDescriptor
import dev.ujhhgtg.wekit.dexkit.sql.SqlClassLiteralConstraint
import dev.ujhhgtg.wekit.dexkit.sql.SqlClassQuery
import dev.ujhhgtg.wekit.dexkit.sql.SqlFieldQuery
import dev.ujhhgtg.wekit.dexkit.sql.SqlMethodQuery
import dev.ujhhgtg.wekit.dexkit.sql.SqlStringMatchMode
import dev.ujhhgtg.wekit.dexkit.sql.sqlClass
import dev.ujhhgtg.wekit.dexkit.sql.sqlField
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Executes one generated candidate query and returns the descriptors from its first column. */
fun interface DexDescriptorQuery {
    fun descriptors(sql: String): List<String>
}

private const val DEFAULT_CANDIDATE_PAGE_SIZE = 4096

/** One candidate source: canonical SQL text (paged) or a known-short descriptor list. */
private sealed interface CandidateSource {
    class Sql(val sql: String) : CandidateSource

    class Descriptors(val descriptors: List<String>) : CandidateSource
}

/**
 * Query-backed matcher scope: generates candidate sets only through canonical DexSQL fast-path
 * shapes (the same shapes the SQL DSL emits), then evaluates the full DexKit matcher semantics over
 * the metadata API.
 *
 * Candidate generation rules (the pinned engine only fast-paths canonical shapes):
 * - method specs use the typed SQL DSL (methods x method_strings / method_using_numbers /
 *   method_field_accesses canonical joins with single-equality ON + file_id/method_idx WHERE);
 * - structural constraints (declaredClass/returnType class structure, invokeMethods,
 *   usingFields, callerMethods) are evaluated in Kotlin, never joined in SQL;
 * - class specs use className/packages/class-level literals through the DSL; a class whose only
 *   cheap predicate is class-level usingStrings (or a nested cheap member) is narrowed in two
 *   steps: canonical method/field lookup, then map the candidate's declared class and evaluate;
 * - field specs use name/type/declaredClass/packages through the DSL.
 *
 * A spec with no fast-path-expressible predicate is an explicit unsupported-shape error instead
 * of a multi-gigabyte generic table scan.
 *
 * unique* helpers stop as soon as a second match is found and page SQL candidates, so the amount of
 * evaluated work/cache growth is bounded. Raw documents are read through [rawMetadata] and cheap
 * scalar prefilters run before a parsed metadata model is constructed (rejected candidates never
 * enter the session cache).
 */
class QueryMatcherScope(
    val metadata: DexMetadata,
    private val query: DexDescriptorQuery,
    val relations: DexMatcherRelations? = null,
    /** Raw metadata document reader; lets cheap prefilters run before model construction. */
    private val rawMetadata: ((DexMetadataKind, String) -> JsonObject?)? = null,
    private val candidatePageSize: Int = DEFAULT_CANDIDATE_PAGE_SIZE,
) {
    private val evaluator = DexMatcherEvaluator(metadata, relations)

    fun findMethods(
        spec: MethodSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexMethodInfo> {
        val sources = methodCandidateSources(spec, packages) ?: unsupportedCandidate("method")
        return queryDescriptors(sources).asSequence()
            .filterNot { it.startsWith("[") }
            .mapNotNull { loadMethod(it, spec) }
            .filter { matchesPackages(it.declaredClassName, packages, excludePackages) }
            .filter { evaluator.matchesMethod(it, spec) }
            .toList()
    }

    fun findClasses(
        spec: ClassSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexClassInfo> {
        val sources = classCandidateSources(spec, packages) ?: unsupportedCandidate("class")
        return queryDescriptors(sources).asSequence()
            .filterNot { it.startsWith("[") }
            .mapNotNull { loadClass(it, spec) }
            .filter { matchesPackages(it.name, packages, excludePackages) }
            .filter { evaluator.matchesClass(it, spec) }
            .toList()
    }

    fun findFields(
        spec: FieldSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): List<DexFieldInfo> {
        val sources = fieldCandidateSources(spec, packages) ?: unsupportedCandidate("field")
        return queryDescriptors(sources).asSequence()
            .filterNot { it.startsWith("[") }
            .mapNotNull { loadField(it, spec) }
            .filter { matchesPackages(it.declaredClassName, packages, excludePackages) }
            .filter { evaluator.matchesField(it, spec) }
            .toList()
    }

    /**
     * Single-result helper: null when nothing matched; multiple matches are an ambiguity error.
     * Evaluation stops as soon as a second match is found; candidates are paged.
     */
    fun uniqueMethod(
        spec: MethodSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): String? = uniqueMatches(findMethodsUpToTwo(spec, packages, excludePackages), "method") {
        it.descriptor
    }

    /** Single-result helper: null when nothing matched; multiple matches are an ambiguity error. */
    fun uniqueClass(
        spec: ClassSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): String? = uniqueMatches(findClassesUpToTwo(spec, packages, excludePackages), "class") {
        it.descriptor
    }

    /** Single-result helper: null when nothing matched; multiple matches are an ambiguity error. */
    fun uniqueField(
        spec: FieldSpec,
        packages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
    ): String? = uniqueMatches(findFieldsUpToTwo(spec, packages, excludePackages), "field") {
        it.descriptor
    }

    // ---------------------------------------------------------------------
    // Bounded unique search (stop at the second match, paged candidates)
    // ---------------------------------------------------------------------

    private fun findMethodsUpToTwo(
        spec: MethodSpec,
        packages: List<String>,
        excludePackages: List<String>,
    ): List<DexMethodInfo> {
        val sources = methodCandidateSources(spec, packages) ?: unsupportedCandidate("method")
        val matches = mutableListOf<DexMethodInfo>()
        val matchedDescriptors = HashSet<String>(4)
        forEachCandidateDescriptor(sources) { descriptor ->
            if (!descriptor.startsWith("[")) {
                val info = loadMethod(descriptor, spec)
                if (info != null &&
                    matchesPackages(info.declaredClassName, packages, excludePackages) &&
                    evaluator.matchesMethod(info, spec) &&
                    matchedDescriptors.add(info.descriptor)
                ) {
                    matches += info
                }
            }
            matches.size >= 2
        }
        return matches
    }

    private fun findClassesUpToTwo(
        spec: ClassSpec,
        packages: List<String>,
        excludePackages: List<String>,
    ): List<DexClassInfo> {
        val sources = classCandidateSources(spec, packages) ?: unsupportedCandidate("class")
        val matches = mutableListOf<DexClassInfo>()
        val matchedDescriptors = HashSet<String>(4)
        forEachCandidateDescriptor(sources) { descriptor ->
            if (!descriptor.startsWith("[")) {
                val info = loadClass(descriptor, spec)
                if (info != null &&
                    matchesPackages(info.name, packages, excludePackages) &&
                    evaluator.matchesClass(info, spec) &&
                    matchedDescriptors.add(info.descriptor)
                ) {
                    matches += info
                }
            }
            matches.size >= 2
        }
        return matches
    }

    private fun findFieldsUpToTwo(
        spec: FieldSpec,
        packages: List<String>,
        excludePackages: List<String>,
    ): List<DexFieldInfo> {
        val sources = fieldCandidateSources(spec, packages) ?: unsupportedCandidate("field")
        val matches = mutableListOf<DexFieldInfo>()
        val matchedDescriptors = HashSet<String>(4)
        forEachCandidateDescriptor(sources) { descriptor ->
            if (!descriptor.startsWith("[")) {
                val info = loadField(descriptor, spec)
                if (info != null &&
                    matchesPackages(info.declaredClassName, packages, excludePackages) &&
                    evaluator.matchesField(info, spec) &&
                    matchedDescriptors.add(info.descriptor)
                ) {
                    matches += info
                }
            }
            matches.size >= 2
        }
        return matches
    }

    private fun forEachCandidateDescriptor(
        sources: List<CandidateSource>,
        action: (String) -> Boolean,
    ) {
        for (source in sources) {
            when (source) {
                is CandidateSource.Descriptors -> {
                    for (descriptor in source.descriptors) {
                        if (action(descriptor)) return
                    }
                }

                is CandidateSource.Sql -> {
                    var offset = 0
                    while (true) {
                        val page = query.descriptors(pagedSql(source.sql, candidatePageSize, offset))
                        if (page.isEmpty()) break
                        for (descriptor in page) {
                            if (action(descriptor)) return
                        }
                        if (page.size < candidatePageSize) break
                        offset += candidatePageSize
                    }
                }
            }
        }
    }

    private fun queryDescriptors(sources: List<CandidateSource>): List<String> =
        sources.flatMap { source ->
            when (source) {
                is CandidateSource.Descriptors -> source.descriptors
                is CandidateSource.Sql -> query.descriptors(source.sql)
            }
        }.distinct()

    private fun <T> uniqueMatches(matches: List<T>, kind: String, descriptor: (T) -> String): String? =
        when (matches.size) {
            0 -> null
            1 -> descriptor(matches.single())
            else -> error(
                "Query matcher: multiple " + kind + "s matched, expected one: " +
                    matches.joinToString(",") { descriptor(it) },
            )
        }

    // ---------------------------------------------------------------------
    // Candidate loading with cheap scalar prefiltering
    // ---------------------------------------------------------------------

    private fun loadMethod(descriptor: String, spec: MethodSpec): DexMethodInfo? {
        val source = rawMetadata ?: return metadata.methodInfo(descriptor)
        val json = source.invoke(DexMetadataKind.Method, descriptor) ?: return null
        if (!methodPrefilter(json, spec)) return null
        return DexMethodInfo(json, metadata)
    }

    private fun loadClass(descriptor: String, spec: ClassSpec): DexClassInfo? {
        val source = rawMetadata ?: return metadata.classInfo(descriptor)
        val json = source.invoke(DexMetadataKind.Class, descriptor) ?: return null
        if (!classPrefilter(json, spec)) return null
        return DexClassInfo(json, metadata)
    }

    private fun loadField(descriptor: String, spec: FieldSpec): DexFieldInfo? {
        val source = rawMetadata ?: return metadata.fieldInfo(descriptor)
        val json = source.invoke(DexMetadataKind.Field, descriptor) ?: return null
        if (!fieldPrefilter(json, spec)) return null
        return DexFieldInfo(json, metadata)
    }

    // ---------------------------------------------------------------------
    // Candidate source generation: canonical fast-path shapes only
    // ---------------------------------------------------------------------

    private fun methodCandidateSources(spec: MethodSpec, packages: List<String>): List<CandidateSource>? {
        // Exact descriptor specs need no SQL: the descriptor itself is the only candidate.
        spec.descriptor?.let { return listOf(CandidateSource.Descriptors(listOf(it))) }
        methodOwnCandidateSources(spec, packages)?.let { return it }
        for (child in spec.allOf) {
            methodOwnCandidateSources(child, emptyList())?.let { return it }
        }
        if (spec.anyOf.isNotEmpty()) {
            val childSources = spec.anyOf.map { methodOwnCandidateSources(it, emptyList()) }
            if (childSources.all { it != null }) {
                return childSources.filterNotNull().flatten().distinct()
            }
        }
        return null
    }

    private fun methodOwnCandidateSources(
        spec: MethodSpec,
        packages: List<String>,
    ): List<CandidateSource>? {
        val steps = mutableListOf<(SqlMethodQuery) -> Unit>()
        spec.name?.takeIf { it.match == NameMatch.Equals }?.let { nameSpec ->
            steps += { q -> q.name = nameSpec.value }
        }
        spec.modifiers?.let { mask -> steps += { q -> q.modifiers(mask) } }
        spec.declaredClass?.let { classSpec ->
            classSpec.className?.let { nameSpec ->
                steps += { q -> q.className(nameSpec.match.toSqlMode(), nameSpec.value) }
            }
            if (classSpec.usingStrings.isNotEmpty()) {
                steps += { q ->
                    q.declaredClass { addClassLiteralStrings(classSpec.usingStrings) }
                }
            }
        }
        spec.returnType?.className?.takeIf { it.match != NameMatch.EndsWith }?.let { nameSpec ->
            steps += { q -> q.returnType(nameSpec.match.toSqlMode(), nameSpec.value) }
        }
        spec.paramCount?.let { range -> steps += { q -> q.paramCount(range.first, range.last) } }
        spec.paramTypes
            ?.takeIf { types -> types.all { it == null || it.match == NameMatch.Equals } }
            ?.let { types ->
                val values = types.map { it?.value }.toTypedArray()
                steps += { q -> q.paramTypes(*values) }
            }
        spec.usingStrings.forEach { stringSpec ->
            steps += { q -> addMethodStringPredicate(q, stringSpec) }
        }
        if (spec.usingNumbers.isNotEmpty()) {
            val numbers = spec.usingNumbers.map { it as Any }.toTypedArray()
            steps += { q -> q.usingNumbers(*numbers) }
        }
        if (packages.isNotEmpty()) {
            val pack = packages.toTypedArray()
            steps += { q -> q.searchPackages(*pack) }
        }
        if (steps.isEmpty()) return null
        return listOf(CandidateSource.Sql(sqlMethod { steps.forEach { step -> step(this) } }.toSql()))
    }

    private fun classCandidateSources(spec: ClassSpec, packages: List<String>): List<CandidateSource>? {
        classDirectCandidateSources(spec, packages)?.let { return it }
        // Class-level usingStrings: canonical method lookup for each literal, then map the
        // candidate method's declared class. Never join classes x methods x method_strings.
        if (spec.usingStrings.isNotEmpty()) {
            val classDescriptors = linkedSetOf<String>()
            for (stringSpec in spec.usingStrings) {
                val sql = sqlMethod { addMethodStringPredicate(this, stringSpec) }.toSql()
                query.descriptors(sql).forEach { methodDescriptor ->
                    if (!methodDescriptor.startsWith("[")) {
                        declaredClassNameOfMethod(methodDescriptor)?.let {
                            classDescriptors += dottedClassNameToDescriptor(it)
                        }
                    }
                }
            }
            return listOf(CandidateSource.Descriptors(classDescriptors.toList()))
        }
        // Nested member methods: canonical method lookup, then map declared classes.
        for (methodSpec in spec.methods) {
            val methodSources = methodCandidateSources(methodSpec, emptyList()) ?: continue
            val classDescriptors = queryDescriptors(methodSources)
                .filterNot { it.startsWith("[") }
                .mapNotNull { declaredClassNameOfMethod(it) }
                .map(::dottedClassNameToDescriptor)
                .distinct()
            return listOf(CandidateSource.Descriptors(classDescriptors))
        }
        // Nested member fields: canonical field lookup, then map declared classes.
        for (fieldSpec in spec.fields) {
            val fieldSources = fieldCandidateSources(fieldSpec, emptyList()) ?: continue
            val classDescriptors = queryDescriptors(fieldSources)
                .filterNot { it.startsWith("[") }
                .mapNotNull { declaredClassNameOfField(it) }
                .map(::dottedClassNameToDescriptor)
                .distinct()
            return listOf(CandidateSource.Descriptors(classDescriptors))
        }
        // Superclass-only class specs use the canonical superclass unit shape.
        spec.superClass?.className?.let { nameSpec ->
            val sql = sqlClass {
                superClass(nameSpec.match.toSqlMode(), nameSpec.value)
                if (packages.isNotEmpty()) searchPackages(*packages.toTypedArray())
            }.toSql()
            return listOf(CandidateSource.Sql(sql))
        }
        return null
    }

    private fun classDirectCandidateSources(
        spec: ClassSpec,
        packages: List<String>,
    ): List<CandidateSource>? {
        // Class-level usingStrings are intentionally NOT emitted as a class-literal SQL join here;
        // they are resolved in two canonical steps in classCandidateSources (method lookup, then
        // map the declared class), which keeps the class query a simple fast descriptor scan.
        val steps = mutableListOf<(SqlClassQuery) -> Unit>()
        spec.className?.let { nameSpec ->
            steps += { q -> q.className(nameSpec.match.toSqlMode(), nameSpec.value) }
        }
        if (packages.isNotEmpty()) {
            val pack = packages.toTypedArray()
            steps += { q -> q.searchPackages(*pack) }
        }
        if (steps.isEmpty()) return null
        return listOf(CandidateSource.Sql(sqlClass { steps.forEach { step -> step(this) } }.toSql()))
    }

    private fun fieldCandidateSources(spec: FieldSpec, packages: List<String>): List<CandidateSource>? {
        val steps = mutableListOf<(SqlFieldQuery) -> Unit>()
        spec.name?.takeIf { it.match == NameMatch.Equals }?.let { nameSpec ->
            steps += { q -> q.name = nameSpec.value }
        }
        spec.declaredClass?.takeIf(::classSpecIsNameOnly)?.className
            ?.takeIf { it.match == NameMatch.Equals }?.let { nameSpec ->
                steps += { q -> q.declaredClass(nameSpec.value) }
            }
        spec.type?.takeIf(::classSpecIsNameOnly)?.className
            ?.takeIf { it.match == NameMatch.Equals }?.let { nameSpec ->
                steps += { q -> q.type(nameSpec.value) }
            }
        if (packages.isNotEmpty()) {
            val pack = packages.toTypedArray()
            steps += { q -> q.searchPackages(*pack) }
        }
        if (steps.isEmpty()) return null
        return listOf(CandidateSource.Sql(sqlField { steps.forEach { step -> step(this) } }.toSql()))
    }

    private fun declaredClassNameOfMethod(descriptor: String): String? {
        rawMetadata?.invoke(DexMetadataKind.Method, descriptor)?.let { json ->
            return json.stringValue("declared_class_name")
        }
        return metadata.methodInfo(descriptor)?.declaredClassName
    }

    private fun declaredClassNameOfField(descriptor: String): String? {
        rawMetadata?.invoke(DexMetadataKind.Field, descriptor)?.let { json ->
            return json.stringValue("declared_class_name")
        }
        return metadata.fieldInfo(descriptor)?.declaredClassName
    }

    private fun unsupportedCandidate(kind: String): Nothing = throw UnsupportedOperationException(
        "Query matcher " + kind + " candidate shape is unsupported: the spec has no fast-path " +
            "predicate (method: name/modifiers/declaredClass className|usingStrings/returnType " +
            "className/paramCount/paramTypes/usingStrings/usingNumbers/packages; class: className/" +
            "class usingStrings/cheap nested members/superClass; field: name/declaredClass/type/" +
            "packages). Add one, or extend the matcher helper with a canonical fast-path shape.",
    )
}

// ---------------------------------------------------------------------------
// Canonical SQL predicate helpers (delegating to the typed SQL DSL)
// ---------------------------------------------------------------------------

private fun NameMatch.toSqlMode(): SqlStringMatchMode = when (this) {
    NameMatch.Equals -> SqlStringMatchMode.Equals
    NameMatch.StartsWith -> SqlStringMatchMode.StartsWith
    NameMatch.Contains -> SqlStringMatchMode.Contains
    NameMatch.EndsWith -> SqlStringMatchMode.EndsWith
}

private fun addMethodStringPredicate(query: SqlMethodQuery, spec: StringSpec) {
    when (spec.match) {
        NameMatch.Equals -> query.usingEqStrings(spec.value)
        NameMatch.StartsWith -> query.usingPrefixStrings(spec.value)
        NameMatch.Contains -> query.usingStrings(spec.value)
        NameMatch.EndsWith -> query.usingSuffixStrings(spec.value)
    }
}

private fun SqlClassLiteralConstraint.addClassLiteralStrings(strings: List<StringSpec>) {
    strings.forEach { spec ->
        when (spec.match) {
            NameMatch.Equals -> usingEqStrings(spec.value)
            NameMatch.StartsWith -> usingPrefixStrings(spec.value)
            NameMatch.Contains -> usingStrings(spec.value)
            NameMatch.EndsWith -> usingSuffixStrings(spec.value)
        }
    }
}

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

// ---------------------------------------------------------------------------
// Paging and cheap raw-document prefiltering
// ---------------------------------------------------------------------------

private fun pagedSql(sql: String, pageSize: Int, offset: Int): String {
    val base = sql.trim().removeSuffix(";")
    return base + " LIMIT " + pageSize + " OFFSET " + offset + ";"
}

private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

/** Cheap necessary-condition prefilter on the raw method document (no model construction). */
private fun methodPrefilter(json: JsonObject, spec: MethodSpec): Boolean {
    spec.descriptor?.let { if (json.stringValue("descriptor") != it) return false }
    spec.name?.takeIf { it.match == NameMatch.Equals }?.let {
        if (json.stringValue("name") != it.value) return false
    }
    spec.modifiers?.let { mask ->
        val modifiers = json.intValue("modifiers") ?: return@let
        if ((modifiers and mask) != mask) return false
    }
    spec.paramCount?.let { range ->
        val count = json.intValue("param_count") ?: return@let
        if (count !in range) return false
    }
    spec.paramTypes?.takeIf { types -> types.all { it == null || it.match == NameMatch.Equals } }?.let { types ->
        val names = json.stringList("param_type_names")
        if (names.size != types.size) return false
        types.forEachIndexed { index, type ->
            if (type != null && names[index] != type.value) return false
        }
    }
    spec.declaredClass?.takeIf(::classSpecIsNameOnly)?.className?.let {
        val name = json.stringValue("declared_class_name") ?: return@let
        if (!it.matches(name)) return false
    }
    spec.returnType?.takeIf(::classSpecIsNameOnly)?.className?.let {
        val name = json.stringValue("return_type_name") ?: return@let
        if (!it.matches(name)) return false
    }
    if (spec.usingStrings.isNotEmpty()) {
        val strings = json.stringList("using_strings")
        for (stringSpec in spec.usingStrings) {
            if (strings.none { stringSpec.matches(it) }) return false
        }
    }
    return true
}

/** Cheap necessary-condition prefilter on the raw class document. */
private fun classPrefilter(json: JsonObject, spec: ClassSpec): Boolean {
    spec.className?.let {
        val name = json.stringValue("name") ?: return@let
        if (!it.matches(name)) return false
    }
    spec.modifiers?.let { mask ->
        val modifiers = json.intValue("modifiers") ?: return@let
        if ((modifiers and mask) != mask) return false
    }
    spec.methodCount?.let { range ->
        val size = (json["methods"] as? JsonArray)?.size ?: return@let
        if (size !in range) return false
    }
    spec.fieldCount?.let { range ->
        val size = (json["fields"] as? JsonArray)?.size ?: return@let
        if (size !in range) return false
    }
    spec.interfaceCount?.let { range ->
        val size = (json["interfaces"] as? JsonArray)?.size ?: return@let
        if (size !in range) return false
    }
    spec.superClass?.takeIf(::classSpecIsNameOnly)?.className?.let {
        val name = json.objectValue("super_class")?.stringValue("name") ?: return@let
        if (!it.matches(name)) return false
    }
    if (spec.interfaces.isNotEmpty()) {
        val interfaceArray = json["interfaces"] as? JsonArray ?: return true
        val interfaceNames = interfaceArray.mapNotNull { (it as? JsonObject)?.stringValue("name") }
        for (interfaceSpec in spec.interfaces) {
            val interfaceName = interfaceSpec.className ?: continue
            if (!classSpecIsNameOnly(interfaceSpec)) continue
            if (interfaceNames.none { interfaceName.matches(it) }) return false
        }
    }
    return true
}

/** Cheap necessary-condition prefilter on the raw field document. */
private fun fieldPrefilter(json: JsonObject, spec: FieldSpec): Boolean {
    spec.name?.takeIf { it.match == NameMatch.Equals }?.let {
        if (json.stringValue("name") != it.value) return false
    }
    spec.modifiers?.let { mask ->
        val modifiers = json.intValue("modifiers") ?: return@let
        if ((modifiers and mask) != mask) return false
    }
    spec.type?.takeIf(::classSpecIsNameOnly)?.className?.let {
        val name = json.stringValue("type_name") ?: return@let
        if (!it.matches(name)) return false
    }
    spec.declaredClass?.takeIf(::classSpecIsNameOnly)?.className?.let {
        val name = json.stringValue("declared_class_name") ?: return@let
        if (!it.matches(name)) return false
    }
    return true
}
