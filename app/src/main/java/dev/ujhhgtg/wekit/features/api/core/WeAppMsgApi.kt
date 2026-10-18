package dev.ujhhgtg.wekit.features.api.core

import android.util.Pair
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldUsingType
import java.lang.reflect.Modifier

object WeAppMsgApi : ApiFeature(), IResolveDex {

    override val technicalId = "AppMsg 发送服务"
    override val nameRes = R.string.feature_we_app_msg_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_app_msg_api_description

    data class NativeSendResult(val statusCode: Int, val localMsgId: Long?)

    private val methodParseXml by dexMethod()    // op0.q.u(String)
    private val methodSendAppMsg by dexMethod()  // k0.J(...)
    private val fieldQuoteItem by dexField()
    private val fieldQuoteMsgSource by dexField()
    private val classAppMsgContent by dexClass("""SELECT DISTINCT c.descriptor AS descriptor FROM classes c JOIN methods cm ON cm.class_descriptor = c.descriptor JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN method_strings ms1 ON ms1.method = cm.descriptor WHERE ms0.value LIKE '%<appmsg appid="%' AND ms1.value LIKE '%parse amessage xml failed%' ORDER BY c.descriptor;""")
    private val classAppMsgLogic by dexClass("""SELECT DISTINCT c.descriptor AS descriptor FROM classes c JOIN methods cm ON cm.class_descriptor = c.descriptor JOIN method_strings ms0 ON ms0.method = cm.descriptor JOIN method_strings ms1 ON ms1.method = cm.descriptor WHERE ms0.value LIKE '%MicroMsg.AppMsgLogic%' AND ms1.value LIKE '%summerbig sendAppMsg attachFilePath%' ORDER BY c.descriptor;""")

    private const val TAG = "WeAppMsgApi"

    override fun resolveDex(dexKit: DexKitBridge) {
        methodParseXml.find(dexKit, true) {
            matcher {
                declaredClass = classAppMsgContent.data.name
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramTypes(String::class.java.name)
                returnType = classAppMsgContent.data.name
                usingStrings("parse msg failed")
            }
        }

        methodSendAppMsg.find(dexKit) {
            matcher {
                declaredClass = classAppMsgLogic.data.name
                modifiers = Modifier.STATIC
                paramCount = 6
                paramTypes(
                    classAppMsgContent.data.name,
                    "java.lang.String",
                    null,
                    null,
                    null,
                    null
                )
            }
        }

        val quoteItemField = classAppMsgContent.data.fields.single {
            it.typeName == "com.tencent.mm.plugin.msgquote.model.MsgQuoteItem"
        }
        fieldQuoteItem.setDescriptor(quoteItemField)
        // AppMsgLogic reads the outgoing msgSource, not the quoted message's msgsource.
        fieldQuoteMsgSource.setDescriptor(
            classAppMsgLogic.data.methods.asSequence()
                .flatMap { it.usingFields.asSequence() }
                .filter { it.usingType == FieldUsingType.Read }
                .map { it.field }
                .filter {
                    it.className == quoteItemField.typeName &&
                        it.typeName == "java.lang.String"
                }
                .distinctBy { it.descriptor }
                .single()
        )
    }

    fun sendXmlAppMsg(
        target: String,
        title: String,
        appId: String,
        url: String?,
        data: ByteArray?,
        xmlContent: String
    ): Boolean {
        return try {
            WeLogger.i(TAG, "sending appmsg to $target")
            val contentObj = methodParseXml.method.invoke(null, xmlContent)
            if (contentObj == null) {
                WeLogger.e(TAG, "failed to parse xml")
                return false
            }

            // XML parsing leaves the outgoing quote msgSource unset. Older send paths
            // pass it to fromXml without a null check; native quote composition uses "".
            val quoteItem = fieldQuoteItem.field.get(contentObj)
            if (quoteItem != null && fieldQuoteMsgSource.field.get(quoteItem) == null) {
                fieldQuoteMsgSource.field.set(quoteItem, "")
            }

            methodSendAppMsg.method.invoke(
                null,         // static
                contentObj, // content
                appId,             // appId
                title,             // title/appName
                target,            // toUser
                url,               // attachFilePath
                data               // thumbDat
            )

            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to send appmsg", e)
            false
        }
    }

    fun sendAppMsgObject(target: String, contentObj: Any): NativeSendResult {
        val result = methodSendAppMsg.method.invoke(
            null,
            contentObj,
            "",
            "",
            target,
            "",
            null,
        ) as Pair<*, *>
        return NativeSendResult(result.first as Int, result.second as? Long)
    }
}
