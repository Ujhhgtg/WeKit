package dev.ujhhgtg.wekit.features.items.moments

import android.app.Activity
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.data.KvStore.prefOption
import dev.ujhhgtg.wekit.dex.dsl.dexField
import dev.ujhhgtg.wekit.dex.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.PlaceholderChips
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.formatEpoch
import java.util.Locale

object DisplayDetails : ClickableFeature() {

    override val technicalId = "底部详细信息"
    override val nameRes = R.string.feature_display_details_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_display_details_description

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

    override fun onEnable() {
        methodGetTimeString.hookAfter {
            result = buildBottomText(thisObject!!, result as String)
        }

        methodGetShowGroupEnable.hookAfter {
            if (hideGroupIcon) result = false
        }

        methodLoadOldItemView.hookAfter {
            val itemView = thisObject as View
            val item = fieldOldViewItem.field.get(itemView) ?: return@hookAfter
            val snsInfo = WeMomentsApi.methodGetTimelineItemInfo.method.invoke(item)!!
            updateBottomView(itemView, snsInfo)
        }

        methodSetDetailHeader.hookAfter {
            if (result != true) return@hookAfter
            val activity = thisObject as Activity
            val snsInfo = methodGetDetailSnsInfo.method.invoke(activity, false) ?: return@hookAfter
            updateBottomView(activity.window.decorView, snsInfo)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var textFormatValue by remember { mutableStateOf(TextFieldValue(textFormat)) }
            var timeFormatValue by remember { mutableStateOf(timeFormat) }
            var hideIcon by remember { mutableStateOf(hideGroupIcon) }
            var isTextFormatFocused by remember { mutableStateOf(false) }
            AlertDialogContent(
                title = { Text(stringResource(R.string.moments_display_details_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseSupportingWidget(
                                title =
                                    stringResource(R.string.moments_display_details_text_format),
                            ) {
                                Column {
                                    OutlinedTextField(
                                        value = textFormatValue,
                                        onValueChange = {
                                            textFormatValue = it
                                            textFormat = it.text.ifBlank { DEFAULT_TEXT_FORMAT }
                                        },
                                        singleLine = true,
                                        modifier =
                                            Modifier.fillMaxWidth()
                                                .padding(horizontal = 16.dp)
                                                .onFocusChanged {
                                                    isTextFormatFocused = it.isFocused
                                                },
                                    )
                                    Text(
                                        stringResource(
                                            R.string.moments_custom_details_insert_placeholder,
                                        ),
                                        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                                    )
                                    PlaceholderChips(
                                        placeholders =
                                            listOf(
                                                PH_ORIGINAL,
                                                PH_TIME,
                                                PH_TYPE,
                                                PH_SNS_ID,
                                                PH_USER_NAME,
                                            ),
                                        value = textFormatValue,
                                        isFieldFocused = isTextFormatFocused,
                                        onValueChange = {
                                            textFormatValue = it
                                            textFormat = it.text.ifBlank { DEFAULT_TEXT_FORMAT }
                                        },
                                        modifier = Modifier.padding(horizontal = 16.dp),
                                    )
                                }
                            }
                        }
                        item {
                            BaseSupportingWidget(
                                title =
                                    stringResource(R.string.moments_display_details_time_format),
                            ) {
                                OutlinedTextField(
                                    value = timeFormatValue,
                                    onValueChange = {
                                        timeFormatValue = it
                                        timeFormat = it.ifBlank { DEFAULT_TIME_FORMAT }
                                    },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                )
                            }
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title =
                                    stringResource(
                                        R.string.moments_display_details_hide_visibility_icon,
                                    ),
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
        }
    }

    private fun updateBottomView(root: View, snsInfo: Any) {
        val timeViewId = root.resources.getIdentifier("qv", "id", PackageNames.WECHAT)
        val timeView = root.findViewById<TextView>(timeViewId) ?: return
        timeView.text = buildBottomText(snsInfo, timeView.text.toString())
        if (hideGroupIcon) {
            val groupIconId = root.resources.getIdentifier("pt", "id", PackageNames.WECHAT)
            root.findViewById<View>(groupIconId)?.isVisible = false
        }
    }

    private fun buildBottomText(snsInfo: Any, originalText: String): String {
        val snsId = fieldSnsId.field.getLong(snsInfo)
        val userName = fieldUserName.field.get(snsInfo) as String
        val createTime = fieldCreateTime.field.getInt(snsInfo)
        val type = fieldType.field.getInt(snsInfo)
        val timeText = formatEpoch(createTime.toLong() * 1000, timeFormat)
        val typeText = "0x" + type.toString(16).uppercase(Locale.ROOT)

        val format =
            if (CustomDetails.isEnabled) {
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

    private val fieldSnsId by dexField {
        name = "field_snsId"
        declaredClass(WeMomentsApi.classImproveSnsInfo.data.superClass!!.name)
    }

    private val fieldUserName by dexField {
        name = "field_userName"
        declaredClass(WeMomentsApi.classImproveSnsInfo.data.superClass!!.name)
    }

    private val fieldCreateTime by dexField {
        name = "field_createTime"
        declaredClass(WeMomentsApi.classImproveSnsInfo.data.superClass!!.name)
    }

    private val fieldType by dexField {
        name = "field_type"
        declaredClass(WeMomentsApi.classImproveSnsInfo.data.superClass!!.name)
    }

    private val methodGetTimeString by dexMethod {
        declaredClass(WeMomentsApi.classImproveSnsInfo.data.name)
        usingEqStrings("getTimeString")
    }

    private val methodGetShowGroupEnable by dexMethod {
        declaredClass(WeMomentsApi.classImproveSnsInfo.data.name)
        usingEqStrings("getShowGroupEnable")
    }

    private val methodLoadOldItemView by dexMethod {
        declaredClass("com.tencent.mm.plugin.sns.ui.improve.compatibility.OldVersionItemView")
        returnType = "void"
        paramTypes("int")
        usingEqStrings("loadView")
    }

    private val fieldOldViewItem by dexField {
        declaredClass("com.tencent.mm.plugin.sns.ui.improve.compatibility.OldVersionItemView")
        type(WeMomentsApi.methodGetTimelineItemInfo.data.declaredClassName)
    }

    private val methodSetDetailHeader by dexMethod {
        declaredClass("com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI")
        returnType = "boolean"
        paramCount = 0
        usingEqStrings("setHeader", "com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI")
    }

    private val methodGetDetailSnsInfo by dexMethod {
        declaredClass("com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI")
        returnType = "com.tencent.mm.plugin.sns.storage.SnsInfo"
        paramTypes("boolean")
        usingEqStrings("fixId", "com.tencent.mm.plugin.sns.ui.SnsCommentDetailUI")
    }
}
