package dev.ujhhgtg.wekit.features.items.moments

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.isVisible
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.ui.widget.imageview.WeImageView
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.formatEpoch
import java.util.Locale

@Feature(
    name = "底部详细信息", categories = ["朋友圈"],
    description = "在朋友圈列表项底部显示详情信息"
)
object DisplayDetails : ClickableFeature(), IResolveDex {

    private val TAG = This.Class.simpleName

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
        ).forEach { clazz -> clazz.reflekt().firstMethod {
            name = "onCreate"
            parameters(Bundle::class)
        }.hookAfter {
            val activity = thisObject as Activity
            scheduleAttach(activity)
        } }

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

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var textFormatInput by remember { mutableStateOf(textFormat) }
            var timeFormatInput by remember { mutableStateOf(timeFormat) }
            var hideGroupIconInput by remember { mutableStateOf(hideGroupIcon) }

            AlertDialogContent(
                title = { Text("朋友圈底部信息详细") },
                text = {
                    DefaultColumn {
                        Text($$"占位符: $originalText $time $type $snsId $userName")
                        OutlinedTextField(
                            value = textFormatInput,
                            onValueChange = { textFormatInput = it },
                            label = { Text("文本格式") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = timeFormatInput,
                            onValueChange = { timeFormatInput = it },
                            label = { Text("时间格式") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        ListItem(
                            headlineContent = { Text("隐藏可见范围图标") },
                            trailingContent = {
                                Switch(
                                    checked = hideGroupIconInput,
                                    onCheckedChange = { hideGroupIconInput = it }
                                )
                            },
                            modifier = Modifier.clickable { hideGroupIconInput = !hideGroupIconInput }
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        textFormat = textFormatInput.ifBlank { DEFAULT_TEXT_FORMAT }
                        timeFormat = timeFormatInput.ifBlank { DEFAULT_TIME_FORMAT }
                        hideGroupIcon = hideGroupIconInput
                        showToast("已保存, 重新进入朋友圈后生效")
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                }
            )
        }
    }

    private fun scheduleAttach(activity: Activity) {
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2000).forEach { delay ->
            root.postDelayed({
                runCatching { attachToLists(root) }
                    .onFailure { WeLogger.w(TAG, "attach failed, delay=${delay}ms") }
            }, delay.toLong())
        }
    }

    private fun attachToLists(root: ViewGroup) {
        val container = root.findViewWhich<WxRecyclerView> { it is WxRecyclerView } ?: error("RecyclerView not found")
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
        val timeTextView = itemGroup.findViewWhich<TextView> { view ->
            if (view !is TextView || !view.isVisible) return@findViewWhich false
            val text = view.text?.toString().orEmpty()
            TIMESTAMP_REGEX.matches(text.trim()) || timeText.isNotEmpty() && text.contains(timeText)
        } ?: return

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
            val buttons = (timeTextView.parent as? ViewGroup).findViewsWhich<View> {
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
            declaredClass(classImproveInteractionLayout.clazz)
            type(classImproveSnsInfo.clazz)
        }
    }

    private val fieldSnsId by dexField {
        matcher {
            declaredClass(classImproveSnsInfo.clazz.superclass!!)
            name = "field_snsId"
        }
    }

    private val fieldUserName by dexField {
        matcher {
            declaredClass(classImproveSnsInfo.clazz.superclass!!)
            name = "field_userName"
        }
    }

    private val fieldCreateTime by dexField {
        matcher {
            declaredClass(classImproveSnsInfo.clazz.superclass!!)
            name = "field_createTime"
        }
    }

    private val fieldType by dexField {
        matcher {
            declaredClass(classImproveSnsInfo.clazz.superclass!!)
            name = "field_type"
        }
    }

    private val methodGetTimeString by dexMethod(allowFailure = true) {
        matcher {
            declaredClass(classImproveSnsInfo.clazz)
            usingEqStrings("getTimeString")
        }
    }

    private fun locateSnsInfo(itemView: View): Any? {
        val interactionView = itemView.findViewWhich<View> {
            classImproveInteractionLayout.clazz.isInstance(it)
        } ?: return null

        return fieldInteractionSnsInfo.field.get(interactionView)
    }
}
