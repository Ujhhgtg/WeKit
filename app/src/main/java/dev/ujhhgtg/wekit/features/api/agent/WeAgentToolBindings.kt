package dev.ujhhgtg.wekit.features.api.agent

import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.core.AgentTool
import dev.ujhhgtg.wekit.features.core.AgentToolParam
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

    @AgentTool(name = "get-self-info", description = "Get the currently logged-in user's wxid and custom wxid.", sideEffect = false)
    fun getSelfInfo(): String = WeChatService.getSelfInfo().render { "wxId=${it.wxId}, customWxId=${it.customWxId}" }

    @AgentTool(name = "get-current-talker", description = "Get the conversation id of the currently open chat window.", sideEffect = false)
    fun getCurrentTalker(): String = WeChatService.getCurrentTalker().render { "convId=$it" }

    @AgentTool(name = "get-contacts", description = "List contacts by type. Type is one of: all, friends, groups, official_accounts.", sideEffect = false)
    fun getContacts(
        @AgentToolParam("Contact type: all | friends | groups | official_accounts") type: String,
    ): String = WeChatService.listContacts(type).render { list ->
        if (list.isEmpty()) "No contacts." else list.joinToString("\n") { c ->
            "wxId=${c.wxId}, nickname=${c.nickname}, customWxId=${c.customWxId}, remarkName=${c.remarkName}"
        }
    }

    @AgentTool(name = "get-chat-history", description = "List paged messages of a conversation, latest first.", sideEffect = false)
    fun getChatHistory(
        @AgentToolParam("Conversation id (friend wxid or group id ending with @chatroom)") convId: String,
        @AgentToolParam("1-based page index; defaults to 1") pageIndex: Int?,
        @AgentToolParam("page size; defaults to 20") pageSize: Int?,
    ): String = WeChatService.listMessages(convId, pageIndex ?: 1, pageSize ?: 20).render { list ->
        if (list.isEmpty()) "No messages." else list.joinToString("\n") { "${it.sender}: ${it.content}" }
    }

    @AgentTool(name = "get-group-members", description = "List all members of a group (id must end with @chatroom).", sideEffect = false)
    fun getGroupMembers(
        @AgentToolParam("Group id ending with @chatroom") groupId: String,
    ): String = WeChatService.listGroupMembers(groupId).render { list ->
        if (list.isEmpty()) "No members." else list.joinToString("\n") { m ->
            "wxId=${m.wxId}, nickname=${m.nickname}, customWxId=${m.customWxId}, remarkName=${m.remarkName}"
        }
    }

    @AgentTool(name = "lookup-contact-id", description = "Resolve a conversation id (wxid) from a display/remark name. Optionally match a member's group-specific name.", sideEffect = false)
    fun lookupContactId(
        @AgentToolParam("Display name or remark name of the target") displayName: String,
        @AgentToolParam("Optional group id (@chatroom) to match a group member's in-group name") groupId: String?,
    ): String = WeChatService.getConvIdByDisplayName(displayName, groupId).render { "wxId=$it" }

    @AgentTool(name = "get-contact-display-name", description = "Get the display name for a conversation/contact id.", sideEffect = false)
    fun getContactDisplayName(
        @AgentToolParam("Conversation id (friend wxid or group @chatroom id)") convId: String,
    ): String = WeChatService.getDisplayNameByConvId(convId).render { it }

    @AgentTool(name = "get-contact-detail", description = "Get detailed info (nickname, remark, custom id, type) for a contact or group.", sideEffect = false)
    fun getContactDetail(
        @AgentToolParam("Conversation id (friend wxid or group @chatroom id)") convId: String,
    ): String = WeChatService.getContactDetail(convId).render { d ->
        "wxId=${d.wxId}, nickname=${d.nickname}, remarkName=${d.remarkName}, customWxId=${d.customWxId}, isGroup=${d.isGroup}"
    }

    @AgentTool(name = "list-contact-labels", description = "List all contact labels (tags) with their ids.", sideEffect = false)
    fun listContactLabels(): String = WeChatService.listContactLabels().render { list ->
        if (list.isEmpty()) "No labels." else list.joinToString("\n") { "labelId=${it.labelId}, name=${it.labelName}" }
    }

    @AgentTool(name = "get-contacts-by-label", description = "List wxids of contacts under a given label id or name.", sideEffect = false)
    fun getContactsByLabel(
        @AgentToolParam("Label id or label name") labelIdOrName: String,
    ): String = WeChatService.getContactsByLabel(labelIdOrName).render { list ->
        if (list.isEmpty()) "No contacts for this label." else list.joinToString(", ")
    }

    @AgentTool(name = "cache-image", description = "Cache an image message into WeChat's own storage by its server id (equivalent to tapping the image to download from CDN). Does NOT decode or copy it to Download/WeKit/. Returns the internal WeChat image path. May take a while if not cached yet.", sideEffect = false)
    fun cacheImage(
        @AgentToolParam("Server id (msgSvrId) of the image message to cache") msgSvrId: Long,
    ): String = WeChatService.cacheImage(msgSvrId).render { "path=$it" }

    @AgentTool(name = "download-image", description = "Download the image of an image message by its server id: cache it from CDN if needed, then decode and save it to Download/WeKit/. Returns the saved local file path. May take a while if not cached yet.", sideEffect = false)
    fun downloadImage(
        @AgentToolParam("Server id (msgSvrId) of the image message to download") msgSvrId: Long,
    ): String = WeChatService.downloadImage(msgSvrId).render { "path=$it" }

    @AgentTool(name = "download-sticker", description = "Decode the sticker/emoji of a sticker message by its server id, convert it to GIF and save it to Download/WeKit/. Returns the saved local file path.", sideEffect = false)
    fun downloadSticker(
        @AgentToolParam("Server id (msgSvrId) of the sticker message to download") msgSvrId: Long,
    ): String = WeChatService.downloadSticker(msgSvrId).render { "path=$it" }

    @AgentTool(name = "download-voice", description = "Decode the voice of a voice message by its server id (silk → mp3) and save it to Download/WeKit/. Returns the saved local mp3 file path.", sideEffect = false)
    fun downloadVoice(
        @AgentToolParam("Server id (msgSvrId) of the voice message to download") msgSvrId: Long,
    ): String = WeChatService.downloadVoice(msgSvrId).render { "path=$it" }

    @AgentTool(name = "cache-file", description = "Cache a file message into WeChat's own storage by its server id (equivalent to tapping the file bubble to download). Does NOT copy it to Download/WeKit/. Returns the internal WeChat file path. May take a while for large files.", sideEffect = false)
    fun cacheFile(
        @AgentToolParam("Server id (msgSvrId) of the file message to cache") msgSvrId: Long,
        @AgentToolParam("Conversation ID (wxId / talker) of the conversation the message belongs to; can be empty, if empty, the module tries to auto-detect the convId.") convId: String,
    ): String = WeChatService.cacheFile(msgSvrId, convId.ifEmpty { null }).render { "path=$it" }

    @AgentTool(name = "download-file", description = "Download a file message by its server id: cache it into WeChat's storage if needed, then copy it to Download/WeKit/. Returns the saved local file path. May take a while for large files.", sideEffect = false)
    fun downloadFile(
        @AgentToolParam("Server id (msgSvrId) of the file message to download") msgSvrId: Long,
        @AgentToolParam("Conversation ID (wxId / talker) of the conversation the message belongs to; can be empty, if empty, the module tries to auto-detect the convId.") convId: String,
    ): String = WeChatService.downloadFile(msgSvrId, convId.ifEmpty { null }).render { "path=$it" }

    @AgentTool(name = "query-database", description = "Run a read-only SQL SELECT against WeChat's decrypted database. Returns rows as JSON-ish text. Use with care.", sideEffect = false, group = AgentTool.BUILTIN_WECHAT_SQL)
    fun queryDatabase(
        @AgentToolParam("A single SELECT statement") sql: String,
    ): String {
        if (!WeDatabaseApi.isReady) return "Error: database not ready"
        // Anti-loop: suppress SQL-event triggers briefly so the agent's own query doesn't fire them.
        WeAgentService.triggerManager.suppressSqlBriefly()
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

    @AgentTool(name = "send-text-message", description = "Send a text message to a conversation.", sideEffect = true)
    fun sendTextMessage(
        @AgentToolParam("Target conversation id (friend wxid or group @chatroom id)") convId: String,
        @AgentToolParam("Message text") content: String,
    ): String = WeChatService.sendMessage("text", convId, content).renderOk("Sent.")

    @AgentTool(name = "send-image-message", description = "Send an image file to a conversation by local file path.", sideEffect = true)
    fun sendImageMessage(
        @AgentToolParam("Target conversation id") convId: String,
        @AgentToolParam("Local image file path") path: String,
    ): String = WeChatService.sendImageMessage(convId, path).renderOk("Sent.")

    @AgentTool(name = "send-file-message", description = "Send a file to a conversation.", sideEffect = true)
    fun sendFileMessage(
        @AgentToolParam("Target conversation id") convId: String,
        @AgentToolParam("Local file path") path: String,
        @AgentToolParam("File title/name") title: String,
    ): String = WeChatService.sendFileMessage(convId, path, title).renderOk("Sent.")

    @AgentTool(name = "send-emoji-message", description = "Send an emoji/sticker by local path or 32-char md5.", sideEffect = true)
    fun sendEmojiMessage(
        @AgentToolParam("Target conversation id") convId: String,
        @AgentToolParam("Emoji local path or 32-char md5") emojiPathOrMd5: String,
    ): String = WeChatService.sendEmojiMessage(convId, emojiPathOrMd5).renderOk("Sent.")

    @AgentTool(name = "send-pat-message", description = "Send a 'pat' (拍一拍) to a member in a conversation.", sideEffect = true)
    fun sendPatMessage(
        @AgentToolParam("Target conversation id") convId: String,
        @AgentToolParam("wxid of the pat target") patTarget: String,
    ): String = WeChatService.sendPatMessage(convId, patTarget).renderOk("Patted.")

    @AgentTool(name = "send-quote-message", description = "Reply to a specific message by quoting it.", sideEffect = true)
    fun sendQuoteMessage(
        @AgentToolParam("Target conversation id") convId: String,
        @AgentToolParam("Server id of the message being quoted") msgSvrId: Long,
        @AgentToolParam("Reply text") content: String,
    ): String = WeChatService.sendQuoteMessage(convId, msgSvrId, content).renderOk("Sent.")

    @AgentTool(name = "revoke-message", description = "Revoke (recall) a previously sent message by its local message id.", sideEffect = true)
    fun revokeMessage(
        @AgentToolParam("Local message id to revoke") msgId: Long,
    ): String = WeChatService.revokeMessage(msgId).renderOk("Revoked.")

    @AgentTool(name = "insert-system-message", description = "Insert a local-only system message into a conversation (not sent over network).", sideEffect = true)
    fun insertSystemMessage(
        @AgentToolParam("Target conversation id") convId: String,
        @AgentToolParam("System message content") content: String,
    ): String = WeChatService.insertSystemMessage(convId, content, System.currentTimeMillis()).renderOk("Inserted.")

    @AgentTool(name = "set-current-talker", description = "Set the active conversation (open chat window target).", sideEffect = true)
    fun setCurrentTalker(
        @AgentToolParam("Conversation id to activate") convId: String,
    ): String = WeChatService.setCurrentTalker(convId).renderOk("Talker set.")

    @AgentTool(name = "share-webpage", description = "Share a webpage card to a conversation.", sideEffect = true)
    fun shareWebpage(
        @AgentToolParam("Target conversation id") convId: String,
        @AgentToolParam("Card title") title: String,
        @AgentToolParam("Card description") description: String,
        @AgentToolParam("Webpage URL") webpageUrl: String,
    ): String = WeChatService.shareWebpage(convId, title, description, webpageUrl, null, "").renderOk("Shared.")

    @AgentTool(name = "share-text", description = "Share plain text via an app-message card.", sideEffect = true)
    fun shareText(
        @AgentToolParam("Target conversation id") convId: String,
        @AgentToolParam("Text to share") text: String,
    ): String = WeChatService.shareText(convId, text, "").renderOk("Shared.")

    @AgentTool(name = "add-chatroom-member", description = "Add a member to a group chat.", sideEffect = true)
    fun addChatroomMember(
        @AgentToolParam("Group id ending with @chatroom") groupId: String,
        @AgentToolParam("wxid of the member to add") memberWxid: String,
    ): String = WeChatService.addChatroomMember(groupId, memberWxid).renderOk("Added.")

    @AgentTool(name = "del-chatroom-member", description = "Remove a member from a group chat.", sideEffect = true)
    fun delChatroomMember(
        @AgentToolParam("Group id ending with @chatroom") groupId: String,
        @AgentToolParam("wxid of the member to remove") memberWxid: String,
    ): String = WeChatService.delChatroomMember(groupId, memberWxid).renderOk("Removed.")

    @AgentTool(name = "invite-chatroom-member", description = "Invite a member to a group chat (invitation flow).", sideEffect = true)
    fun inviteChatroomMember(
        @AgentToolParam("Group id ending with @chatroom") groupId: String,
        @AgentToolParam("wxid of the member to invite") memberWxid: String,
    ): String = WeChatService.inviteChatroomMember(groupId, memberWxid).renderOk("Invited.")

    @AgentTool(name = "modify-contact-labels", description = "Set the label (tag) list for a contact.", sideEffect = true)
    fun modifyContactLabels(
        @AgentToolParam("Contact wxid") wxId: String,
        @AgentToolParam("Full list of label names to assign") labels: List<String>,
    ): String = WeChatService.modifyContactLabels(wxId, labels).renderOk("Labels updated.")

    @AgentTool(name = "post-moment-text", description = "Post a text-only moment (朋友圈).", sideEffect = true)
    fun postMomentText(
        @AgentToolParam("Moment text content") content: String,
    ): String = WeChatService.postMomentText(content, null, null).renderOk("Posted.")

    @AgentTool(name = "post-moment-pics", description = "Post a moment (朋友圈) with text and local image paths.", sideEffect = true)
    fun postMomentPics(
        @AgentToolParam("Moment text content") content: String,
        @AgentToolParam("Local image file paths") picPaths: List<String>,
    ): String = WeChatService.postMomentPics(content, picPaths, null, null).renderOk("Posted.")

    @AgentTool(name = "verify-friend", description = "Accept/verify an incoming friend request.", sideEffect = true)
    fun verifyFriend(
        @AgentToolParam("Requesting user's id") userId: String,
        @AgentToolParam("Verification ticket from the request") ticket: String,
        @AgentToolParam("Scene code of the request") scene: Int,
    ): String = WeChatService.verifyFriend(userId, ticket, scene, null).renderOk("Verified.")

    @AgentTool(name = "execute-database-statement", description = "Execute an arbitrary non-query SQL statement (INSERT/UPDATE/DELETE/DDL) against WeChat's database. Dangerous and irreversible.", sideEffect = true, group = AgentTool.BUILTIN_WECHAT_SQL)
    fun executeDatabaseStatement(
        @AgentToolParam("A single non-query SQL statement") sql: String,
    ): String {
        if (!WeDatabaseApi.isReady) return "Error: database not ready"
        // Anti-loop: suppress SQL-event triggers around the agent's own write so it can't self-trigger.
        WeAgentService.triggerManager.suppressSqlBriefly()
        return runCatching {
            WeDatabaseApi.execStatement(sql)
            "Statement executed."
        }.getOrElse { "Statement failed: ${it.message}" }
    }
}

