package dev.ujhhgtg.wekit.hooks.items.debug

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.condition.type.Modifiers
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.getBoolOrFalse
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

@HookItem(path = "调试/重定向微信日志", desc = "将微信内部日志打印至模块日志")
object RedirectHostLogs : ClickableHookItem() {

    private val TAG = nameOf(RedirectHostLogs)
    private const val KEY_PREFIX = "redirect_"

    override fun onEnable() {
        com.tencent.mars.xlog.Log::class.asResolver().apply {
            if (getBoolOrFalse("${KEY_PREFIX}v"))
                firstMethod {
                    name = "v"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore { param ->
                    runCatching {
                        val tag = param.args[0] as String
                        var formatString = param.args[1] as String
                        formatString = formatString.format(*(param.args[2] as Array<*>))
                        WeLogger.v(TAG, "[V] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}d"))
                firstMethod {
                    name = "d"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore { param ->
                    runCatching {
                        val tag = param.args[0] as String
                        var formatString = param.args[1] as String
                        formatString = formatString.format(*(param.args[2] as Array<*>))
                        WeLogger.d(TAG, "[D] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}i"))
                firstMethod {
                    name = "i"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore { param ->
                    runCatching {
                        val tag = param.args[0] as String
                        var formatString = param.args[1] as String
                        formatString = formatString.format(*(param.args[2] as Array<*>))
                        WeLogger.i(TAG, "[I] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}w"))
                firstMethod {
                    name = "w"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore { param ->
                    runCatching {
                        val tag = param.args[0] as String
                        var formatString = param.args[1] as String
                        formatString = formatString.format(*(param.args[2] as Array<*>))
                        WeLogger.w(TAG, "[W] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}e"))
                firstMethod {
                    name = "e"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore { param ->
                    runCatching {
                        val tag = param.args[0] as String
                        var formatString = param.args[1] as String
                        formatString = formatString.format(*(param.args[2] as Array<*>))
                        WeLogger.e(TAG, "[E] [$tag] $formatString")
                    }
                }
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {

            AlertDialogContent(
                title = { Text("重定向微信日志") },
                text = {
                    var v by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}v")) }
                    var d by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}d")) }
                    var i by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}i")) }
                    var w by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}w")) }
                    var e by remember { mutableStateOf(getBoolOrFalse("${KEY_PREFIX}e")) }

                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                v = !v
                                WePrefs.putBool("${KEY_PREFIX}v", v)
                            },
                            headlineContent = { Text("Verbose") },
                            trailingContent = { Switch(v, null) })
                        ListItem(
                            modifier = Modifier.clickable {
                                d = !d
                                WePrefs.putBool("${KEY_PREFIX}d", d)
                            },
                            headlineContent = { Text("Debug") },
                            trailingContent = { Switch(d, null) })
                        ListItem(
                            modifier = Modifier.clickable {
                                i = !i
                                WePrefs.putBool("${KEY_PREFIX}i", i)
                            },
                            headlineContent = { Text("Info") },
                            trailingContent = { Switch(i, null) })
                        ListItem(
                            modifier = Modifier.clickable {
                                w = !w
                                WePrefs.putBool("${KEY_PREFIX}w", w)
                            },
                            headlineContent = { Text("Warning") },
                            trailingContent = { Switch(w, null) })
                        ListItem(
                            modifier = Modifier.clickable {
                                e = !e
                                WePrefs.putBool("${KEY_PREFIX}e", e)
                            },
                            headlineContent = { Text("Error") },
                            trailingContent = { Switch(e, null) })
                    }
                },
                confirmButton = {
                    Button(onDismiss) { Text("确定") }
                })
        }
    }
}
