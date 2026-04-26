package dev.ujhhgtg.wekit.hooks.api.core.models

interface IWeContact {
    val wxId: String
    val nickname: String
    val displayName: String
}

// 基础用户信息模型
data class WeContact(
    override val wxId: String,
    override val nickname: String,
    val customWxId: String,
    val remarkName: String,
    val initialNickname: String,
    val nicknamePinyin: String,
    val avatarUrl: String,
    val encryptedUsername: String,
    val type: Int
) : IWeContact {
    override val displayName: String
        get() = if (remarkName.isNotBlank()) "$remarkName ($nickname)" else nickname
}

// 群聊信息模型
data class WeGroup(
    override val wxId: String,
    override val nickname: String,
    val nicknameShortPinyin: String,
    val nicknamePinyin: String,
    val avatarUrl: String
) : IWeContact {
    override val displayName: String
        get() = nickname
}

// 公众号信息模型
data class WeOfficialAccount(
    override val wxId: String,
    override val nickname: String,
    val avatarUrl: String
) : IWeContact {
    override val displayName: String
        get() = nickname
}

// 消息模型
data class WeMessage(
    val msgId: Long,
    val talker: String,
    val content: String,
    val typeCode: Int,
    val createTime: Long,
    val isSend: Int
)
