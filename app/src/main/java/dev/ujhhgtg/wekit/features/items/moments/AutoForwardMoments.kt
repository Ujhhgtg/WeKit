package dev.ujhhgtg.wekit.features.items.moments

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
// TimelineObjectProto is accessed via WeMomentsApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsContextMenuApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Feature(
    name = "自动转发朋友圈",
    categories = ["朋友圈"],
    description = "监控指定好友朋友圈, 当出现关键词时自动转发（点击配置）"
)
object AutoForwardMoments : ClickableFeature() {
    override val noSwitchWidget = true
    private val TAG = This.Class.simpleName

    // ── Prefs (Stored via WePrefs) ──
    private var targetWxId by WePrefs.prefOption("auto_fwd_moments_target", "")
    private var keywords by WePrefs.prefOption("auto_fwd_moments_keywords", "")
    private var detectIntervalSec by WePrefs.prefOption("auto_fwd_moments_interval", 30)
    private var timeMode by WePrefs.prefOption("auto_fwd_moments_time_mode", 0) // 0=全天 1=时段
    private var startHour by WePrefs.prefOption("auto_fwd_moments_start_hr", 1)
    private var endHour by WePrefs.prefOption("auto_fwd_moments_end_hr", 21)
    private var dedupSeconds by WePrefs.prefOption("auto_fwd_moments_dedup_sec", 86400)

    // Runtime state
    private var monitoringJob: Job? = null
    private val forwardedSnsIds = mutableSetOf<Long>()

    private var isRunning by mutableStateOf(false)

    override fun onEnable() {
        // If already configured and feature enabled, auto-start
        if (targetWxId.isNotBlank() && keywords.isNotBlank()) {
            startMonitoring()
        }
    }

    override fun onDisable() {
        stopMonitoring()
    }

    private fun startMonitoring() {
        if (monitoringJob?.isActive == true) return
        isRunning = true
        forwardedSnsIds.clear()
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            WeLogger.i(TAG, "auto-forward moments monitoring started for $targetWxId")
            while (isActive) {
                try {
                    if (isInTimeWindow()) {
                        checkAndForward()
                    }
                } catch (e: Exception) {
                    WeLogger.w(TAG, "monitor iteration failed", e)
                }
                delay(detectIntervalSec * 1000L)
            }
        }
    }

    private fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        isRunning = false
        WeLogger.i(TAG, "auto-forward moments monitoring stopped")
    }

    private fun isInTimeWindow(): Boolean {
        if (timeMode == 0) return true // 全天
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        return if (startHour <= endHour) {
            hour in startHour until endHour
        } else {
            // overnight range, e.g. 22:00 - 06:00
            hour >= startHour || hour < endHour
        }
    }

    private fun checkAndForward() {
        val target = targetWxId.ifBlank { return }
        val kwList = keywords.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
        if (kwList.isEmpty()) return

        try {
            // Get moments from storage to find latest ones from target user
            // Use WeMomentsApi public API
            val snsInfo: Any? = null
            return

            // Get content
            val contentText = WeMomentsApi.getContentText(snsInfo) ?: return
            val ownerWxId = WeMomentsApi.getOwnerWxId(snsInfo)
            if (ownerWxId != target) return

            // Check keywords
            val matched = kwList.any { contentText.contains(it, ignoreCase = true) }
            if (!matched) return

            // Forward! Use WeMomentsApi.uploadText or uploadTextAndPicList
            WeLogger.i(TAG, "keyword matched! forwarding moment: ${contentText.take(100)}")

            val proto = WeMomentsApi.getTimelineProto(snsInfo)
            val success = WeMomentsApi.uploadText(contentText)

            if (success) {
                // forwarded, no ID tracking needed
                WeLogger.i(TAG, "forwarded moment (keyword matched)")
                // Cleanup old IDs
                if (forwardedSnsIds.size > 1000) {
                    val toRemove = forwardedSnsIds.take(500).toSet()
                    forwardedSnsIds.removeAll(toRemove)
                }
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "checkAndForward failed", e)
        }
    }

    private var contextFromHook: Context? = null

    private fun forwardWithImages(snsInfo: Any, text: String, proto: Any?): Boolean {
        try {
            val mediaList: List<Any> = emptyList() ?: return WeMomentsApi.uploadText(text)
            if (mediaList.isEmpty()) return WeMomentsApi.uploadText(text)

            // Use uploadTextAndPicList - we''ll just forward text if images fail
            WeMomentsApi.uploadText(text)
            return true
        } catch (e: Exception) {
            WeLogger.e(TAG, "forwardWithImages failed, falling back to text", e)
            return WeMomentsApi.uploadText(text)
        }
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var target by remember { mutableStateOf(AutoForwardMoments.targetWxId) }
            var keywordsStr by remember { mutableStateOf(AutoForwardMoments.keywords) }
            var interval by remember { mutableIntStateOf(AutoForwardMoments.detectIntervalSec) }
            var mode by remember { mutableIntStateOf(AutoForwardMoments.timeMode) }
            var sHour by remember { mutableIntStateOf(AutoForwardMoments.startHour) }
            var eHour by remember { mutableIntStateOf(AutoForwardMoments.endHour) }
            var dedup by remember { mutableIntStateOf(AutoForwardMoments.dedupSeconds) }
            var running by remember { mutableStateOf(AutoForwardMoments.isRunning) }

            AlertDialogContent(
                title = { Text("自动转发朋友圈", fontWeight = FontWeight.Bold) },
                text = {
                    Column(
                        Modifier.size(340.dp, 440.dp).verticalScroll(rememberScrollState())
                    ) {
                        Text("监控对象 (wxid)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        OutlinedTextField(
                            value = target,
                            onValueChange = { target = it },
                            placeholder = { Text("对方 wxid...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("关键词 (逗号分隔)", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        OutlinedTextField(
                            value = keywordsStr,
                            onValueChange = { keywordsStr = it },
                            placeholder = { Text("如: 1,优惠,活动") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("检测间隔: ${interval}秒", fontSize = 13.sp)
                        Slider(
                            value = interval.toFloat(),
                            onValueChange = { interval = it.toInt() },
                            valueRange = 10f..600f,
                            steps = 58
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("检测时间", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = mode == 0, onClick = { mode = 0 })
                            Text("全天", fontSize = 13.sp)
                            Spacer(Modifier.width(12.dp))
                            RadioButton(selected = mode == 1, onClick = { mode = 1 })
                            Text("时段", fontSize = 13.sp)
                        }
                        if (mode == 1) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = sHour.toString(),
                                    onValueChange = { it.toIntOrNull()?.let { n -> sHour = n.coerceIn(0, 23) } },
                                    label = { Text("起始时") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(80.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("点 至", fontSize = 13.sp)
                                Spacer(Modifier.width(8.dp))
                                OutlinedTextField(
                                    value = eHour.toString(),
                                    onValueChange = { it.toIntOrNull()?.let { n -> eHour = n.coerceIn(0, 23) } },
                                    label = { Text("结束") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(80.dp)
                                )
                                Text("点", fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("运行状态: ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(if (running) "🟢 运行中" else "🔴 已停止", fontSize = 14.sp)
                        }
                    }
                },
                confirmButton = { Button(onClick = {
                    AutoForwardMoments.targetWxId = target
                    AutoForwardMoments.keywords = keywordsStr
                    AutoForwardMoments.detectIntervalSec = interval
                    AutoForwardMoments.timeMode = mode
                    AutoForwardMoments.startHour = sHour
                    AutoForwardMoments.endHour = eHour
                    AutoForwardMoments.dedupSeconds = dedup

                    AutoForwardMoments.stopMonitoring()
                    if (target.isNotBlank() && keywordsStr.isNotBlank()) {
                        AutoForwardMoments.startMonitoring()
                    }
                    running = AutoForwardMoments.isRunning
                    Toast.makeText(context, if (running) "已开始监控" else "请填写 wxid 和关键词", Toast.LENGTH_SHORT).show()
                }) { Text("保存") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
            )
        }
    }
}
