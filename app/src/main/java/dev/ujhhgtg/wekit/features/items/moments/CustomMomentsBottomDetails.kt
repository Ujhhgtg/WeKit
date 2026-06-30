package dev.ujhhgtg.wekit.features.items.moments

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import org.json.JSONObject

@Feature(
    name = "自定义朋友圈底部信息",
    categories = ["朋友圈"],
    description = "长按朋友圈动态自定义该条底部详细信息内容"
)
object CustomMomentsBottomDetails : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    private const val MENU_ID = 777007
    private const val PREF_KEY = "moments_custom_bottom_details"

    override fun onEnable() {
        WeMomentsContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                MENU_ID,
                "底部信息修改",
                EditIcon,
                { _, _ -> true }
            ) click@{ moment ->
                val snsId = resolveSnsId(moment.snsInfo)
                if (snsId == null) {
                    showToast("未找到朋友圈 ID")
                    return@click
                }
                showEditor(moment.activity, snsId)
            }
        )
    }

    fun getCustomText(snsId: Long): String? {
        return loadCustomTexts()[snsId.toString()]?.takeIf { it.isNotBlank() }
    }

    private fun showEditor(context: Context, snsId: Long) {
        showComposeDialog(context) {
            var textInput by remember { mutableStateOf(getCustomText(snsId).orEmpty()) }

            AlertDialogContent(
                title = { Text("自定义底部信息") },
                text = {
                    DefaultColumn {
                        Text("留空保存可清除该条自定义内容。")
                        Text($$"占位符: ${originalText} ${time} ${type} ${snsId} ${userName}")
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            label = { Text("底部信息内容") },
                            minLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        setCustomText(snsId, textInput)
                        showToast(if (textInput.isBlank()) "已清除自定义底部信息" else "已保存自定义底部信息")
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                }
            )
        }
    }

    private fun resolveSnsId(snsInfo: Any?): Long? {
        return (snsInfo?.reflekt()?.getField("field_snsId", true) as? Number)?.toLong()
    }

    private fun setCustomText(snsId: Long, text: String) {
        val customTexts = loadCustomTexts()
        val key = snsId.toString()
        val normalized = text.trim()
        if (normalized.isBlank()) {
            customTexts.remove(key)
        } else {
            customTexts[key] = normalized
        }
        saveCustomTexts(customTexts)
    }

    private fun loadCustomTexts(): MutableMap<String, String> {
        val raw = WePrefs.getStringOrDef(PREF_KEY, "{}")
        return runCatching {
            val json = JSONObject(raw)
            val result = mutableMapOf<String, String>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.optString(key, "")
                if (key.isNotBlank() && value.isNotBlank()) {
                    result[key] = value
                }
            }
            result
        }.getOrDefault(mutableMapOf())
    }

    private fun saveCustomTexts(customTexts: Map<String, String>) {
        val json = JSONObject()
        customTexts.forEach { (snsId, text) ->
            json.put(snsId, text)
        }
        WePrefs.putString(PREF_KEY, json.toString())
    }
}
