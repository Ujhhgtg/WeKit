package dev.ujhhgtg.wekit.features.api.core

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.api.net.WePacketHelper
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

@Feature(name = "联系人服务", categories = ["API"], description = "提供联系人管理能力")
object WeContactApi : ApiFeature(), IResolveDex {

    private const val TAG = "WeContactApi"

    /** Operation passed in the `deletecontact` payload's field `4`. */
    enum class DeleteMode(val opCode: Int) {
        /** Remove the contact only. */
        DELETE_ONLY(1),

        /** Block the contact, then remove it. */
        BLOCK_AND_DELETE(3)
    }

    /**
     * Delete (and optionally block) a contact via the `deletecontact` CGI.
     *
     * Suspends until the server responds, returning `true` on success and `false` on failure.
     * Callers that delete in bulk should space out invocations themselves, as WeChat's server
     * rate-limits these requests.
     */
    suspend fun deleteContact(wxId: String, mode: DeleteMode = DeleteMode.DELETE_ONLY): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val body = """{"2":"$wxId","4":${mode.opCode}}"""
                WePacketHelper.sendCgi("/cgi-bin/micromsg-bin/deletecontact", 376, 0, 0, body) {
                    onSuccess { _, _ -> if (cont.isActive) cont.resume(true) }
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

    // C1350 case 26: m4953("com.tencent.mm.pluginsdk.model") + C2812.m4143("MicroMsg.NetSceneVerifyUser.dkverify", "/cgi-bin/micromsg-bin/verifyuser")
    // C1261: "ConstructorNetSceneEq3" → constructor paramCount=3 (auto-detect 6-8 from ctor overloads in WAuxv m1785)
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
