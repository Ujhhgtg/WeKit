package dev.ujhhgtg.wekit.hooks.items.payment

import android.content.ContentValues
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.hooks.api.core.WeApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.hooks.api.core.WeMessageApi
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.hooks.api.core.models.MessageType
import dev.ujhhgtg.wekit.hooks.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge

@HookItem(path = "红包与支付/自动接收转账", description = "监听消息并自动接收转账")
object AutoAcceptTransfers : SwitchHookItem(), IResolvesDex, WeDatabaseListenerApi.IInsertListener {

    private val TAG = This.Class.simpleName

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return

        val type = values.getAsInteger("type") ?: 0
        if (type != MessageType.TRANSFER.code) return

        WeLogger.i(TAG, "detected transfer message; type=$type")
        handleTransfer(values)
    }

    private val RECEIVER_USERNAME_REGEX = Regex("""receiver_username.*?>\s*<!\[CDATA\[(.*?)]]>""")

    private val PAY_SUBTYPE_REGEX = Regex("<paysubtype.*?(\\d+)</paysubtype>")

    private fun parseReceiverFromXml(xml: String): String? {
        return runCatching {
            RECEIVER_USERNAME_REGEX
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
        }.getOrDefault(null)
    }

    private fun parsePaySubtypeFromXml(xml: String): String? {
        return runCatching {
            PAY_SUBTYPE_REGEX
                .find(xml)
                ?.groupValues
                ?.get(1)
                ?.trim()
        }.getOrDefault(null)
    }

    private fun handleTransfer(values: ContentValues) {
        if (values.getAsInteger("isSend") == 1) return

        val content = values.getAsString("content") ?: return
        val receiver = parseReceiverFromXml(content)

        if (receiver != WeApi.selfWxId) {
            WeLogger.w(TAG, "receiver is not self, ignoring")
            return
        }

        val subtype = parsePaySubtypeFromXml(content)
        if (subtype != "1") {
            WeLogger.w(TAG, "status=$subtype is not 1, ignoring")
            return
        }

        val msgInfo = MessageInfo(WeMessageApi.convertMsgInfoFromContentValues(values, true))

        val transferMsg = msgInfo.toTransferMessage() ?: run {
            WeLogger.w(TAG, "failed to parse transfer message")
            return
        }

        val payerUsername = msgInfo.sender
        // FIXME: payerUsername is empty
//        if (msg.payerUsername == WeApi.selfWxId) {
//            WeLogger.w(TAG, "self is payer, ignoring")
//            return
//        }
        if (payerUsername == WeApi.selfWxId) {
            WeLogger.w(TAG, "self is payer, ignoring")
            return
        }

        val netScene = run {
            val transactionId = transferMsg.transactionId
            val transferId = transferMsg.transferId
            // FIXME: payerUsername is empty
//            val payerUser = msg.payerUsername
            val invalidTime = transferMsg.invalidTime

            WeLogger.d(TAG, "$transactionId, $transferId, $payerUsername, $invalidTime")

            val ctor = ctorNetSceneTransferOperation.constructor
            return@run when (ctor.parameterCount) {
                10 -> ctor.newInstance(transactionId, transferId, 0, "confirm", payerUsername, invalidTime, "", null, 1, null)
                12 -> ctor.newInstance(transactionId, transferId, 0, "confirm", payerUsername, invalidTime, "", null, 1, null, 0L, "")
                13 -> ctor.newInstance(transactionId, transferId, 0, "confirm", payerUsername, invalidTime, "", null, 1, null, 0L, "", "")
                14 -> ctor.newInstance(transactionId, transferId, 0, "confirm", payerUsername, invalidTime, "", null, 1, "", null, 0L, "", "")
                else -> error("unknown NetSceneTransferOperation constructor variant")
            }
        }

        WeNetSceneApi.addNetSceneToQueue(netScene)
        WeLogger.i(TAG, "constructed net scene and added to queue")
    }

    private val ctorNetSceneTransferOperation by dexConstructor()

    override fun resolveDex(dexKit: DexKitBridge) {
        ctorNetSceneTransferOperation.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.remittance.model")
            matcher {
                declaredClass {
                    usingEqStrings("Micromsg.NetSceneTenpayRemittanceConfirm", "/cgi-bin/mmpay-bin/transferoperation")
                }

                usingEqStrings("account click info , key is %s, value is %s")
            }
        }
    }
}
