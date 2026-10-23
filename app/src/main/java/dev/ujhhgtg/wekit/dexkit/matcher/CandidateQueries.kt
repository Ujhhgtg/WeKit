package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.meta.dottedClassNameToDescriptor
import dev.ujhhgtg.wekit.dexkit.meta.javaNameToDescriptor

/**
 * Raw canonical-SQL candidate builders for the structural T5/T6 spec shapes that the typed SQL DSL
 * cannot express (class member/interface joins, nested class constraints, field access joins).
 *
 * Every generated condition is a necessary condition of a true match, so the result is a superset
 * that the evaluator narrows down. Deterministic ORDER BY keeps candidate order stable.
 */
internal class CandidateQueryParts {
    val joins = mutableListOf<String>()
    val where = mutableListOf<String>()
    private var aliasIndex = 0

    fun nextAlias(prefix: String): String = prefix + aliasIndex++

    fun isEmpty(): Boolean = joins.isEmpty() && where.isEmpty()
}

private fun sqlLiteral(value: String): String = "'" + value.replace("'", "''") + "'"

private fun slashed(value: String): String = value.trim().replace('.', '/')

private fun isObjectClassName(value: String): Boolean {
    val name = value.trim()
    return name.isNotEmpty() &&
        !name.contains('[') &&
        !name.contains(']') &&
        !name.startsWith("L") &&
        !name.endsWith(";") &&
        name !in setOf("void", "boolean", "byte", "short", "char", "int", "long", "float", "double")
}

/** Condition on a class-name descriptor column (class_descriptor / superclass_descriptor / ...). */
internal fun descriptorNameCondition(column: String, nameSpec: NameSpec): String {
    val value = nameSpec.value.trim()
    return when (nameSpec.match) {
        NameMatch.Equals -> column + " = " + sqlLiteral(dottedClassNameToDescriptor(value))
        NameMatch.StartsWith -> {
            val converted = "L" + slashed(value)
            "instr(" + column + ", " + sqlLiteral(converted) + ") = 1"
        }

        NameMatch.Contains -> "instr(" + column + ", " + sqlLiteral(slashed(value)) + ") > 0"

        NameMatch.EndsWith -> {
            val converted = slashed(value)
            val suffix = converted + ";"
            "substr(" + column + ", -" + (converted.codePointCount(0, converted.length) + 1) + ") = " +
                sqlLiteral(suffix)
        }
    }
}

/** Condition on a Java-type descriptor column (return_descriptor / type_descriptor). */
internal fun javaTypeNameCondition(column: String, nameSpec: NameSpec): String? {
    val value = nameSpec.value.trim()
    return when (nameSpec.match) {
        NameMatch.Equals -> column + " = " + sqlLiteral(javaNameToDescriptor(value))
        NameMatch.StartsWith -> if (!isObjectClassName(value)) null
        else "instr(" + column + ", " + sqlLiteral("L" + slashed(value)) + ") = 1"

        NameMatch.Contains -> if (!isObjectClassName(value)) null
        else "instr(" + column + ", " + sqlLiteral(slashed(value)) + ") > 0"

        NameMatch.EndsWith -> if (!isObjectClassName(value)) null
        else {
            val converted = slashed(value)
            val suffix = converted + ";"
            "substr(" + column + ", -" + (converted.codePointCount(0, converted.length) + 1) + ") = " +
                sqlLiteral(suffix)
        }
    }
}

/** True when a class spec only constrains the class name (no members/counts/strings/modifiers). */
private fun candidateClassSpecIsNameOnly(spec: ClassSpec): Boolean =
    spec.superClass == null &&
        spec.interfaces.isEmpty() &&
        spec.fields.isEmpty() &&
        spec.methods.isEmpty() &&
        spec.fieldCount == null &&
        spec.methodCount == null &&
        spec.interfaceCount == null &&
        spec.modifiers == null &&
        spec.usingStrings.isEmpty()

private fun stringCondition(column: String, spec: StringSpec): String = when {
    spec.value.isEmpty() && spec.match != NameMatch.Equals -> column + " = ''"
    spec.match == NameMatch.Equals -> column + " = " + sqlLiteral(spec.value)
    spec.match == NameMatch.StartsWith -> "instr(" + column + ", " + sqlLiteral(spec.value) + ") = 1"
    spec.match == NameMatch.Contains -> "instr(" + column + ", " + sqlLiteral(spec.value) + ") > 0"
    else -> {
        val converted = spec.value
        "substr(" + column + ", -" + converted.codePointCount(0, converted.length) + ") = " +
            sqlLiteral(converted)
    }
}

internal fun packageCandidateCondition(column: String, packages: List<String>): String? {
    if (packages.isEmpty()) return null
    return packages.joinToString(separator = " OR ", prefix = "(", postfix = ")") { pkg ->
        "instr(" + column + ", " + sqlLiteral("L" + slashed(pkg).trim('/') + "/") + ") = 1"
    }
}

/**
 * Adds candidate conditions for a class row (columns: descriptor, access_flags,
 * superclass_descriptor). Member constraints become necessary joins on fields/methods/interfaces.
 */
internal fun addClassCandidateParts(spec: ClassSpec, classAlias: String, parts: CandidateQueryParts) {
    spec.className?.let { parts.where += descriptorNameCondition(classAlias + ".descriptor", it) }
    spec.modifiers?.let { mask ->
        parts.where += "((" + classAlias + ".access_flags & " + mask + ") = " + mask + ")"
    }
    // A superclass class-name condition is a necessary condition; deeper superclass structure is
    // evaluated by the matcher, not prefetched.
    spec.superClass?.className?.let {
        parts.where += descriptorNameCondition(classAlias + ".superclass_descriptor", it)
    }
    for (interfaceSpec in spec.interfaces) {
        val interfaceName = interfaceSpec.className ?: continue
        val alias = parts.nextAlias("ci")
        parts.joins += "JOIN class_interfaces " + alias + " ON " + alias + ".file_id = " +
            classAlias + ".file_id AND " + alias + ".class_idx = " + classAlias + ".class_idx"
        parts.where += descriptorNameCondition(alias + ".interface_descriptor", interfaceName)
    }
    for (fieldSpec in spec.fields) {
        val alias = parts.nextAlias("cf")
        parts.joins += "JOIN fields " + alias + " ON " + alias + ".class_descriptor = " +
            classAlias + ".descriptor"
        addFieldCandidateParts(fieldSpec, alias, parts)
    }
    for (methodSpec in spec.methods) {
        val alias = parts.nextAlias("cm")
        parts.joins += "JOIN methods " + alias + " ON " + alias + ".class_descriptor = " +
            classAlias + ".descriptor"
        addMethodCandidateParts(methodSpec, alias, parts)
    }
    // Class-level using strings are existential per literal over the class methods.
    for (stringSpec in spec.usingStrings) {
        val methodAlias = parts.nextAlias("cms")
        val stringAlias = parts.nextAlias("cmsv")
        parts.joins += "JOIN methods " + methodAlias + " ON " + methodAlias +
            ".class_descriptor = " + classAlias + ".descriptor"
        parts.joins += "JOIN method_strings " + stringAlias + " ON " + stringAlias + ".method = " +
            methodAlias + ".descriptor AND " + stringAlias + ".file_id = " + methodAlias +
            ".file_id AND " + stringAlias + ".method_idx = " + methodAlias + ".method_idx"
        parts.where += stringCondition(stringAlias + ".value", stringSpec)
    }
}

/** Adds candidate conditions for a method row (methods table columns). */
internal fun addMethodCandidateParts(spec: MethodSpec, methodAlias: String, parts: CandidateQueryParts) {
    spec.descriptor?.let { parts.where += methodAlias + ".descriptor = " + sqlLiteral(it) }
    spec.name?.takeIf { it.match == NameMatch.Equals }?.let {
        parts.where += methodAlias + ".name = " + sqlLiteral(it.value)
    }
    spec.modifiers?.let { mask ->
        parts.where += "((" + methodAlias + ".access_flags & " + mask + ") = " + mask + ")"
    }
    spec.paramCount?.let { range ->
        if (range.first == range.last) {
            parts.where += methodAlias + ".param_count = " + range.first
        } else {
            parts.where += methodAlias + ".param_count >= " + range.first
            parts.where += methodAlias + ".param_count <= " + range.last
        }
    }
    spec.paramTypes?.takeIf { types -> types.none { t -> t != null && t.match != NameMatch.Equals } }?.let { types ->
        val pattern = types.joinToString(",") { type ->
            if (type == null) "?" else javaNameToDescriptor(type.value)
        }
        parts.where += "param_types_match(" + methodAlias + ".param_descriptors, " +
            sqlLiteral(pattern) + ")"
    }
    for (stringSpec in spec.usingStrings) {
        val alias = parts.nextAlias("ms")
        parts.joins += "JOIN method_strings " + alias + " ON " + alias + ".method = " +
            methodAlias + ".descriptor AND " + alias + ".file_id = " + methodAlias +
            ".file_id AND " + alias + ".method_idx = " + methodAlias + ".method_idx"
        parts.where += stringCondition(alias + ".value", stringSpec)
    }
    spec.declaredClass?.let { classSpec ->
        val alias = parts.nextAlias("dc")
        parts.joins += "JOIN classes " + alias + " ON " + alias + ".descriptor = " +
            methodAlias + ".class_descriptor"
        addClassCandidateParts(classSpec, alias, parts)
    }
    spec.returnType?.let { classSpec ->
        val nameSpec = classSpec.className
        if (candidateClassSpecIsNameOnly(classSpec)) {
            // Name-only class specs cover primitives/arrays too: convert the Java type name to its
            // descriptor instead of joining the classes table (class joins miss 'Z'/'[I' rows).
            nameSpec?.let {
                javaTypeNameCondition(methodAlias + ".return_descriptor", it)?.let { condition ->
                    parts.where += condition
                }
            }
        } else {
            // Structural class-member constraints can only match object return types; the classes
            // join naturally skips primitive/array return descriptors.
            val alias = parts.nextAlias("rc")
            parts.joins += "JOIN classes " + alias + " ON " + alias + ".descriptor = " +
                methodAlias + ".return_descriptor"
            addClassCandidateParts(classSpec, alias, parts)
        }
    }
}

/** Adds candidate conditions for a field row (fields table columns). */
internal fun addFieldCandidateParts(spec: FieldSpec, fieldAlias: String, parts: CandidateQueryParts) {
    spec.name?.takeIf { it.match == NameMatch.Equals }?.let {
        parts.where += fieldAlias + ".name = " + sqlLiteral(it.value)
    }
    spec.type?.className?.let { nameSpec ->
        javaTypeNameCondition(fieldAlias + ".type_descriptor", nameSpec)?.let { parts.where += it }
    }
    spec.declaredClass?.let { classSpec ->
        val alias = parts.nextAlias("dc")
        parts.joins += "JOIN classes " + alias + " ON " + alias + ".descriptor = " +
            fieldAlias + ".class_descriptor"
        addClassCandidateParts(classSpec, alias, parts)
    }
    addFieldAccessCandidateParts(spec.readMethods, fieldAlias, "get", parts)
    addFieldAccessCandidateParts(spec.writeMethods, fieldAlias, "put", parts)
}

private fun addFieldAccessCandidateParts(
    methods: List<MethodSpec>,
    fieldAlias: String,
    usingType: String,
    parts: CandidateQueryParts,
) {
    for (methodSpec in methods) {
        val accessAlias = parts.nextAlias("fa")
        val methodAlias = parts.nextAlias("fam")
        parts.joins += "JOIN method_field_accesses " + accessAlias + " ON " + accessAlias +
            ".field = " + fieldAlias + ".descriptor AND " + accessAlias + ".using_type = " +
            sqlLiteral(usingType)
        parts.joins += "JOIN methods " + methodAlias + " ON " + methodAlias +
            ".descriptor = " + accessAlias + ".method"
        addMethodCandidateParts(methodSpec, methodAlias, parts)
    }
}
