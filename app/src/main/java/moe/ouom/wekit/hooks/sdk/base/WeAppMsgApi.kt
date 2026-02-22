package moe.ouom.wekit.hooks.sdk.base

import android.annotation.SuppressLint
import moe.ouom.wekit.core.dsl.dexClass
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.ApiHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.utils.common.SyncUtils
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 微信 AppMsg (XML消息) 发送 API
 * 适配版本：WeChat 待补充 ~ 8.0.68
 */
@SuppressLint("DiscouragedApi")
@HookItem(path = "API/AppMsg 发送服务", desc = "提供 XML 卡片消息发送能力")
object WeAppMsgApi : ApiHookItem(), IDexFind {

    // -------------------------------------------------------------------------------------
    // DexKit 定义
    // -------------------------------------------------------------------------------------
    private val dexClassAppMsgContent by dexClass() // op0.q
    private val dexClassAppMsgLogic by dexClass()   // com.tencent.mm.pluginsdk.model.app.k0

    private val dexMethodParseXml by dexMethod()    // op0.q.u(String)
    private val dexMethodSendAppMsg by dexMethod()  // k0.J(...)

    // -------------------------------------------------------------------------------------
    // 运行时缓存
    // -------------------------------------------------------------------------------------
    private var parseXmlMethod: Method? = null
    private var sendAppMsgMethod: Method? = null
    private var appMsgContentClass: Class<*>? = null

    private const val TAG = "WeAppMsgApi"

    @SuppressLint("NonUniqueDexKitData")
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()
        WeLogger.i(
            TAG,
            ">>>> 开始查找 AppMsg 发送组件 (Process: ${SyncUtils.getProcessName()}) <<<<"
        )

        // 查找 AppMsgContent (op0.q)
        dexClassAppMsgContent.find(dexKit, descriptors) {
            matcher {
                usingStrings("<appmsg appid=\"", "parse amessage xml failed")
            }
        }

        // 查找 AppMsgLogic (k0)
        dexClassAppMsgLogic.find(dexKit, descriptors) {
            matcher {
                usingStrings("MicroMsg.AppMsgLogic", "summerbig sendAppMsg attachFilePath")
            }
        }

        val contentDesc = descriptors[dexClassAppMsgContent.key]
        val logicDesc = descriptors[dexClassAppMsgLogic.key]

        if (contentDesc != null) {
            // 查找 Parse 方法 (u)
            dexMethodParseXml.find(dexKit, descriptors, true) {
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
                dexMethodSendAppMsg.find(dexKit, descriptors) {
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

        WeLogger.i(TAG, "DexKit 查找结束，共找到 ${descriptors.size} 项")
        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        try {
            // 初始化方法引用
            parseXmlMethod = dexMethodParseXml.method
            sendAppMsgMethod = dexMethodSendAppMsg.method
            appMsgContentClass = dexClassAppMsgContent.clazz

            if (isValid()) {
                WeLogger.i(TAG, "WeAppMsgApi 初始化成功")
            } else {
                WeLogger.e(TAG, "WeAppMsgApi 初始化不完整，部分功能不可用")
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "Entry 初始化异常", e)
        }
    }

    private fun isValid(): Boolean {
        return parseXmlMethod != null && sendAppMsgMethod != null
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
        if (!isValid()) {
            WeLogger.e(TAG, "API 未就绪，无法发送")
            return false
        }

        return try {
            WeLogger.i(TAG, "准备发送 AppMsg -> $toUser")
            val contentObj = parseXmlMethod!!.invoke(null, xmlContent)
            if (contentObj == null) {
                WeLogger.e(TAG, "XML 解析返回 null，请检查 XML 格式")
                return false
            }

            sendAppMsgMethod!!.invoke(
                null,           // static
                contentObj,     // content
                appId,          // appId
                title,          // title/appName
                toUser,         // toUser
                url,           // url
                data            // thumbDat
            )

            WeLogger.i(TAG, "AppMsg 发送指令已调用")
            true
        } catch (e: Throwable) {
            WeLogger.e(TAG, "发送 AppMsg 失败", e)
            false
        }
    }
}