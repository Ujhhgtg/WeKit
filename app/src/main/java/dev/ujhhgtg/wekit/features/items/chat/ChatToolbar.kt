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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_box
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_downward
import com.composables.icons.materialsymbols.outlined.Arrow_upward
import com.composables.icons.materialsymbols.outlined.Attach_file
import com.composables.icons.materialsymbols.outlined.Attach_money
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Delete
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
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.lang.ref.WeakReference

@SuppressLint("StaticFieldLeak")
@Feature(name = "聊天工具栏", categories = ["聊天"], description = "在输入框上方添加工具栏")
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

    // 快捷回复 is a wekit-injected item (not backed by a WeChat grid tool), so it lives
    // outside NAME_TO_ICON_MAP. Its icon is resolved via iconFor().
    private const val QUICK_REPLY_NAME = "快捷回复"

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

    private var itemsOrder by WePrefs.prefOption("chat_toolbar_order", NAME_TO_ICON_MAP.keys.joinToString(","))
    private var enabledItems by WePrefs.prefOption("chat_toolbar_enabled_items", NAME_TO_ICON_MAP.keys)

    // quick replies are stored as a JSON string array so individual replies may safely
    // contain commas, newlines or any other character
    private var quickRepliesRaw by WePrefs.prefOption("chat_toolbar_quick_replies", "")

    private val quickRepliesSerializer = ListSerializer(String.serializer())

    private fun loadQuickReplies(): List<String> {
        val raw = quickRepliesRaw
        if (raw.isEmpty()) return emptyList()
        return runCatching { Json.decodeFromString(quickRepliesSerializer, raw) }
            .getOrElse {
                WeLogger.w(TAG, "failed to parse quick replies, resetting: ${it.message}")
                emptyList()
            }
    }

    private fun saveQuickReplies(replies: List<String>) {
        quickRepliesRaw = Json.encodeToString(quickRepliesSerializer, replies)
    }

    private fun iconFor(name: String): ImageVector =
        if (name == QUICK_REPLY_NAME) MaterialSymbols.Outlined.Chat else NAME_TO_ICON_MAP.getValue(name)

    // ensures 快捷回复 is present and ordered first while keeping the user's saved order for
    // everything else; safe to call on legacy configs that predate the feature
    private fun normalizeOrder(order: List<String>): List<String> {
        val result = order.toMutableList()
        result.remove(QUICK_REPLY_NAME)
        result.add(0, QUICK_REPLY_NAME)
        NAME_TO_ICON_MAP.keys.forEach { if (it !in result) result.add(it) }
        return result
    }

    private fun insertQuickReply(text: String) {
        WeMessageApi.sendText(WeCurrentConversationApi.value, text)
    }

    override fun onEnable() {
        LauncherUI::class.reflekt().firstMethod("startChatting").hookBefore {
            lastConversation = null
        }

        methodAppPanelInitAppGrid.apply {
            hookBefore {
                val appPanel = args[0] as LinearLayout
                // WeChat normally lets MMFlipper.onMeasure feed the real measured size into the
                // measurer (g.a). We have to invoke initAppGrid before the panel is laid out, so we
                // reproduce WeChat's own natural dimensions instead of hardcoding pixels.
                //   width  = screen width (initAppGrid derives column count as gridWidth / dp(82))
                //   height = the MMFlipper height. initAppGrid spreads any height left over after
                //            the icon rows into grid spacing/top-padding, so overshooting here shows
                //            up as extra padding at the bottom of the panel.
                // The panel's port height is NOT a fixed 215dp: getPortHeightPX() returns a value
                // set to match the soft-keyboard height (setPortHeighPx), which is device/IME
                // dependent. The container LinearLayout (a1r, child path 0,0) already has that
                // resolved height in its layoutParams (set in AppPanel.y()), so read it at runtime
                // and only fall back to the 215dp portrait / 158dp landscape default. The flipper
                // is that container minus the MMDotView strip below it (6dp dot + 16dp paddingBottom
                // = 22dp, see layout hy.xml), which is fixed in dp.
                val metrics = appPanel.resources.displayMetrics
                val width = metrics.widthPixels
                val fallbackDp = if (metrics.widthPixels < metrics.heightPixels) 215 else 158
                val containerHeight = appPanel.findViewByChildIndexes<View>(0, 0)
                    ?.layoutParams?.height?.takeIf { it > 0 }
                    ?: (fallbackDp * metrics.density).toInt()
                val dotStrip = (22 * metrics.density).toInt()
                val height = (containerHeight - dotStrip).coerceAtLeast(1)
                val measurer = methodAppPanelOnMeasure.method.declaringClass.createInstance(appPanel)
                methodAppPanelOnMeasure.method.invoke(measurer, width, height)
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
                                val orderList = normalizeOrder(itemsOrder.split(",").filter { it.isNotEmpty() })
                                val list = mutableListOf<Pair<String, () -> Unit>>()

                                list.add(QUICK_REPLY_NAME to {
                                    showQuickReplyPicker(activity)
                                })

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

                                list.distinctBy { it.first }
                                    .filter { it.first in enabledItems }
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
                                    val icon = iconFor(name)
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
                normalizeOrder(itemsOrder.split(",").filter { it.isNotEmpty() }).toMutableStateList()
            }
            val currentEnabled = remember { enabledItems.toMutableStateList() }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("聊天工具栏") },
                text = {
                    LazyColumn {
                        itemsIndexed(currentOrder) { index, name ->
                            ListItem(
                                headlineContent = { Text(name) },
                                leadingContent = {
                                    Icon(iconFor(name), contentDescription = null, modifier = Modifier.size(24.dp))
                                },
                                trailingContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (name == QUICK_REPLY_NAME) {
                                            IconButton(onClick = { showQuickReplyConfig(context) }) {
                                                Icon(MaterialSymbols.Outlined.Settings, contentDescription = "配置快捷回复")
                                            }
                                        }
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

    // shown when the user taps the 快捷回复 chip in the chat toolbar: pick a reply to insert
    private fun showQuickReplyPicker(context: Context) {
        showComposeDialog(context) {
            val replies = remember { loadQuickReplies() }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(QUICK_REPLY_NAME) },
                text = {
                    if (replies.isEmpty()) {
                        Text("暂无快捷回复, 请在「聊天工具栏」设置中配置")
                    } else {
                        LazyColumn {
                            items(replies) { reply ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        insertQuickReply(reply)
                                        onDismiss()
                                    },
                                    headlineContent = { Text(reply) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            )
        }
    }

    // shown from the cogwheel in the 快捷回复 settings row: add/modify/remove replies
    private fun showQuickReplyConfig(context: Context) {
        showComposeDialog(context) {
            val replies = remember { loadQuickReplies().toMutableStateList() }

            AlertDialogContent(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                title = { Text("配置$QUICK_REPLY_NAME") },
                text = {
                    LazyColumn {
                        itemsIndexed(replies) { index, reply ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextField(
                                    value = reply,
                                    onValueChange = { replies[index] = it },
                                    modifier = Modifier.weight(1f),
                                    singleLine = false
                                )
                                IconButton(onClick = { replies.removeAt(index) }) {
                                    Icon(MaterialSymbols.Outlined.Delete, contentDescription = "删除")
                                }
                            }
                        }
                        item {
                            TextButton(onClick = { replies.add("") }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                                    Text("添加")
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        saveQuickReplies(replies.map { it.trim() }.filter { it.isNotEmpty() })
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
