package dev.ujhhgtg.wekit.dexkit.sql

/**
 * A canonical DexSQL query produced by [sqlMethod], [sqlConstructor], [sqlClass] or [sqlField].
 *
 * The text is byte-for-byte identical to the canonical output of the WeKit migration tooling
 * (~/coding/dexsql/tools/extract_wekit_queries.py), so generated queries stay stable as cache keys
 * and remain reviewable.
 */
class DexSqlQuery internal constructor(
    internal val sql: String,
) {
    /** Returns the canonical DexSQL text, including the trailing semicolon. */
    fun toSql(): String = sql

    override fun toString(): String = sql
}

/**
 * Build a dexMethod query.
 *
 * Every declared predicate is combined with AND (DexKit all-of semantics). At least one predicate
 * is required; an empty block throws [IllegalArgumentException].
 */
fun sqlMethod(block: SqlMethodQuery.() -> Unit): DexSqlQuery =
    DexSqlQuery(emitMethod(SqlMethodQuery().apply(block), constructor = false))

/**
 * Build a dexConstructor query.
 *
 * DexKit forces the method name for constructor matchers, so "m.name = '<init>'" is always emitted
 * as the first WHERE condition. Setting [SqlMethodQuery.name] to any other value throws
 * [IllegalArgumentException]; setting it to "<init>" is accepted.
 */
fun sqlConstructor(block: SqlMethodQuery.() -> Unit): DexSqlQuery =
    DexSqlQuery(emitMethod(SqlMethodQuery().apply(block), constructor = true))

/**
 * Build a dexClass query.
 *
 * [SqlClassQuery.usingStrings], the other literal matchers and [SqlClassQuery.method] are
 * existential over the methods of the matched class, so two literals may be used by two different
 * methods. A query without any class-level literal, nested method, [SqlClassQuery.className],
 * [SqlClassQuery.superClass] or package predicate throws [IllegalArgumentException].
 */
fun sqlClass(block: SqlClassQuery.() -> Unit): DexSqlQuery =
    DexSqlQuery(emitClass(SqlClassQuery().apply(block)))

/**
 * Build a dexField query.
 *
 * A query without a field predicate or read/write method matcher throws [IllegalArgumentException].
 */
fun sqlField(block: SqlFieldQuery.() -> Unit): DexSqlQuery =
    DexSqlQuery(emitField(SqlFieldQuery().apply(block)))

/**
 * Builder for method and constructor queries ([sqlMethod], [sqlConstructor]) and for nested method
 * matchers inside [SqlClassQuery.method], [SqlFieldQuery.readMethod] and
 * [SqlFieldQuery.writeMethod].
 *
 * Only DexKit predicates that DexSQL can express are exposed. Unsupported DexKit matchers
 * (modifiers, interfaces, annotations, callers, fields, invoke methods, ...) intentionally do not
 * exist here, so using them is a Kotlin compile error instead of a silently dropped predicate.
 */
class SqlMethodQuery internal constructor() {
    /** Exact method name (m.name); constructors use "<init>". */
    var name: String? = null

    /** Declaring class (m.class_descriptor); accepts com.foo.Bar or Lcom/foo/Bar;. */
    var declaredClass: String? = null

    /**
     * Return type (m.return_descriptor); accepts void, int, java.lang.String, java.lang.String[], or
     * an L...; descriptor.
     */
    var returnType: String? = null

    /** Parameter count (m.param_count). */
    var paramCount: Int? = null

    internal val literals = DexSqlLiterals()
    internal var paramDescriptors: String? = null

    /**
     * Exact parameter types, emitted as one m.param_descriptors condition: paramTypes("java.lang.String",
     * "int") produces 'Ljava/lang/String;,I'.
     *
     * Every element must be a literal type; null/wildcard parameters are not supported by the DSL.
     * Repeated calls replace the previous value.
     */
    fun paramTypes(vararg types: String) {
        require(types.isNotEmpty()) { "DexSQL paramTypes requires at least one type" }
        paramDescriptors = types.joinToString(",") { javaType(it) }
    }

    /** Add method_strings.value LIKE '%value%' (contains) predicates. */
    fun usingStrings(vararg values: String) = literals.usingStrings(*values)

    /** Add method_strings.value = 'value' (equals) predicates. */
    fun usingEqStrings(vararg values: String) = literals.usingEqStrings(*values)

    /** Add method_strings.value LIKE 'value%' (prefix) predicates. */
    fun usingPrefixStrings(vararg values: String) = literals.usingPrefixStrings(*values)

    /** Add method_strings.value LIKE '%value' (suffix) predicates. */
    fun usingSuffixStrings(vararg values: String) = literals.usingSuffixStrings(*values)

    /** Add one method_strings.value LIKE '%value%' (contains) predicate. */
    fun addUsingString(value: String) = literals.addUsingString(value)

    /** Add one method_strings.value = 'value' (equals) predicate. */
    fun addEqString(value: String) = literals.addEqString(value)

    /**
     * Add method_using_numbers predicates. Integer values (Int, Long, Short, Byte) use long_value;
     * Float and Double use double_value.
     */
    fun usingNumbers(vararg values: Number) = literals.usingNumbers(*values)

    /**
     * Restrict the declaring class package, emitted as (m.class_descriptor LIKE 'Lpackage/%');
     * several packages are OR-joined inside the parentheses.
     */
    fun searchPackages(vararg packages: String) = literals.searchPackages(*packages)

    /** Append LIMIT n after ORDER BY. */
    fun limit(value: Int) = literals.setLimit(value)

    /** Append OFFSET n after LIMIT; requires [limit]. */
    fun offset(value: Int) = literals.setOffset(value)
}

/**
 * Builder for class queries ([sqlClass]).
 *
 * Class-level literal matchers and nested [method] matchers are existential over the methods of the
 * matched class (DexKit semantics), so different literals may come from different methods. Only
 * [className] and [superClass] constrain the class definition itself.
 */
class SqlClassQuery internal constructor() {
    /**
     * Exact class descriptor. Emitted against cm.class_descriptor when the query is method-scoped
     * and against c.descriptor otherwise.
     */
    var className: String? = null

    /** Superclass descriptor; joins classes and matches superclass_descriptor. */
    var superClass: String? = null

    internal val literals = DexSqlLiterals()
    internal val methods = mutableListOf<SqlMethodQuery>()

    /** Add class-level method_strings.value LIKE '%value%' (contains) predicates. */
    fun usingStrings(vararg values: String) = literals.usingStrings(*values)

    /** Add class-level method_strings.value = 'value' (equals) predicates. */
    fun usingEqStrings(vararg values: String) = literals.usingEqStrings(*values)

    /** Add class-level method_strings.value LIKE 'value%' (prefix) predicates. */
    fun usingPrefixStrings(vararg values: String) = literals.usingPrefixStrings(*values)

    /** Add class-level method_strings.value LIKE '%value' (suffix) predicates. */
    fun usingSuffixStrings(vararg values: String) = literals.usingSuffixStrings(*values)

    /** Add class-level method_using_numbers predicates (existential over class methods). */
    fun usingNumbers(vararg values: Number) = literals.usingNumbers(*values)

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
