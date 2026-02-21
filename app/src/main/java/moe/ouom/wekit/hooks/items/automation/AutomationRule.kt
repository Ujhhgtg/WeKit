package moe.ouom.wekit.hooks.items.automation

data class AutomationRule(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val script: String,
    val enabled: Boolean = true,
)