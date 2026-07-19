package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.WeKitOperation
import dev.ujhhgtg.wekit.features.core.Param
import dev.ujhhgtg.wekit.features.items.system.servers.WeChatService
import dev.ujhhgtg.wekit.features.items.system.servers.WeChatService.Result

/**
 * The built-in WeAgent tool surface. Every `@AgentTool` function here is discovered by the
 * [dev.ujhhgtg.wekit.features.AgentToolScanner] KSP processor and registered in the generated
 * `AgentToolsProvider`. Each wraps a [WeChatService] (or [WeDatabaseApi]) call and renders the
 * [Result] into a model-readable string.
 *
 * Side-effect classification (§3.2): read-only getters/lists/lookups are `sideEffect = false`
 * (factory-default ENABLED); everything that sends, mutates, or executes arbitrary SQL is
 * `sideEffect = true` (factory-default MANUAL_APPROVAL).
 */
object WeAgentToolBindings {

    // --- Result rendering helpers ---

    private fun <T> Result<T>.render(onSuccess: (T) -> String): String = when (this) {
        is Result.Success -> onSuccess(data)
        is Result.Error -> "Error: $message"
    }

    private fun Result<Unit>.renderOk(okMessage: String = "OK"): String =
        render { okMessage }

    // ---------------------------------------------------------------------
    // Read-only tools (sideEffect = false)
    // ---------------------------------------------------------------------

    @WeKitOperation(name = "get-self-info", description = "Get the currently logged-in user's wxid and custom wxid.", sideEffect = false)
    fun getSelfInfo(): String = WeChatService.getSelfInfo().render { "wxId=${it.wxId}, customWxId=${it.customWxId}" }

    @WeKitOperation(name = "get-current-talker", description = "Get the conversation id of the currently open chat window.", sideEffect = false)
    fun getCurrentTalker(): String = WeChatService.getCurrentTalker().render { "convId=$it" }

    @WeKitOperation(name = "get-contacts", description = "List contacts by type. Type is one of: all, friends, groups, official_accounts.", sideEffect = false)
    fun getContacts(
        @Param("Contact type: all | friends | groups | official_accounts") type: String,
    ): String = WeChatService.listContacts(type).render { list ->
        if (list.isEmpty()) "No contacts." else list.joinToString("\n") { c ->
            "wxId=${c.wxId}, nickname=${c.nickname}, customWxId=${c.customWxId}, remarkName=${c.remarkName}"
        }
    }

    @WeKitOperation(name = "get-chat-history", description = "List paged messages of a conversation, latest first.", sideEffect = false)
    fun getChatHistory(
        @Param("Conversation id (friend wxid or group id ending with @chatroom)") convId: String,
        @Param("1-based page index; defaults to 1") pageIndex: Int?,
        @Param("page size; defaults to 20") pageSize: Int?,
    ): String = WeChatService.listMessages(convId, pageIndex ?: 1, pageSize ?: 20).render { list ->
        if (list.isEmpty()) "No messages." else list.joinToString("\n") { "${it.sender}: ${it.content}" }
    }

    @WeKitOperation(name = "get-group-members", description = "List all members of a group (id must end with @chatroom).", sideEffect = false)
    fun getGroupMembers(
        @Param("Group id ending with @chatroom") groupId: String,
    ): String = WeChatService.listGroupMembers(groupId).render { list ->
        if (list.isEmpty()) "No members." else list.joinToString("\n") { m ->
            "wxId=${m.wxId}, nickname=${m.nickname}, customWxId=${m.customWxId}, remarkName=${m.remarkName}"
        }
    }

    @WeKitOperation(
        name = "lookup-contact-id",
        description = "Resolve a conversation id (wxid) from a display/remark name. Optionally match a member's group-specific name.",
        sideEffect = false
    )
    fun lookupContactId(
        @Param("Display name or remark name of the target") displayName: String,
        @Param("Optional group id (@chatroom) to match a group member's in-group name") groupId: String?,
    ): String = WeChatService.getConvIdByDisplayName(displayName, groupId).render { "wxId=$it" }

    @WeKitOperation(name = "get-contact-display-name", description = "Get the display name for a conversation/contact id.", sideEffect = false)
    fun getContactDisplayName(
        @Param("Conversation id (friend wxid or group @chatroom id)") convId: String,
    ): String = WeChatService.getDisplayNameByConvId(convId).render { it }

    @WeKitOperation(name = "get-contact-detail", description = "Get detailed info (nickname, remark, custom id, type) for a contact or group.", sideEffect = false)
    fun getContactDetail(
        @Param("Conversation id (friend wxid or group @chatroom id)") convId: String,
    ): String = WeChatService.getContactDetail(convId).render { d ->
        "wxId=${d.wxId}, nickname=${d.nickname}, remarkName=${d.remarkName}, customWxId=${d.customWxId}, isGroup=${d.isGroup}"
    }

    @WeKitOperation(name = "list-contact-labels", description = "List all contact labels (tags) with their ids.", sideEffect = false)
    fun listContactLabels(): String = WeChatService.listContactLabels().render { list ->
        if (list.isEmpty()) "No labels." else list.joinToString("\n") { "labelId=${it.labelId}, name=${it.labelName}" }
    }

    @WeKitOperation(name = "get-contacts-by-label", description = "List wxids of contacts under a given label id or name.", sideEffect = false)
    fun getContactsByLabel(
        @Param("Label id or label name") labelIdOrName: String,
    ): String = WeChatService.getContactsByLabel(labelIdOrName).render { list ->
        if (list.isEmpty()) "No contacts for this label." else list.joinToString(", ")
    }

    @WeKitOperation(
        name = "cache-image",
        description = "Cache an image message into WeChat's own storage by its server id (equivalent to tapping the image to download from CDN). Does NOT decode or copy it to Download/WeKit/. Returns the internal WeChat image path. May take a while if not cached yet.",
        sideEffect = false
    )
    fun cacheImage(
        @Param("Server id (msgSvrId) of the image message to cache") msgSvrId: Long,
    ): String = WeChatService.cacheImage(msgSvrId).render { "path=$it" }

    @WeKitOperation(
        name = "download-image",
        description = "Download the image of an image message by its server id: cache it from CDN if needed, then decode and save it to Download/WeKit/. Returns the saved local file path. May take a while if not cached yet.",
        sideEffect = false
    )
    fun downloadImage(
        @Param("Server id (msgSvrId) of the image message to download") msgSvrId: Long,
    ): String = WeChatService.downloadImage(msgSvrId).render { "path=$it" }

    @WeKitOperation(
        name = "download-sticker",
        description = "Decode the sticker/emoji of a sticker message by its server id, convert it to GIF and save it to Download/WeKit/. Returns the saved local file path.",
        sideEffect = false
    )
    fun downloadSticker(
        @Param("Server id (msgSvrId) of the sticker message to download") msgSvrId: Long,
    ): String = WeChatService.downloadSticker(msgSvrId).render { "path=$it" }

    @WeKitOperation(
        name = "download-voice",
        description = "Decode the voice of a voice message by its server id (silk → mp3) and save it to Download/WeKit/. Returns the saved local mp3 file path.",
        sideEffect = false
    )
    fun downloadVoice(
        @Param("Server id (msgSvrId) of the voice message to download") msgSvrId: Long,
    ): String = WeChatService.downloadVoice(msgSvrId).render { "path=$it" }

    @WeKitOperation(
        name = "cache-file",
        description = "Cache a file message into WeChat's own storage by its server id (equivalent to tapping the file bubble to download). Does NOT copy it to Download/WeKit/. Returns the internal WeChat file path. May take a while for large files.",
        sideEffect = false
    )
    fun cacheFile(
        @Param("Server id (msgSvrId) of the file message to cache") msgSvrId: Long,
        @Param("Conversation ID (wxId / talker) of the conversation the message belongs to; can be empty, if empty, the module tries to auto-detect the convId.") convId: String,
    ): String = WeChatService.cacheFile(msgSvrId, convId.ifEmpty { null }).render { "path=$it" }

    @WeKitOperation(
        name = "download-file",
        description = "Download a file message by its server id: cache it into WeChat's storage if needed, then copy it to Download/WeKit/. Returns the saved local file path. May take a while for large files.",
        sideEffect = false
    )
    fun downloadFile(
        @Param("Server id (msgSvrId) of the file message to download") msgSvrId: Long,
        @Param("Conversation ID (wxId / talker) of the conversation the message belongs to; can be empty, if empty, the module tries to auto-detect the convId.") convId: String,
    ): String = WeChatService.downloadFile(msgSvrId, convId.ifEmpty { null }).render { "path=$it" }

    @WeKitOperation(
        name = "query-database",
        description = "Run a read-only SQL SELECT against WeChat's decrypted database. Returns rows as JSON-ish text. Use with care.",
        sideEffect = false,
        group = WeKitOperation.BUILTIN_WECHAT_SQL
    )
    fun queryDatabase(
        @Param("A single SELECT statement") sql: String,
    ): String {
        if (!WeDatabaseApi.isReady) return "Error: database not ready"
        return runCatching {
            val rows = WeDatabaseApi.executeQuery(sql)
            if (rows.isEmpty()) "0 rows." else buildString {
                append("${rows.size} row(s):\n")
                rows.take(200).forEach { row -> append(row.entries.joinToString(", ") { "${it.key}=${it.value}" }).append("\n") }
                if (rows.size > 200) append("… (${rows.size - 200} more rows truncated)")
            }
        }.getOrElse { "Query failed: ${it.message}" }
    }

    // ---------------------------------------------------------------------
    // Side-effecting tools (sideEffect = true)
    // ---------------------------------------------------------------------

    @WeKitOperation(name = "send-text-message", description = "Send a text message to a conversation.", sideEffect = true)
    fun sendTextMessage(
        @Param("Target conversation id (friend wxid or group @chatroom id)") convId: String,
        @Param("Message text") content: String,
    ): String = WeChatService.sendMessage("text", convId, content).renderOk("Sent.")

    @WeKitOperation(name = "send-image-message", description = "Send an image file to a conversation by local file path.", sideEffect = true)
    fun sendImageMessage(
        @Param("Target conversation id") convId: String,
        @Param("Local image file path") path: String,
    ): String = WeChatService.sendImageMessage(convId, path).renderOk("Sent.")

    @WeKitOperation(name = "send-file-message", description = "Send a file to a conversation.", sideEffect = true)
    fun sendFileMessage(
        @Param("Target conversation id") convId: String,
        @Param("Local file path") path: String,
        @Param("File title/name") title: String,
    ): String = WeChatService.sendFileMessage(convId, path, title).renderOk("Sent.")

    @WeKitOperation(name = "send-emoji-message", description = "Send an emoji/sticker by local path or 32-char md5.", sideEffect = true)
    fun sendEmojiMessage(
        @Param("Target conversation id") convId: String,
        @Param("Emoji local path or 32-char md5") emojiPathOrMd5: String,
    ): String = WeChatService.sendEmojiMessage(convId, emojiPathOrMd5).renderOk("Sent.")

    @WeKitOperation(name = "send-pat-message", description = "Send a 'pat' (拍一拍) to a member in a conversation.", sideEffect = true)
    fun sendPatMessage(
        @Param("Target conversation id") convId: String,
        @Param("wxid of the pat target") patTarget: String,
    ): String = WeChatService.sendPatMessage(convId, patTarget).renderOk("Patted.")

    @WeKitOperation(name = "send-quote-message", description = "Reply to a specific message by quoting it.", sideEffect = true)
    fun sendQuoteMessage(
        @Param("Target conversation id") convId: String,
        @Param("Server id of the message being quoted") msgSvrId: Long,
        @Param("Reply text") content: String,
    ): String = WeChatService.sendQuoteMessage(convId, msgSvrId, content).renderOk("Sent.")

    @WeKitOperation(name = "revoke-message", description = "Revoke (recall) a previously sent message by its local message id.", sideEffect = true)
    fun revokeMessage(
        @Param("Local message id to revoke") msgId: Long,
    ): String = WeChatService.revokeMessage(msgId).renderOk("Revoked.")

    @WeKitOperation(
        name = "insert-system-message",
        description = "Insert a local-only system message into a conversation (not sent over network).",
        sideEffect = true
    )
    fun insertSystemMessage(
        @Param("Target conversation id") convId: String,
        @Param("System message content") content: String,
    ): String = WeChatService.insertSystemMessage(convId, content, System.currentTimeMillis()).renderOk("Inserted.")

    @WeKitOperation(name = "set-current-talker", description = "Set the active conversation (open chat window target).", sideEffect = true)
    fun setCurrentTalker(
        @Param("Conversation id to activate") convId: String,
    ): String = WeChatService.setCurrentTalker(convId).renderOk("Talker set.")

    @WeKitOperation(name = "share-webpage", description = "Share a webpage card to a conversation.", sideEffect = true)
    fun shareWebpage(
        @Param("Target conversation id") convId: String,
        @Param("Card title") title: String,
        @Param("Card description") description: String,
        @Param("Webpage URL") webpageUrl: String,
    ): String = WeChatService.shareWebpage(convId, title, description, webpageUrl, null, "").renderOk("Shared.")

    @WeKitOperation(name = "share-text", description = "Share plain text via an app-message card.", sideEffect = true)
    fun shareText(
        @Param("Target conversation id") convId: String,
        @Param("Text to share") text: String,
    ): String = WeChatService.shareText(convId, text, "").renderOk("Shared.")

    @WeKitOperation(name = "add-chatroom-member", description = "Add a member to a group chat.", sideEffect = true)
    fun addChatroomMember(
        @Param("Group id ending with @chatroom") groupId: String,
        @Param("wxid of the member to add") memberWxid: String,
    ): String = WeChatService.addChatroomMember(groupId, memberWxid).renderOk("Added.")

    @WeKitOperation(name = "del-chatroom-member", description = "Remove a member from a group chat.", sideEffect = true)
    fun delChatroomMember(
        @Param("Group id ending with @chatroom") groupId: String,
        @Param("wxid of the member to remove") memberWxid: String,
    ): String = WeChatService.delChatroomMember(groupId, memberWxid).renderOk("Removed.")

    @WeKitOperation(name = "invite-chatroom-member", description = "Invite a member to a group chat (invitation flow).", sideEffect = true)
    fun inviteChatroomMember(
        @Param("Group id ending with @chatroom") groupId: String,
        @Param("wxid of the member to invite") memberWxid: String,
    ): String = WeChatService.inviteChatroomMember(groupId, memberWxid).renderOk("Invited.")

    @WeKitOperation(name = "modify-contact-labels", description = "Set the label (tag) list for a contact.", sideEffect = true)
    fun modifyContactLabels(
        @Param("Contact wxid") wxId: String,
        @Param("Full list of label names to assign") labels: List<String>,
    ): String = WeChatService.modifyContactLabels(wxId, labels).renderOk("Labels updated.")

    @WeKitOperation(name = "post-moment-text", description = "Post a text-only moment (朋友圈).", sideEffect = true)
    fun postMomentText(
        @Param("Moment text content") content: String,
    ): String = WeChatService.postMomentText(content, null, null).renderOk("Posted.")

    @WeKitOperation(name = "post-moment-pics", description = "Post a moment (朋友圈) with text and local image paths.", sideEffect = true)
    fun postMomentPics(
        @Param("Moment text content") content: String,
        @Param("Local image file paths") picPaths: List<String>,
    ): String = WeChatService.postMomentPics(content, picPaths, null, null).renderOk("Posted.")

    @WeKitOperation(name = "verify-friend", description = "Accept/verify an incoming friend request.", sideEffect = true)
    fun verifyFriend(
        @Param("Requesting user's id") userId: String,
        @Param("Verification ticket from the request") ticket: String,
        @Param("Scene code of the request") scene: Int,
    ): String = WeChatService.verifyFriend(userId, ticket, scene, null).renderOk("Verified.")

    @WeKitOperation(
        name = "execute-database-statement",
        description = "Execute an arbitrary non-query SQL statement (INSERT/UPDATE/DELETE/DDL) against WeChat's database. Dangerous and irreversible.",
        sideEffect = true,
        group = WeKitOperation.BUILTIN_WECHAT_SQL
    )
    fun executeDatabaseStatement(
        @Param("A single non-query SQL statement") sql: String,
    ): String {
        if (!WeDatabaseApi.isReady) return "Error: database not ready"
        return runCatching {
            WeDatabaseApi.execStatement(sql)
            "Statement executed."
        }.getOrElse { "Statement failed: ${it.message}" }
    }
}

