package moe.ouom.wekit.hooks.item.chat.msg.autoreply

sealed class MatchRule {
    data class Exact(val pattern: String) : MatchRule()
    data class Contains(val keyword: String) : MatchRule()
    data class Regex(val pattern: String) : MatchRule()
    data class JavaScript(val script: String) : MatchRule() // full script, must define onMessage()
}

data class AutoReplyRule(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val matchRule: MatchRule,
    // for non-JS rules, the static reply text; ignored when matchRule is JavaScript
    val replyText: String = "",
    val enabled: Boolean = true,
)