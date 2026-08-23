package dev.ujhhgtg.wekit.features.api.net

import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi.classNetSceneTenpayRemittanceGen
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi.onEnable
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi.pendingPlaceOrders
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BeforeTransferReqProto
import dev.ujhhgtg.wekit.features.api.net.models.protobuf.BeforeTransferRespProto
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.seconds

object WeTransferApi : ApiFeature(), IResolveDex {

    override val technicalId = "转账服务"
    override val nameRes = R.string.feature_we_transfer_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_transfer_api_description

    private const val TAG = "WeTransferApi"

    /** com.tencent.mm.plugin.remittance.model.q0 — NetSceneTenpayRemittanceGen (transferplaceorder). */
    val classNetSceneTenpayRemittanceGen by dexClass {
        searchPackages("com.tencent.mm.plugin.remittance.model")
        matcher {
            usingEqStrings(
                "Micromsg.NetSceneTenpayRemittanceGen",
                "payScene: %s, channel: %s dynamicCodeUrl: %s mch_name: %s nickname: %s receiver_true_name %s placeorder_reserves: %s unpayType: %s cancel_outtradeno:%s cancel_reason:%s placeorderAttach:%s"
            )
        }
    }

    /**
     * Maps an in-flight [classNetSceneTenpayRemittanceGen] instance we created to the deferred
     * that awaits its parsed CGI response. Keyed by identity (the native scene doesn't override
     * equals/hashCode). The single [onEnable] hook on `onGYNetEnd` fans responses back here so
     * WeChat's own transfer flows never resolve our deferreds.
     */
    val pendingPlaceOrders = ConcurrentHashMap<Any, CompletableDeferred<JSONObject?>>()

    override fun onEnable() {
        // void onGYNetEnd(int errType, String errMsg, JSONObject resp)
        // Fires for every remittance placeorder (ours and WeChat's own); route only our instances.
        classNetSceneTenpayRemittanceGen.reflekt()
            .firstMethod { name = "onGYNetEnd" }
            .hookAfter {
                val deferred = pendingPlaceOrders.remove(thisObject) ?: return@hookAfter
                val resp = args.getOrNull(2) as? JSONObject
                WeLogger.d(TAG, "onGYNetEnd captured: errType=${args.getOrNull(0)}, errMsg=${args.getOrNull(1)}, resp=$resp")
                deferred.complete(resp)
            }
    }

    override fun onDisable() {
        pendingPlaceOrders.values.forEach { it.complete(null) }
        pendingPlaceOrders.clear()
    }

    /**
     * Holds the fixed per-session parameters that every transferplaceorder in a brute-force run
     * reuses: the target's masked real name, the `truename_extend` key from beforetransfer, and
     * a single `placeorder_reserves` token generated once so WeChat treats the retries as
     * continuations of the same order rather than fresh large transfers.
     */
    data class TransferContext(
        val memberId: String,
        val groupId: String?,
        val maskedRealName: String,
        val truenameExtend: String,
        val nickname: String,
        val amountYuan: Double,
        val placeorderReserves: String
    )

    /** Step 1: `/cgi-bin/mmpay-bin/beforetransfer` → masked real name + truename_extend key. */
    suspend fun fetchBeforeTransfer(memberId: String, groupId: String?): BeforeTransferRespProto? =
        suspendCancellableCoroutine { cont ->
            val reqBytes = BeforeTransferReqProto(userName = memberId, groupId = groupId).encode()
            WePacketHelper.sendCgi("/cgi-bin/mmpay-bin/beforetransfer", 2783, 0, 0, reqBytes) {
                onSuccess { bytes ->
                    val proto = bytes?.let { runCatching { BeforeTransferRespProto.decode(it) }.getOrNull() }
                    if (cont.isActive) cont.resume(proto)
                }
                onFailure { errType, errCode, errMsg ->
                    WeLogger.w(TAG, "beforetransfer failed: errType=$errType errCode=$errCode errMsg=$errMsg")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    /**
     * Step 2: build and dispatch a `transferplaceorder` ([classNetSceneTenpayRemittanceGen]) and
     * await its parsed JSON response via the [pendingPlaceOrders] routing hook.
     *
     * [inputName]/[checknameSign] are null on the probe call (to obtain the checkname challenge)
     * and set on each brute-force attempt. Placing an order does not move money — the actual
     * transfer requires a separate password-confirmed step that we never reach.
     */
    suspend fun sendPlaceOrder(
        ctx: TransferContext,
        inputName: String?,
        checknameSign: String?
    ): JSONObject? {
        val deferred = CompletableDeferred<JSONObject?>()
        val scene = try {
            // 30-arg constructor (positions matched against a real transferplaceorder call):
            //  1 fee          2 feeType="1"   3 receiverName   4 maskTruename
            //  5 payScene=31  6 transferScene=2   7 desc=""     8 i19=0
            //  9 s5=null     10 s6=null    11 dynamicCodeUrl="" 12 mchName=null
            // 13 s9=null     14 channel=14 15 receiverOpenid="" 16 s11=""
            // 17 s12=null    18 nickname   19 receiverTruename  20 f2fEvent=null
            // 21 inputName   22 checknameSign  23 truenameExtend  24 placeorderReserves
            // 25 unpayType=0 26 cancelOuttradeno=""  27 cancelReason=0  28 groupUsername
            // 29 placeorderAttach=""  30 hasTryHkpay=false
            classNetSceneTenpayRemittanceGen.clazz.createInstance(
                ctx.amountYuan, "1", ctx.memberId, ctx.maskedRealName, 31, 2, "", 0, null, null,
                "", null, null, 14, "", "", null, ctx.nickname, ctx.maskedRealName, null,
                inputName, checknameSign, ctx.truenameExtend, ctx.placeorderReserves,
                0, "", 0, ctx.groupId ?: "", "", false
            )
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to construct transferplaceorder scene", e)
            return null
        }

        pendingPlaceOrders[scene] = deferred
        try {
            WeNetSceneApi.sendNetScene(scene)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to enqueue transferplaceorder scene", e)
            pendingPlaceOrders.remove(scene)
            return null
        }

        return withTimeoutOrNull(1.5.seconds) { deferred.await() }.also {
            pendingPlaceOrders.remove(scene)
            if (it == null) WeLogger.w(TAG, "transferplaceorder timed out (inputName=$inputName)")
        }
    }
}
