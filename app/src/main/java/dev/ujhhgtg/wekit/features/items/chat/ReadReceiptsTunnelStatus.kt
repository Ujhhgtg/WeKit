package dev.ujhhgtg.wekit.features.items.chat

enum class ReadReceiptsTunnelMode {
    QUICK,
    TOKEN,
    BROWSER_LOGIN,
}

enum class ReadReceiptsTunnelState {
    STOPPED,
    STARTING,
    CONNECTED,
    RECONNECTING,
    NEEDS_USER_ACTION,
    FAILED,
    STOPPING,
}

data class ReadReceiptsTunnelStatus(
    val state: ReadReceiptsTunnelState,
    val publicUrl: String? = null,
    val error: String? = null,
)
