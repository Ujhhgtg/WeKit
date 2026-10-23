package dev.ujhhgtg.wekit.dexkit.sql

/**
 * A canonical DexSQL query produced by [sqlMethod], [sqlConstructor], [sqlClass] or [sqlField].
 *
 * The builder block is lazy: it runs on every [toSql] call, never at declaration time. Delegate
 * declarations can therefore read other delegates' metadata naturally, for example
 * declaredClass = classFoo.data.name, exactly like DexKit matcher blocks. The emitted static text
 * is byte-for-byte identical to the canonical migration extractor output.
 */
class DexSqlQuery internal constructor(
    private val factory: () -> String,
) {
    /** Compiles the query; the builder block runs on every call (evaluate it during resolution). */
    fun toSql(): String = factory()
}

/**
 * Case-sensitive name match mode for class/type descriptor matchers (DexKit StringMatchType).
 *
 * The SQL engine has no case-insensitive or regex name predicates, so only these modes are
 * exposed: [Equals] keeps the plain descriptor equality, [StartsWith] emits
 * instr(col, 'Lconverted') = 1, [Contains] emits instr(col, 'converted') > 0 and [EndsWith] emits
 * substr(col, -N) = 'converted;' (N includes the descriptor terminator). Literal values are Java
 * names and are converted to descriptor form by the builder.
 */
enum class SqlStringMatchMode {
    Equals,
    StartsWith,
    Contains,
    EndsWith,
}

/** Build a dexMethod query. The block runs when [DexSqlQuery.toSql] is called; an empty block throws
 * then. Only predicates DexSQL can express are exposed on the builder. */
fun sqlMethod(block: SqlMethodQuery.() -> Unit): DexSqlQuery = DexSqlQuery {
    val query = SqlMethodQuery()
    query.block()
    emitMethod(query, constructor = false)
}

/**
 * Build a dexConstructor query. DexKit forces the method name for constructor matchers, so
 * m.name = '<init>' is always emitted as the first WHERE condition.
 */
fun sqlConstructor(block: SqlMethodQuery.() -> Unit): DexSqlQuery = DexSqlQuery {
    val query = SqlMethodQuery()
    query.block()
    emitMethod(query, constructor = true)
}

/** Build a dexClass query; class-level literal and nested method matchers are existential units. */
fun sqlClass(block: SqlClassQuery.() -> Unit): DexSqlQuery = DexSqlQuery {
    val query = SqlClassQuery()
    query.block()
    emitClass(query)
}

/** Build a dexField query; a query without a field predicate or access matcher throws. */
fun sqlField(block: SqlFieldQuery.() -> Unit): DexSqlQuery = DexSqlQuery {
    val query = SqlFieldQuery()
    query.block()
    emitField(query)
}

/**
 * Builder for method and constructor queries ([sqlMethod], [sqlConstructor]) and for nested method
 * matchers inside [SqlClassQuery.method], [SqlFieldQuery.readMethod] and
 * [SqlFieldQuery.writeMethod].
 *
 * Only DexKit predicates that DexSQL can express are exposed; unsupported constructs are Kotlin
 * compile errors instead of silently dropped predicates.
 */
class SqlMethodQuery internal constructor() {
    /** Exact method name (m.name); constructors use "<init>". */
    var name: String? = null

    /** Declaring class (m.class_descriptor); accepts com.foo.Bar or Lcom/foo/Bar;. */
    var declaredClass: String? = null
        set(value) {
            field = value
            declaredClassMatch = null
        }

    /** Name-match slot for [declaredClass]; the exact property maps to [SqlStringMatchMode.Equals]. */
    internal var declaredClassMatch: SqlNameMatch? = null

    /**
     * Return type (m.return_descriptor); accepts void, int, java.lang.String, java.lang.String[], or
     * an L...; descriptor.
     */
    var returnType: String? = null
        set(value) {
            field = value
            returnTypeMatch = null
        }

    /** Name-match slot for [returnType]; the exact property maps to [SqlStringMatchMode.Equals]. */
    internal var returnTypeMatch: SqlNameMatch? = null

    /** Exact parameter count (m.param_count); assigning clears any range set via [paramCount]. */
    var paramCount: Int? = null
        set(value) {
            field = value
            paramCountRange = null
        }

    /** Inclusive parameter-count range; emitted as two comparisons (equality when min == max). */
    internal var paramCountRange: IntRange? = null

    /**
     * Access-flag mask (decimal), emitted as ((m.access_flags & mask) = mask); DexKit modifiers
     * semantics require every masked bit to be set. Assign this property or call [modifiers].
     */
    var modifiers: Int? = null

    internal val literals = DexSqlLiterals()
    internal var paramTypes: List<Any?>? = null

    internal var declaredClassConstraint: SqlClassLiteralConstraint? = null

    /** Set the declaring class; same as assigning [declaredClass]. */
    fun declaredClass(value: String) {
        declaredClass = value
    }

    /** Match the declaring class name case-sensitively (DexKit className with a match type). */
    fun declaredClass(mode: SqlStringMatchMode, value: String) {
        declaredClass = null
        declaredClassMatch = SqlNameMatch(mode, value)
    }

    /** Set the declaring class; alias of [declaredClass]. */
    fun className(value: String) = declaredClass(value)

    /** Match the declaring class name; alias of [declaredClass] with a match mode. */
    fun className(mode: SqlStringMatchMode, value: String) = declaredClass(mode, value)

    /**
     * Require the declaring class to contain methods satisfying the class-level literals in
     * [block]. Every literal value is an independent existential unit; one method may satisfy
     * several values (no injective distinctness).
     */
    fun declaredClass(block: SqlClassLiteralConstraint.() -> Unit) {
        require(declaredClassConstraint == null) { "declaredClass { } may be declared only once" }
        declaredClassConstraint = classLiteralConstraint("declaredClass", block)
    }

    /** Set the return type; same as assigning [returnType]. */
    fun returnType(value: String) {
        returnType = value
    }

    /** Match the return type name case-sensitively (DexKit returnType with a match type). */
    fun returnType(mode: SqlStringMatchMode, value: String) {
        returnType = null
        returnTypeMatch = SqlNameMatch(mode, value)
    }

    /** Set the parameter count; same as assigning [paramCount]. */
    fun paramCount(count: Int) {
        paramCount = count
    }

    /** Inclusive parameter count range; min == max keeps the canonical equality form. */
    fun paramCount(min: Int, max: Int) {
        require(min >= 0 && min <= max) {
            "DexSQL paramCount range must satisfy 0 <= min <= max, was " + min + ".." + max
        }
        paramCount = null
        paramCountRange = min..max
    }

    /** Inclusive parameter count range; same as [paramCount] with two bounds. */
    fun paramCount(range: IntRange) = paramCount(range.first, range.last)

    /**
     * Positional parameter types (m.param_descriptors). A null entry matches exactly one parameter
     * of any type (DexKit ParametersMatcher wildcard); zero arguments require exactly zero
     * parameters. With no null entries and at least one type the existing exact equality form is
     * kept (param_descriptors = 'I,J'); otherwise the canonical
     * param_types_match(..., '<pattern>') form is emitted with '?' per wildcard. Repeated calls
     * replace the previous value.
     */
    fun paramTypes(vararg types: Any?) {
        types.forEach { type ->
            if (type != null) {
                require(type.toString().isNotBlank()) {
                    "DexSQL paramTypes entries must not be blank"
                }
            }
        }
        paramTypes = types.toList()
    }

    /** Add method_strings.value LIKE '%value%' (contains) predicates. */
    fun usingStrings(vararg values: Any) = literals.usingStrings(*values)

    /** Add method_strings.value = 'value' (equals) predicates. */
    fun usingEqStrings(vararg values: Any) = literals.usingEqStrings(*values)

    /** Add method_strings.value LIKE 'value%' (prefix) predicates. */
    fun usingPrefixStrings(vararg values: Any) = literals.usingPrefixStrings(*values)

    /** Add method_strings.value LIKE '%value' (suffix) predicates. */
    fun usingSuffixStrings(vararg values: Any) = literals.usingSuffixStrings(*values)

    /** Add one method_strings.value LIKE '%value%' (contains) predicate. */
    fun addUsingString(value: Any) = literals.addUsingString(value)

    /** Add one method_strings.value = 'value' (equals) predicate. */
    fun addEqString(value: Any) = literals.addEqString(value)

    /** Add method_using_numbers predicates; integers use long_value, Float/Double use double_value. */
    fun usingNumbers(vararg values: Any) = literals.usingNumbers(*values)

    /** Restrict the declaring class package; several packages are OR-joined inside parentheses. */
    fun searchPackages(vararg packages: String) = literals.searchPackages(*packages)

    /** Append LIMIT n after ORDER BY. */
    fun limit(value: Int) = literals.setLimit(value)

    /** Append OFFSET n after LIMIT; requires [limit]. */
    fun offset(value: Int) = literals.setOffset(value)

    /** Set the access-flag mask; same as assigning [modifiers]. */
    fun modifiers(mask: Int) {
        modifiers = mask
    }
}

/**
 * Builder for class queries ([sqlClass]).
 *
 * Class-level literal matchers and nested [method] matchers are existential over the methods of the
 * matched class (DexKit semantics), so different literals may come from different methods.
 */
class SqlClassQuery internal constructor() {
    /**
     * Exact class descriptor. Emitted against cm.class_descriptor when the query is method-scoped
     * and against c.descriptor otherwise.
     */
    var className: String? = null
        set(value) {
            field = value
            classNameMatch = null
        }

    /** Name-match slot for [className]; the exact property maps to [SqlStringMatchMode.Equals]. */
    internal var classNameMatch: SqlNameMatch? = null

    /** Superclass descriptor; joins classes and matches superclass_descriptor. */
    var superClass: String? = null
        set(value) {
            field = value
            superClassMatch = null
        }

    /** Name-match slot for [superClass]; the exact property maps to [SqlStringMatchMode.Equals]. */
    internal var superClassMatch: SqlNameMatch? = null

    internal var superClassConstraint: SqlClassLiteralConstraint? = null

    /**
     * Access-flag mask (decimal) of the matched class, emitted as
     * ((c.access_flags & mask) = mask); DexKit modifiers semantics require every masked bit to be
     * set. Assign this property or call [modifiers].
     */
    var modifiers: Int? = null

    internal val literals = DexSqlLiterals()
    internal val methods = mutableListOf<SqlMethodQuery>()

    /** Set the class descriptor; same as assigning [className]. */
    fun className(value: String) {
        className = value
    }

    /** Match the matched class name case-sensitively (DexKit className with a match type). */
    fun className(mode: SqlStringMatchMode, value: String) {
        className = null
        classNameMatch = SqlNameMatch(mode, value)
    }

    /** Set the superclass descriptor; same as assigning [superClass]. */
    fun superClass(value: String) {
        superClass = value
    }

    /** Match the superclass name case-sensitively (DexKit superClass with a match type). */
    fun superClass(mode: SqlStringMatchMode, value: String) {
        superClass = null
        superClassMatch = SqlNameMatch(mode, value)
    }

    /**
     * Require the superclass to contain methods satisfying the class-level literals in [block].
     * Every literal value is an independent existential unit; no injective distinctness applies.
     */
    fun superClass(block: SqlClassLiteralConstraint.() -> Unit) {
        require(superClassConstraint == null) { "superClass { } may be declared only once" }
        superClassConstraint = classLiteralConstraint("superClass", block)
    }

    /** Add class-level method_strings.value LIKE '%value%' (contains) predicates. */
    fun usingStrings(vararg values: Any) = literals.usingStrings(*values)

    /** Add class-level method_strings.value = 'value' (equals) predicates. */
    fun usingEqStrings(vararg values: Any) = literals.usingEqStrings(*values)

    /** Add class-level method_strings.value LIKE 'value%' (prefix) predicates. */
    fun usingPrefixStrings(vararg values: Any) = literals.usingPrefixStrings(*values)

    /** Add class-level method_strings.value LIKE '%value' (suffix) predicates. */
    fun usingSuffixStrings(vararg values: Any) = literals.usingSuffixStrings(*values)

    /** Add class-level method_using_numbers predicates (existential over class methods). */
    fun usingNumbers(vararg values: Any) = literals.usingNumbers(*values)

    /** Add an existential nested method matcher (DexKit methods { add { ... } }). */
    fun method(block: SqlMethodQuery.() -> Unit) {
        methods += SqlMethodQuery().apply(block)
    }

    /** Restrict the package, emitted against the class descriptor column. */
    fun searchPackages(vararg packages: String) = literals.searchPackages(*packages)

    /** Append LIMIT n after ORDER BY. */
    fun limit(value: Int) = literals.setLimit(value)

    /** Append OFFSET n after LIMIT; requires [limit]. */
    fun offset(value: Int) = literals.setOffset(value)

    /** Set the access-flag mask; same as assigning [modifiers]. */
    fun modifiers(mask: Int) {
        modifiers = mask
    }
}

/**
 * Builder for field queries ([sqlField]).
 *
 * [readMethod] and [writeMethod] constrain the methods that access the field
 * (method_field_accesses.using_type); they are not field-name predicates.
 */
class SqlFieldQuery internal constructor() {
    /** Exact field name (f.name). */
    var name: String? = null

    /** Declaring class (f.class_descriptor); accepts com.foo.Bar or Lcom/foo/Bar;. */
    var declaredClass: String? = null

    /** Field type (f.type_descriptor); same type syntax as [SqlMethodQuery.returnType]. */
    var type: String? = null

    internal val literals = DexSqlLiterals()
    internal val accesses = mutableListOf<SqlFieldAccess>()

    /** Set the declaring class; same as assigning [declaredClass]. */
    fun declaredClass(value: String) {
        declaredClass = value
    }

    /** Set the field type; same as assigning [type]. */
    fun type(value: String) {
        type = value
    }

    /** Add a read-access method matcher (method_field_accesses.using_type = 'get'). */
    fun readMethod(block: SqlMethodQuery.() -> Unit) {
        accesses += SqlFieldAccess(SqlMethodQuery().apply(block), "get")
    }

    /** Add a write-access method matcher (method_field_accesses.using_type = 'put'). */
    fun writeMethod(block: SqlMethodQuery.() -> Unit) {
        accesses += SqlFieldAccess(SqlMethodQuery().apply(block), "put")
    }

    /** Restrict the declaring class package, emitted as (f.class_descriptor LIKE 'Lpackage/%'). */
    fun searchPackages(vararg packages: String) = literals.searchPackages(*packages)

    /** Append LIMIT n after ORDER BY. */
    fun limit(value: Int) = literals.setLimit(value)

    /** Append OFFSET n after LIMIT; requires [limit]. */
    fun offset(value: Int) = literals.setOffset(value)
}

/** Build and validate one [SqlClassLiteralConstraint] block. */
private fun classLiteralConstraint(
    owner: String,
    block: SqlClassLiteralConstraint.() -> Unit,
): SqlClassLiteralConstraint {
    val constraint = SqlClassLiteralConstraint().apply(block)
    require(
        constraint.calls.isNotEmpty() ||
            constraint.classNameMatch != null ||
            constraint.superClassMatch != null,
    ) {
        "sql* " + owner + " { } requires at least one literal or name-match call"
    }
    return constraint
}

/**
 * Class-level matcher used by [SqlMethodQuery.declaredClass] and [SqlClassQuery.superClass].
 *
 * Every literal value is one independent existential unit over the methods of the constrained
 * class; a method may satisfy several values. The name-match slots describe the constrained class
 * itself (className) and its superclass (superClass) and are emitted as class-level joins. Only
 * class-level constructs exist on this builder, so any other DexKit construct is a Kotlin compile
 * error instead of a silently dropped predicate.
 */
class SqlClassLiteralConstraint internal constructor() {
    internal val calls = mutableListOf<SqlLiteralCall>()
    internal var classNameMatch: SqlNameMatch? = null
    internal var superClassMatch: SqlNameMatch? = null

    /** Match the constrained class name (hoisted to the anchor class descriptor). */
    fun className(mode: SqlStringMatchMode, value: String) {
        classNameMatch = SqlNameMatch(mode, value)
    }

    /** Match the constrained class name; equals form. */
    fun className(value: String) = className(SqlStringMatchMode.Equals, value)

    /** Match the constrained class's superclass name (adds one classes join on the anchor). */
    fun superClass(mode: SqlStringMatchMode, value: String) {
        superClassMatch = SqlNameMatch(mode, value)
    }

    /** Match the constrained class's superclass name; equals form. */
    fun superClass(value: String) = superClass(SqlStringMatchMode.Equals, value)

    /** Add one method_strings.value LIKE '%value%' (contains) call. */
    fun usingStrings(vararg values: Any) = addStringCall(SqlStringMode.CONTAINS, values)

    /** Add one method_strings.value = 'value' (equals) call. */
    fun usingEqStrings(vararg values: Any) = addStringCall(SqlStringMode.EQUALS, values)

    /** Add one method_strings.value LIKE 'value%' (prefix) call. */
    fun usingPrefixStrings(vararg values: Any) = addStringCall(SqlStringMode.PREFIX, values)

    /** Add one method_strings.value LIKE '%value' (suffix) call. */
    fun usingSuffixStrings(vararg values: Any) = addStringCall(SqlStringMode.SUFFIX, values)

    /** Add one method_using_numbers call. */
    fun usingNumbers(vararg values: Any) = addNumberCall(values)

    private fun addStringCall(mode: SqlStringMode, values: Array<out Any>) {
        require(values.isNotEmpty()) {
            "SqlClassLiteralConstraint requires at least one value per call"
        }
        calls += SqlLiteralCall(values.map { SqlStringLiteral(it, mode) })
    }

    private fun addNumberCall(values: Array<out Any>) {
        require(values.isNotEmpty()) {
            "SqlClassLiteralConstraint requires at least one value per call"
        }
        calls += SqlLiteralCall(numbers = values.toList())
    }
}
