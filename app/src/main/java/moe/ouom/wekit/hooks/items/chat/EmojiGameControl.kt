package moe.ouom.wekit.hooks.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseSwitchFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.ui.compose.showComposeDialog
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import kotlin.random.Random

@HookItem(path = "聊天与消息/猜拳骰子控制", desc = "自定义猜拳和骰子的结果")
object EmojiGameControl : BaseSwitchFunctionHookItem(), IDexFind {
    private const val MD5_MORRA = "9bd1281af3a31710a45b84d736363691"
    private const val MD5_DICE = "08f223fa83f1ca34e143d1e580252c7c"
    private const val TAG = "EmojiGameControl"

    private val methodRandom by dexMethod()
    private val methodPanelClick by dexMethod()

    private var valMorra = 0
    private var valDice = 0

    enum class MorraType(val index: Int, val chineseName: String) {
        SCISSORS(0, "剪刀"), STONE(1, "石头"), PAPER(2, "布")
    }

    enum class DiceFace(val index: Int, val chineseName: String) {
        ONE(0, "一"), TWO(1, "二"), THREE(2, "三"),
        FOUR(3, "四"), FIVE(4, "五"), SIX(5, "六")
    }

    // ================== Dex Finding ==================
    override fun dexFind(dexKit: DexKitBridge): Map<String, String> {
        val descriptors = mutableMapOf<String, String>()

        // Find Random Method
        methodRandom.find(dexKit, descriptors) {
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

        // Find Panel Click Method
        methodPanelClick.find(dexKit, descriptors) {
            matcher {
//                usingStrings("penn send capture emoji click emoji: %s status: %d.")
                usingEqStrings("MicroMsg.EmojiPanelClickListener")
            }
        }

        return descriptors
    }

    override fun entry(classLoader: ClassLoader) {
        // Hook 1: Control the Random Result
        methodRandom.toDexMethod {
            hook {
                afterIfEnabled { param ->
                    val type = param.args[0] as Int
                    // Arg 0 determines type: 2 is Morra, 5 is Dice
                    param.result = when (type) {
                        2 -> valMorra
                        5 -> valDice
                        else -> param.result
                    }
                }
            }
        }

        // Hook 2: Intercept Click to show In-App Selection
        methodPanelClick.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    try {
                        // Arg 3 is the Emoji Info Object
                        val obj = param.args[3] ?: return@beforeIfEnabled

                        // Reflection: Check if status is 0 (first final int field)
                        val fields = obj.javaClass.declaredFields
                        var infoType = -1
                        for (field in fields) {
                            if (field.type == Int::class.javaPrimitiveType && java.lang.reflect.Modifier.isFinal(field.modifiers)) {
                                field.isAccessible = true
                                infoType = field.getInt(obj)
                                break
                            }
                        }

                        if (infoType == 0) {
                            // Reflection: Get EmojiInfo inner object to check MD5
                            val emojiInfoField = fields.firstOrNull { it.type.name.contains("IEmojiInfo") }

                            if (emojiInfoField != null) {
                                emojiInfoField.isAccessible = true
                                val emojiInfo = emojiInfoField.get(obj)

                                if (emojiInfo != null) {
                                    val getMd5Method = XposedHelpers.findMethodExact(emojiInfo.javaClass, "getMd5", *arrayOf<Any>())
                                    val emojiMd5 = getMd5Method.invoke(emojiInfo) as? String

                                    when (emojiMd5) {
                                        MD5_MORRA -> showSelectDialog(param, isDice = false)
                                        MD5_DICE -> showSelectDialog(param, isDice = true)
                                    }
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "Error in Click Hook", e)
                    }
                }
            }
        }
    }

    private fun showSelectDialog(param: XC_MethodHook.MethodHookParam, isDice: Boolean) {
        param.result = null

        val activity = getActivity() ?: run {
            WeLogger.e(TAG, "Cannot find Top Activity")
            return
        }

        showComposeDialog(activity) { onDismiss ->
            EmojiGameDialog(
                isDice = isDice,
                onSend = { isSingle, inputText ->
                    try {
                        if (isSingle) {
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                        } else {
                            val values = parseMultipleInput(inputText, isDice)
                            if (values.isEmpty()) {
                                Toast.makeText(activity, "输入格式错误，请重试", Toast.LENGTH_SHORT).show()
                                return@EmojiGameDialog
                            }
                            sendMultiple(param, values, isDice, activity)
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "failed to send", e)
                        Toast.makeText(activity, "发送失败", Toast.LENGTH_SHORT).show()
                    }
                },
                onRandom = { isSingle ->
                    try {
                        if (isSingle) {
                            if (isDice) valDice = Random.nextInt(0, 6)
                            else valMorra = Random.nextInt(0, 3)
                            XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                        } else {
                            val count = if (isDice) Random.nextInt(3, 10) else Random.nextInt(3, 8)
                            val values = List(count) {
                                if (isDice) Random.nextInt(0, 6) else Random.nextInt(0, 3)
                            }
                            sendMultiple(param, values, isDice, activity)
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "failed to send random", e)
                        Toast.makeText(activity, "发送失败", Toast.LENGTH_SHORT).show()
                    }
                },
                onDismiss = onDismiss
            )
        }
    }

    @Composable
    private fun EmojiGameDialog(
        isDice: Boolean,
        onSend: (isSingle: Boolean, inputText: String) -> Unit,
        onRandom: (isSingle: Boolean) -> Unit,
        onDismiss: () -> Unit
    ) {
        var isSingleMode by remember { mutableStateOf(true) }
        var inputText    by remember { mutableStateOf("") }

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

        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = if (isDice) "选择骰子点数" else "选择猜拳结果",
                    style = MaterialTheme.typography.titleMedium
                )

                HorizontalDivider()

                // ── Mode selector ──────────────────────────────────────────────
                Text("发送模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("单次" to true, "多次" to false).forEach { (label, single) ->
                        FilterChip(
                            selected = isSingleMode == single,
                            onClick  = { isSingleMode = single },
                            label    = { Text(label) }
                        )
                    }
                }

                HorizontalDivider()

                // ── Single mode: radio buttons ─────────────────────────────────
                AnimatedVisibility(visible = isSingleMode) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        options.forEachIndexed { index, label ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { selectedIndex = index }
                            ) {
                                RadioButton(
                                    selected = selectedIndex == index,
                                    onClick  = { selectedIndex = index }
                                )
                                Text(label)
                            }
                        }
                    }
                }

                // ── Multiple mode: text input ──────────────────────────────────
                AnimatedVisibility(visible = !isSingleMode) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = if (isDice) "输入多个点数 (1-6)" else "输入多个选项 (1-3)\n1=剪刀, 2=石头, 3=布",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value         = inputText,
                            onValueChange = { inputText = it },
                            placeholder   = { Text(if (isDice) "例如: 123456" else "例如: 123") },
                            singleLine    = true,
                            modifier      = Modifier.fillMaxWidth()
                        )
                    }
                }

                // ── Buttons ───────────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = { onDismiss() }) { Text("取消") }
                    TextButton(onClick = { onRandom(isSingleMode); onDismiss() }) { Text("随机") }
                    Button(onClick = { onSend(isSingleMode, inputText); onDismiss() })  { Text("发送") }
                }
            }
        }
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
                    // Set the value for this iteration
                    if (isDice) {
                        valDice = value
                    } else {
                        valMorra = value
                    }

                    // Invoke the original method
                    XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)

                    // Add delay between sends (except for the last one)
                    if (index < values.size - 1) {
                        Thread.sleep(300)
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send at index $index", e)
                    activity.runOnUiThread {
                        Toast.makeText(activity, "第 ${index + 1} 次发送失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            activity.runOnUiThread {
                Toast.makeText(activity, "已发送 ${values.size} 次", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    // Helper to get current activity
    @SuppressLint("PrivateApi")
    private fun getActivity(): Activity? {
        try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = XposedHelpers.callStaticMethod(activityThreadClass, "currentActivityThread")
            val activities = XposedHelpers.getObjectField(activityThread, "mActivities") as Map<*, *>
            for (value in activities.values) {
                val activityRecord = value as Any
                val paused = XposedHelpers.getBooleanField(activityRecord, "paused")
                if (!paused) {
                    return XposedHelpers.getObjectField(activityRecord, "activity") as Activity
                }
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "Failed to get Activity", e)
        }
        return null
    }
}
