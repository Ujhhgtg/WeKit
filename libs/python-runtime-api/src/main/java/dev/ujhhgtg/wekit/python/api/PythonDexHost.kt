package dev.ujhhgtg.wekit.python.api

interface PythonDexHost {
    fun findClasses(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): List<PythonResolvedClass>

    fun findMethods(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): List<PythonResolvedMember>

    fun findConstructors(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): List<PythonResolvedMember>

    fun findFields(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): List<PythonResolvedField>

    fun findClass(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): PythonResolvedClass = findClasses(
        matcher, searchPackages, excludePackages, ignorePackagesCase,
    ).requireSingle("class")

    fun findMethod(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): PythonResolvedMember = findMethods(
        matcher, searchPackages, excludePackages, ignorePackagesCase,
    ).requireSingle("method")

    fun findConstructor(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): PythonResolvedMember = findConstructors(
        matcher, searchPackages, excludePackages, ignorePackagesCase,
    ).requireSingle("constructor")

    fun findField(
        matcher: Any,
        searchPackages: List<String>,
        excludePackages: List<String>,
        ignorePackagesCase: Boolean,
    ): PythonResolvedField = findFields(
        matcher, searchPackages, excludePackages, ignorePackagesCase,
    ).requireSingle("field")

    private fun <T> List<T>.requireSingle(kind: String): T {
        require(size == 1) { "DexKit $kind query returned $size matches" }
        return single()
    }
}

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
