package moe.ouom.wekit.hooks.items.chat

enum class MsgType(val code: Int) {
    MOMENTS(0),
    TEXT(1),
    IMAGE(3),
    VOICE(34),
    FRIEND_VERIFY(37),
    CONTACT_RECOMMEND(40),
    CARD(42),
    VIDEO(43),
    EMOJI(0x2F),
    LOCATION(0x30),
    APP(49),
    VOIP(50),
    STATUS(51),
    VOIP_NOTIFY(52),
    VOIP_INVITE(53),
    MICRO_VIDEO(62),
    SYSTEM_NOTICE(0x270F),
    SYSTEM(10000),
    SYSTEM_LOCATION(10002),
    SO_GOU_EMOJI(0x100031),
    LINK(0x1000031),
    RECALL(0x10002710),
    SERVICE(0x13000031),
    TRANSFER(0x19000031),
    RED_PACKET(0x1A000031),
    YEAR_RED_PACKET(0x1C000031),
    ACCOUNT_VIDEO(0x1D000031),
    RED_PACKET_COVER(0x20010031),
    VIDEO_ACCOUNT(0x2D000031),
    VIDEO_ACCOUNT_CARD(0x2E000031),
    GROUP_NOTE(0x30000031),
    QUOTE(0x31000031),
    PAT(0x37000031),
    VIDEO_ACCOUNT_LIVE(0x3A000031),
    PRODUCT(0x3A100031),
    UNKNOWN(0x3A200031),
    MUSIC(0x3E000031),
    FILE(0x41000031),
    TEXT_WITH_QUOTE(822083633);

    companion object {
        fun fromCode(code: Int): MsgType? = entries.find { it.code == code }

        fun isType(code: Int, vararg types: MsgType): Boolean =
            types.any { it.code == code }

        fun isText(code: Int) = code == TEXT.code || code == TEXT_WITH_QUOTE.code
        fun isImage(code: Int) = code == IMAGE.code
        fun isVoice(code: Int) = code == VOICE.code
        fun isVideo(code: Int) = code == VIDEO.code
        fun isFile(code: Int) = code == FILE.code
        fun isApp(code: Int) = code == APP.code
        fun isLink(code: Int) = code == LINK.code || code == MUSIC.code || code == PRODUCT.code
        fun isRedPacket(code: Int) = code == RED_PACKET.code || code == YEAR_RED_PACKET.code
        fun isSystem(code: Int) = code == SYSTEM.code || code == SYSTEM_NOTICE.code
        fun isEmoji(code: Int) = code == EMOJI.code || code == SO_GOU_EMOJI.code
        fun isLocation(code: Int) = code == LOCATION.code || code == SYSTEM_LOCATION.code
        fun isQuote(code: Int) = code == QUOTE.code
        fun isPat(code: Int) = code == PAT.code
        fun isTransfer(code: Int) = code == TRANSFER.code
        fun isGroupNote(code: Int) = code == GROUP_NOTE.code
        fun isVideoAccount(code: Int) = code == VIDEO_ACCOUNT.code || code == VIDEO_ACCOUNT_CARD.code || code == VIDEO_ACCOUNT_LIVE.code
        fun isCard(code: Int) = code == CARD.code
        fun isMoments(code: Int) = code == MOMENTS.code
        fun isVoip(code: Int) = code == VOIP.code || code == VOIP_NOTIFY.code || code == VOIP_INVITE.code
    }
}