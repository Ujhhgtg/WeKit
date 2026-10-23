package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.meta.FieldUsingType

/**
 * Case-sensitive string match mode mirroring DexKit StringMatchType (Equals/StartsWith/Contains/
 * EndsWith). DexKit's ignoreCase option and SimilarRegex are not representable here.
 */
enum class NameMatch {
    Equals,
    StartsWith,
    Contains,
    EndsWith,
    ;

    fun matches(candidate: String, value: String): Boolean = when (this) {
        Equals -> candidate == value
        StartsWith -> candidate.startsWith(value)
        Contains -> candidate.contains(value)
        EndsWith -> candidate.endsWith(value)
    }
}

/** A dotted Java-name matcher (class/type names). Descriptor conversion is internal to candidates. */
data class NameSpec(val value: String, val match: NameMatch = NameMatch.Equals) {
    fun matches(candidate: String): Boolean = match.matches(candidate, value)
}

/**
 * One DexKit usingStrings literal: case-sensitive, [NameMatch.Contains] by default. DexKit forces
 * the equals form for an empty literal in non-equals modes.
 */
data class StringSpec(val value: String, val match: NameMatch = NameMatch.Contains) {
    fun matches(candidate: String): Boolean =
        if (value.isEmpty() && match != NameMatch.Equals) candidate.isEmpty()
        else match.matches(candidate, value)
}

/** One positional parameter type matcher; a null entry in [MethodSpec.paramTypes] is a wildcard. */
data class TypeSpec(val value: String, val match: NameMatch = NameMatch.Equals) {
    fun matches(candidate: String): Boolean = match.matches(candidate, value)
}

/**
 * Method matcher (DexKit MethodMatcher replacement).
 *
 * Nested [invokeMethods]/[callerMethods] are matched injectively: every spec must be satisfied by
 * a distinct related method. [anyOf]/[allOf] evaluate on the candidate method itself (empty groups
 * are true). [usingFields] entries are matched injectively against the candidate's field accesses.
 */
data class MethodSpec(
    /** Exact full method descriptor ("Lcom/x/Y;->name(I)V"); DexKit descriptor matcher. */
    val descriptor: String? = null,
    val name: NameSpec? = null,
    /** Contains semantics: (candidate.modifiers and mask) == mask. */
    val modifiers: Int? = null,
    val declaredClass: ClassSpec? = null,
    val returnType: ClassSpec? = null,
    /** null = unconstrained; empty list = exactly zero parameters; null entries are wildcards. */
    val paramTypes: List<TypeSpec?>? = null,
    val paramCount: IntRange? = null,
    val usingStrings: List<StringSpec> = emptyList(),
    /** TODO(T4): metadata does not expose usingNumbers yet; non-empty lists fail loudly. */
    val usingNumbers: List<Number> = emptyList(),
    val usingFields: List<UsingFieldSpec> = emptyList(),
    val invokeMethods: List<MethodSpec> = emptyList(),
    /** TODO(T4): metadata does not expose callers yet; non-empty lists fail loudly. */
    val callerMethods: List<MethodSpec> = emptyList(),
    val anyOf: List<MethodSpec> = emptyList(),
    val allOf: List<MethodSpec> = emptyList(),
)

/** One field access matcher (DexKit UsingFieldMatcher); null [usingType] means any direction. */
data class UsingFieldSpec(
    val field: FieldSpec = FieldSpec(),
    val usingType: FieldUsingType? = null,
)

/**
 * Class matcher (DexKit ClassMatcher replacement).
 *
 * [interfaces]/[fields]/[methods] are matched injectively: every spec must be satisfied by a
 * distinct member. Count ranges apply to the actual member list size. [usingStrings] is
 * class-level: every literal must match at least one string anywhere in the class's methods
 * (existential, not injective), mirroring DexKit.
 */
data class ClassSpec(
    val className: NameSpec? = null,
    val superClass: ClassSpec? = null,
    val interfaces: List<ClassSpec> = emptyList(),
    val fields: List<FieldSpec> = emptyList(),
    val methods: List<MethodSpec> = emptyList(),
    val fieldCount: IntRange? = null,
    val methodCount: IntRange? = null,
    val interfaceCount: IntRange? = null,
    /** Contains semantics: (candidate.modifiers and mask) == mask. */
    val modifiers: Int? = null,
    val usingStrings: List<StringSpec> = emptyList(),
)

/**
 * Field matcher (DexKit FieldMatcher replacement).
 *
 * readMethods/writeMethods are DexKit MethodsMatcher constraints over the methods that access the
 * field; the metadata API does not expose those relation lists yet, so non-empty lists fail loudly
 * (TODO(T4)).
 */
data class FieldSpec(
    val name: NameSpec? = null,
    /** Contains semantics: (candidate.modifiers and mask) == mask. */
    val modifiers: Int? = null,
    val type: ClassSpec? = null,
    val declaredClass: ClassSpec? = null,
    val readMethods: List<MethodSpec> = emptyList(),
    val writeMethods: List<MethodSpec> = emptyList(),
)
