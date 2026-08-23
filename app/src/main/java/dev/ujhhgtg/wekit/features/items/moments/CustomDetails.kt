package dev.ujhhgtg.wekit.features.items.moments

import android.content.Context
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.i18n.LocalWeKitLocalizedContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.PlaceholderChips
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

object CustomDetails : SwitchFeature(), WeMomentsContextMenuApi.IMenuItemsProvider {

    override val technicalId = "自定义底部详细信息"
    override val nameRes = R.string.feature_custom_details_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_custom_details_description

    private const val TAG = "CustomDetails"

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
                localizedMomentsString(R.string.moments_custom_details_menu),
                EditIcon,
                { _, _ -> true }
            ) click@{ moment ->
                val snsId = resolveSnsId(moment.snsInfo)
                if (snsId == null) {
                    showToast(moment.activity.localizedMomentsString(R.string.moments_sns_id_not_found))
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
            val localizedContext by rememberUpdatedState(LocalWeKitLocalizedContext.current)

            AlertDialogContent(
                title = { Text(stringResource(R.string.moments_custom_details_title)) },
                text = {
                    DefaultColumn {
                        Text(stringResource(R.string.moments_custom_details_empty_hint))
                        OutlinedTextField(
                            value = textInput,
                            onValueChange = { textInput = it },
                            label = { Text(stringResource(R.string.moments_custom_details_content)) },
                            minLines = 3,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                        )

                        Text(stringResource(R.string.moments_custom_details_insert_placeholder))

                        PlaceholderChips(
                            placeholders = PLACEHOLDERS,
                            value = textInput,
                            isFieldFocused = isFocused,
                            onValueChange = { textInput = it },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                },
                confirmButton = {
                    Button(onClick = {
                        setCustomText(snsId, textInput.text)
                        showToast(
                            localizedContext.getString(
                                if (textInput.text.isBlank()) R.string.moments_custom_details_cleared
                                else R.string.moments_custom_details_saved
                            )
                        )
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.action_save))
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
