package dev.ujhhgtg.wekit.features.items.moments

import dev.ujhhgtg.wekit.R
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.ui.widget.imageview.WeImageView
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/** Which value row currently has its edit view swapped in. */
private enum class EditField { TEXT, TIME }

@Feature(
    id = "底部详细信息",
    nameRes = "feature_display_details_name",
    categoryIds = [FeatureCategoryIds.MOMENTS],
    descriptionRes = "feature_display_details_description",
)
object DisplayDetails : ClickableFeature(), IResolveDex {

    private const val TAG = "DisplayDetails"

    /** Roots already carrying our layout listener, so repeated attach attempts stay idempotent. */
    private val attachedRoots: MutableSet<ViewGroup> = Collections.newSetFromMap(WeakHashMap())

    private var textFormat by prefOption("moments_details_text_format", DEFAULT_TEXT_FORMAT)
    private var timeFormat by prefOption("moments_details_time_format", DEFAULT_TIME_FORMAT)
    private var hideGroupIcon by prefOption("moments_details_hide_group", false)

    private const val DEFAULT_TEXT_FORMAT = $$"$time | $originalText"
    private const val DEFAULT_TIME_FORMAT = "yyyy-MM-dd HH:mm:ss"

    private const val PH_ORIGINAL = $$"$originalText"
    private const val PH_TIME = $$"$time"
    private const val PH_TYPE = $$"$type"
    private const val PH_SNS_ID = $$"$snsId"
    private const val PH_USER_NAME = $$"$userName"

    private val TIMESTAMP_REGEX = Regex(
        """^\d+分钟前$|^\d+小时前$|^\d+天前$|^刚刚$|^昨天$|^\d+\s*mins?\s*ago$|^\d+\s*hrs?\s*ago$|^\d+\s*days?\s*ago$|^yesterday$""",
        RegexOption.IGNORE_CASE
    )

    override fun onEnable() {
        listOf(
            ImproveSnsTimelineUI::class.java,
            SnsUserUI::class.java
        ).forEach { clazz ->
            clazz.reflekt().firstMethod {
                name = "onCreate"
                parameters(Bundle::class)
            }.hookAfter {
                val activity = thisObject as Activity
                scheduleAttach(activity)
            }
        }

        if (!methodGetTimeString.isPlaceholder) methodGetTimeString.hookAfter {
            val snsInfo = thisObject
            val snsId = (fieldSnsId.field.get(snsInfo) as? Number)?.toLong() ?: return@hookAfter
            val userName = (fieldUserName.field.get(snsInfo) as? String).orEmpty()
            val createTime = (fieldCreateTime.field.get(snsInfo) as? Number)?.toInt() ?: 0
            val type = (fieldType.field.get(snsInfo) as? Number)?.toInt() ?: 0
            val originalText = result as? String ?: ""
            result = buildBottomText(snsId, userName, createTime, type, originalText)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var textFormatValue by remember { mutableStateOf(textFormat) }
            var timeFormatValue by remember { mutableStateOf(timeFormat) }
            var hideIcon by remember { mutableStateOf(hideGroupIcon) }
            var editing by remember { mutableStateOf<EditField?>(null) }
            var draft by remember { mutableStateOf("") }
            val localizedContext = LocalContext.current

            fun commitField(field: EditField, value: String) {
                when (field) {
                    EditField.TEXT -> {
                        textFormatValue = value
                        textFormat = value.ifBlank { DEFAULT_TEXT_FORMAT }
                    }
                    EditField.TIME -> {
                        timeFormatValue = value
                        timeFormat = value.ifBlank { DEFAULT_TIME_FORMAT }
                    }
                }
                showToast(localizedContext.getString(R.string.moments_display_details_saved))
            }

            val editingField = editing
            if (editingField == null) {
                AlertDialogContent(
                    title = { Text(stringResource(R.string.moments_display_details_title)) },
                    text = {
                        SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.moments_display_details_title),
                                    description = stringResource(
                                        R.string.moments_display_details_placeholders,
                                        PH_ORIGINAL, PH_TIME, PH_TYPE, PH_SNS_ID, PH_USER_NAME,
                                    ),
                                )
                            }
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.moments_display_details_text_format),
                                    description = textFormatValue,
                                    onClick = {
                                        draft = textFormatValue
                                        editing = EditField.TEXT
                                    },
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                                )
                            }
                            item {
                                BaseWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.moments_display_details_time_format),
                                    description = timeFormatValue,
                                    onClick = {
                                        draft = timeFormatValue
                                        editing = EditField.TIME
                                    },
                                    trailingContent = { Icon(MaterialSymbols.Outlined.Edit, null) },
                                )
                            }
                            item {
                                SwitchWidget(
                                    iconPlaceholder = false,
                                    title = stringResource(R.string.moments_display_details_hide_visibility_icon),
                                    checked = hideIcon,
                                    onCheckedChange = {
                                        hideIcon = it
                                        hideGroupIcon = it
                                    },
                                )
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                    },
                )
            } else {
                AlertDialogContent(
                    title = {
                        Text(
                            stringResource(
                                if (editingField == EditField.TEXT) {
                                    R.string.moments_display_details_text_format
                                } else {
                                    R.string.moments_display_details_time_format
                                }
                            )
                        )
                    },
                    text = {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    confirmButton = {
                        Button(onClick = {
                            commitField(editingField, draft)
                            editing = null
                        }) { Text(stringResource(R.string.dialog_confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { editing = null }) { Text(stringResource(R.string.dialog_cancel)) }
                    },
                )
            }
        }
    }

    private fun scheduleAttach(activity: Activity) {
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2000).forEach { delay ->
            root.postDelayed({
                runCatching { attachToLists(root) }
                    .onFailure { WeLogger.w(TAG, "attach failed, delay=${delay}ms", it) }
            }, delay.toLong())
        }
    }

    private fun attachToLists(root: ViewGroup) {
        val container = root.findViewWhich { it is WxRecyclerView } as WxRecyclerView? ?: error("RecyclerView not found")
        // scheduleAttach retries four times, and every attempt that finds the list would otherwise
        // register its own layout listener, multiplying the per-item reflection/regex work.
        synchronized(attachedRoots) {
            if (!attachedRoots.add(root)) return
        }
        container.viewTreeObserver.addOnGlobalLayoutListener {
            for (i in 0 until container.childCount) {
                runCatching {
                    processItemView(container.getChildAt(i))
                }
            }
        }
    }

    private fun processItemView(itemView: View) {
        val snsInfo = locateSnsInfo(itemView) ?: return

        val snsId = (fieldSnsId.field.get(snsInfo) as? Number)?.toLong() ?: return
        val userName = (fieldUserName.field.get(snsInfo) as? String).orEmpty()
        val createTime = (fieldCreateTime.field.get(snsInfo) as? Number)?.toInt() ?: 0
        val type = (fieldType.field.get(snsInfo) as? Number)?.toInt() ?: 0

        val timeText = formatEpoch(createTime.toLong() * 1000, timeFormat)
        val itemGroup = itemView as ViewGroup
        val timeTextView = itemGroup.findViewWhich { view ->
            if (view !is TextView || !view.isVisible) return@findViewWhich false
            val text = view.text?.toString().orEmpty()
            TIMESTAMP_REGEX.matches(text.trim()) || timeText.isNotEmpty() && text.contains(timeText)
        } as? TextView? ?: return

        // The getTimeString hook keeps the time view on the detail text; only fill the bare relative-time gap here.
        val originalText = timeTextView.text?.toString().orEmpty()
        if (TIMESTAMP_REGEX.matches(originalText.trim())) {
            val built = buildBottomText(
                snsId = snsId,
                userName = userName,
                createTime = createTime,
                type = type,
                originalText = originalText
            )
            if (originalText != built) {
                timeTextView.text = built
            }
        }

        if (hideGroupIcon) {
            val buttons = (timeTextView.parent as? ViewGroup).findViewsWhich {
                it is WeImageView
            }.toList()
            if (buttons.size > 1) {
                WeLogger.i(TAG, "hid visibility button")
                buttons[0].isVisible = false
            }
        }
    }

    private fun buildBottomText(
        snsId: Long,
        userName: String,
        createTime: Int,
        type: Int,
        originalText: String,
    ): String {
        val timeText = formatEpoch(createTime.toLong() * 1000, timeFormat)
        val typeText = "0x" + type.toString(16).uppercase(Locale.ROOT)

        val format = if (CustomDetails.isEnabled) {
            CustomDetails.getCustomText(snsId) ?: textFormat
        } else {
            textFormat
        }
        return format
            .replace(PH_ORIGINAL, originalText)
            .replace(PH_TIME, timeText)
            .replace(PH_TYPE, typeText)
            .replace(PH_SNS_ID, snsId.toString())
            .replace(PH_USER_NAME, userName)
    }

    private val classImproveSnsInfo by dexClass {
        matcher {
            usingEqStrings("ImproveInfo(name=")
        }
    }

    private val classImproveInteractionLayout by dexClass {
        matcher {
            usingEqStrings("MicroMsg.Improve.InteractionLayout")
        }
    }

    private val fieldInteractionSnsInfo by dexField {
        matcher {
            declaredClass(classImproveInteractionLayout.data.name)
            type(classImproveSnsInfo.data.name)
        }
    }

    private val fieldSnsId by dexField {
        matcher {
            declaredClass(classImproveSnsInfo.data.superClass!!.name)
            name = "field_snsId"
        }
    }

    private val fieldUserName by dexField {
        matcher {
            declaredClass(classImproveSnsInfo.data.superClass!!.name)
            name = "field_userName"
        }
    }

    private val fieldCreateTime by dexField {
        matcher {
            declaredClass(classImproveSnsInfo.data.superClass!!.name)
            name = "field_createTime"
        }
    }

    private val fieldType by dexField {
        matcher {
            declaredClass(classImproveSnsInfo.data.superClass!!.name)
            name = "field_type"
        }
    }

    private val methodGetTimeString by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classImproveSnsInfo.data.name)
            usingEqStrings("getTimeString")
        }
    }

    private fun locateSnsInfo(itemView: View): Any? {
        val interactionView = itemView.findViewWhich {
            classImproveInteractionLayout.clazz.isInstance(it)
        } ?: return null

        return fieldInteractionSnsInfo.field.get(interactionView)
    }
}
