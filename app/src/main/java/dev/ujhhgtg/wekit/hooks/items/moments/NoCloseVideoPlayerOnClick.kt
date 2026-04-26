package dev.ujhhgtg.wekit.hooks.items.moments

import android.app.Activity
import android.view.MotionEvent
import android.widget.FrameLayout
import com.highcapable.kavaref.extension.isSubclassOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Field
import java.lang.reflect.Method

@HookItem(
    path = "朋友圈/单击不关闭视频播放器",
    description = "朋友圈视频播放器内单击视频将展开/折叠控制栏而非关闭视频 (遇到长视频下意识点一下就给我视频关了, 有点反人类了)"
)
object NoCloseVideoPlayerOnClick : SwitchHookItem(), IResolvesDex {

    private lateinit var activityField: Field
    private lateinit var viewStateField: Field
    private lateinit var getToggleBtnMethod: Method

    override fun onEnable() {
        methodVideoOnTouchListenerOnTouch.hookBefore {
            val event = args[1] as MotionEvent
            if ((event.action and 0xFF) == MotionEvent.ACTION_UP) {
                if (!::activityField.isInitialized) {
                    activityField = thisObject.asResolver()
                        .firstField { type { it isSubclassOf Activity::class } }
                        .self
                }

                val activity = activityField.get(thisObject) as Activity

                if (!::viewStateField.isInitialized) {
                    viewStateField = activity.asResolver()
                        .firstField {
                            type {
                                !it.isPrimitive &&
                                        !it.name.startsWith("java.") &&
                                        !it.name.startsWith("android.")
                            }
                        }.self
                }

                val viewState = viewStateField.get(activity)

                // this doesn't actually inherit HeroSeekBarView
                val expandableSeekBar = (viewState.asResolver()
                    .optional()
                    .firstFieldOrNull { type = "com.tencent.mm.pluginsdk.ui.seekbar.ExpandableHeroSeekBarView" }
                    ?: return@hookBefore).get()!!

                if (!::getToggleBtnMethod.isInitialized) {
                    getToggleBtnMethod = expandableSeekBar.asResolver()
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

    private val methodVideoOnTouchListenerOnTouch by dexMethod()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodVideoOnTouchListenerOnTouch.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.sns.ui")
            matcher {
                name = "onTouch"
                usingEqStrings("com/tencent/mm/plugin/sns/ui/SnsOnlineVideoActivity$5", $$"android/view/View$OnTouchListener", "onTouch")
            }
        }
    }
}
