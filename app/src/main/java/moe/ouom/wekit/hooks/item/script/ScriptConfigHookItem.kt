package moe.ouom.wekit.hooks.item.script

import android.content.Context
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgHelper
import moe.ouom.wekit.hooks.sdk.protocol.WePkgManager
import moe.ouom.wekit.hooks.sdk.protocol.intf.IWePkgInterceptor
import moe.ouom.wekit.ui.creator.dialog.ScriptManagerDialog
import moe.ouom.wekit.util.WeProtoData
import moe.ouom.wekit.util.log.WeLogger
import moe.ouom.wekit.util.script.JsExecutor
import moe.ouom.wekit.util.script.ScriptEvalManager
import moe.ouom.wekit.util.script.ScriptFileManager

/**
 * 脚本配置管理器Hook项
 */
@HookItem(
    path = "脚本管理/脚本开关",
    desc = "点击管理JavaScript脚本配置"
)
class ScriptConfigHookItem : BaseClickableFunctionHookItem(), IWePkgInterceptor {

    private fun sendCgi(uri: String, cgiId: Int, funcId: Int, routeId: Int, jsonPayload: String) {
        WePkgHelper.INSTANCE?.sendCgi(uri, cgiId, funcId, routeId, jsonPayload) {
            onSuccess { json, bytes -> WeLogger.e("异步CGI请求成功：回包: $json") }
            onFail { type, code, msg -> WeLogger.e("异步CGI请求失败: $type, $code, $msg") }
        }
    }

    override fun entry(classLoader: ClassLoader) {
        // 注入脚本接口
        JsExecutor.getInstance().injectScriptInterfaces(::sendCgi, WeApiUtils, WeProtoUtils, WeDataBaseUtils, WeMessageUtils)
        // 注册拦截器
        WePkgManager.addInterceptor(this)
    }

    override fun onRequest(uri: String, cgiId: Int, reqBytes: ByteArray): ByteArray? {
        try {
            // 解析 Protobuf 数据
            val data = WeProtoData()
            data.fromBytes(reqBytes)
            // 转换为 JSON 进行处理
            val json = data.toJSON()
            // 应用脚本修改
            val modifiedJson = ScriptEvalManager.getInstance().executeOnRequest(uri, cgiId, json)
            // 应用修改并转回字节数组
            data.applyViewJSON(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e("ScriptConfig", e)
        }

        return null
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        try {
            // 解析 Protobuf 数据
            val data = WeProtoData()
            data.fromBytes(respBytes)
            // 转换为 JSON 进行处理
            val json = data.toJSON()
            // 应用脚本修改
            val modifiedJson = ScriptEvalManager.getInstance().executeOnResponse(uri, cgiId, json)
            // 应用修改并转回字节数组
            data.applyViewJSON(modifiedJson, true)
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e("ScriptConfig", e)
        }
        return null
    }

    override fun unload(classLoader: ClassLoader) {
        WePkgManager.removeInterceptor(this)
        super.unload(classLoader)
    }

    override fun onClick(context: Context) {
        val scriptManager = ScriptFileManager.getInstance()
        val jsEvalManager = ScriptEvalManager.getInstance()
        ScriptManagerDialog(context, scriptManager, jsEvalManager).show()
    }

}