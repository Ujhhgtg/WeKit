package dev.ujhhgtg.wekit.features.items.moments

import android.content.ContentValues
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.features.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.workflow.data.WorkflowRepository
import dev.ujhhgtg.wekit.workflow.engine.TriggerContext
import dev.ujhhgtg.wekit.workflow.engine.WorkflowEngine
import dev.ujhhgtg.wekit.workflow.model.Workflow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@Feature(
    name = "自动转发",
    categories = ["朋友圈"],
    description = "浏览或同步朋友圈时, 自动触发绑定工作流处理指定朋友圈"
)
object AutoRepostMoments : AutoMomentsBase(),
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener,
    AutoRefresh.IRefreshListener {

    override val TAG = "AutoRepostMoments"

    private const val MODE_WHEN_SEEN = 0
    private const val MODE_ALL_LOADED = 1
    private const val RETRY_INTERVAL_MS = 30_000L
    private const val MAX_ACTION_DELAY_MS = 300_000L

    // 内存态去重, 避免同一会话内重复处理
    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val actionLock = Any()

    @Volatile
    private var lastActionSentAt = 0L

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        AutoRefresh.addListener(this)

        installTimelineHooks()

        if (currentMode == MODE_ALL_LOADED) {
            scanCachedTargetMoments()
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
        AutoRefresh.removeListener(this)
    }

    /** Called by [AutoRefresh] on every scheduled refresh cycle. */
    override fun onRefresh() {
        if (currentMode == MODE_ALL_LOADED) {
            scanCachedTargetMoments()
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var mode by remember { mutableIntStateOf(currentMode) }
            var delayInput by remember { mutableStateOf(actionDelayMs.toString()) }
            var selectedId by remember { mutableStateOf(selectedWorkflowId) }
            val workflows by produceState<List<Workflow>>(emptyList()) {
                value = runCatching { WorkflowRepository.getAll() }.getOrDefault(emptyList())
            }

            AlertDialogContent(
                title = { Text("自动转发") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        val boundName = workflows.find { it.id == selectedId }?.name
                            ?: selectedId?.let { "未知工作流 ($it)" }
                            ?: "未绑定"

                        ListItem(
                            modifier = Modifier.clickable {
                                showComposeDialog(context) {
                                    AlertDialogContent(
                                        title = { Text("选择工作流") },
                                        text = {
                                            Column(
                                                Modifier
                                                    .selectableGroup()
                                                    .verticalScroll(rememberScrollState())
                                            ) {
                                                ModeRow(
                                                    title = "不绑定",
                                                    summary = "禁用自动触发",
                                                    checked = selectedId == null,
                                                    onClick = { selectedId = null; onDismiss() }
                                                )
                                                if (workflows.isEmpty()) {
                                                    ListItem(
                                                        headlineContent = { Text("暂无工作流") },
                                                        supportingContent = { Text("请先在工作流页面创建工作流") }
                                                    )
                                                }
                                                workflows.forEach { workflow ->
                                                    ModeRow(
                                                        title = workflow.name,
                                                        summary = workflow.description.takeIf { it.isNotBlank() } ?: workflow.id,
                                                        checked = selectedId == workflow.id,
                                                        onClick = { selectedId = workflow.id; onDismiss() }
                                                    )
                                                }
                                            }
                                        },
                                        dismissButton = { TextButton(onDismiss) { Text("取消") } },
                                        confirmButton = {}
                                    )
                                }
                            },
                            supportingContent = { Text(boundName) },
                            headlineContent = { Text("绑定工作流") },
                        )

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Text(
                            text = "运行机制",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                        Column(Modifier.selectableGroup()) {
                            ModeRow(
                                title = "刷到时即时处理",
                                summary = "仅在滚动浏览朋友圈、视图可见时触发工作流",
                                checked = mode == MODE_WHEN_SEEN,
                                onClick = { mode = MODE_WHEN_SEEN }
                            )
                            ModeRow(
                                title = "本地缓存全量处理",
                                summary = "自动扫描本地已缓存和后续收到的所有朋友圈\n需启用「朋友圈/自动刷新」",
                                checked = mode == MODE_ALL_LOADED,
                                onClick = { mode = MODE_ALL_LOADED }
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        TextField(
                            value = delayInput,
                            onValueChange = { delayInput = it.filter { c -> c.isDigit() }.take(7) },
                            label = { Text("操作间隔 (毫秒)") },
                            supportingText = { Text("在相邻两次工作流触发之间等待, 建议设置较大间隔以免过于频繁") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            selectedWorkflowId = selectedId
                            currentMode = mode
                            actionDelayMs = (delayInput.toLongOrNull() ?: 0L).coerceIn(0L, MAX_ACTION_DELAY_MS)
                            handledSnsIds.clear()
                            lastAttemptAt.clear()
                            showToast("已保存")
                            if (mode == MODE_ALL_LOADED) {
                                scanCachedTargetMoments()
                            }
                            onDismiss()
                        }
                    ) {
                        Text("保存")
                    }
                }
            )
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        processSnsInfoValues(table, values)
    }

    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        processSnsInfoValues(table, values)
    }

    override fun processVisibleItems(list: ViewGroup) {
        if (selectedWorkflowId == null) return
        for (i in 0 until list.childCount) {
            runCatching {
                locateSnsInfo(list.getChildAt(i))?.let { processSnsInfoAsync(it, "visible") }
            }.onFailure {
                WeLogger.w(TAG, "failed to process visible Moments item", it)
            }
        }
    }

    private fun processSnsInfoValues(table: String, values: ContentValues) {
        if (table != "SnsInfo") return
        if (currentMode != MODE_ALL_LOADED) return
        if (selectedWorkflowId == null) return

        // Skip deleted/recalled moments (sourceType != 0)
        val sourceType = values.getAsInteger("sourceType") ?: 0
        if (sourceType != 0) return

        val snsId = values.getAsLong("snsId") ?: return
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        processSnsInfoAsync(snsInfo, "database")
    }

    private fun scanCachedTargetMoments() {
        if (selectedWorkflowId == null) return
        thread(name = "ScanMomentsToAutoForwardThread") {
            WeLogger.d(TAG, "scanCachedTargetMoments: scanning")
            val snsIds = runCatching {
                queryCachedTargetSnsIds()
            }.onFailure {
                WeLogger.w(TAG, "failed to query cached target moments", it)
            }.getOrDefault(emptyList())

            WeLogger.d(TAG, "scanCachedTargetMoments: found ${snsIds.size} cached moments")
            for (snsId in snsIds) {
                val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: run {
                    WeLogger.w(TAG, "scanCachedTargetMoments: failed to get snsInfo for snsId=$snsId")
                    continue
                }
                processSnsInfo(snsInfo, "cached")
            }
        }
    }

    private fun queryCachedTargetSnsIds(): List<Long> {
        if (!WeDatabaseApi.isReady) return emptyList()

        val sql = """
            SELECT snsId
            FROM SnsInfo
            WHERE snsId != 0
              AND (sourceType = 0)
            ORDER BY createTime DESC
        """.trimIndent()

        val result = mutableListOf<Long>()
        WeDatabaseApi.rawQuery(sql, emptyArray()).use { cursor ->
            while (cursor.moveToNext()) {
                result += cursor.getLong(0)
            }
        }
        return result
    }

    private fun processSnsInfo(snsInfo: Any, source: String) {
        val workflowId = selectedWorkflowId ?: return
        val owner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
        if (owner == WeApi.selfWxId) return

        if (WeMomentsApi.isDeleted(snsInfo)) {
            WeLogger.d(TAG, "processSnsInfo: skipping deleted moments for owner=$owner")
            return
        }

        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: run {
            WeLogger.w(TAG, "processSnsInfo: failed to get snsTableId for owner=$owner")
            return
        }

        // Skip moments intercepted by AntiMomentsDelete
        if (isIntercepted(snsInfo)) {
            WeLogger.d(TAG, "processSnsInfo: skipping intercepted moments for owner=$owner")
            return
        }

        // 持久去重, 避免重启后对同一朋友圈重复触发工作流
        if (snsTableId in handledSnsIds) return
        if (isAlreadyForwarded(snsTableId)) {
            handledSnsIds.add(snsTableId)
            return
        }
        if (!canAttempt(snsTableId)) return

        val momentContent = WeMomentsApi.getMomentContent(snsInfo)
        val contentText = momentContent?.contentText.orEmpty()
        val type = momentContent?.type ?: 0

        val result = sendWithDelay {
            val workflow = runBlocking { WorkflowRepository.getById(workflowId) }
                ?: return@sendWithDelay WeMomentsApi.ActionResult(
                    success = true, sent = false, message = "workflow not found: $workflowId"
                )

            val latestOwner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
            if (latestOwner == WeApi.selfWxId) {
                return@sendWithDelay WeMomentsApi.ActionResult(success = true, sent = false, message = "own moment, skipped")
            }
            if (WeMomentsApi.isDeleted(snsInfo)) {
                return@sendWithDelay WeMomentsApi.ActionResult(success = true, sent = false, message = "deleted/recalled")
            }
            if (isAlreadyForwarded(snsTableId)) {
                return@sendWithDelay WeMomentsApi.ActionResult(success = true, sent = false, message = "already handled")
            }

            val ctx = TriggerContext.newMoment(
                sender = owner,
                content = contentText,
                snsId = snsTableId,
                type = type,
            )
            runBlocking { WorkflowEngine.execute(workflow, ctx) }.let { engineResult ->
                when (engineResult) {
                    is WorkflowEngine.ExecutionResult.Completed ->
                        WeMomentsApi.ActionResult(success = true, sent = true, message = "workflow completed")
                    is WorkflowEngine.ExecutionResult.Stopped ->
                        WeMomentsApi.ActionResult(success = true, sent = false, message = "workflow stopped: ${engineResult.reason}")
                    is WorkflowEngine.ExecutionResult.Error ->
                        WeMomentsApi.ActionResult(success = false, sent = false, message = engineResult.message, error = engineResult.cause)
                }
            }
        }

        if (result.success) {
            handledSnsIds.add(snsTableId)
            if (result.sent) markForwarded(snsTableId)
            WeLogger.i(TAG, "auto-repost $source sent=${result.sent}, owner=$owner, sns=$snsTableId")
        } else {
            val message = "auto-repost $source failed, owner=$owner, sns=$snsTableId, message=${result.message}"
            result.error?.let { WeLogger.w(TAG, message, it) } ?: WeLogger.w(TAG, message)
        }
    }

    private fun canAttempt(snsTableId: String): Boolean {
        synchronized(lastAttemptAt) {
            val now = System.currentTimeMillis()
            val last = lastAttemptAt[snsTableId] ?: 0L
            if (now - last < RETRY_INTERVAL_MS) return false
            lastAttemptAt[snsTableId] = now
            return true
        }
    }

    private fun processSnsInfoAsync(snsInfo: Any, source: String) {
        thread(name = "AutoForwardMomentThread") {
            runCatching { processSnsInfo(snsInfo, source) }
                .onFailure { WeLogger.w(TAG, "auto-repost processing failed", it) }
        }
    }

    private fun sendWithDelay(block: () -> WeMomentsApi.ActionResult): WeMomentsApi.ActionResult =
        synchronized(actionLock) {
            val delay = actionDelayMs
            if (delay > 0) {
                val wait = delay - (System.currentTimeMillis() - lastActionSentAt)
                if (wait > 0) Thread.sleep(wait)
            }

            val result = block()
            if (result.sent) {
                lastActionSentAt = System.currentTimeMillis()
            }
            result
        }

    // 持久化已处理集合, 防止重启后重复触发; 超出上限时丢弃最旧的部分
    private fun isAlreadyForwarded(snsTableId: String): Boolean = snsTableId in forwardedSnsIds

    @Synchronized
    private fun markForwarded(snsTableId: String) {
        val updated = LinkedHashSet(forwardedSnsIds)
        updated.add(snsTableId)
        if (updated.size > MAX_FORWARDED_RECORDS) {
            val overflow = updated.size - MAX_FORWARDED_RECORDS
            val iterator = updated.iterator()
            repeat(overflow) {
                if (iterator.hasNext()) {
                    iterator.next(); iterator.remove()
                }
            }
        }
        forwardedSnsIds = updated
    }

    private var currentMode by WePrefs.prefOption("moments_auto_forward_mode", MODE_WHEN_SEEN)
    private var actionDelayMs by WePrefs.prefOption("moments_auto_forward_action_delay_ms", 0L)
    private var forwardedSnsIds by WePrefs.prefOption("moments_auto_forward_forwarded_ids", emptySet())
    var selectedWorkflowId: String? by WePrefs.prefOption("auto_repost_workflow_id", null as String?)

    private const val MAX_FORWARDED_RECORDS = 1000
}

@Composable
private fun ModeRow(
    title: String,
    summary: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        modifier = Modifier
            .clickable(onClick = onClick)
            .fillMaxWidth(),
        leadingContent = {
            RadioButton(
                selected = checked,
                onClick = null
            )
        },
        supportingContent = { Text(summary) },
        headlineContent = { Text(title) },
    )
}
