package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.WeContactApi.deleteContact
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BlockContactProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.DelContactProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.OpLog
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.UserNameProto
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.int
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

object WeContactApi : ApiFeature(), IResolveDex {

    override val technicalId = "联系人服务"
    override val nameRes = R.string.feature_we_contact_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_contact_api_description

    private const val TAG = "WeContactApi"
    private const val VERIFY_USER_SCENE_REQUEST = 3
    private const val PROBE_MESSAGE_SUPPRESSION_MILLIS = 600_000L

    sealed interface RelationshipProbeResult {
        data object Normal : RelationshipProbeResult
        data object Deleted : RelationshipProbeResult
        data object Blacklisted : RelationshipProbeResult
        data class AccountRestricted(val message: String?) : RelationshipProbeResult
        data class RateLimited(val message: String?) : RelationshipProbeResult
        data class Failed(val errType: Int?, val errCode: Int?, val message: String?) : RelationshipProbeResult
        data object Timeout : RelationshipProbeResult
    }

    private data class PendingRelationshipProbe(
        val deferred: CompletableDeferred<RelationshipProbeResult>,
    )

    private val pendingRelationshipProbes =
        ConcurrentHashMap<Any, PendingRelationshipProbe>()
    private val probeMessageSuppressionUntil =
        ConcurrentHashMap<String, Long>()

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

                WePacketHelper.sendCgi(
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

    private val ctorNetSceneVerifyUserProbe by dexConstructor {
        searchPackages("com.tencent.mm.pluginsdk.model")
        matcher {
            declaredClass {
                usingEqStrings(
                    "MicroMsg.NetSceneVerifyUser.dkverify",
                    "This NetSceneVerifyUser init NEVER use opcode == MM_VERIFYUSER_VERIFYOK",
                )
            }
            paramCount(5)
            paramTypes(int, List::class.java, List::class.java, BString, BString)
        }
    }

    override fun onEnable() {
        ctorNetSceneVerifyUserProbe.constructor.declaringClass.reflekt()
            .firstMethod { name = "onGYNetEnd" }
            .hookAfter {
                val pending = pendingRelationshipProbes.remove(thisObject) ?: return@hookAfter
                val errType = args[1] as Int
                val errCode = args[2] as Int
                val errMsg = args[3] as String?
                pending.deferred.complete(classifyRelationshipProbe(errType, errCode, errMsg))
            }

        WeMessageApi.methodMsgInfoStorageInsertMessage.hookBefore {
            val message = MessageInfo(args[0]!!)
            val suppressionUntil = probeMessageSuppressionUntil[message.talker] ?: return@hookBefore
            if (suppressionUntil < System.currentTimeMillis()) {
                probeMessageSuppressionUntil.remove(message.talker, suppressionUntil)
                return@hookBefore
            }
            if (message.typeCode == 10000 && isRelationshipProbeMessage(message.content)) {
                probeMessageSuppressionUntil.remove(message.talker)
                result = -1L
            }
        }
    }

    override fun onDisable() {
        pendingRelationshipProbes.values.forEach { pending ->
            pending.deferred.complete(
                RelationshipProbeResult.Failed(null, null, "WeContactApi disabled")
            )
        }
        pendingRelationshipProbes.clear()
        probeMessageSuppressionUntil.clear()
    }

    suspend fun probeRelationship(wxId: String): RelationshipProbeResult {
        val deferred = CompletableDeferred<RelationshipProbeResult>()
        val scene = try {
            ctorNetSceneVerifyUserProbe.newInstance(
                1,
                listOf(wxId),
                listOf(VERIFY_USER_SCENE_REQUEST),
                "",
                "",
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to construct verify-user probe for $wxId", e)
            return RelationshipProbeResult.Failed(null, null, e.message)
        }

        val now = System.currentTimeMillis()
        probeMessageSuppressionUntil.forEach { (contact, expiresAt) ->
            if (expiresAt < now) probeMessageSuppressionUntil.remove(contact, expiresAt)
        }
        probeMessageSuppressionUntil[wxId] = now + PROBE_MESSAGE_SUPPRESSION_MILLIS
        pendingRelationshipProbes[scene] = PendingRelationshipProbe(deferred)

        return try {
            WeNetSceneApi.sendNetScene(scene)
            withTimeoutOrNull(20.seconds) { deferred.await() }
                ?: RelationshipProbeResult.Timeout
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to dispatch verify-user probe for $wxId", e)
            RelationshipProbeResult.Failed(null, null, e.message)
        } finally {
            pendingRelationshipProbes.remove(scene)
        }
    }

    private fun classifyRelationshipProbe(
        errType: Int,
        errCode: Int,
        errMsg: String?,
    ): RelationshipProbeResult = when {
        errType == 4 && errCode == -34 || errMsg?.contains("操作过于频繁") == true ->
            RelationshipProbeResult.RateLimited(errMsg)
        errType == 0 && errCode == 0 -> RelationshipProbeResult.Normal
        errType == 4 && errCode == -44 -> RelationshipProbeResult.Deleted
        errType == 4 && errCode == -22 -> RelationshipProbeResult.Blacklisted
        errType == 4 && errCode == -24 -> RelationshipProbeResult.AccountRestricted(errMsg)
        else -> RelationshipProbeResult.Failed(errType, errCode, errMsg)
    }

    private fun isRelationshipProbeMessage(content: String): Boolean =
        content.contains("weixin://findfriend/verifycontact", ignoreCase = true) ||
            content.contains("拒收") ||
            content.contains("successfully sent but rejected", ignoreCase = true) ||
            content.contains("被封") ||
            content.contains("被限制登录")

    fun verifyUser(userId: String, ticket: String, scene: Int, privacy: Int = 0) {
        try {
            val netScene = ctorNetSceneVerifyUser.newInstance(3, userId, ticket, scene, "", privacy, null, null)
            WeNetSceneApi.sendNetScene(netScene)
        } catch (e: Exception) {
            WeLogger.e("WeContactApi", "verifyUser failed", e)
        }
    }
}
