package dev.ujhhgtg.wekit.python.api

/**
 * Spec-based DEX query host for Python plugins.
 *
 * The Python `wekit.dex` module builds plain snake_case spec dicts and passes their JSON encoding;
 * the host executes them through WeKit's standalone
 * [dev.ujhhgtg.dexsql.matcher.DexMatcherSession]. There is no DexKit matcher ABI in this API.
 */
interface PythonDexHost {
    fun findClasses(
        specJson: String,
    ): List<PythonResolvedClass>

    fun findMethods(
        specJson: String,
    ): List<PythonResolvedMember>

    fun findConstructors(
        specJson: String,
    ): List<PythonResolvedMember>

    fun findFields(
        specJson: String,
    ): List<PythonResolvedField>

    fun findClass(
        specJson: String,
    ): PythonResolvedClass = findClasses(
        specJson,
    ).requireSingle("class")

    fun findMethod(
        specJson: String,
    ): PythonResolvedMember = findMethods(
        specJson,
    ).requireSingle("method")

    fun findConstructor(
        specJson: String,
    ): PythonResolvedMember = findConstructors(
        specJson,
    ).requireSingle("constructor")

    fun findField(
        specJson: String,
    ): PythonResolvedField = findFields(
        specJson,
    ).requireSingle("field")

    private fun <T> List<T>.requireSingle(kind: String): T {
        require(size == 1) { "DEX " + kind + " query returned " + size + " matches" }
        return single()
    }
}

data class PythonResolvedClass(
    val name: String,
    val descriptor: String,
    val modifiers: Int,
    val hostVersion: String,
    val hostBuildTag: String,
)

enum class PythonMemberKind { METHOD, CONSTRUCTOR, FIELD }

data class PythonResolvedMember(
    override val descriptor: String,
    val name: String,
    val declaredClass: String,
    val returnType: String,
    val paramTypes: List<String>,
    val modifiers: Int,
    val kind: PythonMemberKind = PythonMemberKind.METHOD,
    val hostVersion: String,
    val hostBuildTag: String,
) : PythonMemberHandle

data class PythonResolvedField(
    val descriptor: String,
    val name: String,
    val declaredClass: String,
    val typeName: String,
    val modifiers: Int,
    val hostVersion: String,
    val hostBuildTag: String,
)
