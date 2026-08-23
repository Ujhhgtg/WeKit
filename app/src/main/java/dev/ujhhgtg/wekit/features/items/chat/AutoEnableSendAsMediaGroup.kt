package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.widget.CheckBox
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object AutoEnableSendAsMediaGroup : SwitchFeature(), IResolveDex {

    override val technicalId = "自动启用合并发送媒体"
    override val nameRes = R.string.feature_auto_enable_send_as_media_group_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_enable_send_as_media_group_description

    private const val ALBUM_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
    private const val IMAGE_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
    private const val KEY_SEND_AS_MEDIA_GROUP = "key_send_as_media_group"

    /**
     * 「发送后合并展示」是 8.0.69+ 才有的选项（AlbumPreviewUI/ImagePreviewUI 中均包含
     * 宿主自身 AOP 埋点字符串 initSendAsMediaGroupingViews），8.0.65/8.0.67 不存在，
     * 因此允许解析失败。
     */
    private val methodInitSendAsMediaGroupingViews by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            usingEqStrings("initSendAsMediaGroupingViews")
        }
    }

    /**
     * 选择数量变化回调（包含 updateSendAsMediaGroupViews 埋点字符串的方法）。
     * 微信在数量 < 3 时会把勾选状态重置为 false，因此需要在这里随数量重新勾选。
     */
    private val methodUpdateSendAsMediaGroupViews by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            usingEqStrings("updateSendAsMediaGroupViews")
        }
    }

    /**
     * 勾选状态字段：在三个支持版本中，包含 updateSendAsMediaGroupViews 埋点字符串的方法
     * 只写入这一个 boolean 字段（选中状态重置为 false），据此唯一定位。
     */
    private val sendAsMediaGroupField by dexField(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            type = "boolean"
            addWriteMethod {
                usingEqStrings("updateSendAsMediaGroupViews")
            }
        }
    }

    /** AlbumPreviewUI 中唯一的 CheckBox 即「发送后合并展示」勾选框 */
    private val sendAsMediaGroupCheckBoxField by dexField(allowFailure = true) {
        matcher {
            declaredClass = ALBUM_PREVIEW_UI
            type(CheckBox::class.java)
        }
    }

    override fun onEnable() {
        if (
            methodInitSendAsMediaGroupingViews.isPlaceholder ||
            methodUpdateSendAsMediaGroupViews.isPlaceholder ||
            sendAsMediaGroupField.isPlaceholder ||
            sendAsMediaGroupCheckBoxField.isPlaceholder
        ) {
            return
        }

        // AlbumPreviewUI 直发时依据该字段决定是否合并展示，必须同步勾选状态字段与勾选框
        methodInitSendAsMediaGroupingViews.hookAfter {
            val activity = thisObject as Activity
            sendAsMediaGroupField.field.set(activity, true)
            (sendAsMediaGroupCheckBoxField.field.get(activity) as CheckBox).setChecked(true)
        }

        // 选择数量达到 3 张及以上时（合并展示仅对 3 张及以上生效）保持勾选
        methodUpdateSendAsMediaGroupViews.hookAfter {
            if (args[0] as Int >= 3) {
                val activity = thisObject as Activity
                sendAsMediaGroupField.field.set(activity, true)
                (sendAsMediaGroupCheckBoxField.field.get(activity) as CheckBox).setChecked(true)
            }
        }

        // ImagePreviewUI 在 initView 中读取该 extra 初始化勾选框
        IMAGE_PREVIEW_UI.toClass().hookBeforeOnCreate {
            val activity = thisObject as Activity
            activity.intent.putExtra(KEY_SEND_AS_MEDIA_GROUP, true)
        }
    }
}
