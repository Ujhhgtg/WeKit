package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import dev.ujhhgtg.wekit.ui.utils.ListItem
import dev.ujhhgtg.wekit.ui.utils.ReorderableList
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.children
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_box
import com.composables.icons.materialsymbols.outlined.Add
import com.composables.icons.materialsymbols.outlined.Arrow_drop_down
import com.composables.icons.materialsymbols.outlined.Attach_file
import com.composables.icons.materialsymbols.outlined.Attach_money
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlined.Edit
import com.composables.icons.materialsymbols.outlined.Favorite
import com.composables.icons.materialsymbols.outlined.Format_list_numbered
import com.composables.icons.materialsymbols.outlined.Location_on
import com.composables.icons.materialsymbols.outlined.Mail
import com.composables.icons.materialsymbols.outlined.Mic
import com.composables.icons.materialsymbols.outlined.Music_note
import com.composables.icons.materialsymbols.outlined.Photo_library
import com.composables.icons.materialsymbols.outlined.Redeem
import com.composables.icons.materialsymbols.outlined.Settings
import com.composables.icons.materialsymbols.outlined.Smart_toy
import com.composables.icons.materialsymbols.outlined.Video_chat
import com.composables.icons.materialsymbols.outlined.Voice_chat
import com.tencent.mm.pluginsdk.ui.chat.AppPanel
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeChatInputBarMenuApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.system.agent.WeAgentOverlayController
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.iterable
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.constructor
import dev.ujhhgtg.wekit.utils.now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private enum class ToolbarDisplayMode(
    val preferenceValue: String,
    @StringRes val labelRes: Int,
) {
    ICON_AND_TEXT("icon_and_text", R.string.chat_toolbar_mode_icon_and_text),
    ICON_ONLY("icon_only", R.string.chat_toolbar_mode_icon_only),
    TEXT_ONLY("text_only", R.string.chat_toolbar_mode_text_only);

    companion object {
        fun fromPreference(value: String): ToolbarDisplayMode =
            entries.firstOrNull { it.preferenceValue == value } ?: ICON_AND_TEXT
    }
}

@SuppressLint("StaticFieldLeak")
object ChatToolbar : ClickableFeature(), IResolveDex {

    override val technicalId = "聊天工具栏"
    override val nameRes = R.string.feature_chat_toolbar_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_toolbar_description

    private const val TAG = "ChatToolbar"

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
        "个人名片" to MaterialSymbols.Outlined.Account_box,
        "音乐" to MaterialSymbols.Outlined.Music_note
    )

    // 快捷回复 and WeAgent are wekit-injected items (not backed by a WeChat grid tool), so they
    // live outside NAME_TO_ICON_MAP. Their icons are resolved via iconFor().
    private const val QUICK_REPLY_NAME = "快捷回复"
    private const val WEAGENT_NAME = "WeAgent"

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

    private data class MenuItem(
        val name: String,
        val onClickListener: AdapterView.OnItemClickListener,
        val onLongClickListener: AdapterView.OnItemLongClickListener,
        val gridView: WeakReference<GridView>,
        val itemView: WeakReference<View>,
        val indexInGrid: Int
    )

    private data class QuickReplyDraft(
        val id: String = UUID.randomUUID().toString(),
        val text: String,
    )

    private class PanelTools {
        val flow = MutableStateFlow<List<Pair<String, MenuItem>>>(emptyList())

        /** null until this panel's grid has been read at least once. */
        var lastSnapshotTime: Instant? = null
        var refreshScheduled = false
    }

    /**
     * A tool list belongs to one AppPanel, not to the process: every chat footer builds its own
     * panel, and WeChat builds more than one footer at a time (see [scheduleGridInitWatchdog]), so a
     * background chat's panel must not be able to overwrite the visible chat's toolbar — nor hand it
     * click targets that belong to another conversation.
     *
     * [MenuItem] only ever holds *weak* references to the panel's views, so a value can't pin its
     * own key here.
     */
    private val panelTools = WeakHashMap<AppPanel, PanelTools>()
    private val appGridToolTypes = WeakHashMap<View, Int>()

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    private fun toolsOf(appPanel: AppPanel): PanelTools =
        synchronized(panelTools) { panelTools.getOrPut(appPanel) { PanelTools() } }

    private fun toolbarNameForAppGridType(type: Int): String? = when (type) {
        0 -> "相册"
        1 -> "拍摄"
        2 -> "视频通话"
        3, 4 -> "语音通话"
        6 -> "位置"
        7 -> "红包"
        8 -> "礼物"
        9 -> "转账"
        11 -> "语音输入"
        12 -> "收藏"
        14 -> "接龙"
        16 -> "个人名片"
        20 -> "文件"
        22 -> "音乐"
        else -> null
    }

    /**
     * AppGrid resolves native tools to an internal type before rendering them. Capture that
     * type from getView instead of using the localized TextView label as an identity.
     */
    private fun captureAppGridToolType(appGrid: Any, itemView: View) {
        val drawable = buildList {
            fun collect(view: View) {
                if (view is ImageView && view.drawable != null) add(view.drawable)
                if (view is ViewGroup) view.children.forEach(::collect)
            }
            collect(itemView)
        }.firstOrNull() ?: return
        val resourceId = drawable.reflekt()
            .firstField { type = Int::class; superclass() }
            .get() as Int
        val resourceName = itemView.resources.getResourceEntryName(resourceId)
        val type = mapOf(
            "panel_icon_pic" to 0,
            "panel_icon_camera" to 1,
            "panel_icon_voip" to 2,
            "panel_icon_multitalk" to 3,
            "panel_icon_voipvoice" to 4,
            "panel_icon_location" to 6,
            "panel_icon_luckymoney" to 7,
            "icons_filled_gift_chatting" to 8,
            "panel_icon_transfer" to 9,
            "panel_icon_voiceinput" to 11,
            "panel_icon_fav" to 12,
            "icons_outlined_continued_form" to 14,
            "panel_icon_friendcard" to 16,
            "panel_icon_file_explorer" to 20,
            "icon_music_filled" to 22,
        )[resourceName]
        if (type == null) return

        synchronized(appGridToolTypes) { appGridToolTypes[itemView] = type }
    }

    private var itemsOrder by WePrefs.prefOption("chat_toolbar_order", NAME_TO_ICON_MAP.keys.joinToString(","))
    private var enabledItems by WePrefs.prefOption("chat_toolbar_enabled_items", NAME_TO_ICON_MAP.keys)
    private var displayModeValue by WePrefs.prefOption(
        "chat_toolbar_display_mode",
        ToolbarDisplayMode.ICON_AND_TEXT.preferenceValue,
    )

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

    private fun iconFor(name: String): ImageVector = when (name) {
        QUICK_REPLY_NAME -> MaterialSymbols.Outlined.Chat
        WEAGENT_NAME -> MaterialSymbols.Outlined.Smart_toy
        else -> NAME_TO_ICON_MAP.getValue(name)
    }

    /**
     * The keys remain the exact host labels and legacy preference identities. Only presentation is
     * localized, so changing WeKit's language never rewrites the saved order or host matching.
     */
    @StringRes
    private fun labelResFor(name: String): Int = when (name) {
        "相册" -> R.string.chat_toolbar_tool_album
        "拍摄" -> R.string.chat_toolbar_tool_camera
        "系统拍摄" -> R.string.chat_toolbar_tool_system_camera
        "视频通话" -> R.string.chat_toolbar_tool_video_call
        "语音通话" -> R.string.chat_toolbar_tool_voice_call
        "位置" -> R.string.chat_toolbar_tool_location
        "红包" -> R.string.chat_toolbar_tool_red_packet
        "礼物" -> R.string.chat_toolbar_tool_gift
        "转账" -> R.string.chat_toolbar_tool_transfer
        "语音输入" -> R.string.chat_toolbar_tool_voice_input
        "收藏" -> R.string.chat_toolbar_tool_favorites
        "接龙" -> R.string.chat_toolbar_tool_solitaire
        "文件" -> R.string.chat_toolbar_tool_file
        "个人名片" -> R.string.chat_toolbar_tool_contact_card
        "音乐" -> R.string.chat_toolbar_tool_music
        QUICK_REPLY_NAME -> R.string.chat_toolbar_quick_reply
        WEAGENT_NAME -> R.string.feature_we_agent_name
        else -> error("unsupported toolbar item: $name")
    }

    // Ensures every supported item is present while preserving the user's saved order. Legacy
    // configs that predate quick replies get that item inserted first, and ones that predate the
    // WeAgent entry get it inserted right before 快捷回复.
    private fun normalizeOrder(order: List<String>): List<String> {
        val supportedItems = setOf(QUICK_REPLY_NAME, WEAGENT_NAME) + NAME_TO_ICON_MAP.keys
        val result = order.filter { it in supportedItems }.distinct().toMutableList()
        if (QUICK_REPLY_NAME !in result) result.add(0, QUICK_REPLY_NAME)
        if (WEAGENT_NAME !in result) result.add(result.indexOf(QUICK_REPLY_NAME), WEAGENT_NAME)
        NAME_TO_ICON_MAP.keys.forEach { if (it !in result) result.add(it) }
        return result
    }

    private fun insertQuickReply(text: String) {
        WeMessageApi.sendText(WeCurrentConversationApi.value, text)
    }

    /**
     * Reading a panel's grid inflates one item view per entry, and WeChat re-runs initAppGrid in
     * bursts, so snapshots are debounced. The debounce is trailing-edge: a suppressed call schedules
     * a single delayed refresh instead of being dropped, so the grid's final state always reaches the
     * toolbar. (Dropping used to lose the *only* initAppGrid of a chat when it happened to land in
     * the window — e.g. right after WeKit loaded, when the initial baseline was still "now".)
     */
    private val toolListDebounce = 2.seconds

    /**
     * AppPanel.t() schedules its grid data load with a 1000ms delay, so WeChat's own initAppGrid
     * normally lands a bit after that; only step in once it clearly hasn't.
     */
    private const val GRID_INIT_WATCHDOG_DELAY_MS = 1500L

    /** Reads the panel's grids into its [PanelTools.flow]. No-op while the grid isn't built yet. */
    private fun snapshotTools(appPanel: AppPanel) {
        val tools = mutableListOf<Pair<String, MenuItem>>()

        // (0, 0, 0) is the MMFlipper holding one GridView per page; absent until AppPanel.init()
        // has inflated the panel's layout.
        val grids = (appPanel.findViewByChildIndexes(0, 0, 0) as ViewGroup?)
            ?.children?.map { view -> view as GridView }
            ?: return

        grids.forEach { grid ->
            val onClickListener = grid.reflekt()
                .firstField { type = AdapterView.OnItemClickListener::class }.get()!! as AdapterView.OnItemClickListener
            val onLongClickListener = grid.reflekt()
                .firstField { type = AdapterView.OnItemLongClickListener::class }.get()!! as AdapterView.OnItemLongClickListener
            val listAdapter = grid.adapter

            listAdapter.iterable(grid).forEachIndexed { index, itemView ->
                val canonicalName = synchronized(appGridToolTypes) {
                    appGridToolTypes[itemView]?.let(::toolbarNameForAppGridType)
                } ?: return@forEachIndexed
                tools.add(
                    canonicalName to MenuItem(
                        canonicalName,
                        onClickListener,
                        onLongClickListener,
                        WeakReference(grid),
                        WeakReference(itemView),
                        index
                    )
                )
            }
        }

        // An empty read means initAppGrid bailed out before building anything (it returns early
        // while the grid dimensions are still unknown). Publishing that would clear a toolbar that
        // already works and mark the panel as snapshotted.
        if (tools.isEmpty()) return

        val state = toolsOf(appPanel)
        state.flow.value = tools
        state.lastSnapshotTime = now()
        WeLogger.d(TAG, "populated tool list with ${tools.size} items")
    }

    /**
     * Makes sure a chat footer's grid gets built even when WeChat never asks for it.
     *
     * initAppGrid has exactly two triggers: the MMFlipper's onMeasure listener — which needs the
     * panel to actually be laid out, i.e. the user tapping "+" — and AppPanel.loadData(), scheduled
     * by AppPanel.init() with a 1000ms delay. loadData() is the one that makes the toolbar work
     * without user interaction, but it runs on a *process-wide* task group tagged
     * "AppPanel-loadinfo" that AppPanel.loadData() itself cancels on every call. So a second chat
     * footer built within that 1s window silently cancels the first panel's pending load, and that
     * panel's grid then stays empty until the user opens it by hand.
     *
     * Opening a chat from a notification or an external app share is exactly that case: WeChat
     * builds more than one chat footer in quick succession. Kick the grid off ourselves rather than
     * depending on WeChat's cancellable schedule.
     */
    private fun scheduleGridInitWatchdog(appPanel: AppPanel) {
        mainHandler.postDelayed({
            if (toolsOf(appPanel).lastSnapshotTime != null) return@postDelayed
            // initAppGrid dereferences views that AppPanel.init() inflates, so only force it once
            // the panel's layout is there.
            if (appPanel.findViewByChildIndexes(0, 0, 0) == null) return@postDelayed

            WeLogger.d(TAG, "grid was never initialized for this chat footer, forcing initAppGrid")
            // R8 staticizes initAppGrid on current builds, which is why the hooks read the panel out
            // of args[0]; tolerate the instance shape too, since this call is outside a hook and an
            // argument mismatch would take the process down.
            val method = methodAppPanelInitAppGrid.method
            if (java.lang.reflect.Modifier.isStatic(method.modifiers)) method.invoke(null, appPanel)
            else method.invoke(appPanel)
        }, GRID_INIT_WATCHDOG_DELAY_MS)
    }

    override fun onEnable() {
        methodAppPanelInitAppGrid.apply {
            hookBefore {
                val appPanel = args[0] as AppPanel
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
                val containerHeight = appPanel.findViewByChildIndexes(0, 0)
                    ?.layoutParams?.height?.takeIf { it > 0 }
                    ?: (fallbackDp * metrics.density).toInt()
                val dotStrip = (22 * metrics.density).toInt()
                val height = (containerHeight - dotStrip).coerceAtLeast(1)
                val measurer = methodAppPanelOnMeasure.method.declaringClass.createInstance(appPanel)
                methodAppPanelOnMeasure.method.invoke(measurer, width, height)
            }

            hookAfter {
                val appPanel = args[0] as AppPanel
                val state = toolsOf(appPanel)

                val elapsed = state.lastSnapshotTime?.let { now() - it }
                if (elapsed == null || elapsed >= toolListDebounce) {
                    snapshotTools(appPanel)
                    return@hookAfter
                }

                // Inside the cooldown: coalesce into one trailing refresh so this update is delayed
                // rather than lost — it may well be the one carrying the panel's final item set.
                if (state.refreshScheduled) return@hookAfter
                state.refreshScheduled = true
                mainHandler.postDelayed({
                    state.refreshScheduled = false
                    snapshotTools(appPanel)
                }, (toolListDebounce - elapsed).inWholeMilliseconds.coerceAtLeast(1))
            }
        }

        WeChatInputBarMenuApi.methodAppGridGetView.hookAfter {
            val itemView = result as View
            captureAppGridToolType(thisObject!!, itemView)
        }

        ChatFooter::class.constructor.hookAfter {
            val chatFooter = thisObject as FrameLayout
            val activity = chatFooter.context as Activity

            val lifecycleOwner = LifecycleOwnerProvider.getOrCreate(activity)

            chatFooter.setLifecycleOwner(lifecycleOwner)
            val linearLayout = chatFooter.findViewByChildIndexes(0, 1)!! as LinearLayout
            linearLayout.setLifecycleOwner(lifecycleOwner)
            if (linearLayout.findViewWhich { it is ComposeView } != null) return@hookAfter
            activity.window.decorView.setLifecycleOwner(lifecycleOwner)

            // The panel is part of the footer's own layout and ChatFooter.initAppPanel() has already
            // run inside the constructor, so it is reachable here. Bind this toolbar to that panel
            // only, and make sure something initializes its grid.
            val appPanel = chatFooter.findViewWhich { it is AppPanel } as AppPanel?
            if (appPanel == null) WeLogger.w(TAG, "no AppPanel in this chat footer, toolbar will stay empty")
            val toolsFlow = appPanel?.let { toolsOf(it).flow } ?: MutableStateFlow(emptyList())
            appPanel?.let { scheduleGridInitWatchdog(it) }

            linearLayout.addView(ComposeView(activity).apply {
                setLifecycleOwner(lifecycleOwner)

                setContent {
                    InjectedUiTheme {
                        val tools by toolsFlow.collectAsStateWithLifecycle()
                        val itemsOrder = remember { itemsOrder }
                        val enabledItems = remember { enabledItems }
                        val displayMode = remember { ToolbarDisplayMode.fromPreference(displayModeValue) }

                        val sortedVisibleItems = remember(tools) {
                            if (tools.isEmpty()) return@remember emptyList()

                            val firstTool = tools[0].second
                            val orderList = normalizeOrder(itemsOrder.split(",").filter { it.isNotEmpty() })
                            val list = mutableListOf<Pair<String, () -> Unit>>()

                            list.add(WEAGENT_NAME to {
                                // The panel is a system overlay window, so it works from any
                                // Activity — and stays reachable when the ball is disabled.
                                WeAgentService.init()
                                WeAgentOverlayController.openPanel()
                            })

                            list.add(QUICK_REPLY_NAME to {
                                showQuickReplyPicker(activity)
                            })

                            // 系统拍摄 is not a grid entry of its own: it is what long-pressing the
                            // first item (相册, grid position 0) does. WeChat's long-click listener
                            // only looks at the position, so the view arguments may stay null.
                            list.add("系统拍摄" to {
                                firstTool.onLongClickListener.onItemLongClick(null, null, 0, 0)
                            })

                            tools.forEach { (name, menuItem) ->
                                // item views are inflated by the snapshot and only held weakly,
                                // so they can be collected before the chip is ever tapped
                                val gridView = menuItem.gridView.get() ?: return@forEach
                                val itemView = menuItem.itemView.get() ?: return@forEach
                                list.add(name to {
                                    menuItem.onClickListener.onItemClick(
                                        gridView,
                                        itemView,
                                        menuItem.indexInGrid,
                                        0
                                    )
                                })
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
                                FeatureChip(stringResource(labelResFor(name)), icon, displayMode, onClick)
                            }
                        }
                    }
                }
            }, 0)
        }
    }

    override fun onDisable() {
        synchronized(panelTools) {
            panelTools.values.forEach { it.flow.value = emptyList() }
            panelTools.clear()
        }
        synchronized(appGridToolTypes) { appGridToolTypes.clear() }
    }

    @OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            val currentOrder = remember {
                normalizeOrder(itemsOrder.split(",").filter { it.isNotEmpty() }).toMutableStateList()
            }
            val currentEnabled = remember { enabledItems.toMutableStateList() }
            var currentDisplayMode by remember {
                mutableStateOf(ToolbarDisplayMode.fromPreference(displayModeValue))
            }
            var displayModeMenuExpanded by remember { mutableStateOf(false) }
            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(stringResource(R.string.feature_chat_toolbar_name)) },
                text = {
                    DefaultColumn {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            ListItem(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { displayModeMenuExpanded = true },
                                content = { Text(stringResource(R.string.chat_toolbar_display_style)) },
                                supportingContent = { Text(stringResource(currentDisplayMode.labelRes)) },
                                trailingContent = {
                                    Icon(
                                        MaterialSymbols.Outlined.Arrow_drop_down,
                                        contentDescription = stringResource(R.string.chat_toolbar_select_display_style_description),
                                    )
                                },
                            )
                            Box(Modifier.align(Alignment.CenterStart)) {
                                DropdownMenuPopup(
                                    expanded = displayModeMenuExpanded,
                                    onDismissRequest = { displayModeMenuExpanded = false },
                                ) {
                                    DropdownMenuGroup(shapes = MenuDefaults.groupShapes()) {
                                        ToolbarDisplayMode.entries.forEachIndexed { index, mode ->
                                            DropdownMenuItem(
                                                selected = mode == currentDisplayMode,
                                                onClick = {
                                                    currentDisplayMode = mode
                                                    displayModeMenuExpanded = false
                                                },
                                                text = { Text(stringResource(mode.labelRes)) },
                                                shapes = MenuDefaults.itemShape(index, ToolbarDisplayMode.entries.size),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Column {
                            Text(stringResource(R.string.chat_toolbar_display_order), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.chat_toolbar_reorder_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        ReorderableList(
                            items = currentOrder,
                            itemKey = { it },
                            onMove = { from, to ->
                                currentOrder.add(to, currentOrder.removeAt(from))
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 480.dp),
                        ) { name, dragHandleModifier ->
                            val label = stringResource(labelResFor(name))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 60.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .then(dragHandleModifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        MaterialSymbols.Outlined.Drag_handle,
                                        contentDescription = stringResource(R.string.chat_toolbar_drag_item_description, label),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Box(
                                    modifier = Modifier.size(36.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        iconFor(name),
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(
                                    text = label,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (name == QUICK_REPLY_NAME) {
                                    IconButton(onClick = { showQuickReplyConfig(context) }) {
                                        Icon(
                                            MaterialSymbols.Outlined.Settings,
                                            contentDescription = stringResource(R.string.chat_toolbar_configure_quick_reply_description),
                                        )
                                    }
                                }
                                Switch(
                                    checked = name in currentEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) {
                                            if (name !in currentEnabled) currentEnabled.add(name)
                                        } else {
                                            currentEnabled.remove(name)
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        itemsOrder = currentOrder.joinToString(",")
                        enabledItems = currentEnabled.toSet()
                        displayModeValue = currentDisplayMode.preferenceValue
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
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
                title = { Text(stringResource(R.string.chat_toolbar_quick_reply)) },
                text = {
                    if (replies.isEmpty()) {
                        Text(stringResource(R.string.chat_toolbar_quick_reply_empty_picker))
                    } else {
                        LazyColumn {
                            items(replies) { reply ->
                                ListItem(
                                    modifier = Modifier.clickable {
                                        insertQuickReply(reply)
                                        onDismiss()
                                    },
                                    content = { Text(reply) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_close))
                    }
                }
            )
        }
    }

    private fun showQuickReplyEditor(
        context: Context,
        @StringRes titleRes: Int,
        initialValue: String = "",
        onSave: (String) -> Unit,
    ) {
        showComposeDialog(context) {
            var value by remember { mutableStateOf(initialValue) }

            AlertDialogContent(
                title = { Text(stringResource(titleRes)) },
                text = {
                    TextField(
                        value = value,
                        onValueChange = { value = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.chat_toolbar_reply_placeholder)) },
                        minLines = 3,
                        maxLines = 8,
                    )
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } },
                confirmButton = {
                    Button(
                        onClick = {
                            onSave(value.trim())
                            onDismiss()
                        },
                        enabled = value.isNotBlank(),
                    ) { Text(stringResource(R.string.action_save)) }
                },
            )
        }
    }

    // Shown from the settings button in the quick-reply row.
    @OptIn(ExperimentalFoundationApi::class)
    private fun showQuickReplyConfig(context: Context) {
        showComposeDialog(context) {
            val replies = remember {
                loadQuickReplies().map { QuickReplyDraft(text = it) }.toMutableStateList()
            }

            AlertDialogContent(
                modifier = Modifier.fillMaxWidth(),
                title = { Text(stringResource(R.string.chat_toolbar_quick_reply)) },
                text = {
                    DefaultColumn {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.chat_toolbar_reply_contents), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.chat_toolbar_reply_reorder_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    showQuickReplyEditor(context, R.string.chat_toolbar_add_quick_reply) { text ->
                                        replies.add(QuickReplyDraft(text = text))
                                    }
                                }
                            ) {
                                Icon(MaterialSymbols.Outlined.Add, contentDescription = null)
                                Text(stringResource(R.string.action_add))
                            }
                        }

                        if (replies.isEmpty()) {
                            Text(
                                stringResource(R.string.chat_toolbar_quick_reply_empty_config),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 28.dp),
                            )
                        } else {
                            ReorderableList(
                                items = replies,
                                itemKey = { it.id },
                                onMove = { from, to ->
                                    replies.add(to, replies.removeAt(from))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp),
                            ) { reply, dragHandleModifier ->
                                val editReply = {
                                    showQuickReplyEditor(
                                        context = context,
                                        titleRes = R.string.chat_toolbar_edit_quick_reply,
                                        initialValue = reply.text,
                                    ) { text ->
                                        val index = replies.indexOfFirst { it.id == reply.id }
                                        if (index >= 0) replies[index] = reply.copy(text = text)
                                    }
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 60.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .then(dragHandleModifier),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            MaterialSymbols.Outlined.Drag_handle,
                                            contentDescription = stringResource(R.string.chat_toolbar_drag_quick_reply_description),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(
                                        text = reply.text,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable(onClick = editReply)
                                            .padding(horizontal = 8.dp, vertical = 12.dp),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(onClick = editReply) {
                                        Icon(
                                            MaterialSymbols.Outlined.Edit,
                                            contentDescription = stringResource(R.string.chat_toolbar_edit_quick_reply_description),
                                        )
                                    }
                                    IconButton(onClick = { replies.removeAll { it.id == reply.id } }) {
                                        Icon(
                                            MaterialSymbols.Outlined.Delete,
                                            contentDescription = stringResource(R.string.chat_toolbar_delete_quick_reply_description),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = {
                        saveQuickReplies(replies.map { it.text.trim() }.filter { it.isNotEmpty() })
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.dialog_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            )
        }
    }
}

@Composable
private fun FeatureChip(
    text: String,
    icon: ImageVector,
    displayMode: ToolbarDisplayMode,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        label = {
            when (displayMode) {
                ToolbarDisplayMode.ICON_ONLY -> Icon(
                    icon,
                    contentDescription = text,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                    tint = MaterialTheme.colorScheme.primary,
                )

                ToolbarDisplayMode.ICON_AND_TEXT,
                ToolbarDisplayMode.TEXT_ONLY -> Text(text)
            }
        },
        leadingIcon = if (displayMode == ToolbarDisplayMode.ICON_AND_TEXT) ({
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        }) else null,
    )
}
