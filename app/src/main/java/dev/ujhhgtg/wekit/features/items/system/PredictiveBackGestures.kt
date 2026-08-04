package dev.ujhhgtg.wekit.features.items.system

import android.app.ActivityThread
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
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
                if (!isModuleActivity(info)) return@hookAfter
                applyFlag(info)
            }

        ActivityThread::class.reflekt()
            .firstMethod { name = "handleLaunchActivity" }
            .hookBefore {
                val record = args[0]!!
                val infoField =
                    record.reflekt().firstField { name = "activityInfo" }
                val info = infoField.get() as ActivityInfo
                val intent = record.reflekt().firstField { name = "intent" }.get() as? Intent
                if (!isModuleActivity(info, intent)) return@hookBefore
                applyFlag(info)
            }
    }

    /**
     * [dev.ujhhgtg.wekit.loader.utils.ActivityProxy] recovers the target Intent before ActivityThread launches a module Activity,
     * but must keep the host stub's ActivityInfo. Inspect the recovered component as well, so the
     * host ActivityInfo receives the predictive-back flags for the Activity it will instantiate.
     */
    private fun isModuleActivity(info: ActivityInfo, intent: Intent? = null): Boolean =
        info.name?.startsWith(PackageNames.MODULE) == true ||
            intent?.component?.className?.startsWith(PackageNames.MODULE) == true

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
