package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.widget.CheckBox
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.resolution.DexResolutionContext
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.reflection.bool
import java.util.Collections
import java.util.WeakHashMap

object AutoEnableSendAsMediaGroup : SwitchFeature(), IResolveDex {
    override val technicalId = "自动启用合并发送媒体"
    override val nameRes = R.string.feature_auto_enable_send_as_media_group_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_auto_enable_send_as_media_group_description

    private const val ALBUM_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
    private const val IMAGE_PREVIEW_UI = "com.tencent.mm.plugin.gallery.ui.ImagePreviewUI"
    private const val KEY_SEND_AS_MEDIA_GROUP = "key_send_as_media_group"
    private const val MEDIA_TAB_ALBUM_UI = "com.tencent.mm.plugin.gallery.ui.MediaTabAlbumUI"

    /**
     * 「发送后合并展示」是 8.0.69+ 才有的选项（AlbumPreviewUI/ImagePreviewUI 中均包含 宿主自身 AOP 埋点字符串
     * initSendAsMediaGroupingViews），8.0.65/8.0.67 不存在， 因此允许解析失败。
     */
    private val methodInitSendAsMediaGroupingViews by
        dexMethod(allowFailure = true) {
            declaredClass = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
            usingEqStrings("initSendAsMediaGroupingViews")
        }

    /**
     * 选择数量变化回调（包含 updateSendAsMediaGroupViews 埋点字符串的方法）。 微信在数量 < 3 时会把勾选状态重置为 false，因此需要在这里随数量重新勾选。
     */
    private val methodUpdateSendAsMediaGroupViews by
        dexMethod(allowFailure = true) {
            declaredClass = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
            usingEqStrings("updateSendAsMediaGroupViews")
        }

    /**
     * 勾选状态字段：在三个支持版本中，包含 updateSendAsMediaGroupViews 埋点字符串的方法 只写入这一个 boolean 字段（选中状态重置为
     * false），据此唯一定位。
     */
    private val sendAsMediaGroupField by
        dexField(allowFailure = true) {
            declaredClass = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
            type = "boolean"
            writeMethod {
                usingEqStrings("updateSendAsMediaGroupViews")
            }
        }

    /** AlbumPreviewUI 中唯一的 CheckBox 即「发送后合并展示」勾选框 */
    private val sendAsMediaGroupCheckBoxField by
        dexField(allowFailure = true) {
            declaredClass = "com.tencent.mm.plugin.gallery.ui.AlbumPreviewUI"
            type = "android.widget.CheckBox"
        }

    /** The half-screen picker's data-class state, identified by its toString() literal. */
    private val classLocalPickerState by dexClass()

    /** State reducer run on every selection change: (state, selectionSnapshot) -> state. */
    private val methodLocalPickerSelectionReducer by dexMethod()

    private val localPickerStateVisible = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())
    private val forceMediaGroupChecked = ThreadLocal<Boolean>()

    override fun resolveDex() {
        // Probe: only hosts with the half-screen chat picker have this state class
        val states = DexResolutionContext.findClasses {
            usingEqString("ChattingLocalMediaPickerState(selection=")
        }
        if (states.isEmpty()) {
            classLocalPickerState.markExpectedFailure()
            methodLocalPickerSelectionReducer.markExpectedFailure()
            return
        }
        val state = states.single()
        classLocalPickerState.setDescriptor(state)
        methodLocalPickerSelectionReducer.setDescriptor(
            DexResolutionContext.findMethods {
                    returnType = state.name
                    paramTypes(state.name, null)
                    usingNumbers(3)
                }
                .single(),
        )
    }

    override fun onEnable() {
        if (classLocalPickerState.isFound) hookLocalPicker()

        if (
            !methodInitSendAsMediaGroupingViews.isFound ||
                !methodUpdateSendAsMediaGroupViews.isFound ||
                !sendAsMediaGroupField.isFound ||
                !sendAsMediaGroupCheckBoxField.isFound
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

        // MediaTabPickerUI hosts MediaTabAlbumUI as an embedded VAS activity;
        // initialize the same state after that activity's view is inflated.
        MEDIA_TAB_ALBUM_UI.toClassOrNull()
            ?.reflekt()
            ?.firstMethodOrNull { name = "initView" }
            ?.hookAfter {
                val activity = thisObject as Activity
                sendAsMediaGroupField.field.set(activity, true)
                (sendAsMediaGroupCheckBoxField.field.get(activity) as CheckBox).setChecked(true)
            }

        // ImagePreviewUI 在 initView 中读取该 extra 初始化勾选框
        IMAGE_PREVIEW_UI.toClass().hookBeforeOnCreate {
            val activity = thisObject as Activity
            activity.intent.putExtra(KEY_SEND_AS_MEDIA_GROUP, true)
        }
    }

    private fun hookLocalPicker() {
        // The 8.0.78+ half-screen chat picker (A/B tested via
        // clicfg_local_media_picker_chatting_opt) keeps this option in its
        // StateCenter. The selection reducer derives
        // checked = old.checked && visible, so selecting the first image
        // clears it and reaching 3 images never restores it. Force it on when
        // the option turns visible; the user can still untick it afterwards.
        methodLocalPickerSelectionReducer.hookBefore {
            forceMediaGroupChecked.set(localPickerStateVisible[args[0]] != true)
        }
        methodLocalPickerSelectionReducer.hookAfter {
            forceMediaGroupChecked.remove()
        }

        // ChattingLocalMediaPickerState(selection, selectionKey, sendOriginal,
        //     mediaGroupAvailableCount, mediaGroupVisible, mediaGroupChecked, ...)
        val stateConstructor =
            classLocalPickerState.reflekt().firstConstructor {
                parameters { it.size > 5 && it[4] == bool && it[5] == bool }
            }
        stateConstructor.hookBefore {
            if (forceMediaGroupChecked.get() == true && args[4] as Boolean) {
                args[5] = true
            }
        }
        stateConstructor.hookAfter {
            localPickerStateVisible[thisObject] = args[4] as Boolean
        }
    }
}
