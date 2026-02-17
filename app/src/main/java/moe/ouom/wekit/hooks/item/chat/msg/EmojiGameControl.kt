package moe.ouom.wekit.hooks.item.chat.msg

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.ouom.wekit.core.dsl.dexMethod
import moe.ouom.wekit.core.model.BaseClickableFunctionHookItem
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.hooks.core.annotation.HookItem
import moe.ouom.wekit.util.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import java.lang.reflect.Modifier
import kotlin.random.Random
import androidx.core.graphics.toColorInt

@HookItem(path = "聊天与消息/猜拳骰子控制", desc = "自定义猜拳和骰子的结果")
class EmojiGameControl : BaseClickableFunctionHookItem(), IDexFind {
    // DSL: Define methods to be found via DexKit
    private val MethodRandom by dexMethod()
    private val MethodPanelClick by dexMethod()

    // Constants
    private val MD5_MORRA = "9bd1281af3a31710a45b84d736363691"
    private val MD5_DICE = "08f223fa83f1ca34e143d1e580252c7c"

    private var valMorra = 0
    private var valDice = 0

    // Enums for logic
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
        MethodRandom.find(dexKit, descriptors) {
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
        MethodPanelClick.find(dexKit, descriptors) {
            matcher {
                usingStrings("penn send capture emoji click emoji: %s status: %d.")
            }
        }

        return descriptors
    }

    // ================== Hook Entry ==================

    override fun entry(classLoader: ClassLoader) {
        // Hook 1: Control the Random Result
        MethodRandom.toDexMethod {
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
        MethodPanelClick.toDexMethod {
            hook {
                beforeIfEnabled { param ->
                    try {
                        // Arg 3 is the Emoji Info Object
                        val obj = param.args[3] ?: return@beforeIfEnabled

                        // Reflection: Check if status is 0 (first final int field)
                        val fields = obj.javaClass.declaredFields
                        var infoType = -1
                        for (field in fields) {
                            if (field.type == Int::class.javaPrimitiveType && Modifier.isFinal(field.modifiers)) {
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
                        WeLogger.e("EmojiGameControl", "Error in Click Hook", e)
                    }
                }
            }
        }
    }

    // ================== In-App Dialog Logic ==================

    private fun showSelectDialog(param: XC_MethodHook.MethodHookParam, isDice: Boolean) {
        // Prevent original method from sending immediately
        param.result = null

        val activity = getActivity()
        if (activity == null) {
            WeLogger.e("EmojiGameControl", "Cannot find Top Activity")
            return
        }

        activity.runOnUiThread {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle(if (isDice) "选择骰子点数" else "选择猜拳结果")

            val mainLayout = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 20)
            }

            // Mode Selection Radio Group
            val modeLabel = TextView(activity).apply {
                text = "发送模式"
                setTextColor(Color.DKGRAY)
                textSize = 14f
                setPadding(0, 0, 0, 10)
            }
            mainLayout.addView(modeLabel)

            val modeRadioGroup = RadioGroup(activity).apply {
                orientation = RadioGroup.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 20)
            }

            val rbSingle = RadioButton(activity).apply {
                id = View.generateViewId()
                text = "单次"
                isChecked = true
            }
            val rbMultiple = RadioButton(activity).apply {
                id = View.generateViewId()
                text = "多次"
            }
            modeRadioGroup.addView(rbSingle)
            modeRadioGroup.addView(rbMultiple)
            mainLayout.addView(modeRadioGroup)

            // Divider
            mainLayout.addView(View(activity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    2
                ).apply { setMargins(0, 10, 0, 20) }
                setBackgroundColor(Color.LTGRAY)
            })

            // Container for switchable content
            val contentContainer = FrameLayout(activity)
            mainLayout.addView(contentContainer)

            // Single Mode UI: RadioGroup
            val singleModeView = ScrollView(activity).apply {
                val radioGroup = RadioGroup(activity).apply {
                    gravity = Gravity.CENTER
                    orientation = RadioGroup.HORIZONTAL
                    setPadding(20, 20, 20, 20)
                }

                if (isDice) {
                    DiceFace.entries.forEachIndexed { index, face ->
                        radioGroup.addView(RadioButton(activity).apply {
                            id = face.index
                            text = face.chineseName
                            isChecked = (index == 0)  // Select first radio button by default
                            setOnClickListener { valDice = face.index }
                        })
                    }
                    valDice = 0  // Set default value
                } else {
                    MorraType.entries.forEachIndexed { index, type ->
                        radioGroup.addView(RadioButton(activity).apply {
                            id = type.index
                            text = type.chineseName
                            isChecked = (index == 0)  // Select first radio button by default
                            setOnClickListener { valMorra = type.index }
                        })
                    }
                    valMorra = 0  // Set default value
                }

                addView(radioGroup)
            }

            // Multiple Mode UI: EditText
            val multipleModeView = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 20, 20, 20)

                val instructionText = TextView(activity).apply {
                    text = if (isDice) {
                        "输入多个点数（1-6）"
                    } else {
                        "输入多个选项（1-3）\n1=剪刀, 2=石头, 3=布"
                    }
                    setTextColor(Color.GRAY)
                    textSize = 13f
                    setPadding(0, 0, 0, 15)
                }
                addView(instructionText)
            }

            val multipleInput = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                hint = if (isDice) "例如: 123456" else "例如: 123"
                setPadding(20, 20, 20, 20)
                setBackgroundColor("#F5F5F5".toColorInt())
            }
            multipleModeView.addView(multipleInput)

            // Set initial visibility
            contentContainer.addView(singleModeView)
            contentContainer.addView(multipleModeView)
            multipleModeView.visibility = View.GONE

            // Mode switch logic
            modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
                when (checkedId) {
                    rbSingle.id -> {
                        singleModeView.visibility = View.VISIBLE
                        multipleModeView.visibility = View.GONE
                    }
                    rbMultiple.id -> {
                        singleModeView.visibility = View.GONE
                        multipleModeView.visibility = View.VISIBLE
                    }
                }
            }

            val scrollContainer = ScrollView(activity).apply { addView(mainLayout) }
            builder.setView(scrollContainer)

            // Send Button
            builder.setPositiveButton("发送") { _, _ ->
                try {
                    val isSingleMode = modeRadioGroup.checkedRadioButtonId == rbSingle.id

                    if (isSingleMode) {
                        // Single send - existing logic
                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                    } else {
                        // Multiple send - parse input and send sequentially
                        val inputText = multipleInput.text.toString().trim()
                        val values = parseMultipleInput(inputText, isDice)

                        if (values.isEmpty()) {
                            Toast.makeText(activity, "输入格式错误，请重试", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }

                        // Send multiple times with delay
                        sendMultiple(param, values, isDice, activity)
                    }
                } catch (e: Throwable) {
                    WeLogger.e("EmojiGameControl", "Failed to send", e)
                    Toast.makeText(activity, "发送失败", Toast.LENGTH_SHORT).show()
                }
            }

            // Random Button
            builder.setNeutralButton("随机") { _, _ ->
                try {
                    val isSingleMode = modeRadioGroup.checkedRadioButtonId == rbSingle.id

                    if (isSingleMode) {
                        if (isDice) valDice = Random.nextInt(0, 6)
                        else valMorra = Random.nextInt(0, 3)
                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args)
                    } else {
                        // Generate random sequence
                        val count = if (isDice) Random.nextInt(3, 10) else Random.nextInt(3, 8)
                        val values = List(count) {
                            if (isDice) Random.nextInt(0, 6) else Random.nextInt(0, 3)
                        }
                        sendMultiple(param, values, isDice, activity)
                    }
                } catch (e: Throwable) {
                    WeLogger.e("EmojiGameControl", "Failed to send random", e)
                    Toast.makeText(activity, "发送失败", Toast.LENGTH_SHORT).show()
                }
            }

            builder.setNegativeButton("取消", null)
            builder.show()
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
                    WeLogger.e("EmojiGameControl", "Failed to send at index $index", e)
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
            WeLogger.e("EmojiGameControl", "Failed to get Activity", e)
        }
        return null
    }

    // ================== Module Config UI (Manager Side) ==================

    override fun onClick(context: Context) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle("预设随机结果")

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 50, 50, 50)
        }

        // Morra Settings
        val labelMorra = TextView(context).apply {
            text = "猜拳默认值"
            setTextColor(Color.BLACK)
            textSize = 16f
        }
        layout.addView(labelMorra)

        val rgMorra = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        MorraType.entries.forEach {
            rgMorra.addView(RadioButton(context).apply {
                text = it.chineseName
                id = it.index
                isChecked = (valMorra == it.index)
            })
        }
        layout.addView(rgMorra)

        // Spacer
        layout.addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })

        // Dice Settings
        val labelDice = TextView(context).apply {
            text = "骰子默认值"
            setTextColor(Color.BLACK)
            textSize = 16f
        }
        layout.addView(labelDice)

        val rgDice = RadioGroup(context).apply { orientation = RadioGroup.HORIZONTAL }
        DiceFace.entries.forEach {
            rgDice.addView(RadioButton(context).apply {
                text = it.chineseName
                id = it.index
                isChecked = (valDice == it.index)
            })
        }
        layout.addView(rgDice)

        builder.setView(layout)
        builder.setPositiveButton("保存") { _, _ ->
            val selMorraId = rgMorra.checkedRadioButtonId
            if (selMorraId != -1) valMorra = selMorraId

            val selDiceId = rgDice.checkedRadioButtonId
            if (selDiceId != -1) valDice = selDiceId

            WeLogger.i("EmojiGameControl", "Settings saved: Morra=$valMorra, Dice=$valDice")
        }
        builder.setNegativeButton("关闭", null)
        builder.show()
    }
}
