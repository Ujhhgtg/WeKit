package dev.ujhhgtg.wekit.features.items.moments

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.EditIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Feature(
    name = "自定义底部详细信息",
    categories = ["朋友圈"],
    description = "长按朋友圈自定义该条底部详细信息\n需同时打开「朋友圈/底部详细信息」"
)
object CustomDetails : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    private val TAG = This.Class.simpleName

    private val PLACEHOLDERS = listOf(
        $$"$originalText",
        $$"$time",
        $$"$type",
        $$"$snsId",
        $$"$userName"
    )

    private val customTextsFile by lazy { KnownPaths.moduleData / "moments_custom_bottom_details.json" }

    override fun onEnable() {
        WeMomentsContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeMomentsContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeMomentsContextMenuApi.MenuItem> {
        return listOf(
            WeMomentsContextMenuApi.MenuItem(
                777017,
                "自定义底部详细信息",
                EditIcon,
                { _, _ -> true }
            ) click@{ moment ->
                val snsId = resolveSnsId(moment.snsInfo)
                if (snsId == null) {
                    showToast("未找到朋友圈 ID!")
                    return@click
                }
                showEditor(moment.activity, snsId)
            }
        )
    }

    fun getCustomText(snsId: Long): String? {
        return getCustomTexts()[snsId.toString()]?.takeIf { it.isNotBlank() }
    }

    private fun showEditor(context: Context, snsId: Long) {
        showComposeDialog(context) {
            var textInput by remember { mutableStateOf(TextFieldValue(getCustomText(snsId).orEmpty())) }
            var isFocused by remember { mutableStateOf(false) }

            val insertPlaceholder = { placeholder: String ->
                val selection = textInput.selection
                val text = textInput.text
                if (isFocused) {
                    val newText = text.substring(0, selection.start) + placeholder + text.substring(selection.end)
                    val newSelection = TextRange(selection.start + placeholder.length)
                    textInput = TextFieldValue(newText, newSelection)
                } else {
                    val newText = text + placeholder
                    textInput = TextFieldValue(newText, TextRange(newText.length))
                }
            }

            AlertDialogContent(
                title = { Text("自定义底部详细信息") },
                text = {
                    DefaultColumn {
                        Text("留空保存可清除该条自定义内容")
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            label = { Text("底部信息内容") },
                            minLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                        )

                        Text("点击插入占位符:")

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            PLACEHOLDERS.forEach { ph ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.secondaryContainer)
                                        .clickable { insertPlaceholder(ph) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = ph,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        setCustomText(snsId, textInput.text)
                        showToast(if (textInput.text.isBlank()) "已清除自定义底部信息" else "已保存自定义底部信息")
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
        val customTexts = loadCustomTexts().toMutableMap()
        val key = snsId.toString()
        val normalized = text.trim()
        if (normalized.isBlank()) {
            customTexts.remove(key)
        } else {
            customTexts[key] = normalized
        }
        saveCustomTexts(customTexts)
    }

    /**
     * Load custom texts from JSON file (snsId -> text).
     */
    private fun loadCustomTexts(): Map<String, String> {
        val file = customTextsFile
        if (!file.exists()) return emptyMap()
        return runCatching {
            DefaultJson.decodeFromString<Map<String, String>>(file.readText())
                .filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
        }.getOrElse { e ->
            WeLogger.e(TAG, "failed to load $customTextsFile", e)
            emptyMap()
        }
    }

    private fun saveCustomTexts(customTexts: Map<String, String>) {
        runCatching {
            customTextsFile.writeText(DefaultJson.encodeToString(customTexts))
        }.onFailure { e ->
            WeLogger.e(TAG, "failed to save $customTextsFile", e)
        }
        markCacheDirty()
    }

    @Volatile
    private var customTextsCache: Map<String, String>? = null
    private val cacheDirty = AtomicBoolean(true)

    private fun getCustomTexts(): Map<String, String> {
        if (cacheDirty.compareAndSet(true, false)) {
            customTextsCache = loadCustomTexts()
        }
        return customTextsCache!!
    }

    private fun markCacheDirty() {
        cacheDirty.set(true)
    }
}
