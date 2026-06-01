package dev.ujhhgtg.wekit.hooks.items.system

import android.app.Activity
import android.app.ActivityThread
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.annotation.RequiresApi
import androidx.compose.material3.Text
import com.highcapable.kavaref.extension.createInstance
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.chatting.ChattingUIFragment
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.asResolver
import dev.ujhhgtg.wekit.utils.reflection.resolve
import org.luckypray.dexkit.DexKitBridge

// https://github.com/Ujhhgtg/PandorasBox
@HookItem(path = "系统与隐私/预见性返回动画", description = "为微信的活动强制启用预见性返回动画 [需 SDK >= 33]")
object PredictiveBackGestures : ClickableHookItem(), IResolvesDex {

    private const val PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 2
    private const val PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3
    private const val PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK = 1 shl 3

    private val TAG = This.Class.simpleName

    private var backCallback: OnBackInvokedCallback? = null

    override fun onEnable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            WeLogger.w(TAG, "sdk < 33, not enabling predictive back gestures")
            return
        }

        ApplicationInfo::class.resolve()
            .firstConstructor {
                parameters(ApplicationInfo::class.java)
            }.hookAfter {
                val info = args[0] as ApplicationInfo
                val field =
                    info.asResolver().firstField { name = "privateFlagsExt" }
                var flags = field.get() as Int
                flags = flags or PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK
                field.set(flags)
            }

        ActivityInfo::class.resolve()
            .firstConstructor()
            .hookAfter {
                val info = thisObject as ActivityInfo
                applyFlag(info)
            }

        ActivityThread::class.resolve()
            .firstMethod { name = "handleLaunchActivity" }
            .hookBefore {
                val record = args[0]
                val infoField =
                    record.asResolver().firstField { name = "activityInfo" }
                val info = infoField.get() as ActivityInfo
                applyFlag(info)
            }

        // --- LauncherUI ChattingUIFragment workaround ---

        methodChattingUIFragmentDoResume.hookAfter {
            val activity = thisObject.asResolver()
                .firstMethod {
                    name = "thisActivity"
                    superclass()
                }.invoke()!!
            if (activity is LauncherUI) {
                enableBackHandling(activity, thisObject as ChattingUIFragment)
            }
        }

        // FIXME: both of them breaks back gesture for media preview UI
        //        finish() makes back gestures first passthrough to ChattingUIFragment then to LauncherUI
        //        doPause() makes back gestures always passthrough to LauncherUI
//        ChattingUIFragment::class.resolve()
//            .firstMethod { name = "finish" }
        methodChattingUIFragmentDoPause
            .hookAfter {
                val activity = thisObject.asResolver()
                    .firstMethod {
                        name = "thisActivity"
                        superclass()
                    }.invoke()!! as Activity
                if (activity is LauncherUI) {
                    disableBackHandling(activity)
                }
            }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun enableBackHandling(activity: Activity, fragment: ChattingUIFragment) {
        WeLogger.d(TAG, "handling back gestures")
        if (backCallback == null) {
            backCallback = OnBackInvokedCallback {
                (classExitChattingUIFragmentRunnable.clazz
                    .createInstance(fragment) as Runnable).run()
            }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                backCallback!!
            )
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun disableBackHandling(activity: Activity) {
        WeLogger.d(TAG, "no longer handling back gestures")
        backCallback?.let {
            activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            backCallback = null
        }
    }

    // --- end LauncherUI ChattingUIFragment workaround ---

    private fun applyFlag(info: ActivityInfo) {
        val field = info.asResolver().firstField { name = "privateFlags" }
        var flags = field.get() as Int
        flags = flags or (PRIVATE_FLAG_ENABLE_ON_BACK_INVOKED_CALLBACK)
        flags = flags and (PRIVATE_FLAG_DISABLE_ON_BACK_INVOKED_CALLBACK).inv()
        field.set(flags)
    }

    private val methodChattingUIFragmentDoResume by dexMethod()

    private val methodChattingUIFragmentDoPause by dexMethod()

    private val classExitChattingUIFragmentRunnable by dexClass()

    override fun resolveDex(dexKit: DexKitBridge) {
        methodChattingUIFragmentDoResume.find(dexKit) {
            matcher {
                declaredClass = "${PackageNames.WECHAT}.ui.chatting.ChattingUIFragment"
                usingEqStrings("doResume")
            }
        }

        methodChattingUIFragmentDoPause.find(dexKit) {
            matcher {
                declaredClass = "${PackageNames.WECHAT}.ui.chatting.ChattingUIFragment"
                usingEqStrings("doPause")
            }
        }

        classExitChattingUIFragmentRunnable.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.SwipeBackLayout", "scrollToFinishActivity, Scrolling %B, hasTranslucent %B, hasCallPopOut %B")
            }
        }
    }

    override fun onClick(context: Context) {
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
