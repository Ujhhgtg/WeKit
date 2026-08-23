package dev.ujhhgtg.wekit.features.items.system

import android.content.ContentValues
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chevron_right
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexConstructor
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.net.WeNetSceneApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.BaseSupportingWidget
import dev.ujhhgtg.wekit.ui.content.m3.BaseWidget
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.BString
import dev.ujhhgtg.wekit.utils.reflection.int
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

object AutoLikeSportsRank : ClickableFeature(), IResolveDex,
    WeDatabaseListenerApi.IInsertListener {

    override val technicalId = "运动排行榜自动点赞"
    override val nameRes = R.string.feature_auto_like_sports_rank_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_auto_like_sports_rank_description

    private const val TAG = "AutoLikeSportsRank"
    private const val SPORTS_ACCOUNT = "gh_43f2581f6fd6"
    private const val MESSAGE_MAX_AGE_MS = 15 * 60 * 1000L
    private const val LIKE_DELAY_MS = 60_000L

    private val ctorSportsRankLike by dexConstructor {
        matcher {
            usingEqStrings("/cgi-bin/mmbiz-bin/rank/addlike")
            paramCount(4)
            paramTypes(BString, BString, int, BString)
        }
    }

    private var minScore by WePrefs.prefOption("sports_rank_min_score", 0)
    private var useWhitelist by WePrefs.prefOption("sports_rank_use_whitelist", false)
    private var whitelist by WePrefs.prefOption("sports_rank_whitelist", emptySet())
    private var blacklist by WePrefs.prefOption("sports_rank_blacklist", emptySet())

    private var scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onEnable() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        WeDatabaseListenerApi.addListener(this)
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        scope.cancel()
    }

    override fun onInsert(table: String, values: ContentValues) {
        if (table != "message") return
        if (values.getAsInteger("isSend") ?: 0 == 1) return
        if (values.getAsString("talker") != SPORTS_ACCOUNT) return

        values.getAsLong("createTime")?.let { createTime ->
            val now = System.currentTimeMillis()
            val normalizedCreateTime = if (decimalDigits(createTime) == decimalDigits(now)) {
                createTime
            } else {
                createTime * 1000L
            }
            if (abs(now - normalizedCreateTime) > MESSAGE_MAX_AGE_MS) return
        }

        scope.launch {
            delay(LIKE_DELAY_MS.milliseconds)
            likeEligibleUsers()
        }
    }

    private fun decimalDigits(value: Long): Int =
        if (value == 0L) 1 else value.toString().removePrefix("-").length

    private fun likeEligibleUsers() {
        if (!WeDatabaseApi.isReady) {
            WeLogger.w(TAG, "database is not ready; skipping sports rank auto-like")
            return
        }

        val rankId = try {
            WeDatabaseApi.rawQuery(
                "SELECT rankID FROM HardDeviceRankInfo ORDER BY rankID DESC LIMIT 1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to read latest sports rank ID", e)
            return
        }
        if (rankId.isNullOrEmpty()) return

        val selfWxId = WeApi.selfWxId
        val targets = mutableListOf<Pair<String, Int>>()
        try {
            WeDatabaseApi.rawQuery(
                "SELECT username, score FROM HardDeviceRankInfo " +
                    "WHERE rankID = ? AND username <> ? AND selfLikeState <> 1 ORDER BY score DESC",
                arrayOf(rankId, selfWxId),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val username = cursor.getString(0) ?: continue
                    targets += username to cursor.getInt(1)
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to read sports rank entries; rankId=$rankId", e)
            return
        }

        val currentMinScore = minScore
        val currentUseWhitelist = useWhitelist
        val currentList = if (currentUseWhitelist) whitelist else blacklist
        val sportsAccountName = WeDatabaseApi.getDisplayName(SPORTS_ACCOUNT)
        for ((username, score) in targets) {
            if (currentUseWhitelist && username !in currentList) continue
            if (!currentUseWhitelist && username in currentList) continue
            if (score <= currentMinScore) continue

            try {
                val request = ctorSportsRankLike.newInstance(username, sportsAccountName, 1, rankId)
                WeNetSceneApi.sendNetScene(request)
            } catch (e: Exception) {
                WeLogger.e(TAG, "failed to like sports rank user; username=$username rankId=$rankId", e)
            }
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var minScoreInput by remember { mutableStateOf(minScore.toString()) }
            var useWhitelistState by remember { mutableStateOf(useWhitelist) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.feature_auto_like_sports_rank_name)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            BaseSupportingWidget(
                                title = stringResource(R.string.sports_rank_min_score),
                            ) {
                                OutlinedTextField(
                                    value = minScoreInput,
                                    onValueChange = { value ->
                                        minScoreInput = value.filter(Char::isDigit).take(9)
                                        minScore = minScoreInput.toIntOrNull() ?: 0
                                    },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                )
                            }
                        }
                        item {
                            SwitchWidget(
                                iconPlaceholder = false,
                                title = stringResource(
                                    if (useWhitelistState) R.string.chat_auto_cache_whitelist_selected
                                    else R.string.chat_auto_cache_blacklist_selected
                                ),
                                description = stringResource(
                                    if (useWhitelistState) R.string.sports_rank_whitelist_description
                                    else R.string.sports_rank_blacklist_description
                                ),
                                checked = useWhitelistState,
                                onCheckedChange = {
                                    useWhitelistState = it
                                    useWhitelist = it
                                },
                            )
                        }
                        item {
                            BaseWidget(
                                iconPlaceholder = false,
                                title = stringResource(
                                    if (useWhitelistState) R.string.chat_auto_cache_configure_whitelist
                                    else R.string.chat_auto_cache_configure_blacklist
                                ),
                                description = stringResource(R.string.chat_auto_cache_select_contacts_hint),
                                onClick = {
                                    val currentList = if (useWhitelistState) whitelist else blacklist
                                    showComposeDialog(context) {
                                        ContactsSelector(
                                            title = stringResource(
                                                if (useWhitelistState) R.string.chat_auto_cache_select_whitelist
                                                else R.string.chat_auto_cache_select_blacklist
                                            ),
                                            contacts = WeDatabaseApi.getFriends(),
                                            initialSelectedWxIds = currentList,
                                            onDismiss = onDismiss,
                                        ) { selectedIds ->
                                            if (useWhitelistState) {
                                                whitelist = selectedIds
                                            } else {
                                                blacklist = selectedIds
                                            }
                                            showToast(
                                                localizedSystemString(
                                                    R.string.sports_rank_contacts_saved,
                                                    selectedIds.size,
                                                )
                                            )
                                            onDismiss()
                                        }
                                    }
                                },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Chevron_right,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }
}
