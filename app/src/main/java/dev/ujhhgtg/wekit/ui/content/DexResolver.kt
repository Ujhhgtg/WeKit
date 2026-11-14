package dev.ujhhgtg.wekit.ui.content

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dex.cache.CloudDexResolver
import dev.ujhhgtg.wekit.dex.cache.DexCacheManager
import dev.ujhhgtg.wekit.dex.resolution.LocalDexFailure
import dev.ujhhgtg.wekit.dex.resolution.LocalDexProgress
import dev.ujhhgtg.wekit.dex.resolution.LocalDexResolver
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.i18n.LocalLocalizedContext
import dev.ujhhgtg.wekit.i18n.localizedString
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.restartHost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DexResolutionMode {
    /** Items need resolution at startup: resolution starts as soon as the dialog is shown. */
    Startup,

    /** User-requested full re-run: waits for confirmation, then clears every cache first. */
    Rerun,
}

private sealed interface DialogPhase {
    data object Ready : DialogPhase

    data object DownloadingCloud : DialogPhase

    data class ResolvingLocal(val total: Int) : DialogPhase
}

private const val TAG = "DexResolver"

/**
 * Below this many items, local resolution is fast enough that the cloud report is not worth
 * fetching.
 */
private const val CLOUD_MIN_ITEMS = 20

@Composable
fun DexResolver(
    items: List<BaseFeature>,
    mode: DexResolutionMode,
    scope: CoroutineScope,
    dismiss: () -> Unit,
) {
    val localizedContext = LocalLocalizedContext.current
    var phase by remember { mutableStateOf<DialogPhase>(DialogPhase.Ready) }
    var pendingItems by remember { mutableStateOf(items) }
    var currentTask by remember { mutableStateOf<LocalDexProgress?>(null) }
    var completed by remember { mutableIntStateOf(0) }
    val localResults = remember { mutableStateMapOf<String, LocalDexProgress>() }

    fun updateProgress(progress: LocalDexProgress) {
        if (progress is LocalDexProgress.Complete || progress is LocalDexProgress.Failed) {
            currentTask = progress
            localResults[progress.displayName] = progress
            completed = localResults.size
        }
    }

    fun startResolution() {
        phase =
            if (items.size < CLOUD_MIN_ITEMS) {
                DialogPhase.ResolvingLocal(items.size)
            } else {
                DialogPhase.DownloadingCloud
            }
        scope.launch {
            if (mode == DexResolutionMode.Rerun) {
                withContext(Dispatchers.IO) { DexCacheManager.clearAllCache() }
            }
            val remainingItems =
                if (items.size < CLOUD_MIN_ITEMS) {
                    items
                } else {
                    try {
                        CloudDexResolver.resolve(items).remainingItems
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        WeLogger.e(TAG, "cloud resolution failed", error)
                        items
                    }
                }
            withContext(Dispatchers.Main.immediate) {
                pendingItems = remainingItems
            }

            val errorReport =
                if (remainingItems.isEmpty()) {
                    null
                } else {
                    withContext(Dispatchers.Main.immediate) {
                        currentTask = null
                        completed = 0
                        localResults.clear()
                        phase = DialogPhase.ResolvingLocal(remainingItems.size)
                    }
                    try {
                        val result =
                            LocalDexResolver.resolve(remainingItems) { progress ->
                                withContext(Dispatchers.Main.immediate) { updateProgress(progress) }
                            }
                        result.failures
                            .takeIf { it.isNotEmpty() }
                            ?.let { buildErrorReport(localizedContext, it) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        // LocalDexResolver has recorded every unfinished item as failed with this
                        // error.
                        WeLogger.e(TAG, "local resolution failed", error)
                        buildErrorReport(
                            localizedContext,
                            listOf(
                                LocalDexFailure(
                                    localizedContext.getString(
                                        R.string.dex_cache_batch_failure_subject,
                                    ),
                                    error,
                                ),
                            ),
                        )
                    }
                }

            withContext(Dispatchers.Main) {
                if (errorReport != null) {
                    copyToClipboard(localizedContext, errorReport)
                    showToast(
                        localizedContext,
                        localizedContext.getString(R.string.dex_cache_error_report_copied),
                    )
                }
                dismiss()
                restartHost()
            }
        }
    }

    LaunchedEffect(Unit) {
        if (mode == DexResolutionMode.Startup) startResolution()
    }

    AlertDialogContent(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.dex_cache_update_title))

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "${pendingItems.size}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val tipText =
                    when (phase) {
                        is DialogPhase.Ready -> {
                            pluralStringResource(
                                R.plurals.dex_cache_rerun_message,
                                pendingItems.size,
                                pendingItems.size,
                            )
                        }

                        is DialogPhase.DownloadingCloud -> {
                            stringResource(R.string.dex_cache_cloud_downloading)
                        }

                        is DialogPhase.ResolvingLocal -> {
                            null
                        }
                    }
                if (tipText != null) {
                    Text(text = tipText, style = MaterialTheme.typography.bodyMedium)
                }

                AnimatedVisibility(visible = phase is DialogPhase.DownloadingCloud) {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                val localPhase = phase as? DialogPhase.ResolvingLocal
                AnimatedVisibility(visible = localPhase != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val currentTaskText =
                            when (val task = currentTask) {
                                is LocalDexProgress.Complete -> {
                                    stringResource(
                                        R.string.dex_cache_status_completed,
                                        task.displayName,
                                    )
                                }

                                is LocalDexProgress.Failed -> {
                                    stringResource(
                                        R.string.dex_cache_status_failed,
                                        task.displayName,
                                    )
                                }

                                else -> {
                                    stringResource(R.string.dex_cache_status_resolving)
                                }
                            }
                        Text(text = currentTaskText, style = MaterialTheme.typography.bodyMedium)
                        LinearWavyProgressIndicator(
                            progress = {
                                if (localPhase == null || localPhase.total == 0) {
                                    0f
                                } else {
                                    completed.toFloat() / localPhase.total
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            amplitude = { progress ->
                                if (progress == 0f || progress == 1f) 0f else 1f
                            },
                        )
                        Text(
                            text =
                                stringResource(
                                    R.string.dex_cache_total_progress,
                                    completed,
                                    localPhase?.total ?: 0,
                                ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        dismissButton = {
            if (phase is DialogPhase.Ready) {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.dialog_cancel)) }
            }
        },
        confirmButton = {
            if (phase is DialogPhase.Ready) {
                TextButton(onClick = ::startResolution) {
                    Text(stringResource(R.string.dex_cache_start_local_resolution))
                }
            }
        },
    )
}

private fun buildErrorReport(context: Context, failedResults: List<LocalDexFailure>) = buildString {
    append(context.localizedString(R.string.dex_error_report_title)).append("\n\n")
    failedResults.forEachIndexed { index, result ->
        append(
            context.localizedString(
                R.string.dex_error_report_entry,
                index + 1,
                result.displayName,
                result.error.toString(),
            ),
        )
    }
}
