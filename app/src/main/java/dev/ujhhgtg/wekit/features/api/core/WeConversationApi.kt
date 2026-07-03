package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.runOnUiThread

@Feature(name = "对话服务", categories = ["API"], description = "提供对话管理能力")
object WeConversationApi : ApiFeature(), IResolveDex {

    private val TAG = nameOf(WeConversationApi)

    // Clears 4096, 1048576, 16777216 and 33554432, which drive 8071/8072 red prefixes
//    private const val ATTR_FLAG_COMMON_RED_BITS = 51384320
//    private const val ATTR_FLAG_8071_8072_RED_PACKET_BITS = 33280
//    private const val TABLE_RCONVERSATION = "rconversation"
//    private const val TABLE_ECS_CONVERSATION_RECORD = "EcsConversationRecord"
    private val classConversationStorage by dexClass {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingEqStrings("rconversation", "PRAGMA table_info( rconversation)")
        }
    }
    private val methodUpdateUnreadByTalker by dexMethod {
        matcher {
            declaredClass(classConversationStorage.clazz)
            usingEqStrings("MicroMsg.ConversationStorage", "updateUnreadByTalker %s")
        }
    }

    // ConversationStorage.k(String) aka `delChatContact`: deletes the rconversation row through the
    // cache-aware storage wrapper and notifies list observers, which is the core of WeChat's native
    // "不显示该聊天" for a normal contact.
    private val methodDelChatContact by dexMethod {
        matcher {
            declaredClass(classConversationStorage.clazz)
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
            declaredClass(classConversationStorage.clazz)
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
    private val methodChatroomStorageGetMemberCount by dexMethod {
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
    private val methodNotifyConversationChanged by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classConversationStorage.clazz.superclass!!)
            paramCount = 3
            paramTypes("int", classConversationStorage.clazz.superclass!!.name, "java.lang.Object")
            returnType(Void.TYPE)
        }
    }

    // ConversationStorage.p(String) -> com.tencent.mm.storage.m3 (the rconversation model for a
    // talker), or null. Needed to hand the conversation object to the native delete helper below.
    private val methodGetConversationByTalker by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classConversationStorage.clazz)
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
    private val methodDoDeleteConversation by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            usingStrings("oplog modContact user:%s", "oplog modContact user:%s remark:%s type:%d ")
            paramCount = 6
            returnType(Void.TYPE)
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

    fun onlyShowFilteredConversations(queryFilter: String, selectedColumns: String = "username") {
        setAllConversationVisibility(false)
        setFilteredConversationsVisibility(true, queryFilter, selectedColumns)
    }

    fun setFilteredConversationsVisibility(visible: Boolean, queryFilter: String, selectedColumns: String = "username") {
        val cursor = WeDatabaseApi.rawQuery("SELECT $selectedColumns FROM rconversation $queryFilter")
        val talkers = mutableListOf<String>()
        while (cursor.moveToNext()) {
            talkers += cursor.getString(0)
        }
        cursor.close()
        setConversationsVisibility(visible, talkers.toTypedArray())
    }

    private lateinit var contactType: Class<*>

    // TODO: this only updates local DB without syncing to server
    fun setIfNotifyNewMessages(convId: String, shouldNotify: Boolean) {
        if (!::contactType.isInitialized) {
            contactType = methodSetDnd.method.parameterTypes[0]
        }

        val contact = contactType.createInstance(convId)
        if (!shouldNotify) {
            methodSetDnd.method.invoke(null, contact, true)
        } else {
            methodSetNoDnd.method.invoke(null, contact, true)
        }
    }
}
