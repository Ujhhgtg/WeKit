package moe.ouom.wekit.hooks.items.beautify

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.logging.WeLogger


@HookItem(
    path = "界面美化/美化活动过渡动画",
    desc = "将部分活动过渡动画替换为默认过渡或元素级共享动画 (没做完)"
)
object BeautifyActivityTransitionAnimation : SwitchHookItem() {

    private val TAG = nameof(BeautifyActivityTransitionAnimation)
    private var width = 0
    private var height = 0
    private var x = 0f
    private var y = 0f
    private var backgroundColor = Color.WHITE

    override fun onEnable() {
        // sender
        View::class.asResolver()
            .firstMethod {
                name = "performClick"
            }
            .hookBefore { param ->
                val view = param.thisObject as View
                WeLogger.d(TAG, "called View.performClick on ${view.javaClass.name}")
                width = view.width
                height = view.height
                val location = intArrayOf(0, 0)
                view.getLocationOnScreen(location)
                x = location[0].toFloat()
                y = location[1].toFloat()
                WeLogger.d(TAG, "set x,y,w,h to $x, $y, $width, $height")
            }

        // receiver
        Activity::class.asResolver()
            .firstMethod {
                name = "onPostCreate"
            }
            .hookBefore { param ->
                val activity = param.thisObject as Activity
                val decorView = activity.window.decorView as ViewGroup

                // 使用 post 确保在 Activity 布局流程队列的末尾执行
                decorView.post {
                    val mask = View(activity)
                    mask.setBackgroundColor(backgroundColor)
                    mask.elevation = 1000f // 确保在最顶层

                    // 设置初始位置
                    val lp = FrameLayout.LayoutParams(width, height)
                    mask.setLayoutParams(lp)
                    decorView.addView(mask)

                    mask.x = x
                    mask.y = y

                    // 将缩放中心设置在 View 的左上角
                    mask.pivotX = 0f
                    mask.pivotY = 0f

                    // 此时 decorView 已经有宽高了
                    val screenW = decorView.width.toFloat()
                    val screenH = decorView.height.toFloat()

                    // 执行动画
                    mask.animate()
                        .x(0f)  // 移动到屏幕最左侧
                        .y(0f)  // 移动到屏幕最顶端
                        .scaleX(screenW / width)
                        .scaleY(screenH / height)
                        .setDuration(600)
                        .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f))
                        .withEndAction { decorView.removeView(mask) }
                        .start()
                }
            }

        Activity::class.asResolver()
            .firstMethod {
                name = "overridePendingTransition"
                parameterCount = 3
            }
            .hookBefore { param ->
                WeLogger.d(
                    TAG,
                    "called Activity.overridePendingTransition on ${param.thisObject.javaClass.name}"
                )
                param.result = null
            }
    }
}