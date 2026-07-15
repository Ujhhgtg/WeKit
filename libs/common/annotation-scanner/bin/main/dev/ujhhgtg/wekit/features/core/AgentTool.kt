package dev.ujhhgtg.wekit.features.core

/**
 * Marks a function as a WeAgent built-in tool. The [dev.ujhhgtg.wekit.features.AgentToolScanner] KSP processor
 * discovers every annotated function at compile time and generates an `AgentToolsProvider`
 * registry (name, description, JSON-schema-derived parameter specs, and a direct invoker),
 * so no hand-written mapping table is needed and new [dev.ujhhgtg.wekit] APIs are picked up
 * automatically once annotated.
 *
 * The annotated function must be a member of a Kotlin `object` or a top-level function, and
 * may be `suspend`. Its parameter types must be one of: [String], [Int], [Long], [Double],
 * [Boolean], or `List<String>` (each optionally nullable). A nullable parameter is treated as
 * optional in the generated schema; a non-null parameter is treated as required. The function
 * should return a human/model-readable [String] describing the outcome.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class AgentTool(
    val name: String,
    val description: String,
    /**
     * `true` = the tool mutates WeChat/device state (send, delete, group admin, arbitrary
     * write SQL, file write, …) and ships with a factory-default permission of
     * "manual approval". `false` = read-only (getters, list/lookup, read-only query) and
     * ships with a factory-default of "enabled".
     */
    val sideEffect: Boolean,
    /**
     * Which built-in provider this tool belongs to. Tools are grouped into separate providers in
     * the settings UI and for permission storage. Defaults to [BUILTIN_WECHAT]. Known groups:
     * `builtin-wechat` (WeChat operations), `builtin-wechat-sql` (raw DB SQL), `builtin-fs`
     * (workspace/memory file tools + skill loading).
     */
    val group: String = BUILTIN_WECHAT,
) {
    companion object {
        const val BUILTIN_WECHAT = "builtin-wechat"
        const val BUILTIN_WECHAT_SQL = "builtin-wechat-sql"
        const val BUILTIN_FS = "builtin-fs"
        const val BUILTIN_JVM = "builtin-jvm"
        const val BUILTIN_UI = "builtin-ui"
        const val BUILTIN_WEBVIEW = "builtin-webview"
        const val BUILTIN_TRIGGER = "builtin-trigger"
        const val BUILTIN_INFO = "builtin-info"
        /** Network tools: HTTP requests, page fetch, and external search APIs (Exa, Brave, …). */
        const val BUILTIN_NET = "builtin-net"
    }
}

/**
 * Optional per-parameter description used to enrich the generated JSON schema for a tool
 * parameter. When absent, the parameter name is used as its own description.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class AgentToolParam(
    val description: String,
)
