package moe.ouom.wekit.hooks.api.core

import android.annotation.SuppressLint
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IResolvesDex
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.logging.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@SuppressLint("DiscouragedApi")
@HookItem(path = "API/AppMsg 发送服务", desc = "提供 XML 卡片消息发送能力")
object WeAppMsgApi : ApiHookItem(), IResolvesDex {

    private val classAppMsgContent by dexClass() // op0.q
    private val classAppMsgLogic by dexClass()   // com.tencent.mm.pluginsdk.model.app.k0

    private val methodParseXml by dexMethod()    // op0.q.u(String)
    private val methodSendAppMsg by dexMethod()  // k0.J(...)

    private var parseXmlMethod: Method? = null
    private var sendAppMsgMethod: Method? = null
    private var appMsgContentClass: Class<*>? = null

    private val TAG = nameof(WeAppMsgApi)

    @SuppressLint("NonUniqueDexKitData")
    override fun resolveDex(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // 查找 AppMsgContent (op0.q)
        classAppMsgContent.find(dexKit, descriptors) {
            matcher {
                usingStrings("<appmsg appid=\"", "parse amessage xml failed")
            }
        }

        // 查找 AppMsgLogic (k0)
        classAppMsgLogic.find(dexKit, descriptors) {
            matcher {
                usingStrings("MicroMsg.AppMsgLogic", "summerbig sendAppMsg attachFilePath")
            }
        }

        val contentDesc = descriptors[classAppMsgContent.key]
        val logicDesc = descriptors[classAppMsgLogic.key]

        if (contentDesc != null) {
            // 查找 Parse 方法 (u)
            methodParseXml.find(dexKit, descriptors, true) {
                matcher {
                    declaredClass = contentDesc
                    modifiers = Modifier.PUBLIC or Modifier.STATIC
                    paramTypes(String::class.java.name)
                    returnType = contentDesc
                    usingStrings("parse msg failed")
                }
            }

            if (logicDesc != null) {
                WeLogger.i(TAG, "dexkit: logicDesc=$logicDesc, contentDesc=$contentDesc")
                // 查找 Send 方法 (J)
                methodSendAppMsg.find(dexKit, descriptors) {
                    matcher {
                        declaredClass = logicDesc
                        modifiers = Modifier.STATIC
                        paramCount = 6
                        paramTypes(
                            contentDesc,
                            "java.lang.String",
                            null,
                            null,
                            null,
                            null
                        )
                    }
                }
            }
        }

        return descriptors
    }

    override fun onEnable() {
        runCatching {
            parseXmlMethod = methodParseXml.method
            sendAppMsgMethod = methodSendAppMsg.method
            appMsgContentClass = classAppMsgContent.clazz
        }.onFailure { e -> WeLogger.e(TAG, "exception during init", e) }
    }

    /**
     * 发送 XML 消息 (AppMsg)
     */
    fun sendXmlAppMsg(
        toUser: String,
        title: String,
        appId: String,
        url: String?,
        data: ByteArray?,
        xmlContent: String
    ): Boolean {
        return try {
            WeLogger.i(TAG, "准备发送 AppMsg -> $toUser")
            val contentObj = parseXmlMethod!!.invoke(null, xmlContent)
            if (contentObj == null) {
                WeLogger.e(TAG, "XML 解析返回 null，请检查 XML 格式")
                return false
            }

            sendAppMsgMethod!!.invoke(
                null,         // static
                contentObj, // content
                appId,            // appId
                title,            // title/appName
                toUser,           // toUser
                url,              // url
                data              // thumbDat
            )

            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "发送 AppMsg 失败", e)
            false
        }
    }
}