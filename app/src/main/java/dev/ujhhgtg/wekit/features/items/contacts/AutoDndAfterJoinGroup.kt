package dev.ujhhgtg.wekit.features.items.contacts

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.ChatroomSyncStateReadResult
import dev.ujhhgtg.wekit.features.api.core.models.WeChatroomSyncState
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.LinkedHashMap
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object AutoDndAfterJoinGroup : SwitchFeature() {
    override val technicalId = "加入群聊自动免打扰"
    override val nameRes = R.string.feature_auto_dnd_after_join_group_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_auto_dnd_after_join_group_description

    private const val TAG = "AutoDndAfterJoinGroup"
    private const val MAX_DEDUP_KEYS = 256

    private val stateLock = Any()
    private val dedupKeys = LinkedHashMap<String, Unit>(MAX_DEDUP_KEYS, 0.75f, true)
    private var observedSelfWxId: String? = null
    private var scope = newScope()

    private data class SyncSnapshot(
        val roomId: String,
        val oldState: ChatroomSyncStateReadResult,
        val selfWxId: String,
    )

    private val methodSyncChatroomMembers by dexMethodFrom {
        val matches =
            DexResolutionContext.findMethods {
                    returnType = "boolean"
                    paramCount = 10..11
                    usingString("MicroMsg.ChatroomMembersLogic")
                    usingString("SyncAddChatroomMember")
                }
                .filter { method ->
                    val params = method.paramTypeNames
                    params[0] == "java.lang.String" &&
                        params[1] == "java.lang.String" &&
                        params[3] == "int" &&
                        params[4] == "int" &&
                        params[5] == "int" &&
                        params[6] == "java.lang.String" &&
                        params[8] == "boolean" &&
                        params[9] == "boolean" &&
                        (params.size == 10 || params[10] == "int") &&
                        params[2] !in PRIMITIVE_TYPE_NAMES &&
                        params[7] !in PRIMITIVE_TYPE_NAMES
                }

        check(matches.size == 1) {
            "expected one ChatroomMembersLogic sync method, found ${matches.size}: " +
                matches.joinToString { it.descriptor }
        }
        matches.single()
    }

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()

        methodSyncChatroomMembers.hook {
            before {
                val roomId = args[0] as String
                if (!roomId.isSupportedChatroomId()) return@before

                val selfWxId = WeApi.selfWxId
                if (selfWxId.isEmpty()) return@before

                val snapshot =
                    SyncSnapshot(
                        roomId,
                        WeDatabaseApi.getChatroomSyncState(roomId),
                        selfWxId,
                    )
                synchronized(stateLock) {
                    observeAccount(selfWxId)
                }
                extra = snapshot
            }

            after {
                val selfWxId = WeApi.selfWxId
                val pendingSnapshot = extra as? SyncSnapshot ?: return@after
                val snapshot =
                    synchronized(stateLock) {
                        if (selfWxId != pendingSnapshot.selfWxId) {
                            observeAccount(selfWxId)
                            return@after
                        }
                        observeAccount(selfWxId)
                        pendingSnapshot
                    }
                if (throwable != null) return@after

                val oldState =
                    when (snapshot.oldState) {
                        ChatroomSyncStateReadResult.MissingRow -> {
                            null
                        }

                        is ChatroomSyncStateReadResult.Available -> {
                            snapshot.oldState.state
                        }

                        ChatroomSyncStateReadResult.Unavailable -> {
                            WeLogger.d(
                                TAG,
                                "skip unavailable pre-sync state for ${snapshot.roomId}",
                            )
                            return@after
                        }
                    }
                val newState =
                    when (val result = WeDatabaseApi.getChatroomSyncState(snapshot.roomId)) {
                        is ChatroomSyncStateReadResult.Available -> {
                            result.state
                        }

                        ChatroomSyncStateReadResult.MissingRow -> {
                            WeLogger.d(
                                TAG,
                                "skip missing post-sync state for ${snapshot.roomId}",
                            )
                            return@after
                        }

                        ChatroomSyncStateReadResult.Unavailable -> {
                            WeLogger.d(
                                TAG,
                                "skip unavailable post-sync state for ${snapshot.roomId}",
                            )
                            return@after
                        }
                    }

                if (!shouldMuteJoinedGroup(oldState, newState, snapshot.selfWxId)) return@after

                submitDnd(snapshot.roomId, newState, snapshot.selfWxId)
            }
        }
    }

    override fun onDisable() {
        scope.cancel()
        synchronized(stateLock) {
            dedupKeys.clear()
            observedSelfWxId = null
        }
    }

    private fun submitDnd(roomId: String, state: WeChatroomSyncState, selfWxId: String) {
        val key = dedupKey(state)
        if (!markDedupKey(key)) {
            WeLogger.d(
                TAG,
                "skip duplicate DND room=$roomId key=$key version=${state.memberVersion}",
            )
            return
        }

        scope.launch {
            try {
                if (WeApi.selfWxId != selfWxId) {
                    WeLogger.d(TAG, "skip stale DND room=$roomId key=$key")
                    return@launch
                }
                if (WeConversationApi.isDnd(roomId)) {
                    WeLogger.d(
                        TAG,
                        "skip already-muted room=$roomId key=$key version=${state.memberVersion}",
                    )
                    return@launch
                }
                WeConversationApi.setDnd(roomId, true)
                WeLogger.i(
                    TAG,
                    "submitted DND room=$roomId key=$key version=${state.memberVersion}",
                )
            } catch (e: Exception) {
                WeLogger.w(
                    TAG,
                    "DND submission failed room=$roomId key=$key version=${state.memberVersion}",
                    e,
                )
            }
        }
    }

    private fun observeAccount(selfWxId: String) {
        if (observedSelfWxId != null && observedSelfWxId != selfWxId) {
            dedupKeys.clear()
        }
        observedSelfWxId = selfWxId
    }

    private fun markDedupKey(key: String): Boolean =
        synchronized(stateLock) {
            if (dedupKeys.containsKey(key)) return@synchronized false
            if (dedupKeys.size >= MAX_DEDUP_KEYS) {
                dedupKeys.entries.iterator().run {
                    next()
                    remove()
                }
            }
            dedupKeys[key] = Unit
            true
        }

    private fun String.isSupportedChatroomId(): Boolean {
        val lowerCaseId = lowercase(Locale.ROOT)
        return lowerCaseId.endsWith("@chatroom") || lowerCaseId.endsWith("@im.chatroom")
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val PRIMITIVE_TYPE_NAMES =
        setOf(
            "boolean",
            "byte",
            "char",
            "double",
            "float",
            "int",
            "long",
            "short",
            "void",
            "java.lang.String",
        )
}
