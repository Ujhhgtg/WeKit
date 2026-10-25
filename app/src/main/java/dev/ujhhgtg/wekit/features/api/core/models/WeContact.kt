package dev.ujhhgtg.wekit.features.api.core.models

interface IWeContact {
    val wxId: String
    val nickname: String
    val displayName: String

    /** 小头像 CDN URL（`img_flag.reserved2`）。 */
    val avatarUrl: String

    /** 大头像 CDN URL（`img_flag.reserved1`）；为空表示宿主未提供。 */
    val avatarBigUrl: String
        get() = ""
}

// 基础用户信息模型
data class WeContact(
    override val wxId: String,
    override val nickname: String,
    val customWxId: String,
    val remarkName: String,
    val initialNickname: String,
    val nicknamePinyin: String,
    override val avatarUrl: String,
    val encryptedUsername: String,
    val type: Int,
    override val avatarBigUrl: String = "",
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
    override val avatarUrl: String,
    override val avatarBigUrl: String = "",
) : IWeContact {
    override val displayName: String
        get() = nickname
}

// 公众号信息模型
data class WeOfficialAccount(
    override val wxId: String,
    override val nickname: String,
    override val avatarUrl: String,
    override val avatarBigUrl: String = "",
) : IWeContact {
    override val displayName: String
        get() = nickname
}

// 消息模型
data class WeMessage(
    val msgId: Long,
    val msgSvrId: Long,
    val talker: String,
    val content: String,
    val typeCode: Int,
    val createTime: Long,
    val isSend: Int,
) {
    val type = MessageType.fromCode(typeCode)
}
