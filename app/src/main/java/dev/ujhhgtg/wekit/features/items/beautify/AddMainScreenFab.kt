package dev.ujhhgtg.wekit.features.items.beautify

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Delete
import com.composables.icons.materialsymbols.outlined.Drag_handle
import com.composables.icons.materialsymbols.outlinedfilled.Add
import com.composables.icons.materialsymbols.outlinedfilled.Bookmark
import com.composables.icons.materialsymbols.outlinedfilled.Camera
import com.composables.icons.materialsymbols.outlinedfilled.Cancel
import com.composables.icons.materialsymbols.outlinedfilled.Check
import com.composables.icons.materialsymbols.outlinedfilled.Check_circle
import com.composables.icons.materialsymbols.outlinedfilled.Close
import com.composables.icons.materialsymbols.outlinedfilled.Drag_pan
import com.composables.icons.materialsymbols.outlinedfilled.Extension
import com.composables.icons.materialsymbols.outlinedfilled.Favorite
import com.composables.icons.materialsymbols.outlinedfilled.Movie
import com.composables.icons.materialsymbols.outlinedfilled.Qr_code_scanner
import com.composables.icons.materialsymbols.outlinedfilled.Restart_alt
import com.composables.icons.materialsymbols.outlinedfilled.Settings
import com.composables.icons.materialsymbols.outlinedfilled.Update
import com.composables.icons.materialsymbols.outlinedfilled.Wallet
import com.tencent.mm.ui.LauncherUI
import com.tencent.mm.ui.conversation.BaseConversationUI
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.activity.settings.SettingsActivity
import dev.ujhhgtg.wekit.features.api.core.WeConversationApi
import dev.ujhhgtg.wekit.features.api.ui.WeMainActivityBeautifyApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.IconButton
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.theme.InjectedUiTheme
import dev.ujhhgtg.wekit.ui.utils.LifecycleOwnerProvider
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.ui.utils.setLifecycleOwner
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.killHost
import dev.ujhhgtg.wekit.utils.restartHost
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@Feature(name = "主屏幕添加 FAB", categories = ["界面美化"], description = "向微信主屏幕添加浮动操作按钮")
object AddMainScreenFab : ClickableFeature() {

    private const val TAG = "AddMainScreenFab"
    private const val KEY_FAB_CONFIG = "fab_button_configs_json"
    private const val KEY_FAB_OFFSET_X = "fab_offset_x_dp"
    private const val KEY_FAB_OFFSET_Y = "fab_offset_y_dp"

    private val FAB_SIZE = 56.dp

    /** FAB 与屏幕右下角默认锚点之间的间距 */
    private val EDGE_PADDING = 16.dp

    /** 默认锚点上移的距离，用于避开微信底部标签栏 */
    private val TAB_BAR_INSET = 60.dp

    /** 拖动时 FAB 与屏幕边缘之间至少保留的距离 */
    private val MIN_SCREEN_MARGIN = 8.dp

    /** 展开的菜单与主 FAB 之间的距离 */
    private val MENU_GAP = 16.dp

    private val SAVE_GREEN = Color(0xFF07C160)

    private var expanded by mutableStateOf(false)

    /** 位置编辑模式。设置界面通过 [ActivityProxy][dev.ujhhgtg.wekit.loader.utils.n] 运行在微信进程内，因此静态标记即可跨界面共享 */
    private var editMode by mutableStateOf(false)

    /** 相对默认锚点的偏移，单位 dp；负值表示向左 / 向上 */
    private var offsetXDp by mutableFloatStateOf(0f)
    private var offsetYDp by mutableFloatStateOf(0f)

    /** 进入编辑模式时的位置，用于「取消」还原 */
    private var offsetBeforeEdit = 0f to 0f

    private class FabMenuEntry(
        val name: String,
        val icon: ImageVector,
        val destructive: Boolean = false,
        val onClick: () -> Unit,
    )

    @Serializable
    enum class FabType {
        START_ACTIVITY,
        MARK_ALL_READ,
        MODULE_SETTINGS,
        RESTART_HOST,
        FORCE_STOP
    }

    @Serializable
    data class FabItemConfig(
        val id: String,
        val type: FabType,
        val name: String,
        val iconName: String,
        val targetActivity: String? = null
    )

    // 可选图标池映射
    private val iconPool by lazy {
        mapOf(
            "Qr_code_scanner" to MaterialSymbols.OutlinedFilled.Qr_code_scanner,
            "Camera" to MaterialSymbols.OutlinedFilled.Camera,
            "Wallet" to MaterialSymbols.OutlinedFilled.Wallet,
            "Movie" to MaterialSymbols.OutlinedFilled.Movie,
            "Settings" to MaterialSymbols.OutlinedFilled.Settings,
            "Extension" to MaterialSymbols.OutlinedFilled.Extension,
            "Cancel" to MaterialSymbols.OutlinedFilled.Cancel,
            "Update" to MaterialSymbols.OutlinedFilled.Update,
            "Bookmark" to MaterialSymbols.OutlinedFilled.Bookmark,
            "Favorite" to MaterialSymbols.OutlinedFilled.Favorite,
            "Check_circle" to MaterialSymbols.OutlinedFilled.Check_circle
        )
    }

    // 预设 Activity 映射
    private val presets = mapOf(
        "扫一扫" to "com.tencent.mm.plugin.scanner.ui.BaseScanUI",
        "朋友圈" to "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI",
        "钱包" to "com.tencent.mm.plugin.mall.ui.MallIndexUIv2",
        "视频号" to "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI",
        "设置" to "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI",
        "收藏夹" to "com.tencent.mm.plugin.fav.ui.FavoriteIndexUI"
    )

    // 默认配置列表
    private val defaultList = listOf(
        FabItemConfig("1", FabType.START_ACTIVITY, "扫一扫", "Qr_code_scanner", "com.tencent.mm.plugin.scanner.ui.BaseScanUI"),
        FabItemConfig("2", FabType.START_ACTIVITY, "朋友圈", "Camera", "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"),
        FabItemConfig("3", FabType.START_ACTIVITY, "钱包", "Wallet", "com.tencent.mm.plugin.mall.ui.MallIndexUIv2"),
        FabItemConfig("4", FabType.START_ACTIVITY, "视频号", "Movie", "com.tencent.mm.plugin.finder.ui.FinderHomeAffinityUI"),
        FabItemConfig("5", FabType.START_ACTIVITY, "设置", "Settings", "com.tencent.mm.plugin.setting.ui.setting_new.MainSettingsUI"),
        FabItemConfig("6", FabType.MODULE_SETTINGS, "模块设置", "Extension"),
        FabItemConfig("9", FabType.RESTART_HOST, "重启微信", "Update"),
        FabItemConfig("7", FabType.FORCE_STOP, "强行停止", "Cancel"),
        FabItemConfig("8", FabType.MARK_ALL_READ, "清空未读", "Check_circle")
    )

    private fun loadConfig(): List<FabItemConfig> {
        val jsonStr = WePrefs.getString(KEY_FAB_CONFIG) ?: return defaultList
        return try {
            Json.decodeFromString<List<FabItemConfig>>(jsonStr)
        } catch (e: Exception) {
            WeLogger.e(TAG, "解析依赖失败，还原默认配置", e)
            defaultList
        }
    }

    private fun saveConfig(list: List<FabItemConfig>) {
        try {
            val jsonStr = Json.encodeToString(list)
            WePrefs.putString(KEY_FAB_CONFIG, jsonStr)
        } catch (e: Exception) {
            WeLogger.e(TAG, "保存配置失败", e)
        }
    }

    /** 估算展开后的菜单高度：每项一个 40dp 的小 FAB，项间距 12dp，再加上与主 FAB 之间的 16dp */
    private fun menuHeightOf(itemCount: Int): Dp =
        if (itemCount <= 0) 0.dp else 40.dp * itemCount + 12.dp * (itemCount - 1) + 16.dp

    private fun loadOffset() {
        offsetXDp = WePrefs.getFloatOrDef(KEY_FAB_OFFSET_X, 0f)
        offsetYDp = WePrefs.getFloatOrDef(KEY_FAB_OFFSET_Y, 0f)
    }

    /**
     * 关闭设置界面并回到微信主界面，同时进入位置编辑模式。
     *
     * [CLEAR_TOP][Intent.FLAG_ACTIVITY_CLEAR_TOP] 会结束 LauncherUI 之上的所有界面，
     * 因此无论设置界面是从主界面还是从微信设置里打开的，都能直接回到主界面。
     */
    private fun enterEditMode(activity: Activity) {
        loadOffset()
        offsetBeforeEdit = offsetXDp to offsetYDp
        editMode = true
        expanded = true

        runCatching {
            activity.startActivity(Intent().apply {
                setClassName(HostInfo.packageName, "com.tencent.mm.ui.LauncherUI")
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            })
        }.onFailure { WeLogger.e(TAG, "无法返回微信主界面", it) }

        activity.finish()
        showToast("拖动主按钮调整位置, 点击主按钮保存")
    }

    private fun exitEditMode(save: Boolean) {
        if (save) {
            WePrefs.putFloat(KEY_FAB_OFFSET_X, offsetXDp)
            WePrefs.putFloat(KEY_FAB_OFFSET_Y, offsetYDp)
            showToast("已保存 FAB 位置")
        } else {
            offsetXDp = offsetBeforeEdit.first
            offsetYDp = offsetBeforeEdit.second
        }
        editMode = false
        expanded = false
    }

    private fun startActivityByName(context: Context, className: String) {
        val intent = Intent().apply {
            setClassName(context.packageName, className)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject!!.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity

            // 编辑过程中被重建（例如旋转屏幕）时不要覆盖尚未保存的位置
            if (!editMode) loadOffset()

            // 动态解析已经保存的配置生成菜单项目
            val configList = loadConfig()

            val menuItems = configList.map { item ->
                val icon = iconPool[item.iconName] ?: MaterialSymbols.OutlinedFilled.Add
                val action: () -> Unit = when (item.type) {
                    FabType.START_ACTIVITY -> {
                        { item.targetActivity?.let { startActivityByName(activity, it) } }
                    }

                    FabType.MARK_ALL_READ -> {
                        {
                            WeConversationApi.markAllAsRead()
                            showToast("已将全部未读消息标为已读")
                        }
                    }

                    FabType.MODULE_SETTINGS -> {
                        {
                            activity.startActivity(Intent(activity, SettingsActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        }
                    }

                    FabType.RESTART_HOST -> {
                        { restartHost() }
                    }

                    FabType.FORCE_STOP -> {
                        { killHost() }
                    }
                }
                FabMenuEntry(item.name, icon, onClick = action)
            }

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
            val root = activity.rootView

            root.addView(
                ComposeView(activity).apply {
                    setLifecycleOwner(lifecycleOwner)

                    setContent {
                        InjectedUiTheme {
                            val backgroundColor = if (isSystemInDarkTheme()) Color(0xFF191919) else Color(0xFFF7F7F7)
                            val activeColor = MaterialTheme.colorScheme.primary
                            val errorColor = MaterialTheme.colorScheme.error
                            val layoutDirection = LocalLayoutDirection.current

                            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                val insets = WindowInsets.safeDrawing.asPaddingValues()
                                val marginStart = insets.calculateStartPadding(layoutDirection).coerceAtLeast(MIN_SCREEN_MARGIN)
                                val marginEnd = insets.calculateEndPadding(layoutDirection).coerceAtLeast(MIN_SCREEN_MARGIN)
                                val marginTop = insets.calculateTopPadding().coerceAtLeast(MIN_SCREEN_MARGIN)
                                val marginBottom = insets.calculateBottomPadding().coerceAtLeast(MIN_SCREEN_MARGIN)

                                // 默认锚点：右下角，上移以避开微信底部标签栏
                                val defaultLeft = maxWidth - EDGE_PADDING - FAB_SIZE
                                val defaultTop = maxHeight - TAB_BAR_INSET - EDGE_PADDING - FAB_SIZE

                                // 允许的偏移范围，保证 FAB 始终完整地留在屏幕内
                                val minDx = (marginStart - defaultLeft).value
                                val maxDx = (maxWidth - FAB_SIZE - marginEnd - defaultLeft).value.coerceAtLeast(minDx)
                                val minDy = (marginTop - defaultTop).value
                                val maxDy = (maxHeight - FAB_SIZE - marginBottom - defaultTop).value.coerceAtLeast(minDy)

                                val fabLeft = defaultLeft + offsetXDp.coerceIn(minDx, maxDx).dp
                                val fabTop = defaultTop + offsetYDp.coerceIn(minDy, maxDy).dp

                                // FAB 靠近哪一侧，菜单就往哪一侧贴
                                val onRight = fabLeft + FAB_SIZE / 2 >= maxWidth / 2

                                val entries = if (editMode) {
                                    listOf(
                                        FabMenuEntry("重置位置", MaterialSymbols.OutlinedFilled.Restart_alt) {
                                            offsetXDp = 0f
                                            offsetYDp = 0f
                                        },
                                        FabMenuEntry("取消", MaterialSymbols.OutlinedFilled.Close, destructive = true) {
                                            exitEditMode(save = false)
                                        },
                                    )
                                } else {
                                    menuItems
                                }

                                // 展开方向始终按真实菜单的高度计算，这样编辑模式下预览到的方向就是最终效果。
                                // 上方放得下就向上展开（默认行为），放不下再考虑向下。
                                val menuHeight = menuHeightOf(maxOf(menuItems.size, entries.size))
                                val roomAbove = fabTop
                                val roomBelow = maxHeight - fabTop - FAB_SIZE
                                val expandDown = when {
                                    menuHeight <= roomAbove -> false
                                    menuHeight <= roomBelow -> true
                                    else -> roomBelow > roomAbove
                                }

                                // 菜单与 FAB 都只有一个固定的调用点，展开方向只改变修饰符参数。
                                // 若改用 if/else 交换两者的顺序，翻转方向会重建 FAB 节点并中断正在进行的拖动。
                                FabMenu(
                                    modifier = Modifier
                                        .align(
                                            when {
                                                expandDown && onRight -> Alignment.TopEnd
                                                expandDown -> Alignment.TopStart
                                                onRight -> Alignment.BottomEnd
                                                else -> Alignment.BottomStart
                                            }
                                        )
                                        // 只固定 FAB 紧贴的那条竖边，避免菜单标签的宽度把菜单推走
                                        .padding(
                                            start = if (onRight) 0.dp else fabLeft,
                                            end = if (onRight) (maxWidth - fabLeft - FAB_SIZE).coerceAtLeast(0.dp) else 0.dp,
                                            top = if (expandDown) (fabTop + FAB_SIZE + MENU_GAP) else 0.dp,
                                            bottom = if (expandDown) 0.dp else (maxHeight - fabTop + MENU_GAP).coerceAtLeast(0.dp),
                                        ),
                                    entries = entries,
                                    visible = expanded || editMode,
                                    onRight = onRight,
                                    expandDown = expandDown,
                                    backgroundColor = backgroundColor,
                                    activeColor = activeColor,
                                    errorColor = errorColor,
                                )

                                MainFab(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset { IntOffset(fabLeft.roundToPx(), fabTop.roundToPx()) },
                                    backgroundColor = backgroundColor,
                                    activeColor = activeColor,
                                    minDx = minDx,
                                    maxDx = maxDx,
                                    minDy = minDy,
                                    maxDy = maxDy,
                                )
                            }
                        }
                    }
                }
            )
        }

        LauncherUI::class.reflekt().firstMethod("startChatting").hookBefore {
            if (!editMode) expanded = false
        }

        BaseConversationUI::class.reflekt().firstMethod("startChatting").hookBefore {
            if (!editMode) expanded = false
        }
    }

    override fun onDisable() {
        super.onDisable()
        editMode = false
        expanded = false
    }

    @Composable
    private fun FabMenu(
        modifier: Modifier,
        entries: List<FabMenuEntry>,
        visible: Boolean,
        onRight: Boolean,
        expandDown: Boolean,
        backgroundColor: Color,
        activeColor: Color,
        errorColor: Color,
    ) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = if (onRight) Alignment.End else Alignment.Start
        ) {
            entries.forEachIndexed { index, entry ->
                // 靠近 FAB 的项先出现、最后消失；向下展开时列表顺序相对 FAB 是反的
                val nearDelay = if (expandDown) index * 35 else (entries.size - 1 - index) * 35
                val farDelay = if (expandDown) (entries.size - 1 - index) * 35 else index * 35
                val slideOffset: (Int) -> Int = if (expandDown) ({ -it / 2 }) else ({ it / 2 })
                val tint = if (entry.destructive) errorColor else activeColor

                AnimatedVisibility(
                    visible = visible,
                    enter = fadeIn(
                        animationSpec = tween(durationMillis = 160, delayMillis = nearDelay, easing = EaseOut)
                    ) + slideInVertically(
                        animationSpec = tween(durationMillis = 180, delayMillis = nearDelay, easing = EaseOutCubic),
                        initialOffsetY = slideOffset
                    ),
                    exit = fadeOut(
                        animationSpec = tween(durationMillis = 100, delayMillis = farDelay, easing = EaseIn)
                    ) + slideOutVertically(
                        animationSpec = tween(durationMillis = 100, delayMillis = farDelay, easing = EaseInCubic),
                        targetOffsetY = slideOffset
                    )
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // FAB 靠左时标签放到按钮右边，否则会被挤出屏幕
                        if (onRight) {
                            FabMenuLabel(entry.name, backgroundColor, tint)
                            FabMenuButton(entry, backgroundColor, tint)
                        } else {
                            FabMenuButton(entry, backgroundColor, tint)
                            FabMenuLabel(entry.name, backgroundColor, tint)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun FabMenuLabel(name: String, backgroundColor: Color, tint: Color) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = backgroundColor,
            tonalElevation = 2.dp,
            shadowElevation = 2.dp
        ) {
            Text(
                text = name,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                color = tint,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }

    @Composable
    private fun FabMenuButton(entry: FabMenuEntry, backgroundColor: Color, tint: Color) {
        SmallFloatingActionButton(
            onClick = {
                entry.onClick()
                // 编辑模式下的菜单项（例如「重置位置」）不应收起菜单
                if (!editMode) expanded = false
            },
            containerColor = backgroundColor,
            shape = CircleShape,
            elevation = FloatingActionButtonDefaults.elevation(2.dp)
        ) {
            Icon(entry.icon, contentDescription = entry.name, tint = tint)
        }
    }

    @Composable
    private fun MainFab(
        modifier: Modifier,
        backgroundColor: Color,
        activeColor: Color,
        minDx: Float,
        maxDx: Float,
        minDy: Float,
        maxDy: Float,
    ) {
        val editing = editMode

        FloatingActionButton(
            onClick = { if (editing) exitEditMode(save = true) else expanded = !expanded },
            containerColor = backgroundColor,
            shape = CircleShape,
            // 拖动会消费触摸事件并取消点击，因此拖动与点击保存可以共存
            modifier = modifier.then(
                if (editing) {
                    Modifier.pointerInput(minDx, maxDx, minDy, maxDy) {
                        detectDragGestures { change, amount ->
                            change.consume()
                            offsetXDp = (offsetXDp + amount.x.toDp().value).coerceIn(minDx, maxDx)
                            offsetYDp = (offsetYDp + amount.y.toDp().value).coerceIn(minDy, maxDy)
                        }
                    }
                } else {
                    Modifier
                }
            )
        ) {
            if (editing) {
                Icon(
                    MaterialSymbols.OutlinedFilled.Check,
                    contentDescription = "保存位置",
                    tint = SAVE_GREEN
                )
            } else {
                val rotation by animateFloatAsState(if (expanded) 45f else 0f)
                Icon(
                    MaterialSymbols.OutlinedFilled.Add,
                    contentDescription = null,
                    tint = activeColor,
                    modifier = Modifier.rotate(rotation)
                )
            }
        }
    }

    private fun showAddFabDialog(
        context: Context,
        existingItems: List<FabItemConfig>,
        onAdd: (FabItemConfig) -> Unit,
    ) {
        showComposeDialog(context) {
            var newType by remember { mutableStateOf(FabType.START_ACTIVITY) }
            var newName by remember { mutableStateOf("") }
            var newActivity by remember { mutableStateOf("") }
            var newIconName by remember { mutableStateOf("Qr_code_scanner") }

            val hasType = { type: FabType -> existingItems.any { it.type == type } }
            val canAdd = newName.isNotBlank() &&
                    (newType != FabType.START_ACTIVITY || newActivity.isNotBlank())

            AlertDialogContent(
                title = { Text("添加快捷按钮") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "选择要放到主屏幕 FAB 菜单里的功能。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "功能类型",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp)
                        )

                        val typeOptions = listOf(
                            FabType.START_ACTIVITY to "启动 Activity",
                            FabType.MARK_ALL_READ to "清空未读",
                            FabType.MODULE_SETTINGS to "模块设置",
                            FabType.RESTART_HOST to "重启微信",
                            FabType.FORCE_STOP to "强行停止",
                        )
                        typeOptions.forEach { (type, label) ->
                            val unavailable = type != FabType.START_ACTIVITY && hasType(type)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !unavailable) { newType = type },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = newType == type,
                                    onClick = { newType = type },
                                    enabled = !unavailable,
                                )
                                Text(
                                    text = if (unavailable) "$label（已添加）" else label,
                                    color = if (unavailable) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
                                )
                            }
                        }

                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text("按钮名称") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )

                        if (newType == FabType.START_ACTIVITY) {
                            OutlinedTextField(
                                value = newActivity,
                                onValueChange = { newActivity = it },
                                label = { Text("Activity 完整类名") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            )
                            Text(
                                "预设入口",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                            presets.forEach { (presetName, presetClass) ->
                                Text(
                                    text = "$presetName  ·  $presetClass",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            newActivity = presetClass
                                            if (newName.isBlank()) newName = presetName
                                        }
                                        .padding(vertical = 6.dp),
                                )
                            }
                        }

                        Text(
                            "图标",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            iconPool.forEach { (iconName, icon) ->
                                val selected = newIconName == iconName
                                Surface(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clickable { newIconName = iconName },
                                    shape = CircleShape,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    tonalElevation = if (selected) 2.dp else 0.dp,
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = iconName,
                                            tint = if (selected) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (!canAdd) return@TextButton
                            onAdd(
                                FabItemConfig(
                                    id = UUID.randomUUID().toString(),
                                    type = newType,
                                    name = newName.trim(),
                                    iconName = newIconName,
                                    targetActivity = if (newType == FabType.START_ACTIVITY) newActivity.trim() else null,
                                )
                            )
                            onDismiss()
                        },
                        enabled = canAdd,
                    ) { Text("添加") }
                },
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var currentItems by remember { mutableStateOf(loadConfig()) }
            var draggingIndex by remember { mutableStateOf<Int?>(null) }
            var dragOffset by remember { mutableFloatStateOf(0f) }
            val listState = rememberLazyListState()
            val coroutineScope = rememberCoroutineScope()

            fun moveItem(from: Int, to: Int) {
                if (from == to || from !in currentItems.indices || to !in currentItems.indices) return
                val updated = currentItems.toMutableList().apply {
                    add(to, removeAt(from))
                }
                currentItems = updated
                saveConfig(updated)
            }

            AlertDialogContent(
                title = { Text("FAB 悬浮按钮") },
                text = {
                    DefaultColumn {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("主屏幕快捷入口", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "长按拖动手柄调整顺序，改动会立即生效",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(
                                onClick = {
                                    showAddFabDialog(context, currentItems) { newItem ->
                                        val updated = currentItems + newItem
                                        currentItems = updated
                                        saveConfig(updated)
                                    }
                                }
                            ) {
                                Icon(MaterialSymbols.OutlinedFilled.Add, contentDescription = null)
                                Text("添加")
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .clickable {
                                    onDismiss()
                                    enterEditMode(context)
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.OutlinedFilled.Drag_pan,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 12.dp)
                            ) {
                                Text("调整位置", fontWeight = FontWeight.Medium)
                                Text(
                                    "回到微信主界面拖动按钮，点击绿色对勾保存",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        if (currentItems.isEmpty()) {
                            Text(
                                "还没有快捷入口，点击右上角“添加”开始配置。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 28.dp),
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 420.dp)
                                    .padding(top = 8.dp),
                                userScrollEnabled = draggingIndex == null,
                            ) {
                                itemsIndexed(
                                    items = currentItems,
                                    key = { _, item -> item.id },
                                ) { index, item ->
                                    val isDragging = index == draggingIndex
                                    val description = when (item.type) {
                                        FabType.START_ACTIVITY -> item.targetActivity ?: "启动 Activity"
                                        FabType.MARK_ALL_READ -> "将全部未读消息标记为已读"
                                        FabType.MODULE_SETTINGS -> "打开模块设置"
                                        FabType.RESTART_HOST -> "重新启动微信进程"
                                        FabType.FORCE_STOP -> "终止微信进程"
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .zIndex(if (isDragging) 1f else 0f)
                                            .graphicsLayer {
                                                translationY = if (isDragging) dragOffset else 0f
                                                scaleX = if (isDragging) 1.02f else 1f
                                                scaleY = if (isDragging) 1.02f else 1f
                                                shadowElevation = if (isDragging) 8.dp.toPx() else 0f
                                            }
                                            .then(if (isDragging) Modifier else Modifier.animateItem())
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .pointerInput(item.id) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = {
                                                            draggingIndex = listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.key == item.id }
                                                                ?.index
                                                                ?: index
                                                            dragOffset = 0f
                                                        },
                                                        onDragCancel = {
                                                            draggingIndex = null
                                                            dragOffset = 0f
                                                        },
                                                        onDragEnd = {
                                                            draggingIndex = null
                                                            dragOffset = 0f
                                                        },
                                                        onDrag = { change, amount ->
                                                            change.consume()
                                                            val currentIndex = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                                            dragOffset += amount.y
                                                            val currentInfo = listState.layoutInfo.visibleItemsInfo
                                                                .firstOrNull { it.index == currentIndex }
                                                                ?: return@detectDragGesturesAfterLongPress
                                                            val start = currentInfo.offset + dragOffset
                                                            val end = start + currentInfo.size
                                                            val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { targetInfo ->
                                                                if (targetInfo.index == currentIndex) {
                                                                    false
                                                                } else if (dragOffset > 0f) {
                                                                    targetInfo.index > currentIndex &&
                                                                            end > targetInfo.offset + targetInfo.size / 2
                                                                } else {
                                                                    targetInfo.index < currentIndex &&
                                                                            start < targetInfo.offset + targetInfo.size / 2
                                                                }
                                                            }
                                                            if (target != null) {
                                                                moveItem(currentIndex, target.index)
                                                                dragOffset -= target.offset - currentInfo.offset
                                                                draggingIndex = target.index
                                                            }

                                                            val viewport = listState.layoutInfo
                                                            val center = currentInfo.offset + dragOffset + currentInfo.size / 2
                                                            when {
                                                                center < viewport.viewportStartOffset + 56 && listState.canScrollBackward ->
                                                                    coroutineScope.launch { listState.scrollBy(-12f) }

                                                                center > viewport.viewportEndOffset - 56 && listState.canScrollForward ->
                                                                    coroutineScope.launch { listState.scrollBy(12f) }
                                                            }
                                                        },
                                                    )
                                                },
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Drag_handle,
                                                contentDescription = "拖动以调整顺序",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Icon(
                                            imageVector = iconPool[item.iconName] ?: MaterialSymbols.OutlinedFilled.Add,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier
                                                .size(28.dp)
                                                .padding(end = 4.dp),
                                        )
                                        Column(modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 8.dp)) {
                                            Text(item.name, fontWeight = FontWeight.Medium)
                                            Text(
                                                description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                val updated = currentItems.filterNot { it.id == item.id }
                                                currentItems = updated
                                                saveConfig(updated)
                                            },
                                        ) {
                                            Icon(
                                                imageVector = MaterialSymbols.Outlined.Delete,
                                                contentDescription = "删除 ${item.name}",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onDismiss) { Text("完成") } },
            )
        }
    }
}
