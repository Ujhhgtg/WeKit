package dev.ujhhgtg.wekit.features.items.system.servers

import android.util.Base64
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeAuthApi
import dev.ujhhgtg.wekit.features.api.core.WeContactApi
import dev.ujhhgtg.wekit.features.api.core.WeContactLabelApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeGroupApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.WePaymentApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.collections.LruCache
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.coroutines.resume

object WeChatService {

    private val GROUP_SENDER_REGEX = Regex("""^([^\n:]+):\n(.+)""", setOf(RegexOption.DOT_MATCHES_ALL))

    // groupId → Map<wxId, displayName>
    private val groupMembersCache = LruCache<String, Map<String, String>>()

    // -------------------------------------------------------------------------
    // Result type
    // -------------------------------------------------------------------------

    sealed class Result<out T> {
        data class Success<out T>(val data: T) : Result<T>()
        data class Error(val message: String) : Result<Nothing>()
    }

    // -------------------------------------------------------------------------
    // Shared request / response models (used by both MCP and REST layers)
    // -------------------------------------------------------------------------

    @Serializable
    data class ContactInfo(
        val wxId: String,
        val nickname: String,
        val customWxId: String = "",
        val remarkName: String = "",
    )

    @Serializable
    data class MessageInfo(
        val sender: String,
        val content: String,
        val type: String,
    )

    @Serializable
    data class SendMessageRequest(
        val type: String,
        val convId: String,
        val content: String,
    )

    @Serializable
    data class SelfInfo(
        val wxId: String,
        val customWxId: String,
    )

    @Serializable
    data class ContactDetail(
        val wxId: String,
        val nickname: String,
        val customWxId: String,
        val remarkName: String,
        val displayName: String,
        val avatarUrl: String,
        val type: Int,
        val isGroup: Boolean
    )

    @Serializable
    data class ContactLabelInfo(
        val labelId: Int,
        val labelName: String
    )

    // -------------------------------------------------------------------------
    // Operations
    // -------------------------------------------------------------------------

    fun getSelfInfo(): Result<SelfInfo> = Result.Success(
        SelfInfo(
            runCatching { WeApi.selfWxId }.getOrDefault(""),
            runCatching { WeApi.selfCustomWxId }.getOrDefault("")
        )
    )

    fun getCurrentTalker(): Result<String> = Result.Success(
        WeCurrentConversationApi.value
    )

    fun setCurrentTalker(wxId: String): Result<Unit> {
        WeCurrentConversationApi.value = wxId
        return Result.Success(Unit)
    }

    fun getContactDetail(wxId: String): Result<ContactDetail> {
        val group = WeDatabaseApi.getGroup(wxId)
        if (group != null) {
            return Result.Success(
                ContactDetail(
                    wxId = group.wxId,
                    nickname = group.nickname,
                    customWxId = "",
                    remarkName = "",
                    displayName = group.displayName,
                    avatarUrl = group.avatarUrl,
                    type = 0,
                    isGroup = true
                )
            )
        }
        val friend = WeDatabaseApi.getFriend(wxId)
        if (friend != null) {
            return Result.Success(
                ContactDetail(
                    wxId = friend.wxId,
                    nickname = friend.nickname,
                    customWxId = friend.customWxId,
                    remarkName = friend.remarkName,
                    displayName = friend.displayName,
                    avatarUrl = friend.avatarUrl,
                    type = friend.type,
                    isGroup = false
                )
            )
        }
        return Result.Error("Contact not found")
    }

    fun sendMessage(req: SendMessageRequest): Result<Unit> =
        sendMessage(req.type, req.convId, req.content)

    fun sendMessage(type: String, convId: String, content: String): Result<Unit> = when (type) {
        "text" -> if (WeMessageApi.sendText(convId, content)) Result.Success(Unit)
        else Result.Error("Failed to send message")

        else -> Result.Error("Unsupported type: $type")
    }

    fun sendImageMessage(toUser: String, path: String): Result<Unit> =
        if (WeMessageApi.sendImage(toUser, path)) Result.Success(Unit)
        else Result.Error("Failed to send image")

    fun sendVoiceMessage(toUser: String, path: String, durationMs: Int): Result<Unit> =
        if (WeMessageApi.sendVoice(toUser, path, durationMs)) Result.Success(Unit)
        else Result.Error("Failed to send voice")

    fun sendVideoMessage(toUser: String, path: String): Result<Unit> =
        if (WeMessageApi.sendVideo(toUser, path)) Result.Success(Unit)
        else Result.Error("Failed to send video")

    fun sendFileMessage(toUser: String, path: String, title: String): Result<Unit> =
        if (WeMessageApi.sendFile(toUser, path, title)) Result.Success(Unit)
        else Result.Error("Failed to send file")

    fun sendEmojiMessage(toUser: String, emojiPathOrMd5: String): Result<Unit> {
        val isMd5 = emojiPathOrMd5.length == 32 && emojiPathOrMd5.all { it.isLetterOrDigit() }
        val ok = if (isMd5) {
            WeMessageApi.sendEmojiByMd5(toUser, emojiPathOrMd5)
        } else {
            WeMessageApi.sendEmoji(toUser, emojiPathOrMd5)
        }
        return if (ok) Result.Success(Unit) else Result.Error("Failed to send emoji")
    }

    fun sendPatMessage(toUser: String, patTarget: String): Result<Unit> =
        if (WeMessageApi.sendPat(toUser, patTarget)) Result.Success(Unit)
        else Result.Error("Failed to send pat")

    fun sendLocationMessage(toUser: String, poiName: String, label: String, x: String, y: String, scale: String): Result<Unit> =
        if (WeMessageApi.sendLocation(toUser, poiName, label, x, y, scale)) Result.Success(Unit)
        else Result.Error("Failed to send location")

    fun sendShareCardMessage(toUser: String, cardWxId: String): Result<Unit> =
        if (WeMessageApi.sendShareCard(toUser, cardWxId)) Result.Success(Unit)
        else Result.Error("Failed to send share card")

    fun sendXmlMessage(toUser: String, xmlContent: String): Result<Unit> =
        if (WeMessageApi.sendXmlAppMsg(toUser, xmlContent)) Result.Success(Unit)
        else Result.Error("Failed to send XML message")

    fun sendQuoteMessage(toUser: String, msgSvrId: Long, content: String): Result<Unit> =
        if (WeMessageApi.sendQuoteMsgByMsgId(toUser, msgSvrId, content)) Result.Success(Unit)
        else Result.Error("Failed to send quote message")

    fun sendCipherMessage(toUser: String, title: String, content: String): Result<Unit> {
        val encodedContent = android.text.Html.escapeHtml(content)
        val xml = """<msg><appmsg type="1"><title>$title</title><des>$title</des><content>|WA|$encodedContent</content></appmsg></msg>"""
        return if (WeMessageApi.sendXmlAppMsg(toUser, xml)) Result.Success(Unit)
        else Result.Error("Failed to send cipher message")
    }

    fun sendNoteMessage(toUser: String, noteXml: String): Result<Unit> {
        val xml = """<msg><appmsg type="53"><title>$noteXml</title><extinfo><solitaire_info></solitaire_info></extinfo></appmsg></msg>"""
        return if (WeMessageApi.sendXmlAppMsg(toUser, xml)) Result.Success(Unit)
        else Result.Error("Failed to send note message")
    }

    fun sendAppBrandMessage(toUser: String, title: String, pagePath: String, username: String): Result<Unit> {
        val xml = """<msg><appmsg type="33"><title>$title</title><weappinfo><item><pagepath><![CDATA[$pagePath]]></pagepath><username>$username</username></item></weappinfo></appmsg></msg>"""
        return if (WeMessageApi.sendXmlAppMsg(toUser, xml)) Result.Success(Unit)
        else Result.Error("Failed to send app brand message")
    }

    fun cacheImage(msgSvrId: Long): Result<String> =
        WeMessageApi.cacheImage(msgSvrId)
            ?.let { Result.Success(it) }
            ?: Result.Error("Failed to cache image")

    fun downloadImage(msgSvrId: Long): Result<String> =
        WeMessageApi.downloadImage(msgSvrId)
            ?.let { Result.Success(it) }
            ?: Result.Error("Failed to download image")

    fun downloadSticker(msgSvrId: Long): Result<String> =
        WeMessageApi.cacheAndSaveSticker(msgSvrId)
            ?.let { Result.Success(it) }
            ?: Result.Error("Failed to download sticker")

    fun downloadVoice(msgSvrId: Long): Result<String> =
        WeMessageApi.cacheAndSaveVoice(msgSvrId)
            ?.let { Result.Success(it) }
            ?: Result.Error("Failed to download voice")

    fun cacheFile(msgSvrId: Long, talker: String?): Result<String> =
        WeMessageApi.cacheFile(msgSvrId, talker)
            ?.let { Result.Success(it) }
            ?: Result.Error("Failed to cache file")

    fun downloadFile(msgSvrId: Long, talker: String?): Result<String> =
        WeMessageApi.downloadFile(msgSvrId, talker)
            ?.let { Result.Success(it) }
            ?: Result.Error("Failed to download file")

    fun saveSticker(msgSvrId: Long): Result<String> =
        WeMessageApi.cacheAndSaveSticker(msgSvrId)
            ?.let { Result.Success(it) }
            ?: Result.Error("Failed to save sticker")

    fun saveVoice(msgSvrId: Long): Result<String> =
        WeMessageApi.cacheAndSaveVoice(msgSvrId)
            ?.let { Result.Success(it) }
            ?: Result.Error("Failed to save voice")

    fun revokeMessage(msgId: Long): Result<Unit> =
        runCatching {
            if (WeMessageApi.revokeMsgByMsgId(msgId)) Result.Success(Unit)
            else Result.Error("Failed to revoke message")
        }.getOrElse { Result.Error(it.message ?: "Failed to revoke message") }

    fun insertSystemMessage(toUser: String, content: String, time: Long): Result<Unit> {
        WeMessageApi.createSimpleMsgInfoAndInsert(MessageType.SYSTEM.code, toUser, content, time)
        return Result.Success(Unit)
    }

    fun shareFile(toUser: String, title: String, path: String, appId: String): Result<Unit> =
        if (WeMessageApi.sendFile(toUser, path, title, appId)) Result.Success(Unit)
        else Result.Error("Failed to share file")

    fun shareWebpage(toUser: String, title: String, description: String, webpageUrl: String, thumbDataBase64: String?, appId: String): Result<Unit> {
        val thumbData = thumbDataBase64?.let { Base64.decode(it, Base64.DEFAULT) }
        return if (WeMessageApi.shareWebpage(toUser, title, description, webpageUrl, thumbData, appId)) Result.Success(Unit)
        else Result.Error("Failed to share webpage")
    }

    fun shareVideo(toUser: String, title: String, description: String, videoUrl: String, thumbDataBase64: String?, appId: String): Result<Unit> {
        val thumbData = thumbDataBase64?.let { Base64.decode(it, Base64.DEFAULT) }
        return if (WeMessageApi.shareVideo(toUser, title, description, videoUrl, thumbData, appId)) Result.Success(Unit)
        else Result.Error("Failed to share video")
    }

    fun shareText(toUser: String, text: String, appId: String): Result<Unit> =
        if (WeMessageApi.shareText(toUser, text, appId)) Result.Success(Unit)
        else Result.Error("Failed to share text")

    fun shareMusic(toUser: String, title: String, description: String, musicUrl: String, musicDataUrl: String, thumbDataBase64: String?, appId: String): Result<Unit> {
        val thumbData = thumbDataBase64?.let { Base64.decode(it, Base64.DEFAULT) }
        return if (WeMessageApi.shareMusic(toUser, title, description, musicUrl, musicDataUrl, thumbData, appId)) Result.Success(Unit)
        else Result.Error("Failed to share music")
    }

    fun shareMusicVideo(toUser: String, title: String, description: String, musicUrl: String, musicDataUrl: String, singerName: String, duration: Int, songLyric: String, thumbDataBase64: String?, appId: String): Result<Unit> {
        val thumbData = thumbDataBase64?.let { Base64.decode(it, Base64.DEFAULT) }
        return if (WeMessageApi.shareMusicVideo(toUser, title, description, musicUrl, musicDataUrl, singerName, duration, songLyric, thumbData, appId)) Result.Success(Unit)
        else Result.Error("Failed to share music video")
    }

    fun shareMiniProgram(toUser: String, title: String, description: String, username: String, path: String, thumbDataBase64: String?, appId: String): Result<Unit> {
        val thumbData = thumbDataBase64?.let { Base64.decode(it, Base64.DEFAULT) }
        return if (WeMessageApi.shareMiniProgram(toUser, title, description, username, path, thumbData, appId)) Result.Success(Unit)
        else Result.Error("Failed to share mini program")
    }

    fun addChatroomMember(groupId: String, memberWxid: String): Result<Unit> {
        WeGroupApi.addMember(groupId, memberWxid)
        return Result.Success(Unit)
    }

    fun addChatroomMembers(groupId: String, memberWxids: List<String>): Result<Unit> {
        WeGroupApi.addMembers(groupId, memberWxids)
        return Result.Success(Unit)
    }

    fun delChatroomMember(groupId: String, memberWxid: String): Result<Unit> {
        WeGroupApi.delMember(groupId, memberWxid)
        return Result.Success(Unit)
    }

    fun delChatroomMembers(groupId: String, memberWxids: List<String>): Result<Unit> {
        WeGroupApi.delMembers(groupId, memberWxids)
        return Result.Success(Unit)
    }

    fun inviteChatroomMember(groupId: String, memberWxid: String): Result<Unit> {
        WeGroupApi.inviteMember(groupId, memberWxid)
        return Result.Success(Unit)
    }

    fun inviteChatroomMembers(groupId: String, memberWxids: List<String>): Result<Unit> {
        WeGroupApi.inviteMembers(groupId, memberWxids)
        return Result.Success(Unit)
    }

    fun listContactLabels(): Result<List<ContactLabelInfo>> =
        Result.Success(
            WeContactLabelApi.getAllLabels().map {
                ContactLabelInfo(it.labelId, it.labelName)
            }
        )

    fun getContactsByLabel(labelIdOrName: String): Result<List<String>> {
        val labelId = labelIdOrName.toIntOrNull()
        val list = if (labelId != null) {
            WeContactLabelApi.getContactsByLabelId(labelId)
        } else {
            WeContactLabelApi.getContactsByLabelName(labelIdOrName)
        }
        return Result.Success(list)
    }

    fun modifyContactLabels(wxId: String, labels: List<String>): Result<Unit> {
        WeContactLabelApi.modifyLabel(wxId, labels)
        return Result.Success(Unit)
    }

    fun postMomentText(content: String, sdkId: String?, sdkAppName: String?): Result<Unit> =
        if (WeMomentsApi.postText(content, sdkId, sdkAppName)) Result.Success(Unit)
        else Result.Error("Failed to post moment text")

    fun postMomentPics(content: String, picPaths: List<String>, sdkId: String?, sdkAppName: String?): Result<Unit> =
        if (WeMomentsApi.postTextAndImages2(content, picPaths, sdkId, sdkAppName)) Result.Success(Unit)
        else Result.Error("Failed to post moment pictures")

    fun confirmTransfer(convId: String, transId: String, transSpanId: String, invalidTime: Int): Result<Unit> =
        if (WePaymentApi.confirmTransfer(convId, transId, transSpanId, invalidTime)) Result.Success(Unit)
        else Result.Error("Failed to confirm transfer")

    fun refuseTransfer(convId: String, transId: String, transSpanId: String): Result<Unit> =
        if (WePaymentApi.refuseTransfer(convId, transId, transSpanId, 0)) Result.Success(Unit)
        else Result.Error("Failed to refuse transfer")

    fun verifyFriend(userId: String, ticket: String, scene: Int, privacy: Int?): Result<Unit> {
        WeContactApi.verifyUser(userId, ticket, scene, privacy ?: 0)
        return Result.Success(Unit)
    }

    fun uploadDeviceStep(stepCount: Long): Result<Unit> =
        runCatching {
            val devStepMgrClazz = "com.tencent.mm.plugin.sport.model.DeviceStepManager".toClass()
            val uploadMethod = devStepMgrClazz.reflekt()
                .firstMethod { parameters(Long::class.java, Long::class.java) }
                .self
            val getInstance = devStepMgrClazz.reflekt()
                .firstMethod { modifiers(Modifiers.STATIC); parameters() }
                .self
            uploadMethod.invoke(getInstance.invoke(null), System.currentTimeMillis() / 1000, stepCount)
            Result.Success(Unit)
        }.getOrElse {
            WeLogger.e("WeChatService", "uploadDeviceStep failed", it)
            Result.Error(it.message ?: "Failed to upload device step")
        }

    fun audioMp3ToSilk(srcPath: String, destPath: String): Result<Unit> =
        runCatching {
            AudioUtils.anyToSilk(srcPath, destPath)
            Result.Success(Unit)
        }.getOrElse { Result.Error(it.message ?: "Failed to convert mp3 to silk") }

    fun audioSilkToMp3(srcPath: String, destPath: String): Result<Unit> =
        runCatching {
            val pcm = "$destPath.tmp"
            AudioUtils.silkToPcm(srcPath, pcm)
            AudioUtils.pcmToMp3(pcm, destPath)
            runCatching { File(pcm).delete() }
            Result.Success(Unit)
        }.getOrElse { Result.Error(it.message ?: "Failed to convert silk to mp3") }

    fun audioGetDuration(path: String): Result<Long> =
        runCatching {
            Result.Success(AudioUtils.getDurationMs(path))
        }.getOrElse { Result.Error(it.message ?: "Failed to get duration") }

    suspend fun jsLogin(appId: String): Result<String> = suspendCancellableCoroutine { cont ->
        WeAuthApi.jsLogin(appId) { code ->
            if (code != null) {
                cont.resume(Result.Success(code))
            } else {
                cont.resume(Result.Error("jsLogin failed"))
            }
        }
    }

    fun listContacts(type: String): Result<List<ContactInfo>> = when (type) {
        "all" -> Result.Success(WeDatabaseApi.getContacts().map {
            ContactInfo(it.wxId, it.nickname)
        })

        "friends" -> Result.Success(
            WeDatabaseApi.getFriends()
                .filter { c ->
                    c.type != 2051 && c.type != 2049 && c.wxId.startsWith("wxid_") && c.wxId != WeApi.selfWxId
                }.map {
                    ContactInfo(it.wxId, it.nickname, it.customWxId, it.remarkName)
                })

        "groups" -> Result.Success(WeDatabaseApi.getGroups().map {
            ContactInfo(it.wxId, it.nickname)
        })

        "official_accounts" -> Result.Success(WeDatabaseApi.getOfficialAccounts().map {
            ContactInfo(it.wxId, it.nickname)
        })

        else -> Result.Error("Unsupported type: $type")
    }

    fun listMessages(convId: String, pageIndex: Int = 1, pageSize: Int = 20): Result<List<MessageInfo>> {
        val isGroup = convId.isGroupChatWxId
        val membersMap: Map<String, String> = if (isGroup) {
            groupMembersCache.getOrPut(convId) {
                WeDatabaseApi.getGroupMembers(convId).associate { m ->
                    m.wxId to (m.remarkName.takeUnless { it.isBlank() }?.let { "$it (${m.nickname})" } ?: m.nickname)
                }
            }
        } else emptyMap()

        val messages = WeDatabaseApi.getMessages(convId, pageIndex, pageSize).map { msg ->
            val isText = MessageType.fromCode(msg.typeCode)?.isText ?: false
            val typeStr = MessageType.fromCode(msg.typeCode)?.name?.lowercase() ?: "unknown"
            val (sender, content) = if (msg.talker.isGroupChatWxId) {
                if (msg.isSend != 0) {
                    "<myself>" to if (isText) msg.content else "<type:$typeStr>"
                } else {
                    val match = GROUP_SENDER_REGEX.find(msg.content).takeIf { isGroup }
                    val sender = match?.groupValues?.get(1)?.let { membersMap[it] ?: it } ?: "<unknown>"
                    val content = if (isText) match?.groupValues?.get(2) ?: msg.content else "<type:$typeStr>"
                    sender to content
                }
            } else {
                val sender = if (msg.isSend != 0) "<myself>" else msg.talker
                val content = if (isText) msg.content else "<type:$typeStr>"
                sender to content
            }
            MessageInfo(sender, content, typeStr)
        }
        return Result.Success(messages)
    }

    fun listGroupMembers(groupId: String): Result<List<ContactInfo>> =
        Result.Success(WeDatabaseApi.getGroupMembers(groupId).map {
            ContactInfo(it.wxId, it.nickname, it.customWxId, it.remarkName)
        })

    fun getConvIdByDisplayName(displayName: String, groupId: String? = null): Result<String> {
        if (groupId != null) {
            return WeDatabaseApi.getGroupMembers(groupId)
                .find { it.nickname == displayName || it.remarkName == displayName }
                ?.let { Result.Success(it.wxId) }
                ?: Result.Error("search matched 0 contact")
        }
        WeDatabaseApi.getFriends()
            .find { it.nickname == displayName || it.remarkName == displayName }
            ?.let { return Result.Success(it.wxId) }
        WeDatabaseApi.getGroups()
            .find { it.nickname == displayName }
            ?.let { return Result.Success(it.wxId) }
        WeDatabaseApi.getOfficialAccounts()
            .find { it.nickname == displayName }
            ?.let { return Result.Success(it.wxId) }
        return Result.Error("search matched 0 contact")
    }

    fun getDisplayNameByConvId(convId: String): Result<String> =
        Result.Success(WeDatabaseApi.getDisplayName(convId))
}

