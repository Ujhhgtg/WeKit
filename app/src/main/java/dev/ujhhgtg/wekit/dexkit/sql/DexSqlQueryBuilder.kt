package dev.ujhhgtg.wekit.dexkit.sql

internal enum class SqlStringMode { CONTAINS, EQUALS, PREFIX, SUFFIX }

internal class SqlStringLiteral(val value: Any, val mode: SqlStringMode)

/** One class-level literal call: either string literals with one mode, or number values. */
internal class SqlLiteralCall(
    val strings: List<SqlStringLiteral> = emptyList(),
    val numbers: List<Any> = emptyList(),
)

/** One ordered read/write method access matcher of a field query. */
internal class SqlFieldAccess(val query: SqlMethodQuery, val usingType: String)

/**
 * Shared predicate storage for the public builders.
 *
 * [strings] and [numberCalls] keep declaration order because every literal becomes its own relation
 * alias; [packages] and the range are shared by method, class and field queries. Number values keep
 * their call grouping because one class-level usingNumbers(...) call is one existential unit.
 */
internal class DexSqlLiterals {
    val strings = mutableListOf<SqlStringLiteral>()
    val numberCalls = mutableListOf<List<Any>>()
    val packages = mutableListOf<String>()
    var limit: Int? = null
        private set
    var offset: Int? = null
        private set

    fun usingStrings(vararg values: Any) = addStrings(values, SqlStringMode.CONTAINS)

    fun usingEqStrings(vararg values: Any) = addStrings(values, SqlStringMode.EQUALS)

    fun usingPrefixStrings(vararg values: Any) = addStrings(values, SqlStringMode.PREFIX)

    fun usingSuffixStrings(vararg values: Any) = addStrings(values, SqlStringMode.SUFFIX)

    fun addUsingString(value: Any) = addStrings(arrayOf(value), SqlStringMode.CONTAINS)

    fun addEqString(value: Any) = addStrings(arrayOf(value), SqlStringMode.EQUALS)

    fun usingNumbers(vararg values: Any) {
        if (values.isNotEmpty()) numberCalls += values.toList()
    }

    fun searchPackages(vararg values: String) = values.forEach { packages += it }

    fun setLimit(value: Int) {
        limit = value
    }

    fun setOffset(value: Int) {
        offset = value
    }

    fun validateRange() {
        limit?.let { require(it >= 0) { "DexSQL limit must be non-negative, was " + it } }
        offset?.let {
            require(it >= 0) { "DexSQL offset must be non-negative, was " + it }
            require(limit != null) { "DexSQL offset requires a limit" }
        }
    }

    private fun addStrings(values: Array<out Any>, mode: SqlStringMode) {
        values.forEach { strings += SqlStringLiteral(it, mode) }
    }
}

/**
 * Assigns @@WEKIT_DEFERRED_n@@ tokens in SQL emission order and records how each token is
 * rendered by [DexSqlQuery.buildSql].
 */
internal class DexSqlEmission {
    private val deferredRenderers = mutableListOf<() -> String>()

    /** Reserve the next token; [render] runs when [DexSqlQuery.buildSql] is called. */
    fun token(render: () -> String): String {
        val index = deferredRenderers.size
        deferredRenderers += render
        return deferredToken(index)
    }

    fun build(sql: String): DexSqlQuery = DexSqlQuery(sql, deferredRenderers)
}

private const val CONSTRUCTOR_NAME = "<init>"

private val PRIMITIVE_DESCRIPTORS = mapOf(
    "void" to "V",
    "boolean" to "Z",
    "byte" to "B",
    "short" to "S",
    "char" to "C",
    "int" to "I",
    "long" to "J",
    "float" to "F",
    "double" to "D",
)

/**
 * Convert a Java type name to a DEX type descriptor, mirroring the canonical extractor:
 * primitives map to their single-letter descriptor, arrays recurse, L...; descriptors pass
 * through, and everything else becomes La/b/C;. A raw array descriptor such as [I is not
 * special-cased, matching the extractor.
 */
internal fun javaType(value: String): String {
    val type = value.trim()
    PRIMITIVE_DESCRIPTORS[type]?.let { return it }
    if (type.endsWith("[]")) return "[" + javaType(type.dropLast(2))
    if (type.startsWith("L") && type.endsWith(";")) return type
    require(type.isNotEmpty()) { "DexSQL Java type must not be blank" }
    return "L" + type.replace('.', '/') + ";"
}

/**
 * Convert a Java class name to a DEX class descriptor; an existing L...; descriptor passes
 * through unchanged.
 */
internal fun classDescriptor(value: String): String {
    val name = value.trim()
    if (name.startsWith("L") && name.endsWith(";")) return name
    require(name.isNotEmpty()) { "DexSQL class name must not be blank" }
    return "L" + name.replace('.', '/').trim('/') + ";"
}

/** Escape a SQL string literal by doubling single quotes. */
internal fun sqlQuote(value: String): String = "'" + value.replace("'", "''") + "'"

private fun packageDescriptor(packageName: String): String =
    "L" + packageName.replace('.', '/').trim('/') + "/%"

private fun packageCondition(column: String, packages: List<String>): String? {
    if (packages.isEmpty()) return null
    return packages.joinToString(separator = " OR ", prefix = "(", postfix = ")") { packageName ->
        column + " LIKE " + sqlQuote(packageDescriptor(packageName))
    }
}

/**
 * Escape a contains/prefix/suffix literal and wrap it into a SQL LIKE pattern. Only used when the
 * literal contains backslash, percent or underscore; brackets stay literal characters.
 */
private fun likePattern(value: String, mode: SqlStringMode): String {
    val escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return wrapLikePattern(escaped, mode)
}

/**
 * Wrap a plain contains/prefix/suffix literal into a SQL LIKE pattern. The caller only uses this
 * when the literal contains no backslash, percent or underscore, which keeps already-applied
 * delegate bytes identical.
 */
private fun wrapLikePattern(value: String, mode: SqlStringMode): String = when (mode) {
    SqlStringMode.CONTAINS -> "%" + value + "%"
    SqlStringMode.PREFIX -> value + "%"
    SqlStringMode.SUFFIX -> "%" + value
    SqlStringMode.EQUALS -> error("EQUALS literals are not LIKE patterns")
}

/** Render a string-literal fragment: raw equals, or a LIKE pattern with conditional escaping. */
private fun likeFragment(value: String, mode: SqlStringMode): String {
    if (mode == SqlStringMode.EQUALS) return sqlQuote(value)
    val needsEscape = value.any { it == '\\' || it == '%' || it == '_' }
    return if (needsEscape) {
        sqlQuote(likePattern(value, mode)) + " ESCAPE '\\'"
    } else {
        sqlQuote(wrapLikePattern(value, mode))
    }
}

private fun deferredText(value: Any?): String =
    value?.toString() ?: error("Deferred SQL value resolved to null")

/** Render a class-name slot: deferred values become @TOKEN@, static values render immediately. */
private fun renderClassLiteral(value: Any, emission: DexSqlEmission): String =
    if (value is SqlDeferredValue) {
        emission.token { sqlQuote(classDescriptor(deferredText(value.provider()))) }
    } else {
        sqlQuote(classDescriptor(value.toString()))
    }

/** Render a type slot: deferred values become @TOKEN@, static values render immediately. */
private fun renderTypeLiteral(value: Any, emission: DexSqlEmission): String =
    if (value is SqlDeferredValue) {
        emission.token { sqlQuote(javaType(deferredText(value.provider()))) }
    } else {
        sqlQuote(javaType(value.toString()))
    }

/** Render a string-literal slot; deferred values tokenize the whole quoted literal fragment. */
private fun renderStringLiteral(value: Any, mode: SqlStringMode, emission: DexSqlEmission): String =
    if (value is SqlDeferredValue) {
        emission.token { likeFragment(deferredText(value.provider()), mode) }
    } else {
        likeFragment(value.toString(), mode)
    }

private fun stringCondition(column: String, literal: SqlStringLiteral, emission: DexSqlEmission): String =
    column + (if (literal.mode == SqlStringMode.EQUALS) " = " else " LIKE ") +
        renderStringLiteral(literal.value, literal.mode, emission)

private fun asNumber(value: Any?): Number =
    value as? Number ?: error("DexSQL number predicate requires a Number, was " + value)

/** Canonical numeric condition suffix: long_value for integers, double_value for Float/Double. */
private fun numberSuffix(number: Number): String {
    if (number is Float) {
        require(number.isFinite()) { "DexSQL numeric predicate must be finite" }
        return "double_value = " + number
    }
    if (number is Double) {
        require(number.isFinite()) { "DexSQL numeric predicate must be finite" }
        return "double_value = " + number
    }
    return "long_value = " + number.toLong()
}

/** Render a numeric condition suffix; deferred values tokenize the column/value suffix. */
private fun renderNumberSuffix(value: Any, emission: DexSqlEmission): String =
    if (value is SqlDeferredValue) {
        emission.token { numberSuffix(asNumber(value.provider())) }
    } else {
        numberSuffix(asNumber(value))
    }

/** One param_descriptors condition; a list containing deferred parts becomes a single token. */
private fun paramDescriptorsCondition(
    baseAlias: String,
    types: List<Any>,
    emission: DexSqlEmission,
): String {
    val fragment = if (types.none { it is SqlDeferredValue }) {
        sqlQuote(types.joinToString(",") { javaType(it.toString()) })
    } else {
        emission.token {
            sqlQuote(
                types.joinToString(",") { part ->
                    when (part) {
                        is SqlDeferredValue -> javaType(deferredText(part.provider()))
                        else -> javaType(part.toString())
                    }
                },
            )
        }
    }
    return baseAlias + ".param_descriptors = " + fragment
}

private class MethodProperties(
    val name: String?,
    val declaredClass: Any?,
    val returnType: Any?,
    val paramCount: Int?,
    val paramTypes: List<Any>?,
    /** Access-flag mask emitted after the properties: add for nested method matchers only. */
    val modifiers: Int? = null,
)

private class MethodJoinResult(
    val joins: List<String>,
    val where: List<String>,
    val nextIndex: Int,
)

private fun MethodProperties.hasPredicates(): Boolean =
    name != null || declaredClass != null || returnType != null ||
        paramCount != null || paramTypes != null || modifiers != null

/** One existential method unit of a class query. */
private class ClassUnit(
    val strings: List<SqlStringLiteral> = emptyList(),
    val numberCalls: List<List<Any>> = emptyList(),
    val properties: MethodProperties? = null,
    /** Whether this unit comes from a nested method matcher (matched injectively). */
    val nestedMethodMatcher: Boolean = false,
)

private fun SqlMethodQuery.toMethodProperties(includeModifiers: Boolean = false): MethodProperties =
    MethodProperties(
        name = name,
        declaredClass = declaredClassValue ?: declaredClass,
        returnType = returnTypeValue ?: returnType,
        paramCount = paramCount,
        paramTypes = paramTypes,
        modifiers = if (includeModifiers) modifiers else null,
    )

/**
 * Emit the joins and conditions of one method matcher.
 *
 * Every literal becomes a separate relation alias, which preserves all-of semantics even when two
 * patterns occur in different rows. [start] is the first free alias index; the returned next free
 * index is used verbatim by class/field queries to keep alias numbering canonical. Deferred literal
 * slots reserve tokens in emission order.
 */
private fun methodJoins(
    strings: List<SqlStringLiteral>,
    numberCalls: List<List<Any>>,
    baseAlias: String,
    start: Int,
    properties: MethodProperties?,
    emission: DexSqlEmission,
): MethodJoinResult {
    val joins = mutableListOf<String>()
    val where = mutableListOf<String>()
    var index = appendStringLiteralJoins(strings, baseAlias, start, joins, where, emission)
    for (numberCall in numberCalls) {
        index = appendNumberLiteralJoins(numberCall, baseAlias, index, joins, where, emission)
    }
    if (properties != null) {
        properties.name?.let { where += baseAlias + ".name = " + sqlQuote(it) }
        properties.declaredClass?.let {
            where += baseAlias + ".class_descriptor = " + renderClassLiteral(it, emission)
        }
        properties.returnType?.let {
            where += baseAlias + ".return_descriptor = " + renderTypeLiteral(it, emission)
        }
        properties.paramCount?.let { where += baseAlias + ".param_count = " + it }
        properties.paramTypes?.let {
            where += paramDescriptorsCondition(baseAlias, it, emission)
        }
        properties.modifiers?.let { mask ->
            where += "((" + baseAlias + ".access_flags & " + mask + ") = " + mask + ")"
        }
    }
    return MethodJoinResult(joins, where, index)
}

private fun appendStringLiteralJoins(
    strings: List<SqlStringLiteral>,
    baseAlias: String,
    start: Int,
    joins: MutableList<String>,
    where: MutableList<String>,
    emission: DexSqlEmission,
): Int {
    var index = start
    for (literal in strings) {
        val alias = "ms" + index
        joins += "JOIN method_strings " + alias + " ON " + alias + ".method = " + baseAlias + ".descriptor"
        where += alias + ".file_id = " + baseAlias + ".file_id"
        where += alias + ".method_idx = " + baseAlias + ".method_idx"
        where += stringCondition(alias + ".value", literal, emission)
        index++
    }
    return index
}

private fun appendNumberLiteralJoins(
    numbers: List<Any>,
    baseAlias: String,
    start: Int,
    joins: MutableList<String>,
    where: MutableList<String>,
    emission: DexSqlEmission,
): Int {
    var index = start
    for (number in numbers) {
        val alias = "mn" + index
        joins += "JOIN method_using_numbers " + alias + " ON " + alias + ".method = " + baseAlias + ".descriptor"
        where += alias + ".file_id = " + baseAlias + ".file_id"
        where += alias + ".method_idx = " + baseAlias + ".method_idx"
        where += alias + "." + renderNumberSuffix(number, emission)
        index++
    }
    return index
}

/**
 * Append the declaredClass { ... } units of a method or constructor query. Every literal VALUE is
 * one independent existential unit over the methods of the declaring class; a method may satisfy
 * several values and no injective distinctness applies.
 */
private fun appendDeclaredClassConstraint(
    constraint: SqlClassLiteralConstraint,
    baseAlias: String,
    start: Int,
    joins: MutableList<String>,
    where: MutableList<String>,
    emission: DexSqlEmission,
): Int {
    var index = start
    var unitIndex = 0
    for (call in constraint.calls) {
        for (literal in call.strings) {
            val alias = "ccm" + unitIndex
            joins += "JOIN methods " + alias + " ON " + alias + ".class_descriptor = " + baseAlias + ".class_descriptor"
            where += alias + ".file_id = " + baseAlias + ".file_id"
            where += alias + ".class_idx = " + baseAlias + ".class_idx"
            index = appendStringLiteralJoins(listOf(literal), alias, index, joins, where, emission)
            unitIndex++
        }
        for (number in call.numbers) {
            val alias = "ccm" + unitIndex
            joins += "JOIN methods " + alias + " ON " + alias + ".class_descriptor = " + baseAlias + ".class_descriptor"
            where += alias + ".file_id = " + baseAlias + ".file_id"
            where += alias + ".class_idx = " + baseAlias + ".class_idx"
            index = appendNumberLiteralJoins(listOf(number), alias, index, joins, where, emission)
            unitIndex++
        }
    }
    return index
}

/**
 * Append the superClass { ... } units of a class query. Every literal VALUE is one existential unit
 * over the methods of the superclass; units carry no file scoping because the superclass may live
 * in another DEX, and no injective distinctness applies.
 */
private fun appendSuperClassConstraint(
    constraint: SqlClassLiteralConstraint,
    methodsBaseAlias: String?,
    start: Int,
    joins: MutableList<String>,
    where: MutableList<String>,
    emission: DexSqlEmission,
): Int {
    val anchor: String
    if (methodsBaseAlias != null) {
        joins += "JOIN classes cc ON cc.descriptor = " + methodsBaseAlias + ".class_descriptor"
        where += "cc.file_id = " + methodsBaseAlias + ".file_id"
        where += "cc.class_idx = " + methodsBaseAlias + ".class_idx"
        anchor = "cc.superclass_descriptor"
    } else {
        anchor = "c.superclass_descriptor"
    }
    var index = start
    var unitIndex = 0
    for (call in constraint.calls) {
        for (literal in call.strings) {
            val alias = "scm" + unitIndex
            joins += "JOIN methods " + alias + " ON " + alias + ".class_descriptor = " + anchor
            index = appendStringLiteralJoins(listOf(literal), alias, index, joins, where, emission)
            unitIndex++
        }
        for (number in call.numbers) {
            val alias = "scm" + unitIndex
            joins += "JOIN methods " + alias + " ON " + alias + ".class_descriptor = " + anchor
            index = appendNumberLiteralJoins(listOf(number), alias, index, joins, where, emission)
            unitIndex++
        }
    }
    return index
}

internal fun emitMethod(query: SqlMethodQuery, constructor: Boolean, emission: DexSqlEmission): String {
    val where = mutableListOf<String>()
    val properties: MethodProperties
    if (constructor) {
        require(query.name == null || query.name == CONSTRUCTOR_NAME) {
            "sqlConstructor always matches '<init>', but name was set to '" + query.name + "'"
        }
        where += "m.name = " + sqlQuote(CONSTRUCTOR_NAME)
        properties = MethodProperties(
            name = null,
            declaredClass = query.declaredClassValue ?: query.declaredClass,
            returnType = query.returnTypeValue ?: query.returnType,
            paramCount = query.paramCount,
            paramTypes = query.paramTypes,
        )
    } else {
        properties = query.toMethodProperties()
    }
    val result = methodJoins(query.literals.strings, query.literals.numberCalls, "m", 0, properties, emission)
    val joins = result.joins.toMutableList()
    where += result.where
    packageCondition("m.class_descriptor", query.literals.packages)?.let { where += it }
    query.modifiers?.let { mask ->
        where += "((m.access_flags & " + mask + ") = " + mask + ")"
    }
    query.declaredClassConstraint?.let {
        appendDeclaredClassConstraint(it, "m", result.nextIndex, joins, where, emission)
    }
    query.literals.validateRange()
    require(joins.isNotEmpty() || where.isNotEmpty()) {
        "sqlMethod requires at least one predicate"
    }
    return buildString {
        append("SELECT DISTINCT m.descriptor AS method FROM methods m")
        appendJoins(joins)
        append(" WHERE ").append(where.joinToString(" AND "))
        append(" ORDER BY m.descriptor")
        appendRange(query.literals)
        append(';')
    }
}

internal fun emitClass(query: SqlClassQuery, emission: DexSqlEmission): String {
    val joins = mutableListOf<String>()
    val where = mutableListOf<String>()
    // Class-level literal matchers and nested method matchers are existential units over the
    // class's methods; each unit may match a different method of the same class.
    val units = mutableListOf<ClassUnit>()
    for (literal in query.literals.strings) {
        units += ClassUnit(strings = listOf(literal))
    }
    for (numberCall in query.literals.numberCalls) {
        units += ClassUnit(numberCalls = listOf(numberCall))
    }
    for (methodQuery in query.methods) {
        requireNestedMethod(methodQuery)
        val properties = methodQuery.toMethodProperties(includeModifiers = true)
        // An unconstrained unit is vacuously satisfied by every method and the extractor rejects
        // it; refuse it here too instead of emitting a meaningless extra methods join.
        require(
            methodQuery.literals.strings.isNotEmpty() ||
                methodQuery.literals.numberCalls.isNotEmpty() ||
                properties.hasPredicates(),
        ) {
            "sqlClass nested method matchers require at least one predicate"
        }
        units += ClassUnit(
            strings = methodQuery.literals.strings,
            numberCalls = methodQuery.literals.numberCalls,
            properties = properties,
            nestedMethodMatcher = true,
        )
    }
    val classNameSlot = query.classNameValue ?: query.className
    val superClassSlot = query.superClassValue ?: query.superClass
    if (units.isNotEmpty()) {
        var nextIndex = 0
        // DexKit matches class method matchers injectively: every nested method matcher must be
        // satisfied by a different method row. Class-level literal units are existential only and
        // must not take part in this constraint.
        val nestedMethodAliases = mutableListOf<String>()
        units.forEachIndexed { unitIndex, unit ->
            val baseAlias = if (unitIndex == 0) "cm" else "cm" + nextIndex
            if (unitIndex != 0) {
                joins += "JOIN methods " + baseAlias + " ON " + baseAlias +
                    ".class_descriptor = cm.class_descriptor"
                where += baseAlias + ".file_id = cm.file_id"
                where += baseAlias + ".class_idx = cm.class_idx"
            }
            if (unit.nestedMethodMatcher) {
                nestedMethodAliases += baseAlias
            }
            val start = if (unitIndex == 0) 0 else nextIndex + 1
            val result = methodJoins(unit.strings, unit.numberCalls, baseAlias, start, unit.properties, emission)
            joins += result.joins
            where += result.where
            nextIndex = result.nextIndex
        }
        classNameSlot?.let { where += "cm.class_descriptor = " + renderClassLiteral(it, emission) }
        superClassSlot?.let {
            joins += "JOIN classes c ON c.descriptor = cm.class_descriptor"
            where += "c.file_id = cm.file_id"
            where += "c.class_idx = cm.class_idx"
            where += "c.superclass_descriptor = " + renderClassLiteral(it, emission)
        }
        packageCondition("cm.class_descriptor", query.literals.packages)?.let { where += it }
        query.modifiers?.let { mask ->
            val hasSuperClassString = superClassSlot != null
            if (!hasSuperClassString) {
                joins += "JOIN classes c ON c.descriptor = cm.class_descriptor"
                where += "c.file_id = cm.file_id"
                where += "c.class_idx = cm.class_idx"
            }
            where += "((c.access_flags & " + mask + ") = " + mask + ")"
        }
        where += methodMatcherDistinctnessConditions(nestedMethodAliases)
        query.superClassConstraint?.let {
            appendSuperClassConstraint(it, "cm", nextIndex, joins, where, emission)
        }
        query.literals.validateRange()
        require(joins.isNotEmpty() || where.isNotEmpty()) {
            "sqlClass requires at least one predicate"
        }
        return buildString {
            append("SELECT DISTINCT cm.class_descriptor AS descriptor FROM methods cm")
            appendJoins(joins)
            append(" WHERE ").append(where.joinToString(" AND "))
            append(" ORDER BY cm.class_descriptor")
            appendRange(query.literals)
            append(';')
        }
    }
    query.literals.validateRange()
    val superClassConstraint = query.superClassConstraint
    require(
        classNameSlot != null || superClassSlot != null ||
            query.literals.packages.isNotEmpty() || superClassConstraint != null ||
            query.modifiers != null,
    ) {
        "sqlClass requires at least one predicate"
    }
    classNameSlot?.let { where += "c.descriptor = " + renderClassLiteral(it, emission) }
    superClassSlot?.let { where += "c.superclass_descriptor = " + renderClassLiteral(it, emission) }
    packageCondition("c.descriptor", query.literals.packages)?.let { where += it }
    query.modifiers?.let { mask ->
        where += "((c.access_flags & " + mask + ") = " + mask + ")"
    }
    superClassConstraint?.let {
        appendSuperClassConstraint(it, null, 0, joins, where, emission)
    }
    return buildString {
        append("SELECT DISTINCT c.descriptor AS descriptor FROM classes c")
        appendJoins(joins)
        append(" WHERE ").append(where.joinToString(" AND "))
        append(" ORDER BY c.descriptor")
        appendRange(query.literals)
        append(';')
    }
}

internal fun emitField(query: SqlFieldQuery, emission: DexSqlEmission): String {
    val joins = mutableListOf<String>()
    val where = mutableListOf<String>()
    query.name?.let { where += "f.name = " + sqlQuote(it) }
    (query.declaredClassValue ?: query.declaredClass)?.let {
        where += "f.class_descriptor = " + renderClassLiteral(it, emission)
    }
    (query.typeValue ?: query.type)?.let {
        where += "f.type_descriptor = " + renderTypeLiteral(it, emission)
    }
    packageCondition("f.class_descriptor", query.literals.packages)?.let { where += it }
    // Access aliases are positional; literal aliases start after the previous unit's aliases.
    var aliasCounter = 0
    query.accesses.forEachIndexed { index, access ->
        requireNestedMethod(access.query)
        val accessAlias = "fa" + index
        val methodAlias = "fm" + index
        joins += "JOIN method_field_accesses " + accessAlias + " ON " + accessAlias + ".field = f.descriptor"
        where += accessAlias + ".file_id = f.file_id"
        where += accessAlias + ".field_idx = f.field_idx"
        joins += "JOIN methods " + methodAlias + " ON " + methodAlias + ".descriptor = " + accessAlias + ".method"
        where += methodAlias + ".file_id = " + accessAlias + ".file_id"
        where += methodAlias + ".method_idx = " + accessAlias + ".method_idx"
        where += accessAlias + ".using_type = " + sqlQuote(access.usingType)
        val start = maxOf(aliasCounter, index + 1)
        val result = methodJoins(
            access.query.literals.strings,
            access.query.literals.numberCalls,
            methodAlias,
            start,
            access.query.toMethodProperties(includeModifiers = true),
            emission,
        )
        joins += result.joins
        where += result.where
        aliasCounter = result.nextIndex
    }
    query.literals.validateRange()
    require(joins.isNotEmpty() || where.isNotEmpty()) {
        "sqlField requires at least one predicate"
    }
    return buildString {
        append("SELECT DISTINCT f.descriptor AS descriptor FROM fields f")
        appendJoins(joins)
        append(" WHERE ").append(where.joinToString(" AND "))
        append(" ORDER BY f.descriptor")
        appendRange(query.literals)
        append(';')
    }
}

/**
 * Pairwise distinctness conditions for injectively matched class method matchers, ordered by alias
 * index: every nested method matcher must bind a different method row (same file_id and
 * method_idx means the same method).
 */
private fun methodMatcherDistinctnessConditions(aliases: List<String>): List<String> {
    val conditions = mutableListOf<String>()
    for (leftIndex in aliases.indices) {
        for (rightIndex in leftIndex + 1 until aliases.size) {
            val left = aliases[leftIndex]
            val right = aliases[rightIndex]
            conditions += "NOT (" + left + ".file_id = " + right + ".file_id AND " +
                left + ".method_idx = " + right + ".method_idx)"
        }
    }
    return conditions
}

/** Nested method matchers only carry matcher predicates, never query-level options. */
private fun requireNestedMethod(query: SqlMethodQuery) {
    require(query.literals.packages.isEmpty()) {
        "Nested method matchers cannot declare searchPackages"
    }
    require(query.literals.limit == null && query.literals.offset == null) {
        "Nested method matchers cannot declare limit/offset"
    }
}

private fun StringBuilder.appendJoins(joins: List<String>) {
    if (joins.isNotEmpty()) append(' ').append(joins.joinToString(" "))
}

private fun StringBuilder.appendRange(literals: DexSqlLiterals) {
    val limit = literals.limit ?: return
    append(" LIMIT ").append(limit)
    literals.offset?.let { append(" OFFSET ").append(it) }
}
