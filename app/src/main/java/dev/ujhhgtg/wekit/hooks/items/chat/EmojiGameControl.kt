package dev.ujhhgtg.wekit.hooks.items.chat

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.dexkit.abc.IResolvesDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.invokeOriginal
import dev.ujhhgtg.wekit.utils.reflection.makeAccessible
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import kotlin.random.Random

@HookItem(path = "聊天/表情游戏控制", description = "自定义猜拳和骰子的结果")
object EmojiGameControl : SwitchHookItem(), IResolvesDex {

    private const val MD5_MORRA = "9bd1281af3a31710a45b84d736363691"
    private const val MD5_DICE = "08f223fa83f1ca34e143d1e580252c7c"
    private val TAG = This.Class.simpleName

    private val methodRandom by dexMethod()
    private val methodPanelClick by dexMethod()

    private var valMorra = 0
    private var valDice = 0

    private enum class MorraType(val index: Int, val chineseName: String) {
        SCISSORS(0, "剪刀"), STONE(1, "石头"), PAPER(2, "布")
    }

    private enum class DiceFace(val index: Int, val chineseName: String) {
        ONE(0, "1"), TWO(1, "2"), THREE(2, "3"),
        FOUR(3, "4"), FIVE(4, "5"), SIX(5, "6")
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        methodRandom.find(dexKit) {
            searchPackages("com.tencent.mm.sdk.platformtools")
            matcher {
                returnType(Int::class.java)
                paramTypes(Int::class.java, Int::class.java)
                invokeMethods {
                    add { name = "currentTimeMillis" }
                    add { name = "nextInt" }
                    matchType = MatchType.Contains
                }
            }
        }

        methodPanelClick.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.EmojiPanelClickListener")
            }
        }
    }

    override fun onEnable() {
        methodRandom.hookAfter {
            val type = args[0] as Int
            // Arg 0 determines type: 2 is Morra, 5 is Dice
            result = when (type) {
                2 -> valMorra
                5 -> valDice
                else -> result
            }
        }

        methodPanelClick.hookBefore {
            val obj = args[3] ?: return@hookBefore

            val fields = obj.javaClass.declaredFields
            var infoType = -1
            for (field in fields) {
                if (field.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isFinal(
                        field.modifiers
                    )
                ) {
                    infoType = field.makeAccessible().getInt(obj)
                    break
                }
            }

            if (infoType == 0) {
                val emojiInfoField =
                    fields.firstOrNull { it.type.name.contains("IEmojiInfo") }

                if (emojiInfoField != null) {
                    emojiInfoField.makeAccessible()
                    val emojiInfo = emojiInfoField.get(obj)

                    if (emojiInfo != null) {
                        val getMd5Method = XposedHelpers.findMethodExact(
                            emojiInfo.javaClass,
                            "getMd5",
                            *arrayOf<Any>()
                        )
                        val emojiMd5 = getMd5Method.invoke(emojiInfo) as? String

                        val activity = ((args[0] as View).context as ContextThemeWrapper).baseContext as Activity

                        when (emojiMd5) {
                            MD5_MORRA -> showSelectDialog(this, false, activity)
                            MD5_DICE -> showSelectDialog(this, true, activity)
                        }
                    }
                }
            }
        }
    }

    private fun showSelectDialog(param: XC_MethodHook.MethodHookParam, isDice: Boolean, activity: Activity) {
        param.result = null

        showComposeDialog(activity) {
            EmojiGameDialogContent(
                isDice = isDice,
                onSend = { isSingle, inputText ->
                    try {
                        if (isSingle) {
                            param.invokeOriginal()
                        } else {
                            val values = parseMultipleInput(inputText, isDice)
                            if (values.isEmpty()) {
                                showToast(activity, "输入格式错误!")
                                return@EmojiGameDialogContent
                            }
                            sendMultiple(param, values, isDice, activity)
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "failed to send", e)
                        showToast(activity, "发送失败")
                    }
                },
                onRandom = { isSingle ->
                    try {
                        if (isSingle) {
                            if (isDice) valDice = Random.nextInt(0, 6)
                            else valMorra = Random.nextInt(0, 3)
                            param.invokeOriginal()
                        } else {
                            val count = if (isDice) Random.nextInt(3, 10) else Random.nextInt(3, 8)
                            val values = List(count) {
                                if (isDice) Random.nextInt(0, 6) else Random.nextInt(0, 3)
                            }
                            sendMultiple(param, values, isDice, activity)
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "failed to send random", e)
                        showToast(activity, "发送失败")
                    }
                },
                onDismiss = onDismiss
            )
        }
    }

    @Composable
    private fun EmojiGameDialogContent(
        isDice: Boolean,
        onSend: (isSingle: Boolean, inputText: String) -> Unit,
        onRandom: (isSingle: Boolean) -> Unit,
        onDismiss: () -> Unit
    ) {
        var isSingleMode by remember { mutableStateOf(true) }
        var inputText by remember { mutableStateOf("") }

        // first item selected by default
        var selectedIndex by remember { mutableIntStateOf(0) }

        val options = if (isDice) DiceFace.entries.map { it.chineseName }
        else MorraType.entries.map { it.chineseName }

        // keep valMorra / valDice in sync
        LaunchedEffect(selectedIndex, isSingleMode) {
            if (isSingleMode) {
                if (isDice) valDice = selectedIndex else valMorra = selectedIndex
            }
        }

        AlertDialogContent(
            title = { Text(if (isDice) "选择骰子点数" else "选择猜拳结果") },
            text = {
                DefaultColumn {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "发送模式: ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf("单次" to true, "多次" to false).forEach { (label, single) ->
                            FilterChip(
                                selected = isSingleMode == single,
                                onClick = { isSingleMode = single },
                                label = { Text(label) }
                            )
                        }
                    }

                    HorizontalDivider()

                    if (isSingleMode) {
                        // --- Single Mode: Direct-send buttons ---
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            options.forEachIndexed { index, label ->
                                FilledTonalButton(
                                    onClick = {
                                        if (isDice) valDice = index else valMorra = index
                                        onSend(true, "")
                                        onDismiss()
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        label,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    } else {
                        // --- Multiple Mode: Text field ---
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it.filter { c -> c.isDigit() } },
                            placeholder = { Text(if (isDice) "输入 1~6" else "输入 1:剪刀 2:石头 3:布") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onDismiss) { Text("取消") }
                TextButton(onClick = {
                    onRandom(isSingleMode)
                    onDismiss()
                }) { Text("随机") }
            },
            confirmButton = {
                // In single mode the option buttons send directly; only show confirm in multi mode
                if (!isSingleMode) {
                    Button(onClick = {
                        onSend(false, inputText)
                        onDismiss()
                    }) { Text("发送") }
                }
            })
    }

    private fun parseMultipleInput(input: String, isDice: Boolean): List<Int> {
        if (input.isEmpty()) return emptyList()

        val maxValue = if (isDice) 6 else 3

        return input.asSequence()
            .mapNotNull { it.digitToIntOrNull() }
            .filter { it in 1..maxValue }
            .map { it - 1 }  // Convert to 0-based index
            .toList()
    }

    private fun sendMultiple(
        param: XC_MethodHook.MethodHookParam,
        values: List<Int>,
        isDice: Boolean,
        activity: Activity
    ) {
        Thread {
            values.forEachIndexed { index, value ->
                try {
                    if (isDice) {
                        valDice = value
                    } else {
                        valMorra = value
                    }

                    param.invokeOriginal()

                    // Add delay between sends (except for the last one)
                    if (index < values.size - 1) {
                        Thread.sleep(300)
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send at index $index", e)
                    activity.runOnUiThread {
                        showToast(activity, "第 ${index + 1} 次发送失败")
                    }
                }
            }

            activity.runOnUiThread {
                showToast(activity, "已发送 ${values.size} 次")
            }
        }.start()
    }
}
