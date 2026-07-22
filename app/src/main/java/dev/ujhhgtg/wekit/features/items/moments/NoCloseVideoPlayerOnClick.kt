package dev.ujhhgtg.wekit.features.items.moments

import android.app.Activity
import android.view.MotionEvent
import android.widget.FrameLayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isBuiltin
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.lang.reflect.Field
import java.lang.reflect.Method

@Feature(
    name = "单击不关闭视频播放器",
    categories = ["朋友圈"],
    description = "朋友圈视频播放器内单击视频将展开/折叠控制栏而非关闭视频 (遇到长视频下意识点一下就给我视频关了, 有点反人类了)"
)
object NoCloseVideoPlayerOnClick : SwitchFeature(), IResolveDex {

    private lateinit var activityField: Field
    private lateinit var viewStateField: Field
    private lateinit var getToggleBtnMethod: Method

    override fun onEnable() {
        methodVideoOnTouchListenerOnTouch.hookBefore {
            val event = args[1] as MotionEvent
            if ((event.action and 0xFF) == MotionEvent.ACTION_UP) {
                if (!::activityField.isInitialized) {
                    activityField = thisObject!!.reflekt()
                        .firstField { type { it isSubclassOf Activity::class } }
                        .self
                }

                val activity = activityField.get(thisObject) as Activity

                if (!::viewStateField.isInitialized) {
                    viewStateField = activity.reflekt()
                        .firstField {
                            type { !it.isBuiltin }
                        }.self
                }

                val viewState = viewStateField.get(activity)

                // this doesn't actually inherit HeroSeekBarView
                val expandableSeekBar = (viewState.reflekt()
                    .firstFieldOrNull { type = "com.tencent.mm.pluginsdk.ui.seekbar.ExpandableHeroSeekBarView" }
                    ?: return@hookBefore).get()!!

                if (!::getToggleBtnMethod.isInitialized) {
                    getToggleBtnMethod = expandableSeekBar.reflekt()
                        .firstMethod { name = "getExpandBarBtn" }
                        .self.makeAccessible()
                }

                val toggleBtn = getToggleBtnMethod.invoke(expandableSeekBar) as FrameLayout
                toggleBtn.performClick()
            }

            // always consume
            result = false
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
