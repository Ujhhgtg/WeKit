package me.hd.wauxv.data.bean

import androidx.annotation.Keep
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType

@Suppress("unused")
@Keep
class MsgInfoBean(
    @JvmField val origin: Any,
) {
    val msg = MessageInfo(origin)

    @JvmField val msgId: Long = msg.id
    @JvmField val msgSvrId: Long = msg.serverId
    @JvmField val type: Int = msg.typeCode
    @JvmField val isSendInt: Int = msg.isSend
    @JvmField val createTime: Long = msg.createTime
    @JvmField val talker: String = msg.talker
    @JvmField val originContent: String = msg.content
    @JvmField val imgPath: String? = msg.imagePath
    @JvmField val lvBuffer: ByteArray = msg.lvBuffer ?: ByteArray(0)
    @JvmField val talkerId: Int = msg.talkerId
    @JvmField val msgSeq: Long = msg.seq

    // --- Field getters ---

    fun getMsgId(): Long = msgId

    fun getMsgSvrId(): Long = msgSvrId

    fun getType(): Int = type

    fun getCreateTime(): Long = createTime

    fun getTalker(): String = talker

    fun getOriginContent(): String = originContent

    fun getImgPath(): String? = imgPath

    fun getLvBuffer(): ByteArray = lvBuffer

    fun getTalkerId(): Int = talkerId

    fun getMsgSeq(): Long = msgSeq

    fun getOrigin(): Any = origin

    fun isSendInt(): Int = isSendInt

    // --- Type checks ---

    /** 1 = text */
    @Suppress("DEPRECATION") fun isText(): Boolean = msg.type == MessageType.TEXT

    /** 3/13/39 = image */
    fun isImage(): Boolean = msg.typeCode in setOf(3, 13, 39)

    /** sticker (47/1048625) */
    fun isEmoji(): Boolean = msg.type?.isSticker == true

    /** 34 = voice */
    fun isVoice(): Boolean = msg.type == MessageType.VOICE

    /** 43 = video, 62 = micro video */
    fun isVideo(): Boolean = msg.type in setOf(MessageType.VIDEO, MessageType.MICRO_VIDEO)

    /** 42 = contact card */
    fun isShareCard(): Boolean = msg.type == MessageType.CARD

    /** 922746929 = pat */
    fun isPat(): Boolean = msg.type == MessageType.PAT

    /** 10000/9999 = system */
    fun isSystem(): Boolean = msg.type?.isSystem == true

    /** 822083633 = quote */
    fun isQuote(): Boolean = msg.type == MessageType.QUOTE

    /** 48/10002 = location */
    fun isLocation(): Boolean = msg.type?.isLocation == true

    /** 49 = app message */
    fun isApp(): Boolean = msg.type == MessageType.APP

    /** 16777265/974127153/1040187441 = link */
    fun isLink(): Boolean = msg.type?.isLink == true

    /** 419430449 = transfer */
    fun isTransfer(): Boolean = msg.type == MessageType.TRANSFER

    /** 436207665/469762097 = red packet */
    fun isRedBag(): Boolean = msg.type?.isRedPacket == true

    /** 486539313 = video account video */
    fun isVideoNumberVideo(): Boolean = msg.type == MessageType.ACCOUNT_VIDEO

    /** 805306417 = group note */
    fun isNote(): Boolean = msg.type == MessageType.GROUP_NOTE

    /** 1090519089 = file */
    fun isFile(): Boolean = msg.type == MessageType.FILE

    /** 268445456 = recall */
    fun isRecalled(): Boolean = msg.type == MessageType.RECALL

    /** 50/52/53 = voip */
    fun isVoip(): Boolean = msg.type?.isVoip == true

    /** Voip content check */
    fun isVoipVideo(): Boolean = isVoip() && msg.content == "voip_content_video"

    /** Voip content check */
    fun isVoipVoice(): Boolean = isVoip() && msg.content == "voip_content_voice"

    fun isSend(): Boolean = isSendInt == 1

    fun isGroupChat(): Boolean = talker.endsWith("@chatroom") || talker.endsWith("@im.chatroom")

    fun isChatroom(): Boolean = talker.endsWith("@chatroom")

    fun isImChatroom(): Boolean = talker.endsWith("@im.chatroom")

    fun isOpenIM(): Boolean = talker.endsWith("@openim")

    fun isOfficialAccount(): Boolean = talker.startsWith("gh_")

    fun isPrivateChat(): Boolean {
        if (!talker.startsWith("wxid_")) {
            val excluded = setOf("gh_", "@chatroom", "weixin", "filehelper", "qqmail")
            if (excluded.any { talker.contains(it, ignoreCase = true) }) return false
        }
        return true
    }

    fun isEnumMsg(type: Int): Boolean = this.type == type

    // --- Sender & content ---

    fun getSendTalker(): String {
        if (isRecalled()) return "recalled"
        return msg.sender
    }

    fun getContent(): String {
        return when {
            isText() -> msg.actualContent
            isImage() -> {
                originContent.ifEmpty { imgPath ?: "" }
            }

            isVoice() || isVideo() -> {
                if (originContent.contains("<msg>") && originContent.contains("</msg>")) {
                    originContent.substringAfter("voicelength=\"").substringBefore("\"")
                } else {
                    originContent.substringAfter(":", originContent)
                }
            }

            isPat() -> getPatMsg()?.getTemplate() ?: originContent
            else -> originContent
        }
    }

    // --- lvBuffer / msg source ---

    fun getMsgSource(): String = msg.msgSource

    fun getAtUserList(): List<String> = msg.mentionedUsers

    fun isAtMe(): Boolean = msg.isAtMe

    fun isAnnounceAll(): Boolean = msg.isAnnounceAll

    fun isNotifyAll(): Boolean = msg.isNotifyAll

    // --- Sub-message helpers ---

    fun getFileMsg(): FileMsg? = if (isFile()) FileMsg(msg.toFileMessage() ?: return null) else null

    fun getImageMsg(): ImageMsg? =
        if (isImage() && originContent.isNotEmpty()) ImageMsg(msg.toImageMessage() ?: return null)
        else null

    fun getQuoteMsg(): QuoteMsg? =
        if (isQuote()) QuoteMsg(msg.toQuoteMessage() ?: return null) else null

    fun getTransferMsg(): TransferMsg? =
        if (isTransfer()) TransferMsg(msg.toTransferMessage() ?: return null) else null

    fun getPatMsg(): PatMsg? = if (isPat()) PatMsg(msg.toPatMessage() ?: return null) else null

    // --- Inner classes ---

    @Keep
    class FileMsg(val msg: MessageInfo.FileMessage) {

        fun getTitle(): String = msg.title

        fun getSize(): Long = msg.size

        fun getExt(): String = msg.ext

        fun getMd5(): String = msg.md5

        fun getUrl(): String = msg.url

        fun getKey(): String = msg.key
    }

    @Keep
    class ImageMsg(val msg: MessageInfo.ImageMessage) {
        fun getAesKey(): String = msg.aesKey

        fun getCdnUrl(): String = msg.thumbUrl

        fun getMd5(): String = msg.md5

        fun getBigImgUrl(): String = msg.bigImgUrl

        fun getMidImgUrl(): String = msg.midImgUrl

        fun getThumbUrl(): String = msg.thumbUrl

        fun getKey(): String = msg.aesKey
    }

    @Keep
    class QuoteMsg(val msg: MessageInfo.QuoteMessage) {
        fun getTitle() = msg.title

        fun getSendTalker() = msg.chatusr

        fun getDisplayName() = msg.displayname

        fun getMsgSource() = msg.msgsource

        fun getOriginContent() = msg.content

        fun getSvrId() = msg.svrid

        fun getTalker() = msg.fromusr

        fun getType() = msg.type

        @Suppress("DEPRECATION")
        fun getContent(): String {
            return when (msg.type) {
                MessageType.TEXT.code -> msg.content
                MessageType.IMAGE.code -> "图片"
                MessageType.VIDEO.code -> "视频"
                MessageType.STICKER.code,
                MessageType.SO_GOU_EMOJI.code -> "表情"
                else -> msg.content
            }
        }
    }

    @Keep
    class TransferMsg(val msg: MessageInfo.TransferMessage) {
        fun getTitle() = msg.title

        fun getDes() = msg.des

        fun getTransactionId() = msg.transactionId

        fun getTransferId() = msg.transferId

        fun getBeginTransferTime() = msg.beginTransferTime

        fun getFeeDesc() = msg.feedesc

        fun getInvalidTime() = msg.invalidTime

        fun getPayerUsername() = msg.payerUsername

        fun getReceiverUsername() = msg.receiverUsername
    }

    @Keep
    class PatMsg(val msg: MessageInfo.PatMessage) {
        fun getTemplate() = msg.template

        fun getFromUser() = msg.fromUser

        fun getCreateTime() = msg.createTime

        fun getPattedUser() = msg.pattedUser

        fun getReadStatus() = msg.readStatus

        fun getRecordNum() = msg.recordNum

        fun getShowModifyTip() = msg.showModifyTip

        fun getSvrId() = msg.svrId

        fun getTalker() = msg.talker
    }
}
