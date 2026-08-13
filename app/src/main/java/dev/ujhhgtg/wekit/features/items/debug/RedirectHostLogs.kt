package dev.ujhhgtg.wekit.features.items.debug

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import dev.ujhhgtg.wekit.ui.utils.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.tencent.mars.xlog.Log
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.getBoolOrFalse
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger

@Feature(
    id = "重定向微信日志",
    nameRes = "feature_redirect_host_logs_name",
    categoryIds = [FeatureCategoryIds.DEBUG],
    descriptionRes = "feature_redirect_host_logs_description",
)
object RedirectHostLogs : ClickableFeature() {

    private const val TAG = "RedirectHostLogs"
    private const val KEY_PREFIX = "redirect_"

    override fun onEnable() {
        Log::class.reflekt().apply {
            if (getBoolOrFalse("${KEY_PREFIX}v"))
                firstMethod {
                    name = "v"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.v(TAG, "[V] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}d"))
                firstMethod {
                    name = "d"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.d(TAG, "[D] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}i"))
                firstMethod {
                    name = "i"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.i(TAG, "[I] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}w"))
                firstMethod {
                    name = "w"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.w(TAG, "[W] [$tag] $formatString")
                    }
                }

            if (getBoolOrFalse("${KEY_PREFIX}e"))
                firstMethod {
                    name = "e"
                    parameterCount = 3
                    modifiers(Modifiers.STATIC)
                }.hookBefore {
                    runCatching {
                        val tag = args[0] as String
                        var formatString = args[1] as String
                        formatString = formatString.format(*(args[2] as Array<*>))
                        WeLogger.e(TAG, "[E] [$tag] $formatString")
                    }
                }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.debug_redirect_host_logs_title)) },
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
                            trailingContent = { Switch(v, null) },
                            content = { Text(stringResource(R.string.debug_log_level_verbose)) },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                d = !d
                                WePrefs.putBool("${KEY_PREFIX}d", d)
                            },
                            trailingContent = { Switch(d, null) },
                            content = { Text(stringResource(R.string.debug_log_level_debug)) },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                i = !i
                                WePrefs.putBool("${KEY_PREFIX}i", i)
                            },
                            trailingContent = { Switch(i, null) },
                            content = { Text(stringResource(R.string.debug_log_level_info)) },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                w = !w
                                WePrefs.putBool("${KEY_PREFIX}w", w)
                            },
                            trailingContent = { Switch(w, null) },
                            content = { Text(stringResource(R.string.debug_log_level_warning)) },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                e = !e
                                WePrefs.putBool("${KEY_PREFIX}e", e)
                            },
                            trailingContent = { Switch(e, null) },
                            content = { Text(stringResource(R.string.debug_log_level_error)) },
                        )
                    }
                },
                confirmButton = {
                    Button(onDismiss) { Text(stringResource(R.string.dialog_confirm)) }
                })
        }
    }
}
