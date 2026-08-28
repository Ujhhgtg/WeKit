package dev.ujhhgtg.wekit.python.api

interface PythonDexHost {
    fun findClasses(matcher: PythonClassMatcher): List<PythonResolvedClass>
    fun findMethods(matcher: PythonMethodMatcher): List<PythonResolvedMember>
    fun findConstructors(matcher: PythonMethodMatcher): List<PythonResolvedMember>
    fun findFields(matcher: PythonFieldMatcher): List<PythonResolvedField>

    fun findClass(matcher: PythonClassMatcher): PythonResolvedClass = findClasses(matcher).requireSingle("class")
    fun findMethod(matcher: PythonMethodMatcher): PythonResolvedMember = findMethods(matcher).requireSingle("method")
    fun findConstructor(matcher: PythonMethodMatcher): PythonResolvedMember =
        findConstructors(matcher).requireSingle("constructor")
    fun findField(matcher: PythonFieldMatcher): PythonResolvedField = findFields(matcher).requireSingle("field")

    private fun <T> List<T>.requireSingle(kind: String): T {
        require(size == 1) { "DexKit $kind query returned $size matches" }
        return single()
    }
}

enum class PythonStringMatchMode { CONTAINS, STARTS_WITH, ENDS_WITH, REGEX, EQUALS }

data class PythonStringMatcher(
    val value: String,
    val mode: PythonStringMatchMode = PythonStringMatchMode.CONTAINS,
    val ignoreCase: Boolean = false,
)

data class PythonClassMatcher(
    val descriptor: String? = null,
    val name: PythonStringMatcher? = null,
    val sourceFile: PythonStringMatcher? = null,
    val modifiers: Int? = null,
    val superClass: String? = null,
    val interfaces: List<String> = emptyList(),
    val usingStrings: List<PythonStringMatcher> = emptyList(),
    val searchPackages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
    val ignorePackagesCase: Boolean = false,
    val fields: List<PythonFieldMatcher> = emptyList(),
    val methods: List<PythonMethodMatcher> = emptyList(),
    val allOf: List<PythonClassMatcher> = emptyList(),
    val anyOf: List<PythonClassMatcher> = emptyList(),
    val noneOf: List<PythonClassMatcher> = emptyList(),
)

data class PythonMethodMatcher(
    val descriptor: String? = null,
    val name: PythonStringMatcher? = null,
    val modifiers: Int? = null,
    val declaredClass: String? = null,
    val returnType: String? = null,
    val parameterTypes: List<String?>? = null,
    val parameterCount: Int? = null,
    val protoShorty: String? = null,
    val opCodes: List<Int> = emptyList(),
    val opNames: List<String> = emptyList(),
    val usingStrings: List<PythonStringMatcher> = emptyList(),
    val usingNumbers: List<Number> = emptyList(),
    val usingFields: List<String> = emptyList(),
    val invokedMethods: List<String> = emptyList(),
    val callerMethods: List<String> = emptyList(),
    val searchPackages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
    val ignorePackagesCase: Boolean = false,
    val allOf: List<PythonMethodMatcher> = emptyList(),
    val anyOf: List<PythonMethodMatcher> = emptyList(),
    val noneOf: List<PythonMethodMatcher> = emptyList(),
)

data class PythonFieldMatcher(
    val descriptor: String? = null,
    val name: PythonStringMatcher? = null,
    val modifiers: Int? = null,
    val declaredClass: String? = null,
    val type: String? = null,
    val searchPackages: List<String> = emptyList(),
    val excludePackages: List<String> = emptyList(),
    val ignorePackagesCase: Boolean = false,
    val readMethods: List<PythonMethodMatcher> = emptyList(),
    val writeMethods: List<PythonMethodMatcher> = emptyList(),
    val allOf: List<PythonFieldMatcher> = emptyList(),
    val anyOf: List<PythonFieldMatcher> = emptyList(),
    val noneOf: List<PythonFieldMatcher> = emptyList(),
)

data class PythonResolvedClass(
    val name: String,
    val descriptor: String,
    val hostVersion: String,
    val hostBuildTag: String,
)

enum class PythonMemberKind { METHOD, CONSTRUCTOR, FIELD }

data class PythonResolvedMember(
    override val descriptor: String,
    val hostVersion: String,
    val hostBuildTag: String,
    val kind: PythonMemberKind = PythonMemberKind.METHOD,
) : PythonMemberHandle

data class PythonResolvedField(
    val descriptor: String,
    val hostVersion: String,
    val hostBuildTag: String,
)
