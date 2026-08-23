package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tencent.mm.api.IEmojiInfo
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HookParam
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.OriginalMethodInvoker
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.getSystemService
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.captureOriginalMethod
import dev.ujhhgtg.wekit.utils.invokeOriginalMethod
import dev.ujhhgtg.wekit.utils.reflection.int
import org.luckypray.dexkit.query.enums.MatchType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.random.Random

object EmojiGameControl : ClickableFeature(), IResolveDex {

    override val technicalId = "表情游戏控制"
    override val nameRes = R.string.feature_emoji_game_control_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_emoji_game_control_description

    private const val MD5_MORRA = "9bd1281af3a31710a45b84d736363691"
    private const val MD5_DICE = "08f223fa83f1ca34e143d1e580252c7c"
    internal val GAME_EMOJI_MD5S = setOf(MD5_MORRA, MD5_DICE)
    private const val GRAVITY_EARTH = 9.81f
    private const val MOTION_THRESHOLD = 2.0f
    private const val TAG = "EmojiGameControl"

    private val methodRandom by dexMethod {
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
    private val methodPanelClick by dexMethod {
        matcher {
            usingEqStrings("MicroMsg.EmojiPanelClickListener")
        }
    }

    private var valMorra = 0
    private var valDice = 0

    private var stealthMode by prefOption("emoji_game_stealth", false)

    private enum class MorraType(@StringRes val nameRes: Int) {
        SCISSORS(R.string.chat_emoji_game_scissors),
        STONE(R.string.chat_emoji_game_rock),
        PAPER(R.string.chat_emoji_game_paper),
    }

    private enum class DiceFace(val chineseName: String) {
        ONE("1"), TWO("2"), THREE("3"),
        FOUR("4"), FIVE("5"), SIX("6")
    }

    // --- Sensor infrastructure ---

    private val latestAccel = FloatArray(3)
    private var sensorManager: SensorManager? = null
    private var keepAliveTask: Runnable? = null
    private var keepAliveHandler: Handler? = null

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                System.arraycopy(event.values, 0, latestAccel, 0, 3)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private fun ensureSensorAlive(delayMs: Long) {
        if (sensorManager == null) {
            sensorManager = HostInfo.application.getSystemService<SensorManager>()
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                sensorManager?.registerListener(accelListener, accel, SensorManager.SENSOR_DELAY_NORMAL)
            } else {
                WeLogger.w(TAG, "accelerometer not available")
            }
        }
        // Reset keep-alive: cancel pending stop, schedule a new one
        keepAliveTask?.let { keepAliveHandler?.removeCallbacks(it) }
        keepAliveTask = Runnable {
            sensorManager?.unregisterListener(accelListener)
            sensorManager = null
        }
        keepAliveHandler = keepAliveHandler ?: Handler(Looper.getMainLooper())
        keepAliveHandler?.postDelayed(keepAliveTask!!, delayMs)
    }

    private fun computePitchRoll(ax: Float, ay: Float, az: Float): Pair<Float, Float> {
        val pitch = Math.toDegrees(atan2(-ay.toDouble(), az.toDouble())).toFloat()
        val roll = Math.toDegrees(atan2(ax.toDouble(), az.toDouble())).toFloat()
        return Pair(pitch, roll)
    }

    private fun isInMotion(ax: Float, ay: Float, az: Float): Boolean {
        val magnitude = sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
        return abs(magnitude - GRAVITY_EARTH) > MOTION_THRESHOLD
    }

    private fun mapToValue(ax: Float, ay: Float, az: Float, isDice: Boolean): Int {
        // No sensor data yet — fall back to random
        if (ax == 0f && ay == 0f && az == 0f) {
            WeLogger.w(TAG, "no sensor data, using random")
            showToast(localizedChatString(R.string.chat_emoji_game_no_sensor))
            return Random.nextInt(if (isDice) 6 else 3)
        }

        // Accelerating significantly — motion fallback
        if (isInMotion(ax, ay, az)) {
            WeLogger.w(TAG, "accelerating signaficantly")
            showToast(localizedChatString(R.string.chat_emoji_game_motion_fallback))
            return Random.nextInt(if (isDice) 6 else 3)
        }

        val (pitch, roll) = computePitchRoll(ax, ay, az)

        return if (isDice) {
            // device nearly vertical (gravity off z-axis)
            if (abs(az) < 5.0f) {
                5  // vertical → 6
            } else if (pitch < -30f) {
                3  // forward → 4
            } else if (pitch > 30f) {
                4  // backward → 5
            } else {
                when {
                    roll < -25f -> 2  // left → 1
                    roll <= 25f -> 1  // center → 2
                    else -> 0         // right → 3
                }
            }
        } else {
            when {
                roll < -25f -> 2   // left → STONE
                roll <= 25f -> 0   // center → SCISSORS
                else -> 1          // right → PAPER
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

        // Start accelerometer when entering a conversation (first time: 20s)
        ChatFooter::class.reflekt()
            .firstMethod {
                name = "setUserName"
            }.hookAfter {
                val conv = args[0] as? String
                if (!conv.isNullOrEmpty()) {
                    ensureSensorAlive(20000L)
                }
            }

        methodPanelClick.hookBefore {
            val obj = args[3] ?: return@hookBefore

            val infoType = obj.reflekt().firstField {
                type = int
                modifiers(Modifiers.FINAL)
            }.get() as Int

            if (infoType != 0) return@hookBefore

            val emojiInfo = obj.reflekt().firstFieldOrNull {
                type = IEmojiInfo::class
            }?.get() as? IEmojiInfo? ?: return@hookBefore

            val emojiMd5 = emojiInfo.md5
            val isDice = emojiMd5 == MD5_DICE
            val isMorra = emojiMd5 == MD5_MORRA

            if (!isDice && !isMorra) return@hookBefore

            val activity = ((args[0] as View).context as ContextThemeWrapper).baseContext as Activity

            if (stealthMode) {
                result = null
                ensureSensorAlive(10000L)

                val (ax, ay, az) = latestAccel.let { Triple(it[0], it[1], it[2]) }
                val value = mapToValue(ax, ay, az, isDice)
                if (isDice) valDice = value else valMorra = value

                val name = if (isDice) DiceFace.entries[value].chineseName
                else activity.localizedChatString(MorraType.entries[value].nameRes)
                showToast(
                    activity,
                    activity.localizedChatString(
                        if (isDice) R.string.chat_emoji_game_dice_result else R.string.chat_emoji_game_morra_result,
                        name,
                    ),
                )

                invokeOriginalMethod()
            } else {
                showSelectDialog(this, isDice, activity)
            }
        }
    }

    override fun onDisable() {
        keepAliveTask?.let { keepAliveHandler?.removeCallbacks(it) }
        keepAliveHandler?.removeCallbacksAndMessages(null)
        sensorManager?.unregisterListener(accelListener)
        sensorManager = null
        keepAliveTask = null
        keepAliveHandler = null
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_emoji_game_control_name)) },
                text = {
                    var stealthInput by remember { mutableStateOf(stealthMode) }

                    SwitchWidget(
                        title = stringResource(R.string.chat_emoji_game_stealth),
                        description = stringResource(R.string.chat_emoji_game_stealth_description),
                        checked = stealthInput,
                        onCheckedChange = {
                            stealthInput = it
                            stealthMode = it
                        },
                    )
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun showSelectDialog(param: HookParam, isDice: Boolean, activity: Activity) {
        val originalMethod = param.captureOriginalMethod()
        param.result = null

        showComposeDialog(activity) {
            EmojiGameDialogContent(
                isDice = isDice,
                onSend = { isSingle, inputText ->
                    try {
                        if (isSingle) {
                            originalMethod()
                        } else {
                            val values = parseMultipleInput(inputText, isDice)
                            if (values.isEmpty()) {
                                showToast(activity, activity.localizedChatString(R.string.chat_emoji_game_invalid_input))
                                return@EmojiGameDialogContent
                            }
                            sendMultiple(originalMethod, values, isDice, activity)
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "failed to send", e)
                        showToast(activity, activity.localizedChatString(R.string.chat_send_failed))
                    }
                },
                onRandom = { isSingle ->
                    try {
                        if (isSingle) {
                            if (isDice) valDice = Random.nextInt(0, 6)
                            else valMorra = Random.nextInt(0, 3)
                            originalMethod()
                        } else {
                            val count = if (isDice) Random.nextInt(3, 10) else Random.nextInt(3, 8)
                            val values = List(count) {
                                if (isDice) Random.nextInt(0, 6) else Random.nextInt(0, 3)
                            }
                            sendMultiple(originalMethod, values, isDice, activity)
                        }
                    } catch (e: Throwable) {
                        WeLogger.e(TAG, "failed to send random", e)
                        showToast(activity, activity.localizedChatString(R.string.chat_send_failed))
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
        else MorraType.entries.map { stringResource(it.nameRes) }

        // keep valMorra / valDice in sync
        LaunchedEffect(selectedIndex, isSingleMode) {
            if (isSingleMode) {
                if (isDice) valDice = selectedIndex else valMorra = selectedIndex
            }
        }

        AlertDialogContent(
            title = { Text(stringResource(if (isDice) R.string.chat_emoji_game_select_dice else R.string.chat_emoji_game_select_morra)) },
            text = {
                DefaultColumn {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.chat_emoji_game_send_mode),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(
                            stringResource(R.string.chat_emoji_game_single) to true,
                            stringResource(R.string.chat_emoji_game_multiple) to false,
                        ).forEach { (label, single) ->
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
                            placeholder = { Text(stringResource(if (isDice) R.string.chat_emoji_game_dice_input else R.string.chat_emoji_game_morra_input)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                TextButton(onClick = {
                    onRandom(isSingleMode)
                    onDismiss()
                }) { Text(stringResource(R.string.chat_emoji_game_random)) }
            },
            confirmButton = {
                // In single mode the option buttons send directly; only show confirm in multimode
                if (!isSingleMode) {
                    Button(onClick = {
                        onSend(false, inputText)
                        onDismiss()
                    }) { Text(stringResource(R.string.chat_emoji_game_send)) }
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
        originalMethod: OriginalMethodInvoker,
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

                    originalMethod()

                    // Add delay between sends (except for the last one)
                    if (index < values.size - 1) {
                        Thread.sleep(300)
                    }
                } catch (e: Throwable) {
                    WeLogger.e(TAG, "failed to send at index $index", e)
                    activity.runOnUiThread {
                        showToast(activity, activity.localizedChatString(R.string.chat_emoji_game_send_index_failed, index + 1))
                    }
                }
            }

            activity.runOnUiThread {
                showToast(activity, activity.localizedChatQuantity(R.plurals.chat_emoji_game_sent_count, values.size, values.size))
            }
        }.start()
    }
}
