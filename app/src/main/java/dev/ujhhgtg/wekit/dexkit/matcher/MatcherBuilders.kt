package dev.ujhhgtg.wekit.dexkit.matcher

import dev.ujhhgtg.wekit.dexkit.meta.FieldUsingType

/**
 * Concise builder DSL for the query-backed matcher specs.
 *
 * The data classes remain the canonical API; these builders mainly remove nesting noise from the
 * T5/T6 nested class/type constraint blocks, e.g.:
 *
 *     methodSpec {
 *         name("onClick")
 *         declaredClass {
 *             superClass { className("android.app", NameMatch.StartsWith) }
 *             method { name("<init>") }
 *         }
 *         invokeMethod { descriptor("Lcom/x/Y;->helper()V") }
 *     }
 */
internal fun classSpec(block: ClassMatcher.() -> Unit): ClassSpec =
    ClassMatcher().apply(block).build()

/** Re-base an existing class spec, then apply [block] as overrides/additions. */
internal fun classSpec(base: ClassSpec, block: ClassMatcher.() -> Unit): ClassSpec =
    ClassMatcher().apply { applyBase(base) }.apply(block).build()

internal fun methodSpec(block: MethodMatcher.() -> Unit): MethodSpec =
    MethodMatcher().apply(block).build()

/** Re-base an existing method spec, then apply [block] as overrides/additions. */
internal fun methodSpec(base: MethodSpec, block: MethodMatcher.() -> Unit): MethodSpec =
    MethodMatcher().apply { applyBase(base) }.apply(block).build()

internal fun fieldSpec(block: FieldMatcher.() -> Unit): FieldSpec =
    FieldMatcher().apply(block).build()

/** Re-base an existing field spec, then apply [block] as overrides/additions. */
internal fun fieldSpec(base: FieldSpec, block: FieldMatcher.() -> Unit): FieldSpec =
    FieldMatcher().apply { applyBase(base) }.apply(block).build()

/** One field access; null [usingType] means any direction. */
fun usingField(
    field: FieldSpec = FieldSpec(),
    usingType: FieldUsingType? = null,
): UsingFieldSpec = UsingFieldSpec(field, usingType)

fun readField(field: FieldSpec = FieldSpec()): UsingFieldSpec =
    UsingFieldSpec(field, FieldUsingType.Read)

fun writeField(field: FieldSpec = FieldSpec()): UsingFieldSpec =
    UsingFieldSpec(field, FieldUsingType.Write)

/**
 * Normalize a DexKit-style count assignment: an Int is the single-value range, an IntRange passes
 * through unchanged and null clears the constraint. Kotlin has no implicit Int -> IntRange
 * conversion, so the builder properties accept Any? and call this setter to keep both legacy
 * assignment shapes source-compatible.
 */
private fun normalizeCountRange(owner: String, value: Any?): IntRange? =
    when (value) {
        null -> null
        is Int -> value..value
        is IntRange -> value
        else ->
            throw IllegalArgumentException(
                "$owner must be assigned an Int or IntRange, was " + value::class.java.name,
            )
    }

private fun normalizeName(owner: String, value: Any?): NameSpec? =
    when (value) {
        null -> null
        is NameSpec -> value
        is String -> NameSpec(value)
        else ->
            throw IllegalArgumentException(
                "$owner must be assigned a String or NameSpec, was " + value::class.java.name,
            )
    }

private fun normalizeClassRef(owner: String, value: Any?): ClassSpec? =
    when (value) {
        null -> null
        is ClassSpec -> value
        is String -> ClassSpec(className = NameSpec(value))
        is Class<*> -> ClassSpec(className = NameSpec(value.name))
        else ->
            throw IllegalArgumentException(
                "$owner must be assigned a String, Class or ClassSpec, was " +
                    value::class.java.name,
            )
    }

private fun normalizeParamType(value: Any?): TypeSpec? =
    when (value) {
        null -> null
        is TypeSpec -> value
        is Class<*> -> TypeSpec(value.name)
        else -> TypeSpec(value.toString())
    }

private fun normalizeParamTypes(owner: String, value: Any?): List<TypeSpec?>? =
    when (value) {
        null -> null
        is List<*> -> value.map(::normalizeParamType)
        is Array<*> -> value.map(::normalizeParamType)
        else ->
            throw IllegalArgumentException(
                "$owner must be assigned a List of String/Class/TypeSpec/null, was " +
                    value::class.java.name,
            )
    }

class ClassMatcher internal constructor() {
    private var classNameValue: NameSpec? = null

    /** Accepts `className = "com.foo.Bar"` or a [NameSpec]; null clears the constraint. */
    var className: Any? = null
        set(value) {
            field = value
            classNameValue = normalizeName("className", value)
        }

    private var superClassValue: ClassSpec? = null

    /** Accepts `superClass = "com.foo.Bar"` or a [ClassSpec]; null clears the constraint. */
    var superClass: Any? = null
        set(value) {
            field = value
            superClassValue = normalizeClassRef("superClass", value)
        }

    private var fieldCountRange: IntRange? = null
    private var methodCountRange: IntRange? = null
    private var interfaceCountRange: IntRange? = null
    private var annotationCountRange: IntRange? = null

    /** Accepts `fieldCount = 1` and `fieldCount = 1..1`; null clears the constraint. */
    var fieldCount: Any? = null
        set(value) {
            field = value
            fieldCountRange = normalizeCountRange("fieldCount", value)
        }

    /** Accepts `methodCount = 1` and `methodCount = 1..1`; null clears the constraint. */
    var methodCount: Any? = null
        set(value) {
            field = value
            methodCountRange = normalizeCountRange("methodCount", value)
        }

    /** Accepts `interfaceCount = 0` and `interfaceCount = 0..0`; null clears the constraint. */
    var interfaceCount: Any? = null
        set(value) {
            field = value
            interfaceCountRange = normalizeCountRange("interfaceCount", value)
        }

    /** Accepts `annotationCount = 1` and `annotationCount = 1..1`; null clears the constraint. */
    var annotationCount: Any? = null
        set(value) {
            field = value
            annotationCountRange = normalizeCountRange("annotationCount", value)
        }

    /** Contains semantics: (modifiers and mask) == mask. */
    var modifiers: Int? = null

    val interfaces = mutableListOf<ClassSpec>()
    val fields = mutableListOf<FieldSpec>()
    val methods = mutableListOf<MethodSpec>()
    val usingStrings = mutableListOf<StringSpec>()
    val usingNumbers = mutableListOf<Number>()
    val packages = mutableListOf<String>()

    /**
     * DexKit annotation constraints (evaluation-only keywords; candidate generation ignores them).
     */
    var annotations: List<AnnotationSpec> = emptyList()

    fun className(value: String, match: NameMatch = NameMatch.Equals) {
        className = NameSpec(value, match)
    }

    fun modifiers(mask: Int) {
        modifiers = mask
    }

    fun fieldCount(count: Int) {
        fieldCount = count
    }

    fun fieldCount(range: IntRange) {
        fieldCount = range
    }

    fun fieldCount(min: Int, max: Int) {
        fieldCount = min..max
    }

    fun methodCount(count: Int) {
        methodCount = count
    }

    fun methodCount(range: IntRange) {
        methodCount = range
    }

    fun methodCount(min: Int, max: Int) {
        methodCount = min..max
    }

    fun interfaceCount(count: Int) {
        interfaceCount = count
    }

    fun interfaceCount(range: IntRange) {
        interfaceCount = range
    }

    fun interfaceCount(min: Int, max: Int) {
        interfaceCount = min..max
    }

    fun annotationCount(count: Int) {
        annotationCount = count
    }

    fun annotationCount(range: IntRange) {
        annotationCount = range
    }

    fun annotationCount(min: Int, max: Int) {
        annotationCount = min..max
    }

    fun superClass(spec: ClassSpec) {
        superClass = spec
    }

    fun superClass(block: ClassMatcher.() -> Unit) {
        superClass = classSpec(block)
    }

    fun superClass(value: String, match: NameMatch = NameMatch.Equals) {
        superClass = ClassSpec(className = NameSpec(value, match))
    }

    fun interfaceSpec(spec: ClassSpec) {
        interfaces += spec
    }

    fun interfaceSpec(block: ClassMatcher.() -> Unit) {
        interfaces += classSpec(block)
    }

    fun field(spec: FieldSpec) {
        fields += spec
    }

    fun field(block: FieldMatcher.() -> Unit) {
        fields += fieldSpec(block)
    }

    fun method(spec: MethodSpec) {
        methods += spec
    }

    fun method(block: MethodMatcher.() -> Unit) {
        methods += methodSpec(block)
    }

    fun usingString(value: String, match: NameMatch = NameMatch.Contains) {
        usingStrings += StringSpec(value, match)
    }

    fun usingEqString(value: String) = usingString(value, NameMatch.Equals)

    fun usingPrefixString(value: String) = usingString(value, NameMatch.StartsWith)

    fun usingSuffixString(value: String) = usingString(value, NameMatch.EndsWith)

    fun usingStrings(values: List<String>) {
        values.forEach { usingString(it) }
    }

    fun usingStrings(vararg values: String) {
        values.forEach { usingString(it) }
    }

    fun usingEqStrings(vararg values: String) {
        values.forEach { usingEqString(it) }
    }

    fun usingEqStrings(values: List<String>) {
        values.forEach { usingEqString(it) }
    }

    fun usingPrefixStrings(vararg values: String) {
        values.forEach { usingPrefixString(it) }
    }

    fun usingPrefixStrings(values: List<String>) {
        values.forEach { usingPrefixString(it) }
    }

    fun usingSuffixStrings(vararg values: String) {
        values.forEach { usingSuffixString(it) }
    }

    fun usingSuffixStrings(values: List<String>) {
        values.forEach { usingSuffixString(it) }
    }

    /** Add already-computed string values (DexKit usingStrings(collection)). */
    fun usingStringValues(values: Collection<String>) {
        values.forEach { usingString(it) }
    }

    fun usingNumber(value: Number) {
        usingNumbers += value
    }

    fun usingNumbers(values: List<Number>) {
        usingNumbers += values
    }

    fun usingNumbers(vararg values: Number) {
        usingNumbers += values.toList()
    }

    fun searchPackages(vararg values: String) {
        packages += values
    }

    fun searchPackages(values: List<String>) {
        packages += values
    }

    fun annotation(spec: AnnotationSpec) {
        annotations = annotations + spec
    }

    fun annotations(specs: List<AnnotationSpec>) {
        annotations = annotations + specs
    }

    fun applyBase(base: ClassSpec) {
        className = base.className
        superClass = base.superClass
        interfaces.addAll(base.interfaces)
        fields.addAll(base.fields)
        methods.addAll(base.methods)
        fieldCount = base.fieldCount
        methodCount = base.methodCount
        interfaceCount = base.interfaceCount
        annotationCount = base.annotationCount
        modifiers = base.modifiers
        usingStrings.addAll(base.usingStrings)
        annotations = base.annotations
        usingNumbers.addAll(base.usingNumbers)
        packages.addAll(base.packages)
    }

    fun build(): ClassSpec =
        ClassSpec(
            className = classNameValue,
            superClass = superClassValue,
            interfaces = interfaces.toList(),
            fields = fields.toList(),
            methods = methods.toList(),
            fieldCount = fieldCountRange,
            methodCount = methodCountRange,
            interfaceCount = interfaceCountRange,
            modifiers = modifiers,
            usingStrings = usingStrings.toList(),
            annotations = annotations,
            usingNumbers = usingNumbers.toList(),
            packages = packages.toList(),
            annotationCount = annotationCountRange,
        )
}

class MethodMatcher internal constructor() {
    var descriptor: String? = null

    private var nameValue: NameSpec? = null

    /** Accepts `name = "onClick"` or a [NameSpec]; null clears the constraint. */
    var name: Any? = null
        set(value) {
            field = value
            nameValue = normalizeName("name", value)
        }

    /** Contains semantics: (modifiers and mask) == mask. */
    var modifiers: Int? = null

    private var declaredClassValue: ClassSpec? = null

    /** Accepts `declaredClass = "com.foo.Bar"` or a [ClassSpec]; null clears the constraint. */
    var declaredClass: Any? = null
        set(value) {
            field = value
            declaredClassValue = normalizeClassRef("declaredClass", value)
        }

    private var returnTypeValue: ClassSpec? = null

    /** Accepts `returnType = "void"` or a [ClassSpec]; null clears the constraint. */
    var returnType: Any? = null
        set(value) {
            field = value
            returnTypeValue = normalizeClassRef("returnType", value)
        }

    private var paramTypesValue: List<TypeSpec?>? = null

    /** Accepts `paramTypes = listOf("int", null)`; null clears the positional constraint. */
    var paramTypes: Any? = null
        set(value) {
            field = value
            paramTypesValue = normalizeParamTypes("paramTypes", value)
        }

    private var paramCountRange: IntRange? = null

    /** Accepts `paramCount = 2` and `paramCount = 0..5`; null clears the constraint. */
    var paramCount: Any? = null
        set(value) {
            field = value
            paramCountRange = normalizeCountRange("paramCount", value)
        }

    val usingStrings = mutableListOf<StringSpec>()
    val usingNumbers = mutableListOf<Number>()
    val packages = mutableListOf<String>()
    val usingFields = mutableListOf<UsingFieldSpec>()
    val invokeMethods = mutableListOf<MethodSpec>()
    val callerMethods = mutableListOf<MethodSpec>()
    val anyOf = mutableListOf<MethodSpec>()
    val allOf = mutableListOf<MethodSpec>()

    /** DexKit opcode constraint (evaluation-only). */
    var opcodes: OpcodeSpec? = null

    /** Accepts `opcodeCount = 5` and `opcodeCount = 5..5`; null clears the count range. */
    var opcodeCount: Any? = null
        set(value) {
            field = value
            opcodes =
                (opcodes ?: OpcodeSpec()).copy(count = normalizeCountRange("opcodeCount", value))
        }

    /** DexKit annotation constraints (evaluation-only). */
    var annotations: List<AnnotationSpec> = emptyList()

    fun name(value: String, match: NameMatch = NameMatch.Equals) {
        name = NameSpec(value, match)
    }

    fun declaredClass(spec: ClassSpec) {
        declaredClass = spec
    }

    fun declaredClass(block: ClassMatcher.() -> Unit) {
        declaredClass = classSpec(block)
    }

    fun returnType(spec: ClassSpec) {
        returnType = spec
    }

    fun returnType(block: ClassMatcher.() -> Unit) {
        returnType = classSpec(block)
    }

    /**
     * Positional parameter types; null entries are wildcards, zero arguments = no parameters.
     * Accepts String/Class/TypeSpec/null so resolver bodies need not wrap simple values.
     */
    fun paramTypes(vararg types: Any?) {
        paramTypes = types.toList()
    }

    fun paramTypes(types: List<Any?>) {
        paramTypes = types
    }

    fun paramType(value: String, match: NameMatch = NameMatch.Equals) {
        paramTypes = paramTypesValue.orEmpty() + TypeSpec(value, match)
    }

    fun modifiers(mask: Int) {
        modifiers = mask
    }

    fun declaredClass(value: String, match: NameMatch = NameMatch.Equals) {
        declaredClass = ClassSpec(className = NameSpec(value, match))
    }

    fun paramCount(count: Int) {
        paramCount = count
    }

    fun paramCount(min: Int, max: Int) {
        require(min in 0..max) { "paramCount range must satisfy 0 <= min <= max" }
        paramCount = min..max
    }

    fun paramCount(range: IntRange) = paramCount(range.first, range.last)

    fun returnType(value: String, match: NameMatch = NameMatch.Equals) {
        returnType = ClassSpec(className = NameSpec(value, match))
    }

    fun usingNumbers(values: List<Number>) {
        usingNumbers += values
    }

    fun opcodes(spec: OpcodeSpec) {
        opcodes = spec
    }

    fun opcodes(
        names: List<String> = emptyList(),
        matchType: OpcodeMatchType = OpcodeMatchType.Contains,
        count: IntRange? = null,
    ) {
        opcodes = OpcodeSpec(names, matchType, count)
    }

    fun opcodeCount(range: IntRange) {
        opcodeCount = range
    }

    fun opcodeCount(min: Int, max: Int) = opcodeCount(min..max)

    fun annotation(spec: AnnotationSpec) {
        annotations = annotations + spec
    }

    fun annotations(specs: List<AnnotationSpec>) {
        annotations = annotations + specs
    }

    fun usingFields(specs: List<UsingFieldSpec>) {
        usingFields += specs
    }

    fun usingString(value: String, match: NameMatch = NameMatch.Contains) {
        usingStrings += StringSpec(value, match)
    }

    fun usingEqString(value: String) = usingString(value, NameMatch.Equals)

    fun usingPrefixString(value: String) = usingString(value, NameMatch.StartsWith)

    fun usingSuffixString(value: String) = usingString(value, NameMatch.EndsWith)

    fun usingStrings(vararg values: String) {
        values.forEach { usingString(it) }
    }

    fun usingEqStrings(vararg values: String) = values.forEach { usingEqString(it) }

    fun usingPrefixStrings(vararg values: String) = values.forEach {
        usingString(it, NameMatch.StartsWith)
    }

    fun usingSuffixStrings(vararg values: String) = values.forEach {
        usingString(it, NameMatch.EndsWith)
    }

    fun usingEqStrings(values: List<String>) {
        values.forEach { usingEqString(it) }
    }

    fun usingPrefixStrings(values: List<String>) {
        values.forEach { usingPrefixString(it) }
    }

    fun usingSuffixStrings(values: List<String>) {
        values.forEach { usingSuffixString(it) }
    }

    fun usingStrings(values: List<String>) {
        values.forEach { usingString(it) }
    }

    /** Add already-computed string values (DexKit usingStrings(collection)). */
    fun usingStringValues(values: Collection<String>) {
        values.forEach { usingString(it) }
    }

    fun usingNumber(value: Number) {
        usingNumbers += value
    }

    fun usingNumbers(vararg values: Number) {
        usingNumbers += values.toList()
    }

    fun searchPackages(vararg values: String) {
        packages += values
    }

    fun searchPackages(values: List<String>) {
        packages += values
    }

    /** Alias of [searchPackages]. */
    fun packages(vararg values: String) = searchPackages(*values)

    /** Alias of [searchPackages]. */
    fun packages(values: List<String>) = searchPackages(values)

    fun usingField(spec: UsingFieldSpec) {
        usingFields += spec
    }

    fun usingField(block: FieldMatcher.() -> Unit) {
        usingFields += UsingFieldSpec(fieldSpec(block))
    }

    fun readField(field: FieldSpec = FieldSpec()) {
        usingFields.add(UsingFieldSpec(field, FieldUsingType.Read))
    }

    fun readField(block: FieldMatcher.() -> Unit) {
        readField(fieldSpec(block))
    }

    fun writeField(field: FieldSpec = FieldSpec()) {
        usingFields.add(UsingFieldSpec(field, FieldUsingType.Write))
    }

    fun writeField(block: FieldMatcher.() -> Unit) {
        writeField(fieldSpec(block))
    }

    fun invokeMethod(spec: MethodSpec) {
        invokeMethods += spec
    }

    fun invokeMethod(block: MethodMatcher.() -> Unit) {
        invokeMethods += methodSpec(block)
    }

    /** Convenience for DexKit addInvoke(descriptor): exact descriptor equality. */
    fun invokeMethod(descriptor: String) {
        invokeMethods += MethodSpec(descriptor = descriptor)
    }

    fun callerMethod(spec: MethodSpec) {
        callerMethods += spec
    }

    fun callerMethod(block: MethodMatcher.() -> Unit) {
        callerMethods += methodSpec(block)
    }

    fun anyOf(spec: MethodSpec) {
        anyOf += spec
    }

    fun anyOf(block: MethodMatcher.() -> Unit) {
        anyOf += methodSpec(block)
    }

    fun allOf(spec: MethodSpec) {
        allOf += spec
    }

    fun allOf(block: MethodMatcher.() -> Unit) {
        allOf += methodSpec(block)
    }

    fun applyBase(base: MethodSpec) {
        descriptor = base.descriptor
        name = base.name
        modifiers = base.modifiers
        declaredClass = base.declaredClass
        returnType = base.returnType
        paramTypes = base.paramTypes
        paramCount = base.paramCount
        usingStrings.addAll(base.usingStrings)
        usingNumbers.addAll(base.usingNumbers)
        packages.addAll(base.packages)
        usingFields.addAll(base.usingFields)
        invokeMethods.addAll(base.invokeMethods)
        callerMethods.addAll(base.callerMethods)
        anyOf.addAll(base.anyOf)
        allOf.addAll(base.allOf)
        opcodes = base.opcodes
        annotations = base.annotations
    }

    fun build(): MethodSpec =
        MethodSpec(
            descriptor = descriptor,
            name = nameValue,
            modifiers = modifiers,
            declaredClass = declaredClassValue,
            returnType = returnTypeValue,
            paramTypes = paramTypesValue,
            paramCount = paramCountRange,
            usingStrings = usingStrings.toList(),
            usingNumbers = usingNumbers.toList(),
            usingFields = usingFields.toList(),
            invokeMethods = invokeMethods.toList(),
            callerMethods = callerMethods.toList(),
            anyOf = anyOf.toList(),
            allOf = allOf.toList(),
            opcodes = opcodes,
            annotations = annotations,
            packages = packages.toList(),
        )
}

class FieldMatcher internal constructor() {
    private var nameValue: NameSpec? = null

    /** Accepts `name = "field"` or a [NameSpec]; null clears the constraint. */
    var name: Any? = null
        set(value) {
            field = value
            nameValue = normalizeName("name", value)
        }

    /** Contains semantics: (modifiers and mask) == mask. */
    var modifiers: Int? = null

    private var typeValue: ClassSpec? = null

    /** Accepts `type = "java.lang.String"` or a [ClassSpec]; null clears the constraint. */
    var type: Any? = null
        set(value) {
            field = value
            typeValue = normalizeClassRef("type", value)
        }

    private var declaredClassValue: ClassSpec? = null

    /** Accepts `declaredClass = "com.foo.Bar"` or a [ClassSpec]; null clears the constraint. */
    var declaredClass: Any? = null
        set(value) {
            field = value
            declaredClassValue = normalizeClassRef("declaredClass", value)
        }

    val readMethods = mutableListOf<MethodSpec>()
    val writeMethods = mutableListOf<MethodSpec>()
    val packages = mutableListOf<String>()

    fun name(value: String, match: NameMatch = NameMatch.Equals) {
        name = NameSpec(value, match)
    }

    fun modifiers(mask: Int) {
        modifiers = mask
    }

    fun type(value: String, match: NameMatch = NameMatch.Equals) {
        type = ClassSpec(className = NameSpec(value, match))
    }

    fun declaredClass(value: String, match: NameMatch = NameMatch.Equals) {
        declaredClass = ClassSpec(className = NameSpec(value, match))
    }

    fun type(spec: ClassSpec) {
        type = spec
    }

    fun type(block: ClassMatcher.() -> Unit) {
        type = classSpec(block)
    }

    fun declaredClass(spec: ClassSpec) {
        declaredClass = spec
    }

    fun declaredClass(block: ClassMatcher.() -> Unit) {
        declaredClass = classSpec(block)
    }

    fun readMethod(spec: MethodSpec) {
        readMethods += spec
    }

    fun readMethod(block: MethodMatcher.() -> Unit) {
        readMethods += methodSpec(block)
    }

    fun writeMethod(spec: MethodSpec) {
        writeMethods += spec
    }

    fun writeMethod(block: MethodMatcher.() -> Unit) {
        writeMethods += methodSpec(block)
    }

    fun readMethods(methods: List<MethodSpec>) {
        readMethods += methods
    }

    fun writeMethods(methods: List<MethodSpec>) {
        writeMethods += methods
    }

    fun searchPackages(vararg values: String) {
        packages += values
    }

    fun searchPackages(values: List<String>) {
        packages += values
    }

    /** Alias of [searchPackages]. */
    fun packages(vararg values: String) = searchPackages(*values)

    /** Alias of [searchPackages]. */
    fun packages(values: List<String>) = searchPackages(values)

    fun applyBase(base: FieldSpec) {
        name = base.name
        modifiers = base.modifiers
        type = base.type
        declaredClass = base.declaredClass
        readMethods.addAll(base.readMethods)
        writeMethods.addAll(base.writeMethods)
        packages.addAll(base.packages)
    }

    fun build(): FieldSpec =
        FieldSpec(
            name = nameValue,
            modifiers = modifiers,
            type = typeValue,
            declaredClass = declaredClassValue,
            readMethods = readMethods.toList(),
            writeMethods = writeMethods.toList(),
            packages = packages.toList(),
        )
}
