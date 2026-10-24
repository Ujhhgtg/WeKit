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

fun classSpec(block: ClassSpecBuilder.() -> Unit): ClassSpec =
    ClassSpecBuilder().apply(block).build()

fun methodSpec(block: MethodSpecBuilder.() -> Unit): MethodSpec =
    MethodSpecBuilder().apply(block).build()

fun fieldSpec(block: FieldSpecBuilder.() -> Unit): FieldSpec =
    FieldSpecBuilder().apply(block).build()

/** One field access; null [usingType] means any direction. */
fun usingField(
    field: FieldSpec = FieldSpec(),
    usingType: FieldUsingType? = null,
): UsingFieldSpec = UsingFieldSpec(field, usingType)

fun readField(field: FieldSpec = FieldSpec()): UsingFieldSpec =
    UsingFieldSpec(field, FieldUsingType.Read)

fun writeField(field: FieldSpec = FieldSpec()): UsingFieldSpec =
    UsingFieldSpec(field, FieldUsingType.Write)

class ClassSpecBuilder internal constructor() {
    var className: NameSpec? = null
    var superClass: ClassSpec? = null
    var fieldCount: IntRange? = null
    var methodCount: IntRange? = null
    var interfaceCount: IntRange? = null

    /** Contains semantics: (modifiers and mask) == mask. */
    var modifiers: Int? = null

    val interfaces = mutableListOf<ClassSpec>()
    val fields = mutableListOf<FieldSpec>()
    val methods = mutableListOf<MethodSpec>()
    val usingStrings = mutableListOf<StringSpec>()

    /** DexKit annotation constraints (evaluation-only keywords; candidate generation ignores them). */
    var annotations: List<AnnotationSpec> = emptyList()

    fun className(value: String, match: NameMatch = NameMatch.Equals) {
        className = NameSpec(value, match)
    }

    fun superClass(spec: ClassSpec) {
        superClass = spec
    }

    fun superClass(block: ClassSpecBuilder.() -> Unit) {
        superClass = classSpec(block)
    }

    fun interfaceSpec(spec: ClassSpec) {
        interfaces += spec
    }

    fun interfaceSpec(block: ClassSpecBuilder.() -> Unit) {
        interfaces += classSpec(block)
    }

    fun field(spec: FieldSpec) {
        fields += spec
    }

    fun field(block: FieldSpecBuilder.() -> Unit) {
        fields += fieldSpec(block)
    }

    fun method(spec: MethodSpec) {
        methods += spec
    }

    fun method(block: MethodSpecBuilder.() -> Unit) {
        methods += methodSpec(block)
    }

    fun usingString(value: String, match: NameMatch = NameMatch.Contains) {
        usingStrings += StringSpec(value, match)
    }

    fun usingEqString(value: String) = usingString(value, NameMatch.Equals)

    fun usingStrings(vararg values: String) {
        values.forEach { usingString(it) }
    }

    fun build(): ClassSpec = ClassSpec(
        className = className,
        superClass = superClass,
        interfaces = interfaces.toList(),
        fields = fields.toList(),
        methods = methods.toList(),
        fieldCount = fieldCount,
        methodCount = methodCount,
        interfaceCount = interfaceCount,
        modifiers = modifiers,
        usingStrings = usingStrings.toList(),
        annotations = annotations,
    )
}

class MethodSpecBuilder internal constructor() {
    var descriptor: String? = null
    var name: NameSpec? = null

    /** Contains semantics: (modifiers and mask) == mask. */
    var modifiers: Int? = null
    var declaredClass: ClassSpec? = null
    var returnType: ClassSpec? = null
    var paramTypes: List<TypeSpec?>? = null
    var paramCount: IntRange? = null

    val usingStrings = mutableListOf<StringSpec>()
    val usingNumbers = mutableListOf<Number>()
    val usingFields = mutableListOf<UsingFieldSpec>()
    val invokeMethods = mutableListOf<MethodSpec>()
    val callerMethods = mutableListOf<MethodSpec>()
    val anyOf = mutableListOf<MethodSpec>()
    val allOf = mutableListOf<MethodSpec>()

    /** DexKit opcode constraint (evaluation-only). */
    var opcodes: OpcodeSpec? = null

    /** DexKit annotation constraints (evaluation-only). */
    var annotations: List<AnnotationSpec> = emptyList()

    fun name(value: String, match: NameMatch = NameMatch.Equals) {
        name = NameSpec(value, match)
    }

    fun declaredClass(spec: ClassSpec) {
        declaredClass = spec
    }

    fun declaredClass(block: ClassSpecBuilder.() -> Unit) {
        declaredClass = classSpec(block)
    }

    fun returnType(spec: ClassSpec) {
        returnType = spec
    }

    fun returnType(block: ClassSpecBuilder.() -> Unit) {
        returnType = classSpec(block)
    }

    /** Positional parameter types; null entries are wildcards, zero arguments = no parameters. */
    fun paramTypes(vararg types: TypeSpec?) {
        paramTypes = types.toList()
    }

    fun paramType(value: String, match: NameMatch = NameMatch.Equals) {
        paramTypes = (paramTypes.orEmpty()) + TypeSpec(value, match)
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

    fun usingNumber(value: Number) {
        usingNumbers += value
    }

    fun usingNumbers(vararg values: Number) {
        usingNumbers += values.toList()
    }

    fun usingField(spec: UsingFieldSpec) {
        usingFields += spec
    }

    fun readField(field: FieldSpec = FieldSpec()) {
        usingFields.add(UsingFieldSpec(field, FieldUsingType.Read))
    }

    fun readField(block: FieldSpecBuilder.() -> Unit) {
        readField(fieldSpec(block))
    }

    fun writeField(field: FieldSpec = FieldSpec()) {
        usingFields.add(UsingFieldSpec(field, FieldUsingType.Write))
    }

    fun writeField(block: FieldSpecBuilder.() -> Unit) {
        writeField(fieldSpec(block))
    }

    fun invokeMethod(spec: MethodSpec) {
        invokeMethods += spec
    }

    fun invokeMethod(block: MethodSpecBuilder.() -> Unit) {
        invokeMethods += methodSpec(block)
    }

    /** Convenience for DexKit addInvoke(descriptor): exact descriptor equality. */
    fun invokeMethod(descriptor: String) {
        invokeMethods += MethodSpec(descriptor = descriptor)
    }

    fun callerMethod(spec: MethodSpec) {
        callerMethods += spec
    }

    fun callerMethod(block: MethodSpecBuilder.() -> Unit) {
        callerMethods += methodSpec(block)
    }

    fun anyOf(spec: MethodSpec) {
        anyOf += spec
    }

    fun anyOf(block: MethodSpecBuilder.() -> Unit) {
        anyOf += methodSpec(block)
    }

    fun allOf(spec: MethodSpec) {
        allOf += spec
    }

    fun allOf(block: MethodSpecBuilder.() -> Unit) {
        allOf += methodSpec(block)
    }

    fun build(): MethodSpec = MethodSpec(
        descriptor = descriptor,
        name = name,
        modifiers = modifiers,
        declaredClass = declaredClass,
        returnType = returnType,
        paramTypes = paramTypes,
        paramCount = paramCount,
        usingStrings = usingStrings.toList(),
        usingNumbers = usingNumbers.toList(),
        usingFields = usingFields.toList(),
        invokeMethods = invokeMethods.toList(),
        callerMethods = callerMethods.toList(),
        anyOf = anyOf.toList(),
        allOf = allOf.toList(),
        opcodes = opcodes,
        annotations = annotations,
    )
}

class FieldSpecBuilder internal constructor() {
    var name: NameSpec? = null

    /** Contains semantics: (modifiers and mask) == mask. */
    var modifiers: Int? = null
    var type: ClassSpec? = null
    var declaredClass: ClassSpec? = null

    val readMethods = mutableListOf<MethodSpec>()
    val writeMethods = mutableListOf<MethodSpec>()

    fun name(value: String, match: NameMatch = NameMatch.Equals) {
        name = NameSpec(value, match)
    }

    fun type(spec: ClassSpec) {
        type = spec
    }

    fun type(block: ClassSpecBuilder.() -> Unit) {
        type = classSpec(block)
    }

    fun declaredClass(spec: ClassSpec) {
        declaredClass = spec
    }

    fun declaredClass(block: ClassSpecBuilder.() -> Unit) {
        declaredClass = classSpec(block)
    }

    fun readMethod(spec: MethodSpec) {
        readMethods += spec
    }

    fun readMethod(block: MethodSpecBuilder.() -> Unit) {
        readMethods += methodSpec(block)
    }

    fun writeMethod(spec: MethodSpec) {
        writeMethods += spec
    }

    fun writeMethod(block: MethodSpecBuilder.() -> Unit) {
        writeMethods += methodSpec(block)
    }

    fun build(): FieldSpec = FieldSpec(
        name = name,
        modifiers = modifiers,
        type = type,
        declaredClass = declaredClass,
        readMethods = readMethods.toList(),
        writeMethods = writeMethods.toList(),
    )
}
