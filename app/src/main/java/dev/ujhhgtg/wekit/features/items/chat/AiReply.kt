package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.composables.icons.materialsymbols.outlined.Autorenew
import com.composables.icons.materialsymbols.outlined.Build_circle
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.BaseFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.m3AppBarBlur
import dev.ujhhgtg.wekit.ui.content.rememberMaterial3BlurBackdrop
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.VectorPathDrawable
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.android.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object AiReply : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {
    override val technicalId = "AI回复"
    override val nameRes = R.string.feature_ai_reply_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_ai_reply_description

    private const val AI_REPLY_MENU_ID = 777028

    private val tonePresets = listOf(
        TonePreset("智能全能", "分析当前对话氛围，给出最得体、自然的回复。"),
        TonePreset("高情商", "说话非常有艺术，能够化解尴尬，照顾对方感受，充满智慧。"),
        TonePreset("轻松闲聊", "语气随性自然，带一点点幽默感，不要官方和生硬。"),
        TonePreset("严谨正式", "语气礼貌、专业、客观，适用于职场或正式商务沟通。"),
        TonePreset("幽默/阴阳", "说话风趣，带点俏皮甚至一点点阴阳怪气，非常有意思。"),
        TonePreset("同理/安慰", "语气非常温柔，站在对方立场思考，给予对方情感上的支撑。"),
        TonePreset("客气周到", "非常有礼貌，多使用敬语，保持一定的礼貌距离。"),
        TonePreset("霸道/冷酷", "言简意赅，语气带有一点压迫感和冷酷的霸总风格。"),
        TonePreset("可爱/萌化", "说话活泼，多用呀、哒、呢，增加适量颜文字，非常可爱。"),
        TonePreset("委婉拒绝", "礼貌地拒绝对方的要求，不让对方感到难堪，语气委婉。"),
    )

    private data class TonePreset(
        val name: String,
        val description: String,
    )

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = AI_REPLY_MENU_ID,
            text = "AI回复",
            drawable = AiReplyIcon(),
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            isSupported = ::isSupportedMessage,
        ) { view, _, msgInfo ->
            showAiReplyDialog(view, msgInfo)
        },
    )

    private fun isSupportedMessage(message: MessageInfo): Boolean =
        message.humanReadableRepr.isNotBlank()

    private fun showAiReplyDialog(view: View, msgInfo: MessageInfo) {
        val messageContent = msgInfo.humanReadableRepr
        val talker = msgInfo.talker

        showComposeDialog(view.context, directlyDismissable = false) {
            AiReplyDialog(
                messageContent = messageContent,
                talker = talker,
                onDismiss = onDismiss,
            )
        }
    }

    @Composable
    private fun AiReplyDialog(
        messageContent: String,
        talker: String,
        onDismiss: () -> Unit,
    ) {
        var replies by remember { mutableStateOf<List<String>>(emptyList()) }
        var isLoading by remember { mutableStateOf(false) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var contextCount by remember { mutableIntStateOf(30) }
        var replyCount by remember { mutableIntStateOf(3) }
        var selectedTone by remember { mutableStateOf(tonePresets.first()) }
        var customPrompt by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        val backdrop = rememberMaterial3BlurBackdrop(enabled = true)

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .m3AppBarBlur(backdrop, blurRadius = 30f, blendAlpha = 0.85f, shape = RoundedCornerShape(20.dp)),
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 顶部：大标题 + 设置齿轮 + 历史会话
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "智能回复",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Build_circle,
                            contentDescription = "设置",
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = MaterialSymbols.Outlined.Autorenew,
                            contentDescription = "历史会话/上下文管理",
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 触发的消息
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = messageContent,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(12.dp),
                    )
                }

                HorizontalDivider(Modifier.padding(vertical = 14.dp))

                // 参考上下文滑块
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "参考上下文",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${contextCount}条",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                SliderRow(
                    value = contextCount,
                    options = listOf(0, 10, 20, 30, 50, 100),
                    enabled = !isLoading,
                    onValueChange = { contextCount = it },
                )
                Text(
                    text = "读取最近聊天记录，数值越大记忆越长，消耗token越高",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(Modifier.height(14.dp))

                // 生成备选数滑块
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "生成备选数",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${replyCount}条",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.height(4.dp))
                SliderRow(
                    value = replyCount,
                    options = listOf(1, 3, 5, 10, 16, 30),
                    enabled = !isLoading,
                    onValueChange = { replyCount = it },
                )
                Text(
                    text = "一次产出多少条回复，数量越多消耗接口额度",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(Modifier.height(16.dp))

                // 快捷语气预设
                Text(
                    text = "快捷语气预设",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(8.dp))

                // 只在列表中取前4个作为快捷预设
                val quickPresets = tonePresets.take(4)
                quickPresets.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { preset ->
                            val selected = preset == selectedTone
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(bottom = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerLow
                                    )
                                    .clickable(enabled = !isLoading) { selectedTone = preset }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column {
                                    Text(
                                        text = preset.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = preset.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        // 如果单行只有1个，补空
                        if (row.size == 1) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }

                Spacer(Modifier.height(4.dp))

                // 自定义输入
                OutlinedTextField(
                    value = customPrompt,
                    onValueChange = { customPrompt = it },
                    enabled = !isLoading,
                    placeholder = {
                        Text(
                            "也可以手动输入你的特殊回复要求...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 1,
                    maxLines = 3,
                )

                HorizontalDivider(Modifier.padding(vertical = 14.dp))

                // 回复内容块
                Text(
                    text = "待生成回复内容",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "这里会填入触发的聊天消息",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.padding(12.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))

                // 错误提示
                errorMessage?.let { err ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // 加载中
                if (isLoading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "AI 思考中...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }

                // 回复列表
                if (replies.isNotEmpty()) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        replies.forEach { reply ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            scope.launch {
                                                val sent = WeMessageApi.sendText(talker, reply)
                                                if (sent) {
                                                    showToast("已发送")
                                                    onDismiss()
                                                } else {
                                                    showToast("发送失败，请查看日志")
                                                }
                                            }
                                        }
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = reply,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // 按钮
                if (replies.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                replies = emptyList()
                                errorMessage = null
                                isLoading = true
                                scope.launch {
                                    val result = generateReplies(
                                        messageContent, talker,
                                        contextCount, replyCount, selectedTone, customPrompt,
                                    )
                                    isLoading = false
                                    result.fold(
                                        onSuccess = { replies = it },
                                        onFailure = { errorMessage = it.message ?: "未知错误" },
                                    )
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("✨不满意？换一批")
                        }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("完成")
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val result = generateReplies(
                                    messageContent, talker,
                                    contextCount, replyCount, selectedTone, customPrompt,
                                )
                                isLoading = false
                                result.fold(
                                    onSuccess = { replies = it },
                                    onFailure = { errorMessage = it.message ?: "未知错误" },
                                )
                            }
                        },
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("✨不满意？换一批")
                    }
                }
            }
        }
    }

    @Composable
    private fun SliderRow(
        value: Int,
        options: List<Int>,
        enabled: Boolean,
        onValueChange: (Int) -> Unit,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == value,
                    onClick = { onValueChange(option) },
                    enabled = enabled,
                    label = {
                        Text(
                            "${option}条",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }
    }

    private suspend fun generateReplies(
        messageContent: String,
        talker: String,
        contextCount: Int,
        replyCount: Int,
        tone: TonePreset,
        customPrompt: String,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val modelId = WeAgentSettings.defaultModelId()
                ?: WeAgentRepository.firstModelId()
                ?: throw IllegalStateException("未配置AI模型，请先在WeAgent设置中添加模型")

            val model = WeAgentRepository.getModel(modelId)
                ?: throw IllegalStateException("未找到模型: $modelId")

            val provider = WeAgentRepository.getModelProvider(model.providerId)
                ?: throw IllegalStateException("未找到模型提供者: ${model.providerId}")

            val client = ModelProviderManager.clientFor(provider)

            val contextText = if (contextCount > 0) {
                buildContextText(talker, contextCount)
            } else {
                ""
            }

            val systemPrompt = buildString {
                append("你是一个微信聊天助手。")
                append("语气要求：${tone.description}")
                if (customPrompt.isNotBlank()) {
                    append("\n额外要求：$customPrompt")
                }
                if (contextText.isNotBlank()) {
                    append("\n\n以下是最近的聊天记录作为上下文参考：\n$contextText")
                }
                append("\n\n请根据对方最后一条消息，生成$replyCount 条不同风格的回复。")
                append("每条回复单独一行，用数字序号「1. 」「2. 」等开头，不要加其他解释。")
            }

            val messages = listOf(
                LlmMessage(role = LlmRole.SYSTEM, content = systemPrompt),
                LlmMessage(role = LlmRole.USER, content = "对方说：$messageContent\n\n请生成${replyCount}条回复："),
            )

            val request = ModelProviderManager.buildRequest(
                model = model,
                messages = messages,
                tools = emptyList(),
                stream = true,
            )

            var replyContent = ""

            client.stream(request).collect { event ->
                when (event) {
                    is LlmStreamEvent.TextDelta -> {
                        replyContent += event.text
                    }
                    is LlmStreamEvent.Completed -> {
                        if (replyContent.isBlank()) {
                            replyContent = event.message.content ?: ""
                        }
                    }
                    is LlmStreamEvent.Failed -> {
                        throw event.error
                    }
                    else -> {}
                }
            }

            parseReplies(replyContent.trim()).ifEmpty {
                throw IllegalStateException("AI未生成有效的回复内容")
            }
        }
    }

    private suspend fun buildContextText(
        talker: String,
        count: Int,
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            val messages = WeDatabaseApi.getMessages(talker, 1, count)

            messages.filter { msg ->
                MessageType.fromCode(msg.typeCode)?.isText == true
            }.reversed().joinToString("\n") { msg ->
                val sender = if (msg.isSend != 0) "我" else {
                    runCatching { WeDatabaseApi.getDisplayName(msg.talker) }.getOrDefault(msg.talker)
                }
                "$sender: ${msg.content}"
            }
        }.getOrDefault("")
    }

    private fun parseReplies(text: String): List<String> {
        val replyRegex = Regex("""^\d+\.\s*(.+)$""", setOf(RegexOption.MULTILINE))
        val numberedReplies = replyRegex.findAll(text).map { it.groupValues[1].trim() }.toList()
        if (numberedReplies.isNotEmpty()) return numberedReplies

        return text.lines().map { it.trim() }.filter { it.isNotBlank() }
    }
}

private class AiReplyIcon : VectorPathDrawable(
    "M420,624L180,660L420,696L456,936L492,696L732,660L492,624L456,384ZM696,96L676,196L576,216L676,236L696,336L716,236L816,216L716,196Z"
)