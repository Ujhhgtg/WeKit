package dev.ujhhgtg.wekit.ui.content

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.cache.CloudDexResolver
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.restartHost
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface DialogPhase {
    data object Idle : DialogPhase
    data object DownloadingCloud : DialogPhase
    data class ResolvingLocal(val total: Int) : DialogPhase
    data class Done(
        val source: CompletionSource,
        val failures: List<LocalDexFailure>,
    ) : DialogPhase

    data class Error(val message: String) : DialogPhase
}

private enum class CompletionSource { Cloud, Local }

private const val TAG = "DexResolver"

@Composable
fun DexResolver(
    context: Context,
    outdatedItems: List<IResolveDex>,
    scope: CoroutineScope,
    dismiss: () -> Unit,
) {
    var phase by remember { mutableStateOf<DialogPhase>(DialogPhase.Idle) }
    var pendingItems by remember { mutableStateOf(outdatedItems) }
    var currentTask by remember { mutableStateOf<LocalDexProgress?>(null) }
    var completed by remember { mutableIntStateOf(0) }
    val localResults = remember { mutableStateMapOf<String, LocalDexProgress>() }

    fun updateProgress(progress: LocalDexProgress) {
        currentTask = progress
        if (progress is LocalDexProgress.Complete || progress is LocalDexProgress.Failed) {
            localResults[progress.displayName] = progress
            completed = localResults.size
        }
    }

    fun startResolution() {
        val currentItems = pendingItems
        phase = DialogPhase.DownloadingCloud
        scope.launch {
            val remainingItems = try {
                CloudDexResolver.resolve(currentItems).remainingItems
            } catch (error: Exception) {
                WeLogger.e(TAG, "cloud resolution failed", error)
                currentItems
            }
            withContext(Dispatchers.Main.immediate) {
                pendingItems = remainingItems
            }
            if (remainingItems.isEmpty()) {
                withContext(Dispatchers.Main.immediate) {
                    phase = DialogPhase.Done(CompletionSource.Cloud, emptyList())
                }
                return@launch
            }
            withContext(Dispatchers.Main.immediate) {
                currentTask = null
                completed = 0
                localResults.clear()
                phase = DialogPhase.ResolvingLocal(remainingItems.size)
            }
            try {
                val result = LocalDexResolver.resolve(remainingItems) { progress ->
                    withContext(Dispatchers.Main.immediate) { updateProgress(progress) }
                }
                withContext(Dispatchers.Main.immediate) {
                    phase = DialogPhase.Done(CompletionSource.Local, result.failures)
                }
            } catch (error: Exception) {
                WeLogger.e(TAG, "local resolution failed", error)
                withContext(Dispatchers.Main.immediate) {
                    phase = DialogPhase.Error(error.message.orEmpty())
                }
            }
        }
    }

    AlertDialogContent(
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = stringResource(R.string.dex_cache_update_title))

                if (phase is DialogPhase.Idle ||
                    phase is DialogPhase.DownloadingCloud ||
                    phase is DialogPhase.ResolvingLocal
                ) {
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
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val unknownError = stringResource(R.string.error_unknown)
                val tipText = when (val currentPhase = phase) {
                    is DialogPhase.Idle ->
                        stringResource(R.string.dex_cache_update_required_message, pendingItems.size)

                    is DialogPhase.DownloadingCloud ->
                        stringResource(R.string.dex_cache_cloud_downloading)
                    is DialogPhase.ResolvingLocal -> null
                    is DialogPhase.Done -> when {
                        currentPhase.source == CompletionSource.Cloud ->
                            stringResource(R.string.dex_cache_cloud_complete)
                        currentPhase.failures.isEmpty() ->
                            stringResource(R.string.dex_cache_resolution_success)
                        else -> stringResource(
                            R.string.dex_cache_resolution_partial_failure,
                            currentPhase.failures.size,
                        )
                    }

                    is DialogPhase.Error -> stringResource(
                        R.string.dex_cache_resolution_error,
                        currentPhase.message.ifBlank { unknownError },
                    )
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
                        val currentTaskText = when (val task = currentTask) {
                            is LocalDexProgress.Complete -> stringResource(
                                R.string.dex_cache_status_completed,
                                task.displayName,
                            )
                            is LocalDexProgress.Failed -> stringResource(
                                R.string.dex_cache_status_failed,
                                task.displayName,
                            )
                            else -> stringResource(R.string.dex_cache_status_resolving)
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
                            text = stringResource(
                                R.string.dex_cache_total_progress,
                                completed,
                                localPhase?.total ?: 0,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                        )
                        LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }

                val donePhase = phase as? DialogPhase.Done
                AnimatedVisibility(visible = donePhase?.failures?.isNotEmpty() == true) {
                    donePhase?.failures?.let { failures ->
                        ErrorDetailsSection(
                            failedResults = failures,
                            onCopy = {
                                copyToClipboard(context, buildErrorReport(context, failures))
                                showToast(context, context.getString(R.string.clipboard_copied))
                            },
                        )
                    }
                }
            }
        },
        dismissButton = {
            val isBusy = phase is DialogPhase.DownloadingCloud || phase is DialogPhase.ResolvingLocal
            if (!isBusy) {
                TextButton(onClick = dismiss) { Text(stringResource(R.string.dialog_close)) }
            }
        },
        confirmButton = {
            if (phase is DialogPhase.Idle) {
                Button(onClick = ::startResolution) {
                    Text(stringResource(R.string.dex_cache_start_local_resolution))
                }
            }
            if (phase is DialogPhase.Done || phase is DialogPhase.Error) {
                Button(
                    onClick = {
                        dismiss()
                        restartHost()
                    },
                ) { Text(stringResource(R.string.restart_wechat)) }
            }
        },
    )
}

@Composable
private fun ErrorDetailsSection(
    failedResults: List<LocalDexFailure>,
    onCopy: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val unknownError = stringResource(R.string.error_unknown)
            val errorText = failedResults.mapIndexed { index, result ->
                stringResource(
                    R.string.dex_cache_failure_detail,
                    index + 1,
                    result.displayName,
                    result.error.message ?: unknownError,
                )
            }.joinToString("")
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState()),
            )
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.dex_cache_copy_error_information))
            }
        }
    }
}

private fun buildErrorReport(
    context: Context,
    failedResults: List<LocalDexFailure>,
) = buildString {
    append(context.getString(R.string.dex_error_report_title)).append("\n\n")
    failedResults.forEachIndexed { index, result ->
        val stackTrace = StringWriter()
        result.error.printStackTrace(PrintWriter(stackTrace))
        append(
            context.getString(
                R.string.dex_error_report_entry,
                index + 1,
                result.displayName,
                result.error.message ?: context.getString(R.string.error_unknown),
                stackTrace.toString(),
            ),
        )
    }
}
