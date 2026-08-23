package dev.ujhhgtg.wekit.features.api.core

import android.util.Pair
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Modifier

object WeAppMsgApi : ApiFeature(), IResolveDex {

    override val technicalId = "AppMsg 发送服务"
    override val nameRes = R.string.feature_we_app_msg_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)
    override val descriptionRes = R.string.feature_we_app_msg_api_description

    data class NativeSendResult(val statusCode: Int, val localMsgId: Long?)

    private val methodParseXml by dexMethod()    // op0.q.u(String)
    private val methodSendAppMsg by dexMethod()  // k0.J(...)

    private const val TAG = "WeAppMsgApi"

    override fun resolveDex(dexKit: DexKitBridge) {
        val classAppMsgContent = dexKit.findClass {
            matcher {
                usingStrings("<appmsg appid=\"", "parse amessage xml failed")
            }
        }.single()

        val classAppMsgLogic = dexKit.findClass {
            matcher {
                usingStrings("MicroMsg.AppMsgLogic", "summerbig sendAppMsg attachFilePath")
            }
        }.single()

        methodParseXml.find(dexKit, true) {
            matcher {
                declaredClass = classAppMsgContent.name
                modifiers = Modifier.PUBLIC or Modifier.STATIC
                paramTypes(String::class.java.name)
                returnType = classAppMsgContent.name
                usingStrings("parse msg failed")
            }
        }

        methodSendAppMsg.find(dexKit) {
            matcher {
                declaredClass = classAppMsgLogic.name
                modifiers = Modifier.STATIC
                paramCount = 6
                paramTypes(
                    classAppMsgContent.name,
                    "java.lang.String",
                    null,
                    null,
                    null,
                    null
                )
            }
        }
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

            methodSendAppMsg.method.invoke(
                null,         // static
                contentObj, // content
                appId,             // appId
                title,             // title/appName
                target,            // toUser
                url,               // url
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
