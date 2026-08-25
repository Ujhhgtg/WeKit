package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi.hideConversation
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi.reloadConversations
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.int
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WeConversationApi : ApiFeature(), IResolveDex {

    override val technicalId = "对话服务"
    override val nameRes = R.string.feature_we_conversation_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_conversation_api_description

    private const val TAG = "WeConversationApi"

    // Clears 4096, 1048576, 16777216 and 33554432, which drive 8071/8072 red prefixes
//    private const val ATTR_FLAG_COMMON_RED_BITS = 51384320
//    private const val ATTR_FLAG_8071_8072_RED_PACKET_BITS = 33280
//    private const val TABLE_RCONVERSATION = "rconversation"
//    private const val TABLE_ECS_CONVERSATION_RECORD = "EcsConversationRecord"
    internal val classConversationStorage by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("rconversation", "PRAGMA table_info( rconversation)")
        }
    }
    internal val methodUpdateUnreadByTalker by dexMethod {
        matcher {
            declaredClass(classConversationStorage.data.name)
            usingEqStrings("MicroMsg.ConversationStorage", "updateUnreadByTalker %s")
        }
    }

    // ConversationStorage.k(String) aka `delChatContact`: deletes the rconversation row through the
    // cache-aware storage wrapper and notifies list observers, which is the core of WeChat's native
    // "不显示该聊天" for a normal contact.
    private val methodDelChatContact by dexMethod {
        matcher {
            declaredClass(classConversationStorage.data.name)
            usingStrings("MicroMsg.ConversationStorage", "delChatContact username:")
            paramCount = 1
            paramTypes(String::class.java)
            returnType(Void.TYPE)
        }
    }

    //    private val methodClearConvRedHintsOnMarkRead by dexMethod(allowFailure = true) {
//        matcher {
//            modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL
//            returnType(Void.TYPE)
//            paramCount = 1
//            paramTypes(String::class.java)
//            usingStrings(
//                "MicroMsg.ConvRedHintStorage",
//                "markReadRemoveRedHint remove red hints"
//            )
//        }
//    }
//    private val methodClearEcsGiftRedLabel by dexMethod(allowFailure = true) {
//        matcher {
//            returnType(Void.TYPE)
//            paramCount = 1
//            paramTypes(String::class.java)
//            usingEqStrings(
//                "MicroMsg.EcsGiftMsgService",
//                "clearEcsGiftRedLabel, talker is empty",
//                "clearEcsGiftRedLabel error"
//            )
//        }
//    }
    private val methodHiddenConvParent by dexMethod {
        matcher {
            declaredClass(classConversationStorage.data.name)
            usingEqStrings("Update rconversation set parentRef = '", "' where 1 != 1 ")
        }
    }

    //    private val methodGetConvByName by dexMethod {
//        matcher {
//            declaredClass(classConversationStorage.clazz)
//            usingEqStrings("MicroMsg.ConversationStorage", "get null with username:")
//        }
//    }
//    private val methodUpdateConversationByObject by dexMethod {
//        matcher {
//            declaredClass(classConversationStorage.clazz)
//            returnType(Int::class.java)
//            paramTypes(methodGetConvByName.method.returnType, String::class.java)
//        }
//    }
    internal val methodChatroomStorageGetMemberCount by dexMethod {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.ChatroomStorage", "[getMemberCount] cost:%sms")
        }
    }
    private val classChatroomMember by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("MicroMsg.ChatRoomMember", "service is null")
        }
    }
    private val methodSetDnd by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.OpenImOpLogLogic", "OpenImOpLogLogic OpenIMModContactMuteOplog username:%s switch add")
        }
    }
    private val methodSetNoDnd by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.OpenImOpLogLogic", "OpenImOpLogLogic OpenIMModContactMuteOplog username:%s switch cancel")
        }
    }

    // RoomServiceFactory.get(roomId) (`ed0.e.hj` on 8.0.76) -> the room service for that room's
    // suffix (`@chatroom`, `@im.chatroom`, ...), which builds the room oplog operations. Anchored
    // on the log it emits for a room id without a suffix.
    private val methodGetRoomService by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("MicroMsg.RoomServiceFactory", "get NotNullChatRoom %s")
        }
    }

    // RoomOpLogCallbackFactory.request() (`com.tencent.mm.roomsdk.model.factory.f.b`) — hands the
    // operation built by the room service to the oplog queue, i.e. actually sends it. 8.0.69+ logs
    // "request oplog with result %s" and older builds "request oplog %s", so match the prefix.
    private val methodRoomOpLogRequest by dexMethod(allowFailure = true) {
        matcher {
            usingStrings("MicroMsg.RoomCallbackFactory", "request oplog")
            paramCount = 0
        }
    }

    // ConversationStorage.M(String) / isPlacedTop — returns true when the conversation's flag
    // has the "placed top" high bits set. Anchored by the string it logs on a null/empty talker.
    private val methodIsPlacedTop by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classConversationStorage.data.name)
            usingStrings("MicroMsg.ConversationStorage", "isPlacedTop failed")
            paramCount = 1
            paramTypes(String::class.java)
            returnType(Boolean::class.javaPrimitiveType!!)
        }
    }

    // ConversationStorage.U(String) / setPlacedTop — sets the high bits in rconversation.flag
    // that WeChat uses to sort pinned conversations to the top.
    private val methodSetPlacedTop by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classConversationStorage.data.name)
            usingStrings("MicroMsg.ConversationStorage", "setPlacedTop conversation failed")
            paramCount = 1
            paramTypes(String::class.java)
            returnType(Boolean::class.javaPrimitiveType!!)
        }
    }

    // ConversationStorage.X(String) / unSetPlacedTop — clears the high bits, unpinning the row.
    private val methodUnSetPlacedTop by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classConversationStorage.data.name)
            usingStrings("MicroMsg.ConversationStorage", "unSetPlacedTop conversation failed")
            paramCount = 1
            paramTypes(String::class.java)
            returnType(Boolean::class.javaPrimitiveType!!)
        }
    }

    // ContactStorageLogic.toModContactOplog(z3) -> the `tn4` modContact proto WeChat sends as
    // oplog cmd 2 to sync a contact's flags (mute/top/...) to the server. Reusing WeChat's own
    // builder guarantees the ~80-field proto (including BitMask/BitVal and remark) is byte-perfect.
    private val methodBuildModContactOplog by dexMethod {
        matcher {
            usingEqStrings(
                "MicroMsg.ContactStorageLogic",
                "oplog modContact user:%s remark:%s BitVal:%d BitValue2:%s isInConvBox:%s isTop:%s isMute:%s"
            )
        }
    }
    val methodNotifyConversationChanged by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classConversationStorage.data.superClass!!.name)
            paramCount = 3
            paramTypes("int", classConversationStorage.data.superClass!!.name, "java.lang.Object")
            returnType(Void.TYPE)
        }
    }

    // ConversationStorage.p(String) -> com.tencent.mm.storage.m3 (the rconversation model for a
    // talker), or null. Needed to hand the conversation object to the native delete helper below.
    private val methodGetConversationByTalker by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classConversationStorage.data.name)
            usingStrings("MicroMsg.ConversationStorage", "get null with username:")
            paramCount = 1
            paramTypes(String::class.java)
        }
    }

    // com.tencent.mm.ui.conversation.s1#d(context, talker, m3, PBool, ProgressDialog, boolean) =
    // doDeleteConv: the work WeChat runs AFTER the user taps 删除 in the "删除该聊天" confirm dialog
    // (both the normal-contact and group confirm handlers call it). Invoking it directly performs a
    // permanent delete with NO confirmation dialog. Its progress callback null-checks the PBool and
    // ProgressDialog, so nulls are safe; the trailing boolean starts a tablet/split-screen
    // EmptyActivity, so we pass false. Anchored by two in-method log strings unique to s1.d.
    private val methodDoDeleteConversation by dexMethod {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingStrings("MicroMsg.ConvDelLogic", "oplog modContact user:%s")
            paramCount = 6
        }
    }
//    private var ecsGiftMsgService: Any? = null

    val conversationStorage by lazy {
        WeServiceApi.storageFeatureService.reflekt()
            .firstMethod {
                returnType = classConversationStorage.clazz
            }
            .invoke()!!
    }

    val chatroomStorage by lazy {
        WeServiceApi.chatroomService.reflekt()
            .firstMethod {
                returnType = methodChatroomStorageGetMemberCount.method.declaringClass
            }
            .invoke()!!
    }

    // this is NOT group 'member'
    // this is the group itself
    fun getGroup(groupId: String): Any {
        return chatroomStorage.reflekt()
            .firstMethod {
                parameters(String::class)
                returnType = classChatroomMember.clazz
            }
            .invoke(groupId)!!
    }

    fun markAllAsRead() {
        val cursor = WeDatabaseApi.rawQuery("SELECT username FROM rconversation WHERE unReadCount>0 OR unReadMuteCount>0 OR atCount>0")
        while (cursor.moveToNext()) {
            val talker = cursor.getString(0)
            try {
                methodUpdateUnreadByTalker.method.invoke(conversationStorage, talker)
                WeLogger.d(TAG, "marked $talker as read")
            } catch (ex: Exception) {
                WeLogger.w(TAG, "exception while updating unread count for $talker", ex)
            }
        }
        cursor.close()
    }

//    fun markAllAsRead() {
//        val talkers = LinkedHashSet<String>()
//        val cursor = WeDatabaseApi.rawQuery("SELECT username, parentRef FROM rconversation")
//        cursor.use { cursor ->
//            while (cursor.moveToNext()) {
//                val talker = cursor.getString(0)
//                if (!talker.isNullOrEmpty()) {
//                    talkers += talker
//                }
//                val parentRef = cursor.getString(1)
//                if (!parentRef.isNullOrEmpty()) {
//                    talkers += parentRef
//                }
//            }
//        }
//
//        for (talker in talkers) {
//            try {
//                clearConversationUnreadState(talker)
//            } catch (ex: Exception) {
//                WeLogger.w(TAG, "exception while updating unread count for $talker", ex)
//            }
//        }
//
//        clearAllConversationRedPacketMarkFields()
//        reloadConversations()
//    }

    //    fun markAsRead(talker: String) {
//        try {
//            clearConversationUnreadState(talker)
//        } catch (ex: Exception) {
//            WeLogger.w(TAG, "exception while updating unread count for $talker", ex)
//        }
//    }

    fun markAsRead(talker: String) {
        try {
            methodUpdateUnreadByTalker.method.invoke(conversationStorage, talker)
            WeLogger.d(TAG, "marked $talker as read")
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while updating unread count for $talker", ex)
        }
    }

    /**
     * Remove a conversation from the home-screen list, the way WeChat's native "不显示该聊天" does:
     * this deletes the `rconversation` row through the host's cache-aware storage wrapper and
     * notifies the conversation-list observers, so the change is reflected immediately without a
     * restart. Chat messages are NOT touched.
     *
     * The observer notification is dispatched synchronously on the calling thread and mutates the
     * list adapters, so this MUST be called on the main thread (see [reloadConversations]).
     *
     * @return true if the native delete was invoked without throwing.
     */
    fun hideConversation(talker: String): Boolean {
        return try {
            methodDelChatContact.method.invoke(conversationStorage, talker)
            true
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while deleting conversation for $talker", ex)
            false
        }
    }

    /** The rconversation model ([com.tencent.mm.storage.m3]) for [talker], or null if none exists. */
    fun getConversationByTalker(talker: String): Any? {
        if (methodGetConversationByTalker.isPlaceholder) return null
        return try {
            methodGetConversationByTalker.method.invoke(conversationStorage, talker)
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while fetching conversation for $talker", ex)
            null
        }
    }

    /**
     * Permanently delete a conversation the way WeChat's "删除该聊天" menu does AFTER the user
     * confirms — i.e. delete the conversation AND its chat messages, syncing the removal to the
     * server. Unlike [hideConversation] (which only hides the row, "不显示该聊天"), this is
     * irreversible. No confirmation dialog is shown.
     *
     * Invokes the host's post-confirm delete worker (s1.d / doDeleteConv). Runs on the main thread
     * because it notifies the conversation-list observers synchronously (see [reloadConversations]).
     *
     * @return true if the native delete was invoked without throwing (false if unavailable on this
     *   build or the call threw).
     */
    fun deleteConversation(talker: String, conversation: Any? = getConversationByTalker(talker)): Boolean {
        if (methodDoDeleteConversation.isPlaceholder) {
            WeLogger.w(TAG, "permanent delete unavailable on this build for $talker")
            return false
        }
        return try {
            // s1.d(context, talker, m3, pBool=null, progressDialog=null, startEmptyActivity=false)
            methodDoDeleteConversation.method.invoke(
                null,
                HostInfo.application,
                talker,
                conversation,
                null,
                null,
                false
            )
            true
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while permanently deleting conversation for $talker", ex)
            false
        }
    }

//    private fun clearConversationUnreadState(talker: String) {
//        try {
//            methodUpdateUnreadByTalker.method.invoke(conversationStorage, talker)
//        } catch (ex: Exception) {
//            WeLogger.w(TAG, "exception while invoking native mark read for $talker", ex)
//        }
//        clearEcsGiftRedLabelViaOfficialService(talker)
//        clearExternalConversationRedHints(talker)
//        notifyConversationChanged(talker)
//        WeLogger.d(TAG, "marked $talker as read")
//    }

//    private fun clearEcsGiftRedLabelViaOfficialService(talker: String): Boolean {
//        return try {
//            val method = resolvedClearEcsGiftRedLabelMethod() ?: return false
//            val service = getEcsGiftMsgService(method) ?: return false
//            method.invoke(service, talker)
//            true
//        } catch (ex: Exception) {
//            WeLogger.w(TAG, "exception while invoking official ecs gift red-label clear for $talker", ex)
//            false
//        }
//    }
//
//    private fun clearExternalConversationRedHints(talker: String) {
//        try {
//            WeDatabaseApi.execStatement(
//                "UPDATE $TABLE_ECS_CONVERSATION_RECORD SET ecsUnhandledGiftCount=0, ecsGiftRedLabelType=0 WHERE talker=?",
//                arrayOf<Any>(talker)
//            )
//        } catch (ex: Exception) {
//            WeLogger.w(TAG, "exception while clearing external red hints for $talker", ex)
//        }
//    }
//
//    private fun clearAllConversationRedPacketMarkFields() {
//        try {
//            WeDatabaseApi.execStatement(
//                "UPDATE $TABLE_RCONVERSATION SET hbMarkRed=0, remitMarkRed=0, attrflag=(attrflag & ${unreadClearAttrFlagMask()}) WHERE hbMarkRed<>0 OR remitMarkRed<>0"
//            )
//        } catch (ex: Exception) {
//            WeLogger.w(TAG, "exception while clearing all red-packet mark fields", ex)
//        }
//    }
//
//    private fun unreadClearAttrFlagMask(): Int {
//        return (ATTR_FLAG_COMMON_RED_BITS or ATTR_FLAG_8071_8072_RED_PACKET_BITS).inv()
//    }
//
//    private fun resolvedClearEcsGiftRedLabelMethod(): Method? {
//        if (methodClearEcsGiftRedLabel.isPlaceholder) return null
//        val method = methodClearEcsGiftRedLabel.method
//        if (method.run {
//                returnType == Void.TYPE &&
//                        parameterCount == 1 &&
//                        parameterTypes[0] == String::class.java
//            }) return method
//        WeLogger.w(
//            TAG,
//            "ignore invalid official ecs gift red-label clear method: ${method.declaringClass.name}.${method.name}, params=${method.parameterCount}"
//        )
//        return null
//    }
//
//    private fun getEcsGiftMsgService(method: Method): Any? {
//        ecsGiftMsgService?.let { return it }
//        val concreteClass = method.declaringClass
//        for (serviceInterface in concreteClass.interfaces) {
//            val service = runCatching {
//                WeServiceApi.getServiceImplByClass(serviceInterface)
//            }.getOrNull()
//            if (service != null && concreteClass.isInstance(service)) {
//                return service.also { ecsGiftMsgService = it }
//            }
//        }
//        return null
//    }

    fun reloadConversations() {
        // notifyConversationChanged dispatches to WeChat's conversation-list UI observers.
        // WeChat's MStorage dispatcher (s85.v0.e) iterates and invokes some listeners
        // synchronously on the calling thread, so calling this off the main thread mutates
        // list adapters from a background thread and triggers ListView's
        // "content of the adapter has changed but ListView did not receive a notification"
        // crash. Callers like AggregateChats' folder-refresh run on a worker thread, so
        // always marshal the notify onto the main thread.
        runOnUiThread {
            try {
                notifyConversationChanged("", 5)
            } catch (ex: Exception) {
                WeLogger.w(TAG, "exception while notifying conversation list reload", ex)
            }
        }
    }

    private fun notifyConversationChanged(talker: String, eventType: Int = 3) {
        if (methodNotifyConversationChanged.isPlaceholder) return
        methodNotifyConversationChanged.method.invoke(conversationStorage, eventType, conversationStorage, talker)
    }

    fun setConversationsVisibility(visible: Boolean, talkers: Array<String>) {
        val operation = if (visible) "" else "hidden_conv_parent"
        if (methodHiddenConvParent.method.parameterCount == 4) {
            methodHiddenConvParent.method.invoke(
                conversationStorage,
                talkers,
                operation,
                true,
                false
            )
        } else {
            methodHiddenConvParent.method.invoke(
                conversationStorage,
                talkers,
                operation
            )
        }
    }

    fun setAllConversationVisibility(visible: Boolean) {
        val cursor = WeDatabaseApi.rawQuery("SELECT username FROM rconversation")
        val talkers = mutableListOf<String>()
        while (cursor.moveToNext()) {
            talkers += cursor.getString(0)
        }
        cursor.close()
        setConversationsVisibility(visible, talkers.toTypedArray())
    }

//    fun onlyShowFilteredConversations(queryFilter: String, selectedColumns: String = "username") {
//        setAllConversationVisibility(false)
//        setFilteredConversationsVisibility(true, queryFilter, selectedColumns)
//    }

//    fun setFilteredConversationsVisibility(visible: Boolean, queryFilter: String, selectedColumns: String = "username") {
//        val cursor = WeDatabaseApi.rawQuery("SELECT $selectedColumns FROM rconversation $queryFilter")
//        val talkers = mutableListOf<String>()
//        while (cursor.moveToNext()) {
//            talkers += cursor.getString(0)
//        }
//        cursor.close()
//        setConversationsVisibility(visible, talkers.toTypedArray())
//    }

    /** ContactStorageLogic (`e2`), the class that owns the DND setters and the modContact builder. */
    private val contactStorageLogic: Class<*> by lazy { methodSetDnd.method.declaringClass }

    /**
     * Toggle "消息免打扰" for [convId] and sync it to the server.
     *
     * Group chats and everyone else are muted through completely different server operations, so
     * this dispatches on the talker — see [setChatRoomDnd] for why the contact path cannot be used
     * for a group.
     */
    fun setDnd(convId: String, dnd: Boolean) {
        if (convId.isGroupChatWxId) {
            setChatRoomDnd(convId, dnd)
            return
        }
        val stub = methodSetDnd.method.parameterTypes[0].createInstance(convId)
        if (dnd) {
            methodSetDnd.method.invoke(null, stub, true)
        } else {
            methodSetNoDnd.method.invoke(null, stub, true)
        }
    }

    /**
     * Mute / unmute a **group chat** by sending the `OpModChatRoomNotify` oplog (op 20), which is
     * what WeChat's own ChatroomInfoUI switch does.
     *
     * A group's mute state is *not* the contact's mute bit: the server keeps it as the room's
     * ChatRoomNotify flag (`0` = muted, see [isDnd]) and only mirrors it into `rcontact.type & 512`
     * when it pushes the room contact back down. Running a group through the contact path
     * ([methodSetDnd]) therefore flips the local `512` bit — so the row does turn muted for a
     * moment — but the modContact oplog it sends is meaningless for a room, and the next contact
     * push from the server resets the bit, which is the "reverts after a few seconds" symptom.
     *
     * Like the native switch, this only sends the operation; the local contact (and with it the
     * conversation-list mute icon) is updated when the server pushes the room contact back.
     *
     * Both `@chatroom` and OpenIM `@im.chatroom` rooms are covered: the room service is picked by
     * the room id's suffix, and each implementation builds its own flavour of the operation.
     */
    private fun setChatRoomDnd(roomId: String, dnd: Boolean) {
        if (methodGetRoomService.isPlaceholder || methodRoomOpLogRequest.isPlaceholder) {
            WeLogger.w(TAG, "room notify oplog unavailable on this build, cannot set dnd for $roomId")
            return
        }
        try {
            val serviceFactory = methodGetRoomService.method.declaringClass
            val roomService = methodGetRoomService.method
                .invoke(WeServiceApi.getServiceByClass(serviceFactory.interfaces[0]), roomId)!!
            // RoomService.modChatRoomNotify(roomId, notifyMsg, defaultNeedPushFlag): notifyMsg is
            // the ChatRoomNotify value to set (1 = notify, 0 = muted). The push flag selects which
            // messages still notify while muted; 0 is the value 8.0.65 hardcodes and the default of
            // the `clicfg_chatroom_mute_refine_default` config newer builds read it from.
            val operation = roomService.reflekt()
                .firstMethod {
                    parameters(BString, int, int)
                    returnType(methodRoomOpLogRequest.method.declaringClass.superclass!!)
                }
                .invoke(roomId, if (dnd) 0 else 1, 0)
            // An unknown room suffix yields WeChat's no-op room service, whose operation object is
            // not the oplog-backed one — sending it is impossible, and silently doing nothing here
            // would look exactly like the bug this replaced.
            if (operation == null || !methodRoomOpLogRequest.method.declaringClass.isInstance(operation)) {
                WeLogger.w(TAG, "no room oplog service for $roomId, dnd=$dnd not applied")
                return
            }
            methodRoomOpLogRequest.method.invoke(operation)
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while setting chat room dnd=$dnd for $roomId", ex)
        }
    }

    /**
     * Returns true when [talker]'s conversation is pinned (「置顶」) in the homepage list.
     * Reads from WeChat's ConversationStorage cache — safe to call on the UI thread.
     */
    fun isPinned(talker: String): Boolean {
        if (methodIsPlacedTop.isPlaceholder) return false
        return try {
            methodIsPlacedTop.method.invoke(conversationStorage, talker) as? Boolean ?: false
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while checking isPlacedTop for $talker", ex)
            false
        }
    }

    /**
     * Pin ([top] = true) or unpin ([top] = false) [talker]'s conversation.
     * Calls WeChat's native setPlacedTop / unSetPlacedTop which writes the high bits in
     * rconversation.flag and notifies conversation-list observers.
     */
    fun setPinned(talker: String, top: Boolean) {
        val method = if (top) methodSetPlacedTop else methodUnSetPlacedTop
        if (method.isPlaceholder) {
            WeLogger.w(TAG, "setPlacedTop unavailable on this build for $talker")
            return
        }
        try {
            method.method.invoke(conversationStorage, talker)
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while setting placedTop=$top for $talker", ex)
        }
    }

    /**
     * Returns true when [talker]'s conversation is muted (「消息免打扰」).
     *
     * Mirrors WeChat's per-conversation mute decision (w3.b / c01.e2):
     * - **Group chats** (`@chatroom`): muted when the ChatRoomNotify flag (z3.T) in the lvbuff
     *   blob is 0. The flag has no column and must be parsed from the LV-encoded blob.
     * - **Everyone else**: muted when rcontact.type has bit 512 set.
     */
    fun isDnd(talker: String): Boolean {
        return try {
            val cursor = WeDatabaseApi.rawQuery(
                "SELECT type,lvbuff FROM rcontact WHERE username=? LIMIT 1",
                arrayOf(talker)
            )
            cursor.use { c ->
                if (!c.moveToFirst()) return@use false
                if (talker.isGroupChatWxId) {
                    val lvbuff = if (!c.isNull(1)) c.getBlob(1) else null
                    parseChatRoomNotify(lvbuff) == 0
                } else {
                    if (!c.isNull(0)) c.getInt(0) and 512 != 0 else false
                }
            }
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while checking isDnd for $talker", ex)
            false
        }
    }

    /**
     * Extracts the ChatRoomNotify flag (field z3.T) from an rcontact lvbuff blob. The blob is
     * WeChat's LV format (com.tencent.mm.sdk.platformtools.e2): a 0x7B header byte, then a fixed
     * sequence of length-value fields — int, int, str, long, int, str, str, int, int, str, str,
     * int(T)... Strings are big-endian short-length prefixed. T is the 12th field. Returns the
     * flag, or null when the blob is missing / malformed (caller treats null as notify-on).
     */
    fun parseChatRoomNotify(lvbuff: ByteArray?): Int? {
        if (lvbuff == null || lvbuff.size < 2 || lvbuff[0].toInt() != 0x7B) return null
        return runCatching {
            val buf = ByteBuffer.wrap(lvbuff).order(ByteOrder.BIG_ENDIAN)
            buf.position(1) // skip 0x7B header
            fun skipStr() {
                val len = buf.short.toInt() and 0xFFFF
                buf.position(buf.position() + len)
            }
            buf.int          // H
            buf.int          // I
            skipStr()        // J
            buf.long         // K
            buf.int          // L
            skipStr()        // M
            skipStr()        // N
            buf.int          // P
            buf.int          // Q
            skipStr()        // R
            skipStr()        // S
            buf.int          // T (ChatRoomNotify)
        }.getOrNull()
    }

    /** Fetch the fully-populated contact ([com.tencent.mm.storage.z3]) for [convId], or null. */
    private fun fetchContact(convId: String): Any {
        return contactStorageLogic.reflekt()
            .firstMethod {
                modifiers = Modifier.STATIC
                parameters(String::class.java)
                returnType(methodSetDnd.method.parameterTypes[0])
            }.invoke(null, convId)!!
    }
}
