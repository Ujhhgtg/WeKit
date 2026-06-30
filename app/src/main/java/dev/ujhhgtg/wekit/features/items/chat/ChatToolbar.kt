package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_box
import com.composables.icons.materialsymbols.outlined.Arrow_downward
import com.composables.icons.materialsymbols.outlined.Arrow_upward
import com.composables.icons.materialsymbols.outlined.Attach_file
import com.composables.icons.materialsymbols.outlined.Attach_money
import com.composables.icons.materialsymbols.outlined.AutoAwesome
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Format_list_numbered
import com.composables.icons.materialsymbols.outlined.Location_on
import com.composables.icons.materialsymbols.outlined.Mail
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Photo_library
import com.composables.icons.materialsymbols.outlined.Redeem
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Video_chat
import com.composables.icons.materialsymbols.outlined.Voice_chat
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.items.chat.ai.AiReplyHelper
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.iterable
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference

@SuppressLint("StaticFieldLeak")
@Feature(name = "聊天工具栏", categories = ["聊天"], description = "在输入框上方添加工具栏 (点击配置); 支持 AI 回复")
object ChatToolbar : ClickableFeature(), IResolveDex {
    private val TAG = This.Class.simpleName
    private val NAME_TO_ICON_MAP = mapOf(
        "相册" to MaterialSymbols.Outlined.Photo_library,
        "拍摄" to MaterialSymbols.Outlined.Camera,
        "系统拍摄" to MaterialSymbols.Outlined.Camera,
        "视频通话" to MaterialSymbols.Outlined.Video_chat,
        "语音通话" to MaterialSymbols.Outlined.Voice_chat,
        "位置" to MaterialSymbols.Outlined.Location_on,
        "红包" to MaterialSymbols.Outlined.Mail,
        "礼物" to MaterialSymbols.Outlined.Redeem,
        "转账" to MaterialSymbols.Outlined.Attach_money,
        "语音输入" to MaterialSymbols.Outlined.Mic,
        "收藏" to MaterialSymbols.Outlined.Favorite,
        "接龙" to MaterialSymbols.Outlined.Format_list_numbered,
        "文件" to MaterialSymbols.Outlined.Attach_file,
        "名片" to MaterialSymbols.Outlined.Account_box,
        "音乐" to MaterialSymbols.Outlined.Music_note
    )

    companion object {
        const val AI_REPLY_KEY = "AI 回复"
        const val AI_SETTINGS_KEY = "AI 配置"
    }

    private val methodAppPanelInitAppGrid by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.pluginsdk.ui.chat.AppPanel"
            usingEqStrings("MicroMsg.AppPanel", "initAppGrid()")
        }
    }
    private val methodAppPanelOnMeasure by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings(
                "MicroMsg.AppPanel",
                "onMeasure width: %d, heigth:%d, isMeasured:%b, gridWidth:%d, gridHeight:%d"
            )
        }
    }
    private var lastConversation: String? = null
    private data class MenuItem(
        val name: String,
        val onClickListener: AdapterView.OnItemClickListener,
        val onLongClickListener: AdapterView.OnItemLongClickListener,
        val gridView: WeakReference<GridView>,
        val itemView: WeakReference<View>,
        val indexInGrid: Int
    )
    private val toolsState = MutableStateFlow<List<Pair<String, MenuItem>>>(emptyList())
    private var itemsOrder by WePrefs.prefOption(
        "chat_toolbar_order",
        (NAME_TO_ICON_MAP.keys.joinToString(",") + "," + AI_REPLY_KEY + "," + AI_SETTINGS_KEY)
    )
    private var enabledItems by WePrefs.prefOption(
        "chat_toolbar_enabled_items",
        NAME_TO_ICON_MAP.keys + AI_REPLY_KEY + AI_SETTINGS_KEY
    )

    override fun onEnable() {
        methodAppPanelInitAppGrid.apply {
            hookBefore {
                val appPanel = args[0] as LinearLayout
                val measurer = methodAppPanelOnMeasure.method.declaringClass.createInstance(appPanel)
                methodAppPanelOnMeasure.method.invoke(measurer, 1440, 1200)
            }
            hookAfter {
                val currentConv = WeCurrentConversationApi.value
                if (currentConv == lastConversation && toolsState.value.isNotEmpty()) return@hookAfter
                val tools = mutableListOf<Pair<String, MenuItem>>()
                val appPanel = args[0] as LinearLayout
                val grids = appPanel.findViewByChildIndexes<ViewGroup>(0, 0, 0)!!
                    .children.map { view -> view as GridView }
                grids.forEach { grid ->
                    val onClickListener = grid.reflekt()
                        .firstField { type = AdapterView.OnItemClickListener::class }.get()!! as AdapterView.OnItemClickListener
                    val onLongClickListener = grid.reflekt()
                        .firstField { type = AdapterView.OnItemLongClickListener::class }.get()!! as AdapterView.OnItemLongClickListener
                    val listAdapter = grid.adapter
                    listAdapter.iterable(grid).forEachIndexed { index, itemView ->
                        val name = (itemView.tag.reflekt()
                            .firstField { type = TextView::class }
                            .get()!! as TextView).text.toString()
                        tools.add(
                            name to MenuItem(
                                name,
                                onClickListener,
                                onLongClickListener,
                                WeakReference(grid),
                                WeakReference(itemView),
                                index
                            )
                        )
                    }
                }
                WeLogger.d(TAG, "populated tool list with ${tools.size} items for conversation: $currentConv")
                toolsState.value = tools
                lastConversation = currentConv
            }
        }
        ChatFooter::class.reflekt()
            .firstConstructor {
                parameters(Context::class, AttributeSet::class, Int::class)
            }
            .hookAfter {
                val chatFooter = thisObject as FrameLayout
                val activity = chatFooter.context as Activity
                val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity )
                chatFooter.setLifecycleOwner(lifecycleOwner)
                val linearLayout = chatFooter.findViewByChildIndexes<LinearLayout>(0, 1)!!
                linearLayout.setLifecycleOwner(lifecycleOwner)
                if (linearLayout.findViewWhich<View> { it is ComposeView } != null) return@hookAfter
                activity.window.decorView.setLifecycleOwner(lifecycleOwner)
                linearLayout.addView(ComposeView(activity).apply {
                    setLifecycleOwner(lifecycleOwner)
                    setContent {
                        AppTheme {
                            DisposableEffect(lifecycleOwner) {
                                val observer = LifecycleEventObserver { _, event ->
                                    when (event) {
                                        Lifecycle.Event.ON_PAUSE,
                                        Lifecycle.Event.ON_STOP,
                                        Lifecycle.Event.ON_DESTROY -> {
                                            lastConversation = null
                                        }
                                        else -> {}
                                    }
                                }
                                lifecycleOwner.lifecycle.addObserver(observer)
                                onDispose {
                                    lifecycleOwner.lifecycle.removeObserver(observer)
                                    toolsState.value = emptyList()
                                    lastConversation = null
                                }
                            }

                            val tools by toolsState.collectAsStateWithLifecycle()
                            val itemsOrder = remember { itemsOrder }
                            val enabledItems = remember { enabledItems }
                            val sortedVisibleItems = remember(tools) {
                                if (tools.isEmpty()) return@remember emptyList()
                                val firstTool = tools[0].second
                                val orderList = itemsOrder.split(",").filter { it.isNotEmpty() }
                                val list = mutableListOf<Pair<String, () -> Unit>>()
                                list.add("相册" to {
                                    firstTool.onClickListener.onItemClick(firstTool.gridView.get()!!, firstTool.itemView.get()!!, 0, 0)
                                })
                                list.add("系统拍摄" to {
                                    firstTool.onLongClickListener.onItemLongClick(null, null, 0, 0)
                                })
                                tools.forEach { (name, menuItem) ->
                                    if (name in NAME_TO_ICON_MAP && name != "相册" && name != "系统拍摄") {
                                        val gridView = menuItem.gridView.get() ?: return@forEach
                                        val itemView = menuItem.itemView.get() ?: return@forEach
                                        list.add(name to {
                                            menuItem.onClickListener.onItemClick(
                                                gridView,
                                                itemView,
                                                menuItem.indexInGrid + 1,
                                                0
                                            )
                                        })
                                    }
                                }
                                // ── AI 回复芯片 ──
                                list.add(AI_REPLY_KEY to {
                                    AiReplyHelper.replyToCurrentConversation(activity)
                                })
                                // ── AI 配置入口 ──
                                list.add(AI_SETTINGS_KEY to {
                                    showAiConfigDialog(activity)
                                })

                                list.filter { it.first in enabledItems }
                                    .sortedBy { item ->
                                        val idx = orderList.indexOf(item.first)
                                        if (idx == -1) Int.MAX_VALUE else idx
                                    }
                            }

                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                            ) {
                                items(sortedVisibleItems, key = { it.first }) { (name, onClick) ->
                                    val icon = when (name) {
                                        AI_REPLY_KEY -> MaterialSymbols.Outlined.AutoAwesome
                                        AI_SETTINGS_KEY -> MaterialSymbols.Outlined.Settings
                                        else -> NAME_TO_ICON_MAP[name]!!
                                    }
                                    FeatureChip(name, icon, onClick)
                                }
                            }
                        }
                    }
                }, 0)
            }
    }

    override fun onDisable() {
        toolsState.value = emptyList()
        lastConversation = null
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            val currentOrder = remember {
                val order = itemsOrder.split(",").filter { it.isNotEmpty() }.toMutableStateList()
                (NAME_TO_ICON_MAP.keys + AI_REPLY_KEY + AI_SETTINGS_KEY).forEach {
                    if (it !in order) order.add(it)
                }
                order
            }
            val currentEnabled = remember { enabledItems.toMutableStateList() }
            AlertDialogContent(
                title = { Text("聊天工具栏配置") },
                text = {
                    LazyColumn(modifier = Modifier.size(height = 400.dp, width = 300.dp)) {
                        itemsIndexed(currentOrder) { index, name ->
                            val icon = when (name) {
                                AI_REPLY_KEY -> MaterialSymbols.Outlined.AutoAwesome
                                AI_SETTINGS_KEY -> MaterialSymbols.Outlined.Settings
                                else -> NAME_TO_ICON_MAP[name]
                            }
                            ListItem(
                                headlineContent = { Text(name) },
                                leadingContent = {
                                    icon?.let { ic ->
                                        Icon(ic, contentDescription = null, modifier = Modifier.size(24.dp))
                                    }
                                },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = {
                                            if (index > 0) {
                                                val temp = currentOrder[index]
                                                currentOrder[index] = currentOrder[index - 1]
                                                currentOrder[index - 1] = temp
                                            }
                                        }, enabled = index > 0) {
                                            Icon(MaterialSymbols.Outlined.Arrow_upward, contentDescription = "Up")
                                        }
                                        IconButton(onClick = {
                                            if (index < currentOrder.size - 1) {
                                                val temp = currentOrder[index]
                                                currentOrder[index] = currentOrder[index + 1]
                                                currentOrder[index + 1] = temp
                                            }
                                        }, enabled = index < currentOrder.size - 1) {
                                            Icon(MaterialSymbols.Outlined.Arrow_downward, contentDescription = "Down")
                                        }
                                        Switch(
                                            checked = name in currentEnabled,
                                            onCheckedChange = { checked ->
                                                if (checked) currentEnabled.add(name) else currentEnabled.remove(name)
                                            }
                                        )
                                    }
                                }
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        itemsOrder = currentOrder.joinToString(",")
                        enabledItems = currentEnabled.toSet()
                        onDismiss()
                    }) {
                        Text("确定")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }

    private fun showAiConfigDialog(context: Context) {
        showComposeDialog(context) {
            var apiUrl by remember { mutableStateOf(AiReplyHelper.apiUrl) }
            var apiKey by remember { mutableStateOf(AiReplyHelper.apiKey) }
            var model by remember { mutableStateOf(AiReplyHelper.model) }
            var systemPrompt by remember { mutableStateOf(AiReplyHelper.systemPrompt) }
            var temperature by remember { mutableFloatStateOf(AiReplyHelper.temperature) }
            var maxTokens by remember { mutableIntStateOf(AiReplyHelper.maxTokens) }
            var contextCount by remember { mutableIntStateOf(AiReplyHelper.contextCount) }

            AlertDialogContent(
                title = { Text("AI 回复配置") },
                text = {
                    Column(
                        modifier = Modifier
                            .size(height = 420.dp, width = 320.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        OutlinedTextField(
                            value = apiUrl,
                            onValueChange = { apiUrl = it },
                            label = { Text("API 地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = model,
                            onValueChange = { model = it },
                            label = { Text("模型名称") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("gpt-4o-mini") }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("温度: ${String.format("%.1f", temperature)}")
                        Slider(
                            value = temperature,
                            onValueChange = { temperature = it },
                            valueRange = 0f..2f,
                            steps = 19
                        )
                        Spacer(Modifier.height(4.dp))
                        OutlinedTextField(
                            value = systemPrompt,
                            onValueChange = { systemPrompt = it },
                            label = { Text("系统提示词") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(100.dp),
                            maxLines = 4
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("上下文消息数: $contextCount")
                            Spacer(Modifier.width(8.dp))
                            Slider(
                                value = contextCount.toFloat(),
                                onValueChange = { contextCount = it.toInt() },
                                valueRange = 1f..50f,
                                steps = 48,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        OutlinedTextField(
                            value = maxTokens.toString(),
                            onValueChange = { it.toIntOrNull()?.let { n -> maxTokens = n } },
                            label = { Text("最大 Token") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        AiReplyHelper.apiUrl = apiUrl
                        AiReplyHelper.apiKey = apiKey
                        AiReplyHelper.model = model
                        AiReplyHelper.systemPrompt = systemPrompt
                        AiReplyHelper.temperature = temperature
                        AiReplyHelper.maxTokens = maxTokens
                        AiReplyHelper.contextCount = contextCount
                        onDismiss()
                    }) {
                        Text("保存")
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
private fun FeatureChip(text: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(text) },
        leadingIcon = {
            Icon(
                icon,
                contentDescription = null,
                Modifier.size(AssistChipDefaults.IconSize)
            )
        }
    )
}
