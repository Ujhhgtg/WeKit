package moe.ouom.wekit.hooks.items.debug

import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.toClass
import dev.ujhhgtg.nameof.nameof
import moe.ouom.wekit.core.model.SwitchHookItem
import moe.ouom.wekit.hooks.utils.annotation.HookItem
import moe.ouom.wekit.utils.logging.WeLogger

@HookItem(path = "调试/重定向宿主日志", desc = "将宿主日志打印至模块日志")
object RedirectHostLogs : SwitchHookItem() {

    private val TAG = nameof(RedirectHostLogs)

    override fun onEnable() {
        "com.tencent.mars.xlog.Log".toClass().asResolver().apply {
//            firstMethod {
//                name = "v"
//                parameterCount = 3
//                modifiers(Modifiers.STATIC)
//            }.hookBefore { param ->
//                val tag = param.args[0] as String
//                var formatString = param.args[1] as String
//                formatString = formatString.format(*(param.args[2] as Array<*>))
//                WeLogger.v(TAG, "verbose from host:\n[$tag] $formatString")
//            }
//
//            firstMethod {
//                name = "d"
//                parameterCount = 3
//                modifiers(Modifiers.STATIC)
//            }.hookBefore { param ->
//                val tag = param.args[0] as String
//                var formatString = param.args[1] as String
//                formatString = formatString.format(*(param.args[2] as Array<*>))
//                WeLogger.d(TAG, "[$tag] $formatString")
//            }
//
//            firstMethod {
//                name = "i"
//                parameterCount = 3
//                modifiers(Modifiers.STATIC)
//            }.hookBefore { param ->
//                val tag = param.args[0] as String
//                var formatString = param.args[1] as String
//                formatString = formatString.format(*(param.args[2] as Array<*>))
//                WeLogger.i(TAG, "[$tag] $formatString")
//            }

            firstMethod {
                name = "w"
                parameterCount = 3
                modifiers(Modifiers.STATIC)
            }.hookBefore { param ->
                val tag = param.args[0] as String
                var formatString = param.args[1] as String
                formatString = formatString.format(*(param.args[2] as Array<*>))
                WeLogger.w(TAG, "[$tag] $formatString")
            }

            firstMethod {
                name = "e"
                parameterCount = 3
                modifiers(Modifiers.STATIC)
            }.hookBefore { param ->
                val tag = param.args[0] as String
                var formatString = param.args[1] as String
                formatString = formatString.format(*(param.args[2] as Array<*>))
                WeLogger.e(TAG, "[$tag] $formatString")
            }
        }
    }
}