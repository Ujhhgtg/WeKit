package dev.ujhhgtg.wekit.features.items.moments

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object NoCloseVideoPlayerOnClick : SwitchFeature(), IResolveDex {

    override val technicalId = "单击不关闭视频播放器"
    override val nameRes = R.string.feature_no_close_video_player_on_click_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_no_close_video_player_on_click_description

    override fun onEnable() {
        methodVideoOnTouchListenerOnTouch.hookBefore {
            // Skip the host's closing gesture detector while preserving View long-click handling.
            result = false
            val event = args[1] as MotionEvent
            if (event.actionMasked != MotionEvent.ACTION_UP) return@hookBefore

            val activity = thisObject!!.reflekt()
                .firstField { type = "com.tencent.mm.plugin.sns.ui.SnsOnlineVideoActivity" }
                .get() as Activity
            val seekBarController = fieldSeekBarController.field.get(activity)!!

            // The legacy controller has no expandable control bar.
            val expandableSeekBar = (seekBarController.reflekt()
                .firstFieldOrNull { type = "com.tencent.mm.pluginsdk.ui.seekbar.ExpandableHeroSeekBarView" }
                ?: return@hookBefore).get()!!
            val toggleBtn = expandableSeekBar.reflekt().invokeMethod("getExpandBarBtn") as View
            toggleBtn.performClick()
        }
    }

    private val fieldSeekBarController by dexField {
        matcher {
            declaredClass = "com.tencent.mm.plugin.sns.ui.SnsOnlineVideoActivity"
            // Identify ISnsVideoSeekBar by its init signature, independent of field order.
            type {
                methods {
                    add {
                        returnType = "void"
                        paramTypes(
                            "android.app.Activity",
                            "android.view.ViewStub",
                            "com.tencent.mm.plugin.sns.ui.OnlineVideoView",
                            null
                        )
                    }
                }
            }
        }
    }

    private val methodVideoOnTouchListenerOnTouch by dexMethod {
        searchPackages("com.tencent.mm.plugin.sns.ui")
        matcher {
            name = "onTouch"
            usingEqStrings("com/tencent/mm/plugin/sns/ui/SnsOnlineVideoActivity$5", $$"android/view/View$OnTouchListener", "onTouch")
        }
    }
}
