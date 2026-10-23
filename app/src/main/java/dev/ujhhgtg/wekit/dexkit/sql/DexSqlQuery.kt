package dev.ujhhgtg.wekit.dexkit.sql

/**
 * A canonical DexSQL query produced by [sqlMethod], [sqlConstructor], [sqlClass] or [sqlField].
 *
 * Static queries contain the final SQL text. Deferred queries contain one
 * @@WEKIT_DEFERRED_n@@ token per deferred slot in [toSql]; [buildSql] replaces each token with the
 * literal rendered from that slot. Static queries are byte-for-byte identical to the canonical
 * migration extractor output; for deferred queries the tokenized template is the parity text.
 */
class DexSqlQuery internal constructor(
    internal val sql: String,
    private val deferredRenderers: List<() -> String> = emptyList(),
) {
    /** Whether the query text contains no deferred values, i.e. [toSql] is the final SQL text. */
    val isStatic: Boolean
        get() = deferredRenderers.isEmpty()

    /** Returns the canonical SQL template, including @@WEKIT_DEFERRED_n@@ tokens for deferred slots. */
    fun toSql(): String = sql

    /** Renders every deferred token and returns the executable SQL text. */
    fun buildSql(): String {
        if (deferredRenderers.isEmpty()) return sql
        var text = sql
        deferredRenderers.forEachIndexed { index, render ->
            text = text.replace(deferredToken(index), render())
        }
        return text
    }

    override fun toString(): String = sql
}

/** Placeholder token emitted for the deferred value at [index] in emission order. */
internal fun deferredToken(index: Int): String = "@@WEKIT_DEFERRED_" + index + "@@"

/**
 * A runtime-computed value for one DexSQL builder slot.
 *
 * The emitted query keeps a @@WEKIT_DEFERRED_n@@ token where the rendered literal would be;
 * [DexSqlQuery.buildSql] evaluates [provider] and renders the result exactly like the equivalent
 * static literal (class descriptor, type descriptor, quoted literal or canonical number).
 */
class SqlDeferredValue internal constructor(
    internal val provider: () -> Any?,
)

/**
 * Mark one builder slot value as deferred. The provider runs when [DexSqlQuery.buildSql] renders the
 * query, so it may read another delegate's metadata; exceptions propagate unchanged.
 */
fun deferred(provider: () -> Any?): SqlDeferredValue = SqlDeferredValue(provider)

/** Build a dexMethod query. At least one predicate is required; an empty block throws. */
fun sqlMethod(block: SqlMethodQuery.() -> Unit): DexSqlQuery {
    val emission = DexSqlEmission()
    val query = SqlMethodQuery().apply(block)
    return emission.build(emitMethod(query, constructor = false, emission = emission))
}

/**
 * Build a dexConstructor query. DexKit forces the method name for constructor matchers, so
 * m.name = '<init>' is always emitted as the first WHERE condition.
 */
fun sqlConstructor(block: SqlMethodQuery.() -> Unit): DexSqlQuery {
    val emission = DexSqlEmission()
    val query = SqlMethodQuery().apply(block)
    return emission.build(emitMethod(query, constructor = true, emission = emission))
}

/** Build a dexClass query; class-level literal and nested method matchers are existential units. */
fun sqlClass(block: SqlClassQuery.() -> Unit): DexSqlQuery {
    val emission = DexSqlEmission()
    val query = SqlClassQuery().apply(block)
    return emission.build(emitClass(query, emission))
}

/** Build a dexField query; a query without a field predicate or access matcher throws. */
fun sqlField(block: SqlFieldQuery.() -> Unit): DexSqlQuery {
    val emission = DexSqlEmission()
    val query = SqlFieldQuery().apply(block)
    return emission.build(emitField(query, emission))
}

/**
 * Builder for method and constructor queries ([sqlMethod], [sqlConstructor]) and for nested method
 * matchers inside [SqlClassQuery.method], [SqlFieldQuery.readMethod] and
 * [SqlFieldQuery.writeMethod].
 *
 * Only DexKit predicates that DexSQL can express are exposed; unsupported constructs are Kotlin
 * compile errors instead of silently dropped predicates. Slots accept a static value or a
 * [SqlDeferredValue] produced by [deferred], emitted as a token in [DexSqlQuery.toSql].
 */
class SqlMethodQuery internal constructor() {
    /** Exact method name (m.name); constructors use "<init>". */
    var name: String? = null

    /** Declaring class (m.class_descriptor); accepts com.foo.Bar or Lcom/foo/Bar;. */
    var declaredClass: String? = null
    internal var declaredClassValue: Any? = null

    /**
     * Return type (m.return_descriptor); accepts void, int, java.lang.String, java.lang.String[], or
     * an L...; descriptor.
     */
    var returnType: String? = null
    internal var returnTypeValue: Any? = null

    /** Parameter count (m.param_count). */
    var paramCount: Int? = null

    /**
     * Access-flag mask (decimal), emitted as ((m.access_flags & mask) = mask); DexKit modifiers
     * semantics require every masked bit to be set. Assign this property or call [modifiers].
     */
    var modifiers: Int? = null

    internal val literals = DexSqlLiterals()
    internal var paramTypes: List<Any>? = null

    internal var declaredClassConstraint: SqlClassLiteralConstraint? = null

    /**
     * Declaring class slot accepting a static class name/descriptor or a [SqlDeferredValue].
     */
    fun declaredClass(value: Any) {
        declaredClassValue = value
    }

    /**
     * Require the declaring class to contain methods satisfying the class-level literals in
     * [block]. Every literal value is an independent existential unit; one method may satisfy
     * several values (no injective distinctness).
     */
    fun declaredClass(block: SqlClassLiteralConstraint.() -> Unit) {
        require(declaredClassConstraint == null) { "declaredClass { } may be declared only once" }
        declaredClassConstraint = classLiteralConstraint("declaredClass", block)
    }

    /** Return type slot accepting a static type or a [SqlDeferredValue]. */
    fun returnType(value: Any) {
        returnTypeValue = value
    }

    /**
     * Exact parameter types, emitted as one m.param_descriptors condition. Parts may mix static
     * types and [SqlDeferredValue]s; a deferred part makes the whole list one token. Repeated calls
     * replace the previous value.
     */
    fun paramTypes(vararg types: Any) {
        require(types.isNotEmpty()) { "DexSQL paramTypes requires at least one type" }
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
    internal var classNameValue: Any? = null

    /** Superclass descriptor; joins classes and matches superclass_descriptor. */
    var superClass: String? = null
    internal var superClassValue: Any? = null
    internal var superClassConstraint: SqlClassLiteralConstraint? = null

    /**
     * Access-flag mask (decimal) of the matched class, emitted as
     * ((c.access_flags & mask) = mask); DexKit modifiers semantics require every masked bit to be
     * set. Assign this property or call [modifiers].
     */
    var modifiers: Int? = null

    internal val literals = DexSqlLiterals()
    internal val methods = mutableListOf<SqlMethodQuery>()

    /** Class descriptor slot accepting a static class name/descriptor or a [SqlDeferredValue]. */
    fun className(value: Any) {
        classNameValue = value
    }

    /** Superclass slot accepting a static class name/descriptor or a [SqlDeferredValue]. */
    fun superClass(value: Any) {
        superClassValue = value
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
    internal var declaredClassValue: Any? = null

    /** Field type (f.type_descriptor); same type syntax as [SqlMethodQuery.returnType]. */
    var type: String? = null
    internal var typeValue: Any? = null

    internal val literals = DexSqlLiterals()
    internal val accesses = mutableListOf<SqlFieldAccess>()

    /** Declaring class slot accepting a static class name/descriptor or a [SqlDeferredValue]. */
    fun declaredClass(value: Any) {
        declaredClassValue = value
    }

    /** Field type slot accepting a static type or a [SqlDeferredValue]. */
    fun type(value: Any) {
        typeValue = value
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
    require(constraint.calls.isNotEmpty()) {
        "sql* " + owner + " { } requires at least one literal call"
    }
    return constraint
}

/**
 * Class-level literal constraint used by [SqlMethodQuery.declaredClass] and
 * [SqlClassQuery.superClass].
 *
 * Every literal value is one independent existential unit over the methods of the constrained
 * class; a method may satisfy several values. Only class-level literal calls exist on this
 * builder, so any other DexKit construct is a Kotlin compile error instead of a silently dropped
 * predicate.
 */
class SqlClassLiteralConstraint internal constructor() {
    internal val calls = mutableListOf<SqlLiteralCall>()

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
