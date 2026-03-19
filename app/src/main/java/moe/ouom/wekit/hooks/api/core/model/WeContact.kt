package moe.ouom.wekit.hooks.api.core.model

// 基础用户信息模型
data class WeContact(
    val wxId: String,
    val nickname: String,
    val customWxid: String,
    val remarkName: String,
    val initialNickname: String,
    val nicknamePinyin: String,
    val avatarUrl: String,
    val encryptedUsername: String,
    val type: Int
)

// 群聊信息模型
data class WeGroup(
    val wxId: String,
    val nickname: String,
    val nicknameShortPinyin: String,
    val nicknamePinyin: String,
    val avatarUrl: String
)

// 公众号信息模型
data class WeOfficial(
    val wxId: String,
    val nickname: String,
    val avatarUrl: String
)

// 消息模型
data class WeMessage(
    val msgId: Long,
    val talker: String,
    val content: String,
    val type: Int,
    val createTime: Long,
    val isSend: Int
)
