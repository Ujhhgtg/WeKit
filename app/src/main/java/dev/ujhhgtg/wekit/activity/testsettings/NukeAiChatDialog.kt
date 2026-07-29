package dev.ujhhgtg.wekit.activity.testsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.wekit.ui.content.nukex.NukeButton
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDialogSurface
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDialogTextField
import dev.ujhhgtg.wekit.ui.content.nukex.NukeDivider
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyph
import dev.ujhhgtg.wekit.ui.content.nukex.NukeGlyphKind
import dev.ujhhgtg.wekit.ui.content.nukex.NukePreferenceRow
import dev.ujhhgtg.wekit.ui.content.nukex.NukeSelectPreference
import dev.ujhhgtg.wekit.ui.content.nukex.NukeText
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTextField
import dev.ujhhgtg.wekit.ui.content.nukex.NukeTheme
import java.net.URI

private enum class PreviewAiDialogPage {
    Configuration,
    Models,
    Contacts,
}

private enum class PreviewAiListMode {
    Blacklist,
    Whitelist,
}

private data class PreviewAiChatConfiguration(
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "sk-demo-only",
    val model: String = "gpt-5.6-sol",
    val systemPrompt: String = "",
    val temperature: String = "0.7",
    val maxTokens: String = "512",
    val contextRounds: String = "6",
    val replyDelayMs: String = "0",
    val listMode: PreviewAiListMode = PreviewAiListMode.Whitelist,
    val targetIds: Set<String> = setOf("wekit_demo"),
)

private data class PreviewAiModel(
    val id: String,
    val owner: String,
)

private val previewAiModels = listOf(
    PreviewAiModel("gpt-5.6-sol", "openai"),
    PreviewAiModel("gpt-4o-mini", "openai"),
    PreviewAiModel("deepseek-chat", "deepseek"),
    PreviewAiModel("claude-3-7-sonnet", "anthropic"),
)

@Composable
internal fun NukeAiChatConfigurationDialog(
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf(PreviewAiDialogPage.Configuration) }
    var configuration by remember { mutableStateOf(PreviewAiChatConfiguration()) }

    when (page) {
        PreviewAiDialogPage.Configuration -> NukeAiChatFormDialog(
            configuration = configuration,
            onConfigurationChange = { configuration = it },
            onNavigate = { page = it },
            onDismiss = onDismiss,
        )

        PreviewAiDialogPage.Models -> NukeAiModelSelectionDialog(
            selectedModel = configuration.model,
            onSelected = {
                configuration = configuration.copy(model = it)
                page = PreviewAiDialogPage.Configuration
            },
            onDismiss = { page = PreviewAiDialogPage.Configuration },
        )

        PreviewAiDialogPage.Contacts -> NukeContactSelectionDialog(
            title = if (configuration.listMode == PreviewAiListMode.Whitelist) {
                "选择 AI 聊天白名单"
            } else {
                "选择 AI 聊天黑名单"
            },
            initialSelected = configuration.targetIds,
            onDismiss = { page = PreviewAiDialogPage.Configuration },
            onConfirm = {
                configuration = configuration.copy(targetIds = it)
                page = PreviewAiDialogPage.Configuration
            },
        )
    }
}

@Composable
private fun NukeAiChatFormDialog(
    configuration: PreviewAiChatConfiguration,
    onConfigurationChange: (PreviewAiChatConfiguration) -> Unit,
    onNavigate: (PreviewAiDialogPage) -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingPage by remember { mutableStateOf<PreviewAiDialogPage?>(null) }
    val baseUrlValid = isValidNukeAiBaseUrl(configuration.baseUrl)
    val temperature = configuration.temperature.toFloatOrNull()
    val maxTokens = configuration.maxTokens.toIntOrNull()
    val contextRounds = configuration.contextRounds.toIntOrNull()
    val replyDelayMs = configuration.replyDelayMs.toLongOrNull()
    val canSave =
        baseUrlValid &&
            configuration.apiKey.isNotBlank() &&
            configuration.model.isNotBlank() &&
            temperature != null && temperature in 0.0f..2.0f &&
            maxTokens != null && maxTokens in 1..32768 &&
            contextRounds != null && contextRounds in 0..20 &&
            replyDelayMs != null && replyDelayMs in 0L..60000L

    val blacklist = "黑名单"
    val whitelist = "白名单"

    NukeDialogSurface(
        title = "AI 聊天设置",
        onDismiss = {
            val destination = pendingPage
            if (destination == null) {
                onDismiss()
            } else {
                onNavigate(destination)
            }
        },
        actions = { dismiss ->
            NukeButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = dismiss,
            )
            NukeButton(
                text = "保存",
                modifier = Modifier.weight(1f),
                primary = true,
                enabled = canSave,
                onClick = dismiss,
            )
        },
    ) { dismiss ->
        Column(
            Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
        ) {
            NukeText(
                text = "使用 OpenAI 兼容的 Chat Completions 接口。聊天内容会发送到你配置的第三方服务。",
                color = NukeTheme.colors.textSecondary,
                fontSize = 12,
                lineHeight = 17,
            )
            Spacer(Modifier.height(14.dp))
            NukeText(
                text = "接口类型",
                color = NukeTheme.colors.textSecondary,
                fontSize = 13,
                lineHeight = 18,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            NukeText(
                text = "OpenAI 兼容接口",
                color = NukeTheme.colors.accent,
                fontSize = 14,
                lineHeight = 19,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(10.dp))
            NukeAiDialogField(
                label = "API Base URL",
                placeholder = "https://api.openai.com/v1",
                value = configuration.baseUrl,
                onValueChange = {
                    onConfigurationChange(configuration.copy(baseUrl = it))
                },
                description = if (configuration.baseUrl.isBlank() || baseUrlValid) {
                    "必须使用 HTTPS；会自动补全 /chat/completions。"
                } else {
                    "请输入有效的 HTTPS API Base URL。"
                },
                descriptionIsError = configuration.baseUrl.isNotBlank() && !baseUrlValid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Spacer(Modifier.height(12.dp))
            NukeAiDialogField(
                label = "API Key",
                placeholder = "sk-…",
                value = configuration.apiKey,
                onValueChange = {
                    onConfigurationChange(configuration.copy(apiKey = it))
                },
                description = "密钥仅保存在本机配置中，不会写入日志。",
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(12.dp))
            NukeAiDialogField(
                label = "模型",
                placeholder = "例如 gpt-4o-mini",
                value = configuration.model,
                onValueChange = {
                    onConfigurationChange(configuration.copy(model = it))
                },
                description = "可以手动填写模型 ID，也可以从服务端模型列表中选择。",
            )
            NukePreferenceRow(
                title = "从接口选择模型",
                description = if (baseUrlValid && configuration.apiKey.isNotBlank()) {
                    "调用当前服务的 GET /models 获取可用模型"
                } else {
                    "请先填写有效的 HTTPS Base URL 和 API Key"
                },
                enabled = baseUrlValid && configuration.apiKey.isNotBlank(),
                trailing = {
                    NukeGlyph(
                        kind = NukeGlyphKind.Chevron,
                        color = NukeTheme.colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = {
                    pendingPage = PreviewAiDialogPage.Models
                    dismiss()
                },
            )
            Spacer(Modifier.height(12.dp))
            NukeAiDialogField(
                label = "系统提示词",
                placeholder = "例如：请像自然的微信好友一样简洁回复。",
                value = configuration.systemPrompt,
                onValueChange = {
                    onConfigurationChange(configuration.copy(systemPrompt = it))
                },
                description = "可选。修改接口、模型、提示词或上下文轮数后会清空已有对话上下文。",
                singleLine = false,
                minLines = 3,
                maxLines = 6,
            )
            Spacer(Modifier.height(12.dp))
            NukeAiDialogField(
                label = "Temperature",
                placeholder = "0.0–2.0",
                value = configuration.temperature,
                onValueChange = {
                    onConfigurationChange(configuration.copy(temperature = it))
                },
                description = "数值越高回复越随机，推荐 0.7。",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                ),
            )
            Spacer(Modifier.height(12.dp))
            NukeAiDialogField(
                label = "最大输出 Tokens",
                placeholder = "1–32768",
                value = configuration.maxTokens,
                onValueChange = {
                    onConfigurationChange(configuration.copy(maxTokens = it))
                },
                description = "限制单次 AI 回复的最大输出长度。",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(12.dp))
            NukeAiDialogField(
                label = "上下文轮数",
                placeholder = "0–20",
                value = configuration.contextRounds,
                onValueChange = {
                    onConfigurationChange(configuration.copy(contextRounds = it))
                },
                description = "每个会话分别保留最近几轮问答；填 0 表示不保留上下文。",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(12.dp))
            NukeAiDialogField(
                label = "回复延迟（毫秒）",
                placeholder = "0–60000",
                value = configuration.replyDelayMs,
                onValueChange = {
                    onConfigurationChange(configuration.copy(replyDelayMs = it))
                },
                description = "收到消息后等待指定时间再请求 AI。",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(8.dp))
            NukeSelectPreference(
                title = "会话名单模式",
                description = if (configuration.listMode == PreviewAiListMode.Whitelist) {
                    "只回复所选好友和群聊；空名单不会回复任何会话。"
                } else {
                    "回复除所选好友和群聊以外的会话；空名单表示全部允许。"
                },
                options = PreviewAiListMode.entries,
                selected = configuration.listMode,
                optionLabel = {
                    if (it == PreviewAiListMode.Whitelist) whitelist else blacklist
                },
                onSelected = {
                    onConfigurationChange(configuration.copy(listMode = it))
                },
            )
            NukePreferenceRow(
                title = if (configuration.listMode == PreviewAiListMode.Whitelist) {
                    "配置白名单"
                } else {
                    "配置黑名单"
                },
                description = "已选 ${configuration.targetIds.size} 个",
                trailing = {
                    NukeGlyph(
                        kind = NukeGlyphKind.Chevron,
                        color = NukeTheme.colors.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                onClick = {
                    pendingPage = PreviewAiDialogPage.Contacts
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun NukeAiDialogField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    description: String,
    descriptionIsError: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 5,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    NukeDialogTextField(
        label = label,
        placeholder = placeholder,
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
    )
    Spacer(Modifier.height(6.dp))
    NukeText(
        text = description,
        color = if (descriptionIsError) {
            Color(0xFFDC2626)
        } else {
            NukeTheme.colors.textSecondary
        },
        fontSize = 12,
        lineHeight = 17,
        fontWeight = if (descriptionIsError) {
            FontWeight.SemiBold
        } else {
            FontWeight.Normal
        },
    )
}

@Composable
private fun NukeAiModelSelectionDialog(
    selectedModel: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var pendingSelection by remember { mutableStateOf<String?>(null) }
    val models = remember(query) {
        if (query.isBlank()) {
            previewAiModels
        } else {
            previewAiModels.filter {
                it.id.contains(query, ignoreCase = true) ||
                    it.owner.contains(query, ignoreCase = true)
            }
        }
    }

    NukeDialogSurface(
        title = "选择 AI 模型",
        onDismiss = {
            val selection = pendingSelection
            if (selection == null) {
                onDismiss()
            } else {
                onSelected(selection)
            }
        },
        actions = { dismiss ->
            NukeButton(
                text = "取消",
                modifier = Modifier.weight(1f),
                onClick = dismiss,
            )
        },
    ) { dismiss ->
        NukeTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = "搜索模型 ID 或提供方",
        )
        Spacer(Modifier.height(10.dp))
        if (models.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .height(220.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                NukeText(
                    text = "接口没有返回可用模型，仍可返回上一页手动填写模型 ID。",
                    color = NukeTheme.colors.textSecondary,
                    fontSize = 13,
                    lineHeight = 19,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(
                    count = models.size,
                    key = { models[it].id },
                ) { index ->
                    val model = models[index]
                    NukePreferenceRow(
                        title = model.id,
                        description = "提供方：${model.owner}",
                        trailing = if (model.id == selectedModel) {
                            {
                                NukeText(
                                    text = "已选择",
                                    color = NukeTheme.colors.accent,
                                    fontSize = 12,
                                    lineHeight = 17,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        } else {
                            null
                        },
                        onClick = {
                            pendingSelection = model.id
                            dismiss()
                        },
                    )
                    if (index < models.lastIndex) {
                        NukeDivider(startPadding = 0.dp, endPadding = 0.dp)
                    }
                }
            }
        }
    }
}

private fun isValidNukeAiBaseUrl(value: String): Boolean {
    val uri = runCatching { URI(value.trim()) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}
