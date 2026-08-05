package dev.ujhhgtg.wekit.features.items.contacts

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.WeChatroomSyncState
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HookCallback
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookDirectly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.util.IdentityHashMap
import java.util.LinkedHashMap
import java.util.Locale

@Feature(
    name = "加入群聊自动免打扰",
    categories = ["联系人与群组"],
    description = "加入新的群聊后自动开启消息免打扰",
)
object AutoDndAfterJoinGroup : SwitchFeature(), IResolveDex {

    private const val TAG = "AutoDndAfterJoinGroup"
    private const val MAX_SNAPSHOTS = 128
    private const val MAX_DEDUP_KEYS = 256

    private val methodSyncChatroomMembers by dexMethod()
    private val stateLock = Any()
    private val snapshots = IdentityHashMap<HookParam, SyncSnapshot>()
    private val dedupKeys = LinkedHashMap<String, Unit>(MAX_DEDUP_KEYS, 0.75f, true)
    private var scope = newScope()

    private data class SyncSnapshot(
        val roomId: String,
        val oldState: WeChatroomSyncState?,
    )

    override fun resolveDex(dexKit: DexKitBridge) {
        val parameterCount = if (DexResolutionContext.host.versionName == "8.0.65") 10 else 11
        val matches = dexKit.findMethod {
            matcher {
                returnType = "boolean"
                paramCount = parameterCount
                usingStrings("MicroMsg.ChatroomMembersLogic", "SyncAddChatroomMember")
            }
        }.filter { method ->
            val params = method.paramTypeNames
            params[0] == "java.lang.String" &&
                params[1] == "java.lang.String" &&
                params[3] == "int" &&
                params[4] == "int" &&
                params[5] == "int" &&
                params[6] == "java.lang.String" &&
                params[8] == "boolean" &&
                params[9] == "boolean" &&
                (parameterCount == 10 || params[10] == "int") &&
                params[2] !in PRIMITIVE_TYPE_NAMES &&
                params[7] !in PRIMITIVE_TYPE_NAMES
        }

        check(matches.size == 1) {
            "expected one ChatroomMembersLogic sync method for ${DexResolutionContext.host.versionName}, found ${matches.size}: " +
                matches.joinToString { it.descriptor }
        }
        methodSyncChatroomMembers.setDescriptor(matches.single())
    }

    override fun onEnable() {
        if (!scope.coroutineContext[Job]!!.isActive) scope = newScope()

        registerUnhook(methodSyncChatroomMembers.method.hookDirectly(object : HookCallback() {
            override fun beforeHookedMethod(param: HookParam) {
                val roomId = param.args[0] as String
                if (!roomId.isSupportedChatroomId()) return

                val snapshot = SyncSnapshot(roomId, WeDatabaseApi.getChatroomSyncState(roomId))
                synchronized(stateLock) {
                    if (snapshots.size >= MAX_SNAPSHOTS) snapshots.entries.iterator().run {
                        next()
                        remove()
                    }
                    snapshots[param] = snapshot
                }
            }

            override fun afterHookedMethod(param: HookParam) {
                val snapshot = synchronized(stateLock) { snapshots.remove(param) } ?: return
                if (param.throwable != null) return

                val newState = WeDatabaseApi.getChatroomSyncState(snapshot.roomId)
                if (newState == null || newState.memberIds.isEmpty()) {
                    WeLogger.d(TAG, "skip unavailable or incomplete sync state for ${snapshot.roomId}")
                    return
                }

                val selfWxId = WeApi.selfWxId
                if (selfWxId.isEmpty() || !shouldMuteJoinedGroup(snapshot.oldState, newState, selfWxId)) return

                submitDnd(snapshot.roomId, newState)
            }
        }))
    }

    override fun onDisable() {
        scope.cancel()
        synchronized(stateLock) {
            snapshots.clear()
            dedupKeys.clear()
        }
    }

    private fun submitDnd(roomId: String, state: WeChatroomSyncState) {
        val key = dedupKey(state)
        if (!markDedupKey(key)) {
            WeLogger.d(TAG, "skip duplicate DND room=$roomId key=$key version=${state.memberVersion}")
            return
        }

        scope.launch {
            try {
                if (WeConversationApi.isDnd(roomId)) {
                    WeLogger.d(TAG, "skip already-muted room=$roomId key=$key version=${state.memberVersion}")
                    return@launch
                }
                WeConversationApi.setDnd(roomId, true)
                WeLogger.i(TAG, "submitted DND room=$roomId key=$key version=${state.memberVersion}")
            } catch (e: Exception) {
                WeLogger.w(TAG, "DND submission failed room=$roomId key=$key version=${state.memberVersion}", e)
            }
        }
    }

    private fun markDedupKey(key: String): Boolean = synchronized(stateLock) {
        if (dedupKeys.containsKey(key)) return@synchronized false
        if (dedupKeys.size >= MAX_DEDUP_KEYS) dedupKeys.entries.iterator().run {
            next()
            remove()
        }
        dedupKeys[key] = Unit
        true
    }

    private fun String.isSupportedChatroomId(): Boolean {
        val lowerCaseId = lowercase(Locale.ROOT)
        return lowerCaseId.endsWith("@chatroom") || lowerCaseId.endsWith("@im.chatroom")
    }

    private fun newScope() = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val PRIMITIVE_TYPE_NAMES = setOf(
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
