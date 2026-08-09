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
import androidx.compose.material3.HorizontalDivider
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
import dev.ujhhgtg.wekit.dexkit.cache.DexCacheManager
import dev.ujhhgtg.wekit.dexkit.resolution.resolveAllDex
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.reflection.withDexKitSuspending
import dev.ujhhgtg.wekit.utils.restartHost
import dev.ujhhgtg.wekit.utils.unreachable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.luckypray.dexkit.DexKitBridge
import java.io.PrintWriter
import java.io.StringWriter

private sealed class ScanProgress {
    data class Start(val displayName: String) : ScanProgress()
    data class Complete(val displayName: String) : ScanProgress()
    data class Failed(val displayName: String, val error: Exception) : ScanProgress()
}

private sealed class ScanResult {
    data class Success(val displayName: String) : ScanResult()
    data class Failed(val displayName: String, val error: Exception) : ScanResult()
}

private sealed class DialogPhase {
    object Idle : DialogPhase()
    object Scanning : DialogPhase()
    data class Done(val failed: List<ScanResult.Failed>) : DialogPhase()
    data class Error(val message: String) : DialogPhase()
}

private val TAG = "DexResolver"

@Composable
fun DexResolver(
    context: Context,
    outdatedItems: List<IResolveDex>,
    scope: CoroutineScope,
    dismiss: () -> Unit
) {
    var phase by remember { mutableStateOf<DialogPhase>(DialogPhase.Idle) }
    var currentTask by remember { mutableStateOf<ScanProgress?>(null) }
    var completed by remember { mutableIntStateOf(0) }
    val scanResults = remember { mutableStateMapOf<String, ScanResult>() }

    fun updateProgress(progress: ScanProgress) {
        when (progress) {
            is ScanProgress.Complete -> {
                scanResults[progress.displayName] = ScanResult.Success(progress.displayName)
                completed = scanResults.size
                currentTask = progress
            }

            is ScanProgress.Failed -> {
                scanResults[progress.displayName] = ScanResult.Failed(progress.displayName, progress.error)
                completed = scanResults.size
                currentTask = progress
            }

            else -> {}
        }
    }

    suspend fun scanItem(
        item: IResolveDex,
        dexKit: DexKitBridge,
        progressChannel: Channel<ScanProgress>
    ): ScanResult {
        val displayName = if (item is BaseFeature) item.technicalPath else unreachable()
        return try {
            progressChannel.send(ScanProgress.Start(displayName))

            item.resolveAllDex(dexKit)

            DexCacheManager.saveItemCache(item)
            progressChannel.send(ScanProgress.Complete(displayName))
            ScanResult.Success(displayName)
        } catch (e: Exception) {
            WeLogger.e(TAG, "failed to scan: $displayName", e)
            progressChannel.send(ScanProgress.Failed(displayName, e))
            ScanResult.Failed(displayName, e)
        }
    }

    fun startScanning() {
        phase = DialogPhase.Scanning
        scope.launch {
            try {
                val progressChannel = Channel<ScanProgress>(Channel.UNLIMITED)

                // progress consumer on Main
                launch(Dispatchers.Main) {
                    for (p in progressChannel) updateProgress(p)
                }

                // parallel scan — same flow/buffer/async structure
                val results = withDexKitSuspending { dexKit ->
                    outdatedItems.asFlow()
                        .map { item ->
                            async(Dispatchers.IO) {
                                scanItem(
                                    item,
                                    dexKit,
                                    progressChannel
                                )
                            }
                        }
                        .buffer(8)
                        .map { it.await() }
                        .toList()
                }

                progressChannel.close()

                val failed = results.filterIsInstance<ScanResult.Failed>()
                phase = DialogPhase.Done(failed)
            } catch (e: Exception) {
                WeLogger.e(TAG, "scanning failed", e)
                phase = DialogPhase.Error(e.message.orEmpty())
            }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Title with icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.dex_cache_update_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                // Badge showing count
                if (phase is DialogPhase.Idle || phase is DialogPhase.Scanning) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = "${outdatedItems.size}",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalDivider()

            // Tip text
            val unknownError = stringResource(R.string.error_unknown)
            val tipText = when (val p = phase) {
                is DialogPhase.Idle -> stringResource(
                    R.string.dex_cache_update_required_message,
                    outdatedItems.size,
                )

                is DialogPhase.Scanning -> null
                is DialogPhase.Done ->
                    if (p.failed.isEmpty()) stringResource(R.string.dex_cache_update_success)
                    else stringResource(R.string.dex_cache_update_partial_failure, p.failed.size)

                is DialogPhase.Error -> stringResource(
                    R.string.dex_cache_unknown_error,
                    p.message.ifBlank { unknownError },
                )
            }
            if (tipText != null) {
                Text(text = tipText, style = MaterialTheme.typography.bodyMedium)
            }

            // Progress
            AnimatedVisibility(visible = phase is DialogPhase.Scanning) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val currentTaskText = when (val task = currentTask) {
                        is ScanProgress.Complete -> stringResource(
                            R.string.dex_cache_status_completed,
                            task.displayName,
                        )
                        is ScanProgress.Failed -> stringResource(
                            R.string.dex_cache_status_failed,
                            task.displayName,
                        )
                        else -> stringResource(R.string.dex_cache_status_adapting)
                    }
                    Text(text = currentTaskText, style = MaterialTheme.typography.bodyMedium)
                    LinearWavyProgressIndicator(
                        progress = { if (outdatedItems.isEmpty()) 0f else completed.toFloat() / outdatedItems.size },
                        modifier = Modifier.fillMaxWidth(),
                        amplitude = { progress ->
                            if (progress == 0f || progress == 1f) {
                                0f
                            } else {
                                1f
                            }
                        }
                    )
                    Text(
                        text = stringResource(
                            R.string.dex_cache_total_progress,
                            completed,
                            outdatedItems.size,
                        ),
                        style = MaterialTheme.typography.labelSmall
                    )
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth()) // indeterminate sub-bar
                }
            }

            // Error details (Done with failures)
            val donePhase = phase as? DialogPhase.Done
            AnimatedVisibility(visible = donePhase?.failed?.isNotEmpty() == true) {
                donePhase?.failed?.let { failed ->
                    ErrorDetailsSection(
                        failedResults = failed,
                        onCopy = {
                            val report = buildErrorReport(context, failed)
                            copyToClipboard(context, report)
                            showToast(context, context.getString(R.string.clipboard_copied))
                        }
                    )
                }
            }

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (phase !is DialogPhase.Scanning) {
                    TextButton(onClick = dismiss) { Text(stringResource(R.string.dialog_close)) }
                }
                if (phase is DialogPhase.Idle) {
                    Button(onClick = ::startScanning) {
                        Text(stringResource(R.string.dex_cache_start_adaptation))
                    }
                }
                if (phase is DialogPhase.Done || phase is DialogPhase.Error) {
                    Button(onClick = {
                        dismiss()
                        restartHost()
                    }) { Text(stringResource(R.string.restart_wechat)) }
                }
            }
        }
    }
}

@Composable
private fun ErrorDetailsSection(
    failedResults: List<ScanResult.Failed>,
    onCopy: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                    .verticalScroll(rememberScrollState())
            )
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.dex_cache_copy_error_information))
            }
        }
    }
}

private fun buildErrorReport(context: Context, failedResults: List<ScanResult.Failed>) = buildString {
    append(context.getString(R.string.dex_error_report_title)).append("\n\n")
    failedResults.forEachIndexed { i, r ->
        val sw = StringWriter()
        r.error.printStackTrace(PrintWriter(sw))
        append(
            context.getString(
                R.string.dex_error_report_entry,
                i + 1,
                r.displayName,
                r.error.message ?: context.getString(R.string.error_unknown),
                sw.toString(),
            ),
        )
    }
}
