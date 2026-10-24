package dev.ujhhgtg.wekit.dexkit.sql

import dev.ujhhgtg.wekit.dexkit.matcher.ClassSpec
import dev.ujhhgtg.wekit.dexkit.matcher.FieldSpec
import dev.ujhhgtg.wekit.dexkit.matcher.MethodSpec
import dev.ujhhgtg.wekit.dexkit.matcher.NameMatch
import dev.ujhhgtg.wekit.dexkit.matcher.StringSpec

/**
 * Spec -> canonical DexSQL IR conversion.
 *
 * The public authoring API is the declarative matcher DSL ([ClassSpec]/[MethodSpec]/[FieldSpec]). A
 * spec is lowered to the existing byte-stable SQL emitter only when every predicate is
 * representable there; [isSqlExpressible] answers that question. Non-expressible specs (struct
 * member constraints, annotations, non-count opcodes, etc.) are evaluated by the query matcher.
 *
 * These functions are internal because [DexSqlQuery]/[SqlMethodQuery]/[SqlClassQuery]/
 * [SqlFieldQuery] are no longer public authoring APIs; they remain the SQL IR used for the
 * canonical fast path and parity tests.
 */
internal fun MethodSpec.toSqlOrNull(constructor: Boolean = false): String? = runCatching {
    emitMethod(toSqlMethodQueryOrNull(constructor) ?: return null, constructor)
}
    .getOrNull()

internal fun MethodSpec.isSqlExpressible(constructor: Boolean = false): Boolean =
    toSqlMethodQueryOrNull(constructor) != null

internal fun ClassSpec.toSqlOrNull(): String? = runCatching {
    emitClass(toSqlClassQueryOrNull() ?: return null)
}
    .getOrNull()

internal fun ClassSpec.isSqlExpressible(): Boolean = toSqlClassQueryOrNull() != null

internal fun FieldSpec.toSqlOrNull(): String? = runCatching {
    emitField(toSqlFieldQueryOrNull() ?: return null)
}
    .getOrNull()

internal fun FieldSpec.isSqlExpressible(): Boolean = toSqlFieldQueryOrNull() != null

private fun NameMatch.toSqlMode(): SqlStringMatchMode =
    when (this) {
        NameMatch.Equals -> SqlStringMatchMode.Equals
        NameMatch.StartsWith -> SqlStringMatchMode.StartsWith
        NameMatch.Contains -> SqlStringMatchMode.Contains
        NameMatch.EndsWith -> SqlStringMatchMode.EndsWith
    }

private fun StringSpec.toSqlMethodLiteral(query: SqlMethodQuery) {
    when (match) {
        NameMatch.Equals -> query.usingEqStrings(value)
        NameMatch.StartsWith -> query.usingPrefixStrings(value)
        NameMatch.Contains -> query.usingStrings(value)
        NameMatch.EndsWith -> query.usingSuffixStrings(value)
    }
}

private fun StringSpec.toSqlClassLiteral(query: SqlClassQuery) {
    when (match) {
        NameMatch.Equals -> query.usingEqStrings(value)
        NameMatch.StartsWith -> query.usingPrefixStrings(value)
        NameMatch.Contains -> query.usingStrings(value)
        NameMatch.EndsWith -> query.usingSuffixStrings(value)
    }
}

private fun StringSpec.toSqlClassConstraintLiteral(constraint: SqlClassLiteralConstraint) {
    when (match) {
        NameMatch.Equals -> constraint.usingEqStrings(value)
        NameMatch.StartsWith -> constraint.usingPrefixStrings(value)
        NameMatch.Contains -> constraint.usingStrings(value)
        NameMatch.EndsWith -> constraint.usingSuffixStrings(value)
    }
}

/** Apply an expressible class spec to the shared class-literal constraint IR. */
private fun ClassSpec.applyToSqlClassLiteralConstraint(constraint: SqlClassLiteralConstraint) {
    className?.let { constraint.className(it.match.toSqlMode(), it.value) }
    usingStrings.forEach { it.toSqlClassConstraintLiteral(constraint) }
    if (usingNumbers.isNotEmpty()) constraint.usingNumbers(*usingNumbers.toTypedArray())
}

/** Constraint form (extra methods joins) vs the plain class-property form. */
private fun ClassSpec.hasClassLiteralCalls(): Boolean =
    usingStrings.isNotEmpty() || usingNumbers.isNotEmpty()

private fun ClassSpec.isNameOnly(): Boolean =
    className != null &&
        superClass == null &&
        interfaces.isEmpty() &&
        fields.isEmpty() &&
        methods.isEmpty() &&
        fieldCount == null &&
        methodCount == null &&
        interfaceCount == null &&
        modifiers == null &&
        usingStrings.isEmpty() &&
        annotations.isEmpty() &&
        usingNumbers.isEmpty() &&
        searchPackages.isEmpty()

/**
 * True when this class spec is fully representable by a [SqlClassLiteralConstraint] (a method
 * declaredClass{} or a class superClass{} block): a name match plus class-level string/number
 * literal units. Structural member constraints, modifiers, annotations and searchPackages have no
 * SQL class-literal form and force the matcher fallback.
 */
private fun ClassSpec.isClassLiteralConstraintExpressible(): Boolean =
    superClass == null &&
        interfaces.isEmpty() &&
        fields.isEmpty() &&
        methods.isEmpty() &&
        fieldCount == null &&
        methodCount == null &&
        interfaceCount == null &&
        modifiers == null &&
        annotations.isEmpty() &&
        searchPackages.isEmpty() &&
        (className != null || usingStrings.isNotEmpty() || usingNumbers.isNotEmpty()) &&
        usingStrings.none(StringSpec::isEmptyNonEquality)

private fun StringSpec.isEmptyNonEquality(): Boolean = value.isEmpty() && match != NameMatch.Equals

internal fun MethodSpec.toSqlMethodQueryOrNull(
    constructor: Boolean = false,
    nested: Boolean = false,
): SqlMethodQuery? {
    if (descriptor != null) return null
    if (nested && searchPackages.isNotEmpty()) return null
    if (
        usingFields.isNotEmpty() ||
            invokeMethods.isNotEmpty() ||
            callerMethods.isNotEmpty() ||
            anyOf.isNotEmpty() ||
            allOf.isNotEmpty() ||
            annotations.isNotEmpty()
    ) {
        return null
    }
    if (opcodes?.names?.isNotEmpty() == true) return null
    if (opcodes?.count == null && opcodes?.names?.isEmpty() == false) return null
    name?.let {
        if (it.match != NameMatch.Equals) return null
        if (constructor && it.value != "<init>") return null
    }
    if (constructor && name != null && name.value != "<init>") return null
    if (usingStrings.any(StringSpec::isEmptyNonEquality)) return null
    val query = SqlMethodQuery()
    if (!constructor) name?.let { query.name = it.value }
    modifiers?.let { query.modifiers = it }
    declaredClass?.let { classSpec ->
        if (!classSpec.isClassLiteralConstraintExpressible()) return null
        val simpleClassProperty =
            classSpec.usingStrings.isEmpty() && classSpec.usingNumbers.isEmpty()
        // Nested method matchers in class/field units only carry scalar method properties; the
        // class-literal constraint form (extra methods joins) is not part of that shape.
        if (nested && !simpleClassProperty) return null
        if (simpleClassProperty) {
            // Simple declaredClass = "..." must stay the method-property form (fast path); the
            // class-literal constraint form uses extra methods joins.
            val nameSpec = classSpec.className ?: return null
            query.declaredClass(nameSpec.match.toSqlMode(), nameSpec.value)
        } else {
            query.declaredClass { classSpec.applyToSqlClassLiteralConstraint(this) }
        }
    }
    returnType?.let { classSpec ->
        if (!classSpec.isNameOnly()) return null
        val nameSpec = classSpec.className ?: return null
        query.returnType(nameSpec.match.toSqlMode(), nameSpec.value)
    }
    paramTypes?.let { types ->
        if (types.any { it != null && it.match != NameMatch.Equals }) return null
        query.paramTypes(*types.map { it?.value }.toTypedArray())
    }
    paramCount?.let { query.paramCount(it) }
    opcodes?.count?.let { query.opcodeCount(it) }
    usingStrings.forEach { it.toSqlMethodLiteral(query) }
    if (usingNumbers.isNotEmpty()) query.usingNumbers(*usingNumbers.toTypedArray())
    if (searchPackages.isNotEmpty()) query.searchPackages(*searchPackages.toTypedArray())
    return query
}

internal fun ClassSpec.toSqlClassQueryOrNull(): SqlClassQuery? {
    if (
        interfaces.isNotEmpty() ||
            fields.isNotEmpty() ||
            fieldCount != null ||
            methodCount != null ||
            interfaceCount != null ||
            annotations.isNotEmpty()
    ) {
        return null
    }
    if (usingStrings.any(StringSpec::isEmptyNonEquality)) return null
    val query = SqlClassQuery()
    className?.let { query.className(it.match.toSqlMode(), it.value) }
    superClass?.let { superSpec ->
        if (!superSpec.isClassLiteralConstraintExpressible()) return null
        if (superSpec.hasClassLiteralCalls()) {
            // Structural superclass constraint: same join/EXISTS shape the legacy sqlClass
            // superClass {} block emitted (class-level literal units over the superclass' methods).
            query.superClass { superSpec.applyToSqlClassLiteralConstraint(this) }
        } else {
            val nameSpec = superSpec.className ?: return null
            query.superClass(nameSpec.match.toSqlMode(), nameSpec.value)
        }
    }
    modifiers?.let { query.modifiers = it }
    usingStrings.forEach { it.toSqlClassLiteral(query) }
    if (usingNumbers.isNotEmpty()) query.usingNumbers(*usingNumbers.toTypedArray())
    if (searchPackages.isNotEmpty()) query.searchPackages(*searchPackages.toTypedArray())
    methods.forEach { methodSpec ->
        val nestedQuery = methodSpec.toSqlMethodQueryOrNull(nested = true) ?: return null
        if (
            nestedQuery.literals.strings.isEmpty() &&
                nestedQuery.literals.numberCalls.isEmpty() &&
                !nestedQuery.hasMatcherPredicates()
        ) {
            return null
        }
        query.methods += nestedQuery
    }
    if (
        className == null &&
            superClass == null &&
            modifiers == null &&
            usingStrings.isEmpty() &&
            usingNumbers.isEmpty() &&
            searchPackages.isEmpty() &&
            methods.isEmpty()
    ) {
        return null
    }
    return query
}

private fun MethodSpec.hasMatcherPredicates(): Boolean =
    name != null ||
        modifiers != null ||
        declaredClass != null ||
        returnType != null ||
        paramTypes != null ||
        paramCount != null ||
        opcodes?.count != null ||
        usingStrings.isNotEmpty() ||
        usingNumbers.isNotEmpty() ||
        searchPackages.isNotEmpty()

private fun SqlMethodQuery.hasMatcherPredicates(): Boolean =
    name != null ||
        modifiers != null ||
        declaredClass != null ||
        returnType != null ||
        paramTypes != null ||
        paramCount != null ||
        opcodeCountRange != null ||
        literals.strings.isNotEmpty() ||
        literals.numberCalls.isNotEmpty() ||
        literals.searchPackages.isNotEmpty()

internal fun FieldSpec.toSqlFieldQueryOrNull(): SqlFieldQuery? {
    val query = SqlFieldQuery()
    name?.let {
        if (it.match != NameMatch.Equals) return null
        query.name = it.value
    }
    modifiers?.let {
        return null
    }
    declaredClass?.let { classSpec ->
        if (!classSpec.isNameOnly()) return null
        val nameSpec = classSpec.className ?: return null
        if (nameSpec.match != NameMatch.Equals) return null
        query.declaredClass = nameSpec.value
    }
    type?.let { classSpec ->
        if (!classSpec.isNameOnly()) return null
        val nameSpec = classSpec.className ?: return null
        if (nameSpec.match != NameMatch.Equals) return null
        query.type = nameSpec.value
    }
    val accesses = buildList {
        readMethods.forEach { add(it to "get") }
        writeMethods.forEach { add(it to "put") }
    }
    for ((methodSpec, usingType) in accesses) {
        val nested = methodSpec.toSqlMethodQueryOrNull(nested = true) ?: return null
        if (!nested.hasMatcherPredicates()) return null
        query.accesses += SqlFieldAccess(nested, usingType)
    }
    if (searchPackages.isNotEmpty()) query.searchPackages(*searchPackages.toTypedArray())
    if (
        query.name == null &&
            query.declaredClass == null &&
            query.type == null &&
            query.accesses.isEmpty() &&
            query.literals.searchPackages.isEmpty()
    ) {
        return null
    }
    return query
}
