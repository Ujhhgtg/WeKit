package dev.ujhhgtg.wekit.python.api

interface PythonDexHost {
    fun findMethod(matcher: PythonMethodMatcher): PythonResolvedMember
}

data class PythonMethodMatcher(
    val returnType: String? = null,
    val parameterCount: Int? = null,
    val usingStrings: List<String> = emptyList(),
)

data class PythonResolvedMember(
    val descriptor: String,
    val hostVersion: String,
    val hostBuildTag: String,
)
