package moe.ouom.wekit.hooks.sdk.base.model

// 基础用户信息模型
data class WeContact(
    val wxid: String,
    val nickname: String,
    val customWxid: String,
    val remarkName: String,
    val initialNickname: String,
    val nicknamePinyin: String,
    val avatarUrl: String,
    val encryptedUsername: String
)

// 群聊信息模型
data class WeGroup(
    val username: String,
    val nickname: String, // 群名称
    val pyInitial: String,
    val quanPin: String,
    val avatarUrl: String
)

// 公众号信息模型
data class WeOfficial(
    val username: String,
    val nickname: String,
    val alias: String,
    val signature: String,
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