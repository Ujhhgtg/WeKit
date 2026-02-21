package moe.ouom.wekit.ui.compose

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.wekit.core.model.BaseHookItem
import moe.ouom.wekit.dexkit.cache.DexCacheManager
import moe.ouom.wekit.dexkit.intf.IDexFind
import moe.ouom.wekit.utils.log.WeLogger
import org.luckypray.dexkit.DexKitBridge
import java.io.PrintWriter
import java.io.StringWriter

private sealed class ScanProgress {
    data class Start(val path: String)                        : ScanProgress()
    data class Complete(val path: String)                     : ScanProgress()
    data class Failed(val path: String, val error: Exception) : ScanProgress()
}

private sealed class ScanResult {
    data class Success(val path: String)                      : ScanResult()
    data class Failed(val path: String, val error: Exception) : ScanResult()
}

private sealed class DialogPhase {
    object Idle     : DialogPhase()
    object Scanning : DialogPhase()
    data class Done(val failed: List<ScanResult.Failed>) : DialogPhase()
    data class Error(val message: String)                : DialogPhase()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DexFinderContent(
    context: Context,
    outdatedItems: List<IDexFind>,
    appInfo: ApplicationInfo,
    scope: CoroutineScope,
    onDismiss: () -> Unit
) {
    var phase       by remember { mutableStateOf<DialogPhase>(DialogPhase.Idle) }
    var currentTask by remember { mutableStateOf("") }
    var taskCounter by remember { mutableIntStateOf(0) }
    var completed   by remember { mutableIntStateOf(0) }
    val scanResults =  remember { mutableStateMapOf<String, ScanResult>() }

    fun updateProgress(progress: ScanProgress) {
        val total = outdatedItems.size
        when (progress) {
            is ScanProgress.Start -> {
                taskCounter++
                currentTask = "正在适配: ${progress.path} ($taskCounter/$total)..."
            }
            is ScanProgress.Complete -> {
                scanResults[progress.path] = ScanResult.Success(progress.path)
                completed = scanResults.size
                currentTask = "已完成: ${progress.path}"
            }
            is ScanProgress.Failed -> {
                scanResults[progress.path] = ScanResult.Failed(progress.path, progress.error)
                completed = scanResults.size
                currentTask = "失败: ${progress.path}"
            }
        }
    }

    suspend fun scanItem(
        item: IDexFind,
        dexKit: DexKitBridge,
        progressChannel: Channel<ScanProgress>
    ): ScanResult {
        val path = if (item is BaseHookItem) item.path else item::class.java.simpleName
        return try {
            progressChannel.send(ScanProgress.Start(path))
            val descriptors = item.dexFind(dexKit)
            WeLogger.i("[DexFinderDialog]", "Total descriptors: ${descriptors.size}, keys: ${descriptors.keys}")
            DexCacheManager.saveCache(item, descriptors)
            progressChannel.send(ScanProgress.Complete(path))
            ScanResult.Success(path)
        } catch (e: Exception) {
            WeLogger.e("[DexFinderDialog] Failed to scan: $path", e)
            progressChannel.send(ScanProgress.Failed(path, e))
            ScanResult.Failed(path, e)
        }
    }

    fun startScanning() {
        phase = DialogPhase.Scanning
        scope.launch {
            try {
                val dexKit = withContext(Dispatchers.IO) { DexKitBridge.create(appInfo.sourceDir) }
                try {
                    val progressChannel = Channel<ScanProgress>(Channel.UNLIMITED)

                    // progress consumer on Main
                    launch(Dispatchers.Main) {
                        for (p in progressChannel) updateProgress(p)
                    }

                    // parallel scan — same flow/buffer/async structure
                    val results = outdatedItems.asFlow()
                        .map { item -> async(Dispatchers.IO) { scanItem(item, dexKit, progressChannel) } }
                        .buffer(8)
                        .map { it.await() }
                        .toList()

                    progressChannel.close()

                    val failed = results.filterIsInstance<ScanResult.Failed>()
                    phase = DialogPhase.Done(failed)
                } finally {
                    dexKit.close()
                }
            } catch (e: Exception) {
                WeLogger.e("[DexFinderDialog] Scanning failed", e)
                phase = DialogPhase.Error("扫描过程中发生未知错误: ${e.message}")
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DEX 缓存更新",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

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
            val tipText = when (val p = phase) {
                is DialogPhase.Idle    ->
                    "检测到 ${outdatedItems.size} 个功能需要更新 DEX 缓存，点击开始适配后将自动扫描并更新。" +
                            "若直接关闭窗口，相关功能将不会被加载"
                is DialogPhase.Scanning -> null
                is DialogPhase.Done    ->
                    if (p.failed.isEmpty()) "适配完成！所有功能已成功更新 DEX 缓存"
                    else "适配完成，但有 ${p.failed.size} 个功能失败"
                is DialogPhase.Error   -> p.message
            }
            if (tipText != null) {
                Text(text = tipText, style = MaterialTheme.typography.bodyMedium)
            }

            // Progress
            AnimatedVisibility(visible = phase is DialogPhase.Scanning) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = currentTask, style = MaterialTheme.typography.bodyMedium)
                    LinearWavyProgressIndicator(
                        progress = { if (outdatedItems.isEmpty()) 0f else completed.toFloat() / outdatedItems.size },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "总进度: $completed/${outdatedItems.size}",
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
                            val text = buildErrorReport(failed)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("WeKit Dex Finder Error", text))
                            Toast.makeText(context, "错误信息已复制到剪贴板", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            // Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
            ) {
                if (phase is DialogPhase.Idle) {
                    Button(onClick = ::startScanning) { Text("开始适配") }
                }
                if (phase is DialogPhase.Done || phase is DialogPhase.Error) {
                    Button(onClick = {
                        onDismiss()
                        Process.killProcess(Process.myPid())
                    }) { Text("重启应用") }
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
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val errorText = buildString {
                failedResults.forEachIndexed { i, r ->
                    append("${i + 1}. ${r.path}\n")
                    append("   错误: ${r.error.message}\n\n")
                }
            }
            Text(
                text = errorText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 160.dp)
                    .verticalScroll(rememberScrollState())
            )
            TextButton(onClick = onCopy) { Text("复制错误信息") }
        }
    }
}

private fun buildErrorReport(failedResults: List<ScanResult.Failed>) = buildString {
    append("=== WeKit Dex 扫描错误报告 ===\n\n")
    failedResults.forEachIndexed { i, r ->
        append("${i + 1}. ${r.path}\n")
        append("   错误信息: ${r.error.message}\n")
        append("   堆栈跟踪:\n")
        val sw = StringWriter()
        r.error.printStackTrace(PrintWriter(sw))
        append(sw.toString())
        append("\n\n")
    }
}