package dev.ujhhgtg.wekit.dexkit.sql

internal enum class SqlStringMode {
    CONTAINS,
    EQUALS,
    PREFIX,
    SUFFIX,
}

internal class SqlStringLiteral(val value: Any, val mode: SqlStringMode)

/** One class-level literal call: either string literals with one mode, or number values. */
internal class SqlLiteralCall(
    val strings: List<SqlStringLiteral> = emptyList(),
    val numbers: List<Any> = emptyList(),
)

/** One ordered read/write method access matcher of a field query. */
internal class SqlFieldAccess(val query: SqlMethodQuery, val usingType: String)

/** One class/type name match: mode plus the Java-name literal. */
internal class SqlNameMatch(val mode: SqlStringMatchMode, val value: String)

/** Literal domain of a name match: dotted class name vs Java type name. */
internal enum class SqlNameLiteralKind {
    Class,
    JavaType,
}

/**
 * Shared predicate storage for the public builders.
 *
 * [strings] and [numberCalls] keep declaration order because every literal becomes its own relation
 * alias; [searchPackages] and the range are shared by method, class and field queries. Number
 * values keep their call grouping because one class-level usingNumbers(...) call is one existential
 * unit.
 */
internal class DexSqlLiterals {
    val strings = mutableListOf<SqlStringLiteral>()
    val numberCalls = mutableListOf<List<Any>>()
    val searchPackages = mutableListOf<String>()
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

    fun searchPackages(vararg values: String) = values.forEach { searchPackages += it }

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

    private fun addStrings(values: Array<out Any>, mode: SqlStringMode) {
        values.forEach { strings += SqlStringLiteral(it, mode) }
    }
}

private const val CONSTRUCTOR_NAME = "<init>"

private val PRIMITIVE_DESCRIPTORS =
    mapOf(
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
 * Convert a Java type name to a DEX type descriptor, mirroring the canonical extractor: primitives
 * map to their single-letter descriptor, arrays recurse, L...; descriptors pass through, and
 * everything else becomes La/b/C;. A raw array descriptor such as [I is not special-cased, matching
 * the extractor.
 */
internal fun javaType(value: String): String {
    val type = value.trim()
    PRIMITIVE_DESCRIPTORS[type]?.let {
        return it
    }
    if (type.endsWith("[]")) return "[" + javaType(type.dropLast(2))
    if (type.startsWith("L") && type.endsWith(";")) return type
    require(type.isNotEmpty()) { "DexSQL Java type must not be blank" }
    return "L" + type.replace('.', '/') + ";"
}

/**
 * Convert a Java class name to a DEX class descriptor; an existing L...; descriptor passes through
 * unchanged.
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
    "L" + packageName.replace('.', '/').trim('/') + "/"

/** Search-searchPackages prefix condition: case-sensitive instr(...) = 1, OR-grouped. */
private fun packageCondition(column: String, searchPackages: List<String>): String? {
    if (searchPackages.isEmpty()) return null
    return searchPackages.joinToString(separator = " OR ", prefix = "(", postfix = ")") {
        packageName ->
        "instr(" + column + ", " + sqlQuote(packageDescriptor(packageName)) + ") = 1"
    }
}

/**
 * Render one DexKit string predicate (case-sensitive): contains uses instr(...) > 0, prefix uses
 * instr(...) = 1, suffix uses substr(column, -N) = literal with N in Unicode code points, and
 * equals keeps the raw literal. DexKit forces Equals for an empty non-equals literal.
 */
private fun stringCondition(column: String, literal: SqlStringLiteral): String {
    val value = literal.value.toString()
    if (value.isEmpty() && literal.mode != SqlStringMode.EQUALS) {
        return column + " = " + sqlQuote(value)
    }
    return when (literal.mode) {
        SqlStringMode.EQUALS -> column + " = " + sqlQuote(value)
        SqlStringMode.CONTAINS -> "instr(" + column + ", " + sqlQuote(value) + ") > 0"
        SqlStringMode.PREFIX -> "instr(" + column + ", " + sqlQuote(value) + ") = 1"
        SqlStringMode.SUFFIX ->
            "substr(" +
                column +
                ", -" +
                value.codePointCount(0, value.length) +
                ") = " +
                sqlQuote(value)
    }
}

private fun asNumber(value: Any?): Number =
    value as? Number ?: error("DexSQL number predicate requires a Number, was $value")

/** Canonical numeric condition suffix: long_value for integers, double_value for Float/Double. */
private fun numberSuffix(number: Number): String {
    if (number is Float) {
        require(number.isFinite()) { "DexSQL numeric predicate must be finite" }
        return "double_value = $number"
    }
    if (number is Double) {
        require(number.isFinite()) { "DexSQL numeric predicate must be finite" }
        return "double_value = $number"
    }
    return "long_value = " + number.toLong()
}

/**
 * Canonical parameter condition: exact equality when every type is known, otherwise the positional
 * wildcard form param_types_match(<alias>.param_descriptors, 'I,?,J'). Empty calls mean exactly
 * zero parameters (empty pattern).
 */
private fun paramTypesCondition(baseAlias: String, types: List<Any?>): String {
    val column = "$baseAlias.param_descriptors"
    if (types.isEmpty()) return "param_types_match($column, '')"
    if (types.none { it == null }) {
        return column + " = " + sqlQuote(types.joinToString(",") { javaType(it.toString()) })
    }
    val pattern =
        types.joinToString(",") { type -> if (type == null) "?" else javaType(type.toString()) }
    return "param_types_match(" + column + ", " + sqlQuote(pattern) + ")"
}

/** Convert one non-equals name-match literal to the descriptor-core slash form. */
private fun nameMatchSlashLiteral(value: String): String {
    val name = value.trim()
    require(name.isNotEmpty()) { "DexSQL name match literal must not be blank" }
    require(name !in PRIMITIVE_DESCRIPTORS) {
        "DexSQL class name match literal must not be a primitive type: '$name'"
    }
    require(!name.contains('/')) {
        "DexSQL class name match literal must be a dotted Java class name: '$name'"
    }
    require(!name.contains('[') && !name.contains(']') && !name.endsWith(';')) {
        "DexSQL class name match literal must not be an array/descriptor form: '$name'"
    }
    return name.replace('.', '/')
}

/** One name-match predicate on a descriptor column (className/returnType/superClass). */
private fun nameMatchCondition(
    column: String,
    match: SqlNameMatch,
    kind: SqlNameLiteralKind,
): String =
    when (match.mode) {
        SqlStringMatchMode.Equals ->
            "$column = " +
                sqlQuote(
                    if (kind == SqlNameLiteralKind.Class) classDescriptor(match.value)
                    else javaType(match.value),
                )

        SqlStringMatchMode.StartsWith -> {
            val converted = "L" + nameMatchSlashLiteral(match.value)
            "instr(" + column + ", " + sqlQuote(converted) + ") = 1"
        }

        SqlStringMatchMode.Contains -> {
            val converted = nameMatchSlashLiteral(match.value)
            "instr(" + column + ", " + sqlQuote(converted) + ") > 0"
        }

        SqlStringMatchMode.EndsWith -> {
            require(kind == SqlNameLiteralKind.Class) {
                "DexSQL returnType EndsWith is not supported (DexKit array/primitive name semantics " +
                    "cannot be expressed in SQL)"
            }
            val converted = nameMatchSlashLiteral(match.value)
            val suffix = "$converted;"
            "substr(" +
                column +
                ", -" +
                (converted.codePointCount(0, converted.length) + 1) +
                ") = " +
                sqlQuote(suffix)
        }
    }

private class MethodProperties(
    val name: String?,
    val classNameMatch: SqlNameMatch?,
    val returnTypeMatch: SqlNameMatch?,
    val paramTypes: List<Any?>?,
    val paramCount: Int? = null,
    val paramCountRange: IntRange? = null,
    val opcodeCountRange: IntRange? = null,
    /** Access-flag mask emitted after the properties: add for nested method matchers only. */
    val modifiers: Int? = null,
)

private class MethodJoinResult(
    val joins: List<String>,
    val where: List<String>,
    val nextIndex: Int,
)

private fun MethodProperties.hasPredicates(): Boolean =
    name != null ||
        classNameMatch != null ||
        returnTypeMatch != null ||
        paramCount != null ||
        paramCountRange != null ||
        paramTypes != null ||
        opcodeCountRange != null ||
        modifiers != null

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
        classNameMatch =
            declaredClassMatch
                ?: declaredClass?.let { SqlNameMatch(SqlStringMatchMode.Equals, it) },
        returnTypeMatch =
            returnTypeMatch ?: returnType?.let { SqlNameMatch(SqlStringMatchMode.Equals, it) },
        paramTypes = paramTypes,
        paramCount = paramCount,
        paramCountRange = paramCountRange,
        opcodeCountRange = opcodeCountRange,
        modifiers = if (includeModifiers) modifiers else null,
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
    numberCalls: List<List<Any>>,
    baseAlias: String,
    start: Int,
    properties: MethodProperties?,
): MethodJoinResult {
    val joins = mutableListOf<String>()
    val where = mutableListOf<String>()
    var index = appendStringLiteralJoins(strings, baseAlias, start, joins, where)
    for (numberCall in numberCalls) {
        index = appendNumberLiteralJoins(numberCall, baseAlias, index, joins, where)
    }
    if (properties != null) {
        properties.name?.let { where += baseAlias + ".name = " + sqlQuote(it) }
        properties.classNameMatch?.let {
            where += nameMatchCondition("$baseAlias.class_descriptor", it, SqlNameLiteralKind.Class)
        }
        properties.returnTypeMatch?.let {
            where +=
                nameMatchCondition("$baseAlias.return_descriptor", it, SqlNameLiteralKind.JavaType)
        }
        properties.opcodeCountRange?.let { range ->
            if (range.first == range.last) {
                where += baseAlias + ".opcode_count = " + range.first
            } else {
                where += baseAlias + ".opcode_count >= " + range.first
                where += baseAlias + ".opcode_count <= " + range.last
            }
        }
        val range = properties.paramCountRange
        if (range != null) {
            if (range.first == range.last) {
                where += baseAlias + ".param_count = " + range.first
            } else {
                where += baseAlias + ".param_count >= " + range.first
                where += baseAlias + ".param_count <= " + range.last
            }
        } else {
            properties.paramCount?.let { where += "$baseAlias.param_count = $it" }
        }
        properties.paramTypes?.let {
            where += paramTypesCondition(baseAlias, it)
        }
        properties.modifiers?.let { mask ->
            where += "(($baseAlias.access_flags & $mask) = $mask)"
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
): Int {
    var index = start
    for (literal in strings) {
        val alias = "ms$index"
        joins += "JOIN method_strings $alias ON $alias.method = $baseAlias.descriptor"
        where += "$alias.file_id = $baseAlias.file_id"
        where += "$alias.method_idx = $baseAlias.method_idx"
        where += stringCondition("$alias.value", literal)
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
): Int {
    var index = start
    for (number in numbers) {
        val alias = "mn$index"
        joins += "JOIN method_using_numbers $alias ON $alias.method = $baseAlias.descriptor"
        where += "$alias.file_id = $baseAlias.file_id"
        where += "$alias.method_idx = $baseAlias.method_idx"
        where += alias + "." + numberSuffix(asNumber(number))
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
): Int {
    var index = start
    var unitIndex = 0
    for (call in constraint.calls) {
        for (literal in call.strings) {
            val alias = "ccm$unitIndex"
            joins += "JOIN methods $alias ON $alias.class_descriptor = $baseAlias.class_descriptor"
            where += "$alias.file_id = $baseAlias.file_id"
            where += "$alias.class_idx = $baseAlias.class_idx"
            index = appendStringLiteralJoins(listOf(literal), alias, index, joins, where)
            unitIndex++
        }
        for (number in call.numbers) {
            val alias = "ccm$unitIndex"
            joins += "JOIN methods $alias ON $alias.class_descriptor = $baseAlias.class_descriptor"
            where += "$alias.file_id = $baseAlias.file_id"
            where += "$alias.class_idx = $baseAlias.class_idx"
            index = appendNumberLiteralJoins(listOf(number), alias, index, joins, where)
            unitIndex++
        }
    }
    constraint.classNameMatch?.let {
        where += nameMatchCondition("$baseAlias.class_descriptor", it, SqlNameLiteralKind.Class)
    }
    constraint.superClassMatch?.let { match ->
        val alias = classMatchJoinAlias(baseAlias)
        joins += "JOIN classes $alias ON $alias.descriptor = $baseAlias.class_descriptor"
        where += "$alias.file_id = $baseAlias.file_id"
        where += "$alias.class_idx = $baseAlias.class_idx"
        where += nameMatchCondition("$alias.superclass_descriptor", match, SqlNameLiteralKind.Class)
    }
    return index
}

/** Stable alias for a classes join matching a unit anchor's superclass (avoids c/cc clashes). */
private fun classMatchJoinAlias(anchorAlias: String): String =
    "csm" + anchorAlias.takeLastWhile { it.isDigit() }

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
): Int {
    val anchor: String
    if (methodsBaseAlias != null) {
        joins += "JOIN classes cc ON cc.descriptor = $methodsBaseAlias.class_descriptor"
        where += "cc.file_id = $methodsBaseAlias.file_id"
        where += "cc.class_idx = $methodsBaseAlias.class_idx"
        anchor = "cc.superclass_descriptor"
    } else {
        anchor = "c.superclass_descriptor"
    }
    var index = start
    var unitIndex = 0
    for (call in constraint.calls) {
        for (literal in call.strings) {
            val alias = "scm$unitIndex"
            joins += "JOIN methods $alias ON $alias.class_descriptor = $anchor"
            index = appendStringLiteralJoins(listOf(literal), alias, index, joins, where)
            unitIndex++
        }
        for (number in call.numbers) {
            val alias = "scm$unitIndex"
            joins += "JOIN methods $alias ON $alias.class_descriptor = $anchor"
            index = appendNumberLiteralJoins(listOf(number), alias, index, joins, where)
            unitIndex++
        }
    }
    constraint.classNameMatch?.let {
        where += nameMatchCondition(anchor, it, SqlNameLiteralKind.Class)
    }
    constraint.superClassMatch?.let {
        throw IllegalArgumentException(
            "DexSQL superClass { } cannot match the superclass of the superclass",
        )
    }
    return index
}

internal fun emitMethod(query: SqlMethodQuery, constructor: Boolean): String {
    val where = mutableListOf<String>()
    val properties: MethodProperties
    if (constructor) {
        require(query.name == null || query.name == CONSTRUCTOR_NAME) {
            "sqlConstructor always matches '<init>', but name was set to '" + query.name + "'"
        }
        where += "m.name = " + sqlQuote(CONSTRUCTOR_NAME)
        properties =
            MethodProperties(
                name = null,
                classNameMatch =
                    query.declaredClassMatch
                        ?: query.declaredClass?.let { SqlNameMatch(SqlStringMatchMode.Equals, it) },
                returnTypeMatch =
                    query.returnTypeMatch
                        ?: query.returnType?.let { SqlNameMatch(SqlStringMatchMode.Equals, it) },
                paramTypes = query.paramTypes,
                paramCount = query.paramCount,
                paramCountRange = query.paramCountRange,
                opcodeCountRange = query.opcodeCountRange,
            )
    } else {
        properties = query.toMethodProperties()
    }
    val result = methodJoins(query.literals.strings, query.literals.numberCalls, "m", 0, properties)
    val joins = result.joins.toMutableList()
    where += result.where
    packageCondition("m.class_descriptor", query.literals.searchPackages)?.let { where += it }
    query.modifiers?.let { mask ->
        where += "((m.access_flags & $mask) = $mask)"
    }
    query.declaredClassConstraint?.let {
        appendDeclaredClassConstraint(it, "m", result.nextIndex, joins, where)
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
        units +=
            ClassUnit(
                strings = methodQuery.literals.strings,
                numberCalls = methodQuery.literals.numberCalls,
                properties = properties,
                nestedMethodMatcher = true,
            )
    }
    val classNameMatch =
        query.classNameMatch ?: query.className?.let { SqlNameMatch(SqlStringMatchMode.Equals, it) }
    val superClassMatch =
        query.superClassMatch
            ?: query.superClass?.let { SqlNameMatch(SqlStringMatchMode.Equals, it) }
    if (units.isNotEmpty()) {
        var nextIndex = 0
        // DexKit matches class method matchers injectively: every nested method matcher must be
        // satisfied by a different method row. Class-level literal units are existential only and
        // must not take part in this constraint.
        val nestedMethodAliases = mutableListOf<String>()
        units.forEachIndexed { unitIndex, unit ->
            val baseAlias = if (unitIndex == 0) "cm" else "cm$nextIndex"
            if (unitIndex != 0) {
                joins +=
                    "JOIN methods " +
                        baseAlias +
                        " ON " +
                        baseAlias +
                        ".class_descriptor = cm.class_descriptor"
                where += "$baseAlias.file_id = cm.file_id"
                where += "$baseAlias.class_idx = cm.class_idx"
            }
            if (unit.nestedMethodMatcher) {
                nestedMethodAliases += baseAlias
            }
            val start = if (unitIndex == 0) 0 else nextIndex + 1
            val result =
                methodJoins(unit.strings, unit.numberCalls, baseAlias, start, unit.properties)
            joins += result.joins
            where += result.where
            nextIndex = result.nextIndex
        }
        classNameMatch?.let {
            where += nameMatchCondition("cm.class_descriptor", it, SqlNameLiteralKind.Class)
        }
        var classesJoined = false
        superClassMatch?.let {
            joins += "JOIN classes c ON c.descriptor = cm.class_descriptor"
            where += "c.file_id = cm.file_id"
            where += "c.class_idx = cm.class_idx"
            classesJoined = true
            where += nameMatchCondition("c.superclass_descriptor", it, SqlNameLiteralKind.Class)
        }
        packageCondition("cm.class_descriptor", query.literals.searchPackages)?.let { where += it }
        query.modifiers?.let { mask ->
            if (!classesJoined) {
                joins += "JOIN classes c ON c.descriptor = cm.class_descriptor"
                where += "c.file_id = cm.file_id"
                where += "c.class_idx = cm.class_idx"
            }
            where += "((c.access_flags & $mask) = $mask)"
        }
        where += methodMatcherDistinctnessConditions(nestedMethodAliases)
        query.superClassConstraint?.let {
            appendSuperClassConstraint(it, "cm", nextIndex, joins, where)
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
        classNameMatch != null ||
            superClassMatch != null ||
            query.literals.searchPackages.isNotEmpty() ||
            superClassConstraint != null ||
            query.modifiers != null,
    ) {
        "sqlClass requires at least one predicate"
    }
    classNameMatch?.let {
        where += nameMatchCondition("c.descriptor", it, SqlNameLiteralKind.Class)
    }
    superClassMatch?.let {
        where += nameMatchCondition("c.superclass_descriptor", it, SqlNameLiteralKind.Class)
    }
    packageCondition("c.descriptor", query.literals.searchPackages)?.let { where += it }
    query.modifiers?.let { mask ->
        where += "((c.access_flags & $mask) = $mask)"
    }
    superClassConstraint?.let {
        appendSuperClassConstraint(it, null, 0, joins, where)
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

internal fun emitField(query: SqlFieldQuery): String {
    val joins = mutableListOf<String>()
    val where = mutableListOf<String>()
    query.name?.let { where += "f.name = " + sqlQuote(it) }
    query.declaredClass?.let { where += "f.class_descriptor = " + sqlQuote(classDescriptor(it)) }
    query.type?.let { where += "f.type_descriptor = " + sqlQuote(javaType(it)) }
    packageCondition("f.class_descriptor", query.literals.searchPackages)?.let { where += it }
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
        val result =
            methodJoins(
                access.query.literals.strings,
                access.query.literals.numberCalls,
                methodAlias,
                start,
                access.query.toMethodProperties(includeModifiers = true),
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
 * index: every nested method matcher must bind a different method row (same file_id and method_idx
 * means the same method).
 */
private fun methodMatcherDistinctnessConditions(aliases: List<String>): List<String> {
    val conditions = mutableListOf<String>()
    for (leftIndex in aliases.indices) {
        for (rightIndex in leftIndex + 1 until aliases.size) {
            val left = aliases[leftIndex]
            val right = aliases[rightIndex]
            conditions +=
                "NOT (" +
                    left +
                    ".file_id = " +
                    right +
                    ".file_id AND " +
                    left +
                    ".method_idx = " +
                    right +
                    ".method_idx)"
        }
    }
    return conditions
}

/** Nested method matchers only carry matcher predicates, never query-level options. */
private fun requireNestedMethod(query: SqlMethodQuery) {
    require(query.literals.searchPackages.isEmpty()) {
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
