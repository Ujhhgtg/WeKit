package moe.ouom.wekit.hooks.items.payment

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.preferences.WePrefs
import moe.ouom.wekit.core.model.ClickableHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.hooks.api.net.WePacketManager
import moe.ouom.wekit.hooks.api.net.intf.IWePacketInterceptor
import moe.ouom.wekit.ui.content.AlertDialogContent
import moe.ouom.wekit.ui.content.Button
import moe.ouom.wekit.ui.content.TextButton
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.hooks.api.net.WeProtoData
import moe.ouom.wekit.utils.logging.WeLogger
import org.json.JSONArray
import org.json.JSONObject

@HookItem(path = "红包与支付/修改转账显示余额", desc = "伪装转账时显示的余额文字")
object ModifyTransferWalletBalanceDisplay : ClickableHookItem(), IWePacketInterceptor {

    private val TAG = nameof(ModifyTransferWalletBalanceDisplay)

    private const val KEY_CFT_BALANCE = "fake_cft_balance"
    private const val KEY_LQT_BALANCE = "fake_lqt_balance"

    override fun onEnable() {
        WePacketManager.addInterceptor(this)
    }

    override fun onResponse(uri: String, cgiId: Int, respBytes: ByteArray): ByteArray? {
        if (cgiId != 2882) return null

        WeLogger.i(TAG, "拦截到收银台数据包: $uri")

        try {
            val data = WeProtoData()
            data.fromBytes(respBytes)
            val json = data.toJsonObject()
            processJsonObject(json)
            data.applyViewJson(json, true)

            WeLogger.i(TAG, "篡改完成，返回新数据包")
            return data.toPacketBytes()
        } catch (e: Exception) {
            WeLogger.e(TAG, e)
        }

        return null
    }

    private fun processJsonObject(obj: JSONObject) {
        val keysList = mutableListOf<String>()
        val keysIterator = obj.keys()
        while (keysIterator.hasNext()) {
            keysList.add(keysIterator.next())
        }

        val customCft = WePrefs.getStringOrDef(KEY_CFT_BALANCE, null)
        val customLqt = WePrefs.getStringOrDef(KEY_LQT_BALANCE, null)

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

                if (value == "CFT" && customCft != null)
                    updateBalanceText(obj, "零钱(剩余$customCft)")
                else if (value == "LQT" && customLqt != null)
                    updateBalanceText(obj, "零钱通(剩余$customLqt)")
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

    override fun onDisable() {
        WePacketManager.removeInterceptor(this)
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var cftInput by remember {
                mutableStateOf(
                    WePrefs.getStringOrDef(KEY_CFT_BALANCE, null) ?: ""
                )
            }
            var lqtInput by remember {
                mutableStateOf(
                    WePrefs.getStringOrDef(KEY_LQT_BALANCE, null) ?: ""
                )
            }

            AlertDialogContent(
                title = { Text("修改显示余额") },
                text = {
                    TextField(
                        value = cftInput,
                        onValueChange = { cftInput = it },
                        label = { Text("零钱余额 (留空不修改)") })
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = lqtInput,
                        onValueChange = { lqtInput = it },
                        label = { Text("零钱通余额 (留空不修改)") })
                },
                confirmButton = {
                    Button(onClick = {
                        if (!cftInput.isBlank())
                            WePrefs.putString(KEY_CFT_BALANCE, cftInput)
                        else
                            WePrefs.remove(KEY_CFT_BALANCE)

                        if (!lqtInput.isBlank())
                            WePrefs.putString(KEY_LQT_BALANCE, lqtInput)
                        else
                            WePrefs.remove(KEY_LQT_BALANCE)
                        onDismiss()
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
            )
        }
    }
}
