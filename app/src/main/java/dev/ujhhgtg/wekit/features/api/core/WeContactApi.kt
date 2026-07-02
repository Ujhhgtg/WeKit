package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BlockContactProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.DelContactProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.OpLog
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.UserNameProto
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Feature(name = "联系人服务", categories = ["API"], description = "提供联系人管理能力")
object WeContactApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeContactApi"

    /** How aggressively [deleteContact] should remove a contact. */
    enum class DeleteMode {
        /** Remove the contact only. */
        DELETE_ONLY,

        /** Block the contact (add to blacklist), then remove it. */
        BLOCK_AND_DELETE
    }

    /**
     * Delete (and optionally block) a contact via the `oplog` CGI.
     *
     * Modern WeChat has no standalone `deletecontact` CGI; contact removal is funneled through
     * the generic oplog endpoint as [OpLog.CMD_DELETE_CONTACT] (and [OpLog.CMD_BLOCK_CONTACT]
     * for blocking). [DeleteMode.BLOCK_AND_DELETE] packs both operations into a single oplog request.
     *
     * Suspends until the server responds, returning `true` on success and `false` on failure.
     * Callers that delete in bulk should space out invocations themselves, as WeChat's server
     * rate-limits these requests.
     */
    suspend fun deleteContact(wxId: String, mode: DeleteMode = DeleteMode.DELETE_ONLY): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val operations = buildList {
                    if (mode == DeleteMode.BLOCK_AND_DELETE) {
                        add(OpLog.operation(OpLog.CMD_BLOCK_CONTACT, BlockContactProto(UserNameProto(wxId))))
                    }
                    add(OpLog.operation(OpLog.CMD_DELETE_CONTACT, DelContactProto(UserNameProto(wxId))))
                }

                WePacketHelper.sendCgiRaw(
                    "/cgi-bin/micromsg-bin/oplog", 681, 0, 0, OpLog.encodeRequest(operations)
                ) {
                    onSuccess { _ -> if (cont.isActive) cont.resume(true) }
                    onFailure { errType, errCode, errMsg ->
                        WeLogger.w(TAG, "deleteContact $wxId failed: $errType, $errCode, $errMsg")
                        if (cont.isActive) cont.resume(false)
                    }
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "deleteContact $wxId failed", e)
                if (cont.isActive) cont.resume(false)
            }
        }

    private val ctorNetSceneVerifyUser by dexConstructor {
        searchPackages("com.tencent.mm.pluginsdk.model")
        matcher {
            usingEqStrings("MicroMsg.NetSceneVerifyUser.dkverify", "getLabelIdList, %s")
        }
    }

    fun verifyUser(userId: String, ticket: String, scene: Int, privacy: Int = 0) {
        try {
            val netScene = ctorNetSceneVerifyUser.newInstance(3, userId, ticket, scene, "", privacy)
            WeNetSceneApi.sendNetScene(netScene)
        } catch (e: Exception) {
            WeLogger.e("WeContactApi", "verifyUser failed", e)
        }
    }
}
