@file:Suppress("NOTHING_TO_INLINE", "DEPRECATION", "unused")

package dev.ujhhgtg.wekit.features.api.core.models

import androidx.annotation.StringRes
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocaleResourceMode
import dev.ujhhgtg.wekit.i18n.LocalizedContextFactory
import dev.ujhhgtg.wekit.i18n.WeKitLocaleController
import dev.ujhhgtg.wekit.utils.HostInfo

// type=0 post
// type=1 plain text
// type=3 image
// type=34 voice
// type=37 add friend request verification
// type=40 friends you possible know
// type=42 contact card
// type=43 video
// type=48 static location
// type=49 app message
// type=50 voip
// type=51 app initialization
// type=52 voip notification
// type=53 voip invitation
// type=419430449 cash transfer
// type=436207665 red packet
// type=1040187441 qq music
// type=1090519089 file

enum class MessageType(val code: Int, @StringRes val displayNameRes: Int) {
    MOMENTS(0, R.string.message_type_moments),

    @Deprecated("Use MessageType.isText()")
    TEXT(1, R.string.message_type_text),
    IMAGE(3, R.string.message_type_image),
    VOICE(34, R.string.message_type_voice),
    FRIEND_VERIFY(37, R.string.message_type_friend_verify),
    CONTACT_RECOMMEND(40, R.string.message_type_contact_recommend),
    CARD(42, R.string.message_type_card),
    VIDEO(43, R.string.message_type_video),

    @Deprecated("Use MessageType.isSticker()")
    STICKER(47, R.string.message_type_sticker),

    @Deprecated("Use MessageType.isLocation()")
    LOCATION(48, R.string.message_type_location),
    APP(49, R.string.message_type_app),

    @Deprecated("Use MessageType.isVoip()")
    VOIP(50, R.string.message_type_voip),
    STATUS(51, R.string.message_type_status),

    @Deprecated("Use MessageType.isVoip()")
    VOIP_NOTIFY(52, R.string.message_type_voip_notify),

    @Deprecated("Use MessageType.isVoip()")
    VOIP_INVITE(53, R.string.message_type_voip_invite),
    MICRO_VIDEO(62, R.string.message_type_micro_video),
    SYSTEM_NOTICE(9999, R.string.message_type_system_notice),

    SYSTEM(10000, R.string.message_type_system),

    @Deprecated("Use MessageType.isLocation()")
    SYSTEM_LOCATION(10002, R.string.message_type_system_location),

    @Deprecated("Use MessageType.isSticker()")
    SO_GOU_EMOJI(1048625, R.string.message_type_sogou_emoji),

    @Deprecated("Use MessageType.isLink()")
    LINK(16777265, R.string.message_type_link),
    RECALL(268445456, R.string.message_type_recall),
    SERVICE(318767153, R.string.message_type_service),
    TRANSFER(419430449, R.string.message_type_transfer),

    @Deprecated("Use MessageType.isRedPacket()")
    RED_PACKET(436207665, R.string.message_type_red_packet),

    @Deprecated("Use MessageType.isRedPacket()")
    SPECIAL_RED_PACKET(469762097, R.string.message_type_special_red_packet),
    ACCOUNT_VIDEO(486539313, R.string.message_type_account_video),
    RED_PACKET_COVER(536936497, R.string.message_type_red_packet_cover),

    @Deprecated("Use MessageType.isVideoAccount()")
    VIDEO_ACCOUNT(754974769, R.string.message_type_video_account),

    @Deprecated("Use MessageType.isVideoAccount()")
    VIDEO_ACCOUNT_CARD(771751985, R.string.message_type_video_account_card),
    GROUP_NOTE(805306417, R.string.message_type_group_note),
    QUOTE(822083633, R.string.message_type_quote),
    PAT(922746929, R.string.message_type_pat),

    @Deprecated("Use MessageType.isVideoAccount()")
    VIDEO_ACCOUNT_LIVE(973078577, R.string.message_type_video_account_live),

    @Deprecated("Use MessageType.isLink()")
    PRODUCT(974127153, R.string.message_type_product),
    UNKNOWN(975175729, R.string.message_type_unknown),

    @Deprecated("Use MessageType.isLink()")
    MUSIC(1040187441, R.string.message_type_music),
    FILE(1090519089, R.string.message_type_file),
    ;

    val displayName: String
        get() = LocalizedContextFactory.create(
            HostInfo.application,
            WeKitLocaleController.resolvedLocale,
            LocaleResourceMode.InjectedHost,
        ).getString(displayNameRes)

    inline val isText get() = code == TEXT.code || code == QUOTE.code
    inline val isLink get() = code == LINK.code || code == MUSIC.code || code == PRODUCT.code
    inline val isRedPacket
        get() =
            code == RED_PACKET.code || code == SPECIAL_RED_PACKET.code
    inline val isSystem get() = code == SYSTEM.code || code == SYSTEM_NOTICE.code
    inline val isSticker get() = code == STICKER.code || code == SO_GOU_EMOJI.code
    inline val isLocation get() = code == LOCATION.code || code == SYSTEM_LOCATION.code
    inline val isVideoAccount
        get() =
            code == VIDEO_ACCOUNT.code || code == VIDEO_ACCOUNT_CARD.code || code == VIDEO_ACCOUNT_LIVE.code
    inline val isVoip
        get() =
            code == VOIP.code || code == VOIP_NOTIFY.code || code == VOIP_INVITE.code

    companion object {

        fun fromCode(code: Int): MessageType? = entries.find { it.code == code }
    }
}
