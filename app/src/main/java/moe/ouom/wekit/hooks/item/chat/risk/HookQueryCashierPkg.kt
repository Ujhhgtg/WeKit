package moe.ouom.wekit.hooks.item.chat.risk

import android.content.Context
import android.text.InputType
import moe.ouom.wekit.config.WeConfig
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.hooks.sdk.protocol.WePkgManager
import moe.ouom.wekit.hooks.sdk.protocol.intf.IWePkgInterceptor
import moe.ouom.wekit.ui.creator.dialog.BaseRikkaDialog
import moe.ouom.wekit.util.WeProtoData
import moe.ouom.wekit.util.log.WeLogger
import org.json.JSONArray
import org.json.JSONObject

@HookItem(path = "聊天与消息/修改转账时的余额", desc = "点击配置")
class HookQueryCashierPkg : BaseClickableFunctionHookItem(), IWePkgInterceptor {
    companion object {
        private const val KEY_CFT_BALANCE = "cashier_cft_balance"
        private const val KEY_LQT_BALANCE = "cashier_lqt_balance"

        // 默认值
        private const val DEFAULT_CFT = "¥999,999.00"
        private const val DEFAULT_LQT = "¥8,888,888.88"
    }

    override fun entry(classLoader: ClassLoader) {
        WePkgManager.addInterceptor(this)
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (cgiId != 2882) return null

        WeLogger.i("HookQueryCashierPkg", "拦截到收银台数据包: $uri")

        try {
            val data = WeProtoData()
            data.fromBytes(respBytes)
            val json = data.toJSON()
            processJsonObject(json)
            data.applyViewJSON(json, true)

            WeLogger.i("HookQueryCashierPkg", "篡改完成，返回新数据包")
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e("HookQueryCashierPkg", e)
        }

        return null
    }

    private fun processJsonObject(obj: JSONObject) {
        val keysList = mutableListOf<String>()
        val keysIterator = obj.keys()
        while (keysIterator.hasNext()) {
            keysList.add(keysIterator.next())
        }

        val config = WeConfig.getDefaultConfig()
        val customCft = config.getStringPrek(KEY_CFT_BALANCE, DEFAULT_CFT) ?: DEFAULT_CFT
        val customLqt = config.getStringPrek(KEY_LQT_BALANCE, DEFAULT_LQT) ?: DEFAULT_LQT

        for (key in keysList) {
            val value = obj.opt(key) ?: continue

            if (value is Number) {
                val longVal = value.toLong()
                if (longVal == 4289901234L || longVal == 2147483648L) {
                    obj.put(key, 4278190080L)
                    continue
                }
            }

            if (key == "5" && value is String) {
                obj.put("7", 0)

                if (obj.has("3")) {
                    handleField3(obj)
                }

                when (value) {
                    "CFT" -> updateBalanceText(obj, "零钱(剩余$customCft)")
                    "LQT" -> updateBalanceText(obj, "零钱通(剩余$customLqt)")
                }
            }

            if (value is JSONObject) {
                processJsonObject(value)
            } else if (value is JSONArray) {
                for (i in 0 until value.length()) {
                    val item = value.optJSONObject(i)
                    if (item != null) processJsonObject(item)
                }
            }
        }
    }

    private fun handleField3(parent: JSONObject) {
        val f3 = parent.get("3")
        if (f3 is JSONObject) {
            val text = f3.optJSONObject("1")?.optString("3") ?: ""
            if (text.contains("不足")) parent.remove("3")
        } else if (f3 is JSONArray) {
            for (i in f3.length() - 1 downTo 0) {
                val item = f3.optJSONObject(i)
                val text = item?.optJSONObject("1")?.optString("3") ?: ""
                if (text.contains("不足")) f3.remove(i)
            }
        }
    }

    private fun updateBalanceText(item: JSONObject, newText: String) {
        try {
            val field2 = item.optJSONObject("2") ?: return
            val subField1 = field2.optJSONObject("1") ?: return
            subField1.put("3", newText)
            val field11 = item.optJSONObject("11")
            field11?.optJSONObject("1")?.put("3", newText.replace(Regex("\\(.*?\\)"), ""))
        } catch (e: Exception) {
            WeLogger.e(e)
        }
    }

    private inner class ConfigDialog(context: Context) : BaseRikkaDialog(context, "收银台余额配置") {
        override fun initPreferences() {
            addCategory("金额设置")

            addEditTextPreference(
                key = KEY_CFT_BALANCE,
                title = "零钱余额",
                summary = "设置支付时显示的零钱余额",
                defaultValue = DEFAULT_CFT,
                hint = "例如: ¥999,999.00",
                inputType = InputType.TYPE_CLASS_TEXT,
            )

            addEditTextPreference(
                key = KEY_LQT_BALANCE,
                title = "零钱通余额",
                summary = "设置支付时显示的零钱通余额",
                defaultValue = DEFAULT_LQT,
                hint = "例如: ¥8,888,888.88",
                inputType = InputType.TYPE_CLASS_TEXT,
            )
        }
    }

    override fun unload(classLoader: ClassLoader) {
        WePkgManager.removeInterceptor(this)
        super.unload(classLoader)
    }

    override fun onClick(context: Context?) {
        context?.let {
            ConfigDialog(it).show()
        }
    }
}
