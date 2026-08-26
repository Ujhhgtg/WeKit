package dev.ujhhgtg.wekit.features.items.chat
import dev.ujhhgtg.wekit.R

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.VectorPathDrawable
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object GroupChatSummary : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {
    override val technicalId = "群聊统计报告"
    override val nameRes = R.string.feature_group_chat_summary_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_group_chat_summary_description

    private const val GROUP_SUMMARY_MENU_ID = 777029

    private val groupSenderRegex = Regex("""^([^\n:]+):\n(.+)""", setOf(RegexOption.DOT_MATCHES_ALL))

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = GROUP_SUMMARY_MENU_ID,
            text = "群聊统计报告",
            drawable = GroupSummaryIcon(),
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            isSupported = ::isSupportedMessage,
        ) { view, _, msgInfo ->
            showGroupSummaryDialog(view, msgInfo.talker)
        },
    )

    private fun isSupportedMessage(message: MessageInfo): Boolean =
        message.talker.isGroupChatWxId

    private fun showGroupSummaryDialog(view: View, talker: String) {
        showComposeDialog(view.context) {
            GroupSummaryDialog(
                talker = talker,
                onDismiss = onDismiss,
            )
        }
    }

    @Composable
    private fun GroupSummaryDialog(
        talker: String,
        onDismiss: () -> Unit,
    ) {
        var report by remember { mutableStateOf<String?>(null) }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var messageCount by remember { mutableIntStateOf(500) }
        val scope = rememberCoroutineScope()

        val groupName = remember(talker) {
            runCatching { WeDatabaseApi.getDisplayName(talker) }.getOrDefault(talker)
        }

        AlertDialogContent(
            title = { Text("群聊统计报告") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = groupName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { messageCount = 200 }, enabled = !isLoading) {
                            Text("200条", color = if (messageCount == 200) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        TextButton(onClick = { messageCount = 500 }, enabled = !isLoading) {
                            Text("500条", color = if (messageCount == 500) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                        TextButton(onClick = { messageCount = 1000 }, enabled = !isLoading) {
                            Text("1000条", color = if (messageCount == 1000) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }

                    HorizontalDivider(Modifier.padding(vertical = 8.dp))

                    if (isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 12.dp),
                                strokeWidth = 3.dp,
                            )
                            Text("正在生成统计报告...")
                        }
                    }

                    errorMessage?.let { err ->
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                        )
                    }

                    report?.let { result ->
                        Text(
                            text = result,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                if (report != null) {
                    Button(
                        onClick = {
                            scope.launch {
                                val reportText = report
                                if (reportText != null && WeMessageApi.sendText(talker, reportText)) {
                                    showToast("已发送报告")
                                    onDismiss()
                                } else {
                                    showToast("发送失败，请查看日志")
                                }
                            }
                        },
                    ) {
                        Text("发送到群聊")
                    }
                } else {
                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val result = generateReport(talker, messageCount)
                                isLoading = false
                                result.fold(
                                    onSuccess = { report = it },
                                    onFailure = { errorMessage = it.message ?: "未知错误" },
                                )
                            }
                        },
                        enabled = !isLoading,
                    ) {
                        Text("生成统计报告")
                    }
                }
            },
            dismissButton = {
                if (report != null) {
                    Row {
                        TextButton(
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                scope.launch {
                                    val result = generateReport(talker, messageCount)
                                    isLoading = false
                                    result.fold(
                                        onSuccess = { report = it },
                                        onFailure = { errorMessage = it.message ?: "未知错误" },
                                    )
                                }
                            },
                            enabled = !isLoading,
                        ) {
                            Text("重新统计")
                        }
                        TextButton(
                            onClick = {
                                copyToClipboard(report!!)
                                showToast("已复制报告内容")
                            },
                        ) {
                            Text("复制")
                        }
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            },
        )
    }

    private suspend fun generateReport(
        talker: String,
        count: Int,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val membersMap = WeDatabaseApi.getGroupMembers(talker).associate { m ->
                m.wxId to (m.remarkName.takeUnless { it.isBlank() }?.let { "$it (${m.nickname})" } ?: m.nickname)
            }

            val now = System.currentTimeMillis()
            val twentyFourHoursAgo = now - 24 * 60 * 60 * 1000L
            val messagesInRange = WeDatabaseApi.getMessagesInRange(talker, twentyFourHoursAgo, now)

            val messages = if (messagesInRange.size >= count) {
                messagesInRange.takeLast(count)
            } else {
                messagesInRange
            }

            if (messages.isEmpty()) {
                throw IllegalStateException("该群聊最近没有消息，无法生成统计报告")
            }

            buildReport(messages, membersMap, talker)
        }
    }

    private fun buildReport(
        messages: List<WeMessage>,
        membersMap: Map<String, String>,
        talker: String,
    ): String {
        val totalCount = messages.size

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTime = dateFormat.format(Date(messages.first().createTime))
        val endTime = dateFormat.format(Date(messages.last().createTime))

        val typeCounts = mutableMapOf<String, Int>()
        val senderCounts = mutableMapOf<String, MutableList<WeMessage>>()
        val timePeriods = mutableMapOf("凌晨" to 0, "上午" to 0, "下午" to 0, "夜晚" to 0)
        val allTextWords = mutableListOf<String>()
        var laughCount = 0
        var questionCount = 0
        var exclamationCount = 0
        var tildeCount = 0
        val lengthDist = mutableMapOf("≤5字" to 0, "6-20字" to 0, "21-50字" to 0, ">50字" to 0)

        for (msg in messages) {
            val type = MessageType.fromCode(msg.typeCode)
            val category = categorizeMessageType(type, msg.typeCode)
            typeCounts.mergeCount(category, 1, Int::plus)

            val senderId = extractSenderId(msg, membersMap)
            senderCounts.getOrPut(senderId) { mutableListOf() }.add(msg)

            val hour = (msg.createTime / 1000) % 86400 / 3600
            val period = when {
                hour < 6 -> "凌晨"
                hour < 12 -> "上午"
                hour < 18 -> "下午"
                else -> "夜晚"
            }
            timePeriods.mergeCount(period, 1, Int::plus)

            if (type?.isText == true) {
                val textContent = extractTextContent(msg, membersMap)
                val words = extractWords(textContent)
                allTextWords.addAll(words)

                val textLen = textContent.length
                when {
                    textLen <= 5 -> lengthDist.mergeCount("≤5字", 1, Int::plus)
                    textLen <= 20 -> lengthDist.mergeCount("6-20字", 1, Int::plus)
                    textLen <= 50 -> lengthDist.mergeCount("21-50字", 1, Int::plus)
                    else -> lengthDist.mergeCount(">50字", 1, Int::plus)
                }

                if (textContent.contains(Regex("[哈哈呵呵嘿嘿😂🤣]"))) laughCount++
                if (textContent.endsWith("?") || textContent.endsWith("？")) questionCount++
                if (textContent.endsWith("!") || textContent.endsWith("！")) exclamationCount++
                if (textContent.contains("~") || textContent.contains("～")) tildeCount++
            }
        }

        val activeSpeakers = senderCounts.size

        val sortedSpeakers = senderCounts.entries
            .sortedByDescending { it.value.size }
            .take(10)

        val wordFreq = allTextWords
            .groupBy { it }
            .mapValues { it.value.size }
            .filter { it.key.length >= 2 || it.key.all { c -> c.isLetterOrDigit() } }
            .filterNot { it.key in commonStopWords }
            .entries
            .sortedByDescending { it.value }
            .take(10)

        val sb = StringBuilder()
        sb.appendLine("群聊统计报告")
        sb.appendLine("统计周期:${startTime}至${endTime}")
        sb.appendLine("总消息:${totalCount}条 发言人数:${activeSpeakers}人")
        sb.appendLine("消息载体 图片:${typeCounts.getOrDefault("图片", 0)}条 语音:${typeCounts.getOrDefault("语音", 0)}条 文本:${typeCounts.getOrDefault("文本", 0)}条 视频:${typeCounts.getOrDefault("视频", 0)}条 系统:${typeCounts.getOrDefault("系统", 0)}条 文件/链接:${typeCounts.getOrDefault("文件/链接", 0)}条 表情:${typeCounts.getOrDefault("表情", 0)}条")
        sb.appendLine("发言排行")
        sortedSpeakers.forEachIndexed { index, (speaker, msgs) ->
            val displayName = membersMap[speaker] ?: speaker
            sb.appendLine("${index + 1}.$displayName:${msgs.size}条")
        }
        sb.appendLine("活跃时段 凌晨(0-5):${timePeriods["凌晨"]}条 上午(6-11):${timePeriods["上午"]}条 下午(12-17):${timePeriods["下午"]}条 夜晚(18-23):${timePeriods["夜晚"]}条")
        if (wordFreq.isNotEmpty()) {
            sb.append("高频词 ")
            wordFreq.forEachIndexed { index, (word, count) ->
                sb.append("$word:${count}次")
                if (index < wordFreq.size - 1) sb.append(" ")
            }
            sb.appendLine()
        }
        sb.appendLine("情绪指纹")
        val textMsgCount = typeCounts.getOrDefault("文本", 0).coerceAtLeast(1)
        sb.appendLine("笑点浓度:${"%.1f".format(laughCount.toDouble() / textMsgCount * 100)}% 疑问句比例:${"%.1f".format(questionCount.toDouble() / textMsgCount * 100)}% 感叹句比例:${"%.1f".format(exclamationCount.toDouble() / textMsgCount * 100)}% 波浪号比例:${"%.1f".format(tildeCount.toDouble() / textMsgCount * 100)}%")
        sb.appendLine("废话程度鉴定 ≤5字:${lengthDist["≤5字"]}条 6-20字:${lengthDist["6-20字"]}条 21-50字:${lengthDist["21-50字"]}条 >50字:${lengthDist[">50字"]}条")
        sb.appendLine("用户画像")
        sortedSpeakers.forEach { (speaker, msgs) ->
            val displayName = membersMap[speaker] ?: speaker
            val pct = "%.1f".format(msgs.size.toDouble() / totalCount * 100)
            val mainType = msgs.groupBy { m ->
                val t = MessageType.fromCode(m.typeCode)
                categorizeMessageType(t, m.typeCode)
            }.maxByOrNull { it.value.size }?.key ?: "文本"
            sb.appendLine("·$displayName:${msgs.size}条($pct%),主发$mainType")
        }
        sb.appendLine()
        sb.appendLine("Hchat 群聊统计·${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")

        return sb.toString()
    }

    private fun categorizeMessageType(type: MessageType?, rawCode: Int): String {
        if (type == null) return "其他"
        return when {
            type.isText -> "文本"
            rawCode == MessageType.IMAGE.code -> "图片"
            rawCode == MessageType.VOICE.code -> "语音"
            rawCode == MessageType.VIDEO.code || rawCode == MessageType.MICRO_VIDEO.code -> "视频"
            type.isSystem -> "系统"
            type.isSticker -> "表情"
            type.isLink || rawCode == MessageType.FILE.code -> "文件/链接"
            else -> "其他"
        }
    }

    private fun extractSenderId(msg: WeMessage, membersMap: Map<String, String>): String {
        if (msg.isSend != 0) return "我"
        val match = groupSenderRegex.find(msg.content)
        return match?.groupValues?.get(1) ?: "<未知>"
    }

    private fun extractTextContent(msg: WeMessage, membersMap: Map<String, String>): String {
        if (msg.isSend != 0) return msg.content
        val match = groupSenderRegex.find(msg.content)
        return match?.groupValues?.get(2) ?: msg.content
    }

    private fun extractWords(text: String): List<String> {
        return text.split(Regex("[\\s,，。！？、；：\"\"''（（））《》【】\\[\\]\\{\\}「」『』\\.!?;:，。！？、；：\n\r\t]+"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length >= 2 }
    }

    private val commonStopWords = setOf(
        "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一",
        "一个", "上", "也", "很", "到", "说", "要", "去", "你", "会", "着",
        "没有", "看", "好", "自己", "这", "他", "她", "它", "们", "那", "些",
        "什么", "怎么", "因为", "所以", "但是", "如果", "虽然", "可以", "这个",
        "那个", "吧", "吗", "啊", "嗯", "哦", "哈", "呀", "呢", "啦", "么",
        "还是", "就是", "不是", "只是", "但是", "而且", "或者", "然后", "以后",
        "时候", "现在", "已经", "可能", "应该", "没有", "觉得", "知道", "看到",
        "过来", "出来", "起来", "进去", "回到", "拿到", "想到", "我们", "你们",
        "他们", "大家", "东西", "意思", "时间", "朋友", "回复", "收到", "明白",
    )

    private fun <K> MutableMap<K, Int>.mergeCount(key: K, value: Int, op: (Int, Int) -> Int) {
        this[key] = op(this.getOrDefault(key, 0), value)
    }
}

private class GroupSummaryIcon : VectorPathDrawable(
    "M420,624L180,660L420,696L456,936L492,696L732,660L492,624L456,384ZM696,96L676,196L576,216L676,236L696,336L716,236L816,216L716,196Z"
)