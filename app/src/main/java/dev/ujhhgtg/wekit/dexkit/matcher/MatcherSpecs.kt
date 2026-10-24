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
    EndsWith;

    fun matches(candidate: String, value: String): Boolean =
        when (this) {
            Equals -> candidate == value
            StartsWith -> candidate.startsWith(value)
            Contains -> candidate.contains(value)
            EndsWith -> candidate.endsWith(value)
        }
}

/**
 * A dotted Java-name matcher (class/type names). Descriptor conversion is internal to candidates.
 */
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

/** DexKit OpCodeMatchType. */
enum class OpcodeMatchType {
    /** Every requested opcode name appears at least once (DexKit Contains semantics). */
    Contains,

    /** The whole opcode name list equals the requested list (order-sensitive). */
    Equals,
}

/**
 * Method opcode constraint (DexKit OpCodeMatcher replacement).
 *
 * Reads the top-level method JSON keys `opcode_count` (number, null when the load has no code
 * items) and `opcodes` ([string] Dalvik mnemonics). Missing/null opcode data is a clean non-match.
 */
data class OpcodeSpec(
    val names: List<String> = emptyList(),
    val matchType: OpcodeMatchType = OpcodeMatchType.Contains,
    /** Range for the total instruction count ([opcode_count]). */
    val count: IntRange? = null,
)

/** DexKit AnnotationVisibilityType name. */
enum class AnnotationVisibility {
    Build,
    Runtime,
    System,
    Unknown;

    fun matches(jsonName: String): Boolean = name.lowercase() == jsonName
}

/** One annotation element matcher (name + encoded value). */
data class AnnotationElementSpec(
    val name: NameSpec,
    val value: AnnotationValue,
)

/** DexKit annotation encoded value matcher. */
sealed interface AnnotationValue {
    /** A JSON string field (DexKit stringValue); compared exactly. */
    data class Text(val value: String) : AnnotationValue

    /** A JSON array (DexKit arrayValue); compared element-by-element in order. */
    data class ListValue(val values: List<AnnotationValue>) : AnnotationValue

    /** A JSON number (integer or floating). */
    data class NumberValue(val value: Number) : AnnotationValue

    /** A JSON boolean. */
    data class BooleanValue(val value: Boolean) : AnnotationValue

    /** A descriptor/enum encoded value; descriptor and dotted Java-name forms are equivalent. */
    data class DescriptorValue(val value: String) : AnnotationValue

    /** A nested annotation value. */
    data class Nested(val annotation: AnnotationSpec) : AnnotationValue
}

/**
 * Annotation matcher (DexKit AnnotationMatcher replacement). Matches one entry of the class/method
 * JSON `annotations` array: every requested element must be present with a matching value.
 */
data class AnnotationSpec(
    /** Dotted annotation type name (or `L...;` descriptor); matched against JSON type_name/type. */
    val type: NameSpec? = null,
    val visibility: AnnotationVisibility? = null,
    val elements: List<AnnotationElementSpec> = emptyList(),
)

/**
 * Method matcher (DexKit MethodMatcher replacement).
 *
 * Nested [invokeMethods]/[callerMethods] are matched injectively: every spec must be satisfied by a
 * distinct related method. [anyOf]/[allOf] evaluate on the candidate method itself (empty groups
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
    /** Numeric operands used by the method; matched injectively against `using_numbers`. */
    val usingNumbers: List<Number> = emptyList(),
    val usingFields: List<UsingFieldSpec> = emptyList(),
    val invokeMethods: List<MethodSpec> = emptyList(),
    /** Injective caller specs, resolved lazily through [DexMatcherRelations]. */
    val callerMethods: List<MethodSpec> = emptyList(),
    val anyOf: List<MethodSpec> = emptyList(),
    val allOf: List<MethodSpec> = emptyList(),
    /** DexKit opCodes constraint (evaluation-only; candidate generation ignores it). */
    val opcodes: OpcodeSpec? = null,
    /** DexKit annotation constraints on the method (evaluation-only). */
    val annotations: List<AnnotationSpec> = emptyList(),
    /**
     * Restrict the declaring class package (dotted package names, OR semantics). Mirrors the DexSQL
     * `searchPackages` delegate call: a class matches a package when its name equals the package or
     * starts with the package plus '.'.
     */
    val searchPackages: List<String> = emptyList(),
    /**
     * Declaring-class packages to reject (dotted, prefix semantics like [searchPackages]): a
     * candidate whose class is in one of these packages (or their subpackages) never matches.
     * Mirrors the DexKit-style delegate-block excludePackages(...) prefilter.
     */
    val excludePackages: List<String> = emptyList(),
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
    /** DexKit annotation constraints on the class (evaluation-only). */
    val annotations: List<AnnotationSpec> = emptyList(),
    /**
     * Class-level numeric-operand constraint: every value must be used by at least one method of
     * the class (existential per value, not injective), mirroring [usingStrings]. The SQL fast path
     * lowers the whole list to one canonical `method_using_numbers` class unit.
     */
    val usingNumbers: List<Number> = emptyList(),
    /**
     * Restrict the class package (dotted package names, OR semantics). Mirrors the DexSQL
     * `searchPackages` delegate call: a class matches a package when its name equals the package or
     * starts with the package plus '.'.
     */
    val searchPackages: List<String> = emptyList(),
    /**
     * Class packages to reject (dotted, prefix semantics like [searchPackages]): a candidate in one
     * of these packages (or their subpackages) never matches. Mirrors the DexKit-style
     * delegate-block excludePackages(...) prefilter.
     */
    val excludePackages: List<String> = emptyList(),
    /**
     * Bound on the number of annotations declared on the class (DexKit annotationCount). Stored as
     * an inclusive range; builders accept both an Int and an IntRange assignment.
     */
    val annotationCount: IntRange? = null,
)

/**
 * Field matcher (DexKit FieldMatcher replacement).
 *
 * readMethods/writeMethods are DexKit MethodsMatcher constraints over the methods that access the
 * field, resolved lazily through [DexMatcherRelations.fieldAccessMethods].
 */
data class FieldSpec(
    val name: NameSpec? = null,
    /** Contains semantics: (candidate.modifiers and mask) == mask. */
    val modifiers: Int? = null,
    val type: ClassSpec? = null,
    val declaredClass: ClassSpec? = null,
    val readMethods: List<MethodSpec> = emptyList(),
    val writeMethods: List<MethodSpec> = emptyList(),
    /**
     * Restrict the declaring class package (dotted package names, OR semantics). Mirrors the DexSQL
     * `searchPackages` delegate call: a class matches a package when its name equals the package or
     * starts with the package plus '.'.
     */
    val searchPackages: List<String> = emptyList(),
    /** Declaring-class packages to reject (dotted, prefix semantics like [searchPackages]). */
    val excludePackages: List<String> = emptyList(),
)
