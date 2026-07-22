package dev.ujhhgtg.wekit.features.items.system

import android.app.ActivityThread
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.system.PredictiveBackGestures.PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK
import dev.ujhhgtg.wekit.features.items.system.PredictiveBackGestures.dispatching
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

// https://github.com/Ujhhgtg/PandorasBox
@Feature(name = "预见性返回动画", categories = ["系统与隐私"], description = "为微信的活动强制启用预见性返回动画\n需系统 Android SDK >= 33")
object PredictiveBackGestures : ClickableFeature() {

    private const val PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 2
    private const val PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3
    private const val PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3

    private const val TAG = "PredictiveBackGestures"

    /**
     * ## 背景
     *
     * 本功能通过强开 [PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK] 让微信的活动进入
     * Android 13+ 的「提前分发 (ahead-of-time)」返回模型，从而获得系统预见性返回动画。
     *
     * ## 旧实现为何出错
     *
     * 一旦进入提前分发模型:
     *
     * 1. 系统在 `Activity.onCreate` 里给每个活动注册一个 **PRIORITY_SYSTEM** 的默认回调
     *    (`Activity.onBackInvoked` / API 33 上是 `navigateBack`)，它只在服务端 finish 活动，
     *    **不会**调用活动重写的 `onBackPressed()`。
     * 2. 系统不再向活动派发 KEYCODE_BACK；且 `Activity.onKeyUp` 仅在 `mDefaultBackCallback == null`
     *    时才回落到 `onBackPressed()`——而该字段此时非空。
     * 3. 预见性动画 (跨活动 / 回桌面) **只有在栈顶回调是 system 回调时**才会播放
     *    (`BackNavigationController`: 非 system 回调一律降级为无动画的 `TYPE_CALLBACK`)。
     *
     * 于是:
     * - 什么都不做 → 动画正常，但系统默认回调绕过了微信自己的返回链
     *   (`onBackPressListeners`、`LauncherUI.moveTaskToBack`、聊天页 fragment 的 `onKeyDown` 返回等)，
     *   导致大量界面直接 finish / 回桌面。
     * - 自己注册一个应用回调 → 由于应用回调不能是 system 优先级 (负优先级会抛异常)，它会顶掉
     *   系统默认回调，动画随之消失；且返回逻辑仍不完整。
     *
     * ## 本实现
     *
     * 直接 hook 系统默认回调的方法体本身 (`Activity.onBackInvoked` / `navigateBack`):
     * - 不新增任何回调，栈顶始终是那个 system 回调 → **动画保持完整**。
     * - 在方法执行前拦截，改为向活动补发一次**真实的 KEYCODE_BACK (down + up)**，
     *   等价于按下硬件返回键，从而原样跑完微信的整条旧返回链
     *   (`dispatchKeyEvent` -> fragment/`onKeyDown` 处理聊天页与各种面板，
     *   terminal case 再经 `onKeyUp` -> `onBackPressed`)，随后 `result = null` 吞掉原本的服务端 finish。
     * - 因为 `onKeyUp` 需要 `mDefaultBackCallback == null`，注入期间临时把该字段置空并在结束后还原；
     *   [dispatching] 兼作重入保护，避免我们补发的返回又触发本 hook。
     *
     * 这样对所有活动统一生效，无需再对 LauncherUI / 聊天页做任何特殊处理。
     */
    private var dispatching = false

    override fun onEnable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            WeLogger.w(TAG, "sdk < 33, not enabling predictive back gestures")
            return
        }

        ApplicationInfo::class.reflekt()
            .firstConstructor {
                parameters(ApplicationInfo::class.java)
            }.hookAfter {
                val info = args[0] as ApplicationInfo
                val field =
                    info.reflekt().firstField { name = "privateFlagsExt" }
                var flags = field.get() as Int
                flags = flags or PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK
                field.set(flags)
            }

        ActivityInfo::class.reflekt()
            .firstConstructor()
            .hookAfter {
                val info = thisObject as ActivityInfo
                if (!info.name.startsWith(PackageNames.MODULE)) return@hookAfter
                applyFlag(info)
            }

        ActivityThread::class.reflekt()
            .firstMethod { name = "handleLaunchActivity" }
            .hookBefore {
                val record = args[0]!!
                val infoField =
                    record.reflekt().firstField { name = "activityInfo" }
                val info = infoField.get() as ActivityInfo
                if (!info.name.startsWith(PackageNames.MODULE)) return@hookBefore
                applyFlag(info)
            }

//        // hook 系统默认返回回调的方法体，把服务端 finish 换成微信原本的返回处理
//        // API 34+ 是 Activity.onBackInvoked()，API 33 是 Activity.navigateBack()
//        val systemBackMethodName =
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
//                "onBackInvoked"
//            } else {
//                "navigateBack"
//            }
//
//        Activity::class.reflekt()
//            .firstMethodOrNull {
//                name = systemBackMethodName
//                parameterCount = 0
//            }
//            ?.hookBefore {
//                val activity = thisObject as Activity
//                if (dispatchLegacyBack(activity)) {
//                    // 已由微信自己的返回链处理，吞掉系统默认的 finish
//                    result = null
//                }
//            }
//            ?: WeLogger.w(TAG, "failed to find Activity.$systemBackMethodName, back handling not bridged")
    }

//    /**
//     * 向活动补发一次完整的 KEYCODE_BACK (down + up)，复用微信原本的返回处理链。
//     *
//     * 注入期间临时把 `Activity.mDefaultBackCallback` 置空，使 `onKeyUp` 能在需要时回落到
//     * `onBackPressed()` (它以该字段为 null 作为「使用旧返回」的判据)；结束后无条件还原。
//     *
//     * @return 是否成功注入 (成功则调用方应吞掉系统默认 finish)
//     */
//    private fun dispatchLegacyBack(activity: Activity): Boolean {
//        if (dispatching) return false // 重入保护: 我们补发的返回不应再次进入本 hook
//        dispatching = true
//
//        val defaultBackCallbackField = runCatching {
//            activity.reflekt().firstField { name = "mDefaultBackCallback"; superclass() }
//        }.getOrNull()
//        val savedCallback = defaultBackCallbackField?.get()
//
//        return try {
//            defaultBackCallbackField?.set(null)
//
//            val now = SystemClock.uptimeMillis()
//            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
//            val up = KeyEvent(
//                now, SystemClock.uptimeMillis(),
//                KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0
//            )
//            activity.dispatchKeyEvent(down)
//            activity.dispatchKeyEvent(up)
//            true
//        } catch (t: Throwable) {
//            WeLogger.e(TAG, "failed to dispatch legacy back", t)
//            false
//        } finally {
//            defaultBackCallbackField?.set(savedCallback)
//            dispatching = false
//        }
//    }

    private fun applyFlag(info: ActivityInfo) {
        val field = info.reflekt().firstField { name = "privateFlags" }
        var flags = field.get() as Int
        flags = flags or PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK
        flags = flags and PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK.inv()
        field.set(flags)
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text("预见性返回动画") },
                text = {
                    Text("如果预见性返回动画没有生效, 说明系统 Android 版本过低 (SDK < 33)")
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } })
        }
    }
}
