package dev.ujhhgtg.wekit.dexkit.sql

internal enum class SqlStringMode { CONTAINS, EQUALS, PREFIX, SUFFIX }

internal class SqlStringLiteral(val value: String, val mode: SqlStringMode)

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
    val numberCalls = mutableListOf<List<Number>>()
    val packages = mutableListOf<String>()
    var limit: Int? = null
        private set
    var offset: Int? = null
        private set

    fun usingStrings(vararg values: String) = addStrings(values, SqlStringMode.CONTAINS)

    fun usingEqStrings(vararg values: String) = addStrings(values, SqlStringMode.EQUALS)

    fun usingPrefixStrings(vararg values: String) = addStrings(values, SqlStringMode.PREFIX)

    fun usingSuffixStrings(vararg values: String) = addStrings(values, SqlStringMode.SUFFIX)

    fun addUsingString(value: String) = addStrings(arrayOf(value), SqlStringMode.CONTAINS)

    fun addEqString(value: String) = addStrings(arrayOf(value), SqlStringMode.EQUALS)

    fun usingNumbers(vararg values: Number) {
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
        limit?.let { require(it >= 0) { "DexSQL limit must be non-negative, was $it" } }
        offset?.let {
            require(it >= 0) { "DexSQL offset must be non-negative, was $it" }
            require(limit != null) { "DexSQL offset requires a limit" }
        }
    }

    private fun addStrings(values: Array<out String>, mode: SqlStringMode) {
        values.forEach { strings += SqlStringLiteral(it, mode) }
    }
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
 * Escape a contains/prefix/suffix literal for SQL LIKE. DexSQL has no ESCAPE clause and treats
 * % and _ as wildcards, so wildcard characters are escaped with bracket expressions.
 */
private fun likePattern(value: String, mode: SqlStringMode): String {
    val escaped = value.replace("[", "[[]").replace("%", "[%]").replace("_", "[_]")
    return when (mode) {
        SqlStringMode.CONTAINS -> "%$escaped%"
        SqlStringMode.PREFIX -> "$escaped%"
        SqlStringMode.SUFFIX -> "%$escaped"
        SqlStringMode.EQUALS -> error("EQUALS literals are not LIKE patterns")
    }
}

private fun stringCondition(column: String, literal: SqlStringLiteral): String =
    if (literal.mode == SqlStringMode.EQUALS) {
        column + " = " + sqlQuote(literal.value)
    } else {
        column + " LIKE " + sqlQuote(likePattern(literal.value, literal.mode))
    }

/**
 * Format one numeric predicate value. Integral floats need an explicit fraction because the
 * canonical extractor formats them as <int>.0.
 */
private fun numberCondition(alias: String, number: Number): String {
    if (number is Float || number is Double) {
        val value = number.toDouble()
        require(value.isFinite()) { "DexSQL numeric predicate must be finite" }
        return "$alias.double_value = $value"
    }
    return alias + ".long_value = " + number.toLong()
}

private class MethodProperties(
    val name: String?,
    val declaredClass: String?,
    val returnType: String?,
    val paramCount: Int?,
    val paramDescriptors: String?,
)

private class MethodJoinResult(
    val joins: List<String>,
    val where: List<String>,
    val nextIndex: Int,
)

private fun MethodProperties.hasPredicates(): Boolean =
    name != null || declaredClass != null || returnType != null ||
        paramCount != null || paramDescriptors != null

/** One existential method unit of a class query. */
private class ClassUnit(
    val strings: List<SqlStringLiteral> = emptyList(),
    val numberCalls: List<List<Number>> = emptyList(),
    val properties: MethodProperties? = null,
    /** Whether this unit comes from a nested method matcher (matched injectively). */
    val nestedMethodMatcher: Boolean = false,
)

private fun SqlMethodQuery.toMethodProperties(): MethodProperties = MethodProperties(
    name = name,
    declaredClass = declaredClass,
    returnType = returnType,
    paramCount = paramCount,
    paramDescriptors = paramDescriptors,
)

/**
 * Emit the joins and conditions of one method matcher.
 *
 * Every literal becomes a separate relation alias, which preserves all-of semantics even when two
 * patterns occur in different rows. [start] is the first free alias index; the returned next free
 * index is used verbatim by class/field queries to keep alias numbering canonical.
 */
private fun methodJoins(
    strings: List<SqlStringLiteral>,
    numberCalls: List<List<Number>>,
    baseAlias: String,
    start: Int,
    properties: MethodProperties?,
): MethodJoinResult {
    val joins = mutableListOf<String>()
    val where = mutableListOf<String>()
    var index = start
    for (literal in strings) {
        val alias = "ms$index"
        joins += "JOIN method_strings $alias ON $alias.method = $baseAlias.descriptor"
        where += "$alias.file_id = $baseAlias.file_id"
        where += "$alias.method_idx = $baseAlias.method_idx"
        where += stringCondition("$alias.value", literal)
        index++
    }
    for (numberCall in numberCalls) {
        for (number in numberCall) {
            val alias = "mn$index"
            joins += "JOIN method_using_numbers $alias ON $alias.method = $baseAlias.descriptor"
            where += "$alias.file_id = $baseAlias.file_id"
            where += "$alias.method_idx = $baseAlias.method_idx"
            where += numberCondition(alias, number)
            index++
        }
    }
    if (properties != null) {
        properties.name?.let { where += baseAlias + ".name = " + sqlQuote(it) }
        properties.declaredClass?.let {
            where += baseAlias + ".class_descriptor = " + sqlQuote(classDescriptor(it))
        }
        properties.returnType?.let {
            where += baseAlias + ".return_descriptor = " + sqlQuote(javaType(it))
        }
        properties.paramCount?.let { where += "$baseAlias.param_count = $it" }
        properties.paramDescriptors?.let {
            where += baseAlias + ".param_descriptors = " + sqlQuote(it)
        }
    }
    return MethodJoinResult(joins, where, index)
}

internal fun emitMethod(query: SqlMethodQuery, constructor: Boolean): String {
    val where = mutableListOf<String>()
    val properties: MethodProperties
    if (constructor) {
        require(query.name == null || query.name == CONSTRUCTOR_NAME) {
            "sqlConstructor always matches '<init>', but name was set to '" + query.name + "'"
        }
        where += "m.name = " + sqlQuote(CONSTRUCTOR_NAME)
        properties = MethodProperties(
            name = null,
            declaredClass = query.declaredClass,
            returnType = query.returnType,
            paramCount = query.paramCount,
            paramDescriptors = query.paramDescriptors,
        )
    } else {
        properties = query.toMethodProperties()
    }
    val result = methodJoins(query.literals.strings, query.literals.numberCalls, "m", 0, properties)
    where += result.where
    packageCondition("m.class_descriptor", query.literals.packages)?.let { where += it }
    query.literals.validateRange()
    require(result.joins.isNotEmpty() || where.isNotEmpty()) {
        "sqlMethod requires at least one predicate"
    }
    return buildString {
        append("SELECT DISTINCT m.descriptor AS method FROM methods m")
        appendJoins(result.joins)
        append(" WHERE ").append(where.joinToString(" AND "))
        append(" ORDER BY m.descriptor")
        appendRange(query.literals)
        append(';')
    }
}

internal fun emitClass(query: SqlClassQuery): String {
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
        val properties = methodQuery.toMethodProperties()
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
    if (units.isNotEmpty()) {
        var nextIndex = 0
        // DexKit matches class method matchers injectively: every nested method matcher must be
        // satisfied by a different method row. Class-level literal units are existential only and
        // must not take part in this constraint.
        val nestedMethodAliases = mutableListOf<String>()
        units.forEachIndexed { unitIndex, unit ->
            val baseAlias = if (unitIndex == 0) "cm" else "cm$nextIndex"
            if (unitIndex != 0) {
                joins += "JOIN methods " + baseAlias + " ON " + baseAlias +
                    ".class_descriptor = cm.class_descriptor"
                where += "$baseAlias.file_id = cm.file_id"
                where += "$baseAlias.class_idx = cm.class_idx"
            }
            if (unit.nestedMethodMatcher) {
                nestedMethodAliases += baseAlias
            }
            val start = if (unitIndex == 0) 0 else nextIndex + 1
            val result = methodJoins(unit.strings, unit.numberCalls, baseAlias, start, unit.properties)
            joins += result.joins
            where += result.where
            nextIndex = result.nextIndex
        }
        query.className?.let { where += "cm.class_descriptor = " + sqlQuote(classDescriptor(it)) }
        query.superClass?.let {
            joins += "JOIN classes c ON c.descriptor = cm.class_descriptor"
            where += "c.file_id = cm.file_id"
            where += "c.class_idx = cm.class_idx"
            where += "c.superclass_descriptor = " + sqlQuote(classDescriptor(it))
        }
        packageCondition("cm.class_descriptor", query.literals.packages)?.let { where += it }
        where += methodMatcherDistinctnessConditions(nestedMethodAliases)
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
    val className = query.className
    val superClass = query.superClass
    require(className != null || superClass != null || query.literals.packages.isNotEmpty()) {
        "sqlClass requires at least one predicate"
    }
    className?.let { where += "c.descriptor = " + sqlQuote(classDescriptor(it)) }
    superClass?.let {
        where += "c.superclass_descriptor = " + sqlQuote(classDescriptor(it))
    }
    packageCondition("c.descriptor", query.literals.packages)?.let { where += it }
    return buildString {
        append("SELECT DISTINCT c.descriptor AS descriptor FROM classes c")
        append(" WHERE ").append(where.joinToString(" AND "))
        append(" ORDER BY c.descriptor")
        appendRange(query.literals)
        append(';')
    }
}

internal fun emitField(query: SqlFieldQuery): String {
    val joins = mutableListOf<String>()
    val where = mutableListOf<String>()
    query.name?.let { where += "f.name = " + sqlQuote(it) }
    query.declaredClass?.let { where += "f.class_descriptor = " + sqlQuote(classDescriptor(it)) }
    query.type?.let { where += "f.type_descriptor = " + sqlQuote(javaType(it)) }
    packageCondition("f.class_descriptor", query.literals.packages)?.let { where += it }
    // Access aliases are positional; literal aliases start after the previous unit's aliases.
    var aliasCounter = 0
    query.accesses.forEachIndexed { index, access ->
        requireNestedMethod(access.query)
        val accessAlias = "fa$index"
        val methodAlias = "fm$index"
        joins += "JOIN method_field_accesses $accessAlias ON $accessAlias.field = f.descriptor"
        where += "$accessAlias.file_id = f.file_id"
        where += "$accessAlias.field_idx = f.field_idx"
        joins += "JOIN methods $methodAlias ON $methodAlias.descriptor = $accessAlias.method"
        where += "$methodAlias.file_id = $accessAlias.file_id"
        where += "$methodAlias.method_idx = $accessAlias.method_idx"
        where += accessAlias + ".using_type = " + sqlQuote(access.usingType)
        val start = maxOf(aliasCounter, index + 1)
        val result = methodJoins(
            access.query.literals.strings,
            access.query.literals.numberCalls,
            methodAlias,
            start,
            access.query.toMethodProperties(),
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
