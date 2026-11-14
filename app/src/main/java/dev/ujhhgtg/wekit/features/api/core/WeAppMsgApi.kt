package dev.ujhhgtg.wekit.features.api.core

import android.util.Pair
import dev.ujhhgtg.dexsql.meta.FieldUsingType
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.dsl.dexClass
import dev.ujhhgtg.wekit.dex.dsl.dexFieldFrom
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.dex.dsl.dexMethodFrom
import dev.ujhhgtg.wekit.dex.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import java.lang.reflect.Modifier

object WeAppMsgApi : ApiFeature() {
    override val technicalId = "AppMsg 发送服务"
    override val nameRes = R.string.feature_we_app_msg_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_app_msg_api_description

    data class NativeSendResult(val statusCode: Int, val localMsgId: Long?)

    private val classAppMsgContent by dexClass {
        usingStrings("<appmsg appid=\"", "parse amessage xml failed")
    }
    private val classAppMsgLogic by dexClass {
        usingStrings("MicroMsg.AppMsgLogic", "summerbig sendAppMsg attachFilePath")
    }

    private const val TAG = "WeAppMsgApi"

    private val methodParseXml by dexMethodFrom {
        DexResolutionContext.findMethods {
                declaredClass = classAppMsgContent.data.name
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramTypes(String::class.java.name)
                returnType = classAppMsgContent.data.name
                usingString("parse msg failed")
            }
            .first()
    }
    private val methodSendAppMsg by dexMethod {
        declaredClass = classAppMsgLogic.data.name
        modifiers = Modifier.STATIC
        paramCount = 6
        paramTypes(classAppMsgContent.data.name, "java.lang.String", null, null, null, null)
    }
    private val fieldQuoteItem by dexFieldFrom {
        classAppMsgContent.data.fields.single {
            it.typeName == "com.tencent.mm.plugin.msgquote.model.MsgQuoteItem"
        }
    }
    private val fieldQuoteMsgSource by dexFieldFrom {
        // AppMsgLogic reads the outgoing msgSource, not the quoted message's msgsource.
        classAppMsgLogic.data.methods
            .asSequence()
            .flatMap { it.usingFields.asSequence() }
            .filter { it.usingType == FieldUsingType.Read }
            .map { it.field }
            .filter {
                it.className == fieldQuoteItem.data.typeName && it.typeName == "java.lang.String"
            }
            .distinctBy { it.descriptor }
            .single()
    }

    fun sendXmlAppMsg(
        target: String,
        title: String,
        appId: String,
        url: String?,
        data: ByteArray?,
        xmlContent: String,
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
                null, // static
                contentObj, // content
                appId, // appId
                title, // title/appName
                target, // toUser
                url, // attachFilePath
                data, // thumbDat
            )

            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "failed to send appmsg", e)
            false
        }
    }

    fun sendAppMsgObject(target: String, contentObj: Any): NativeSendResult {
        val result =
            methodSendAppMsg.method.invoke(
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
