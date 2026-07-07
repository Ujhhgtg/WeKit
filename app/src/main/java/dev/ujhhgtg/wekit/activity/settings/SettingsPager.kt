package dev.ujhhgtg.wekit.activity.settings


import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Auto_delete
import com.composables.icons.materialsymbols.outlined.Block
import com.composables.icons.materialsymbols.outlined.Brightness_medium
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Colorize
import com.composables.icons.materialsymbols.outlined.Contrast
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Frame_bug
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.License
import com.composables.icons.materialsymbols.outlined.Lightbulb_2
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Palette
import com.composables.icons.materialsymbols.outlined.Rule_settings
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Style
import com.composables.icons.materialsymbols.outlined.Sync
import com.composables.icons.materialsymbols.outlined.Update
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Volunteer_activism
import com.composables.icons.materialsymbols.outlined.Wallpaper
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.aboutlibraries.AboutLibrariesProvider
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.features.items.debug.ResetDexCache
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.MiuixSmallTitle
import dev.ujhhgtg.wekit.ui.utils.GitHubIcon
import dev.ujhhgtg.wekit.ui.utils.TelegramIcon
import dev.ujhhgtg.wekit.ui.utils.theme.AppColorSpec
import dev.ujhhgtg.wekit.ui.utils.theme.AppPaletteStyle
import dev.ujhhgtg.wekit.ui.utils.theme.AppThemeMode
import dev.ujhhgtg.wekit.ui.utils.theme.ThemeSettings
import dev.ujhhgtg.wekit.utils.AppUpdater
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.UpdateResult
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.openInSystem
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

// ---------------------------------------------------------------------------
//  Page 2 — Settings
// ---------------------------------------------------------------------------

@Composable
fun SettingsPager(onOpenLicense: () -> Unit) {
    val context = LocalComponentActivity.current

    var showClearConfirm by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateResult.UpdateAvailable?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }

    ClearConfigDialog(show = showClearConfirm, onDismiss = { showClearConfirm = false })
    UpdateAvailableDialog(info = updateInfo, onDismiss = { updateInfo = null }, context = context)
    UpdateErrorDialog(message = updateError, onDismiss = { updateError = null })

    MiuixListScaffold(title = "设置") {
        // 界面
        item {
            MiuixSmallTitle(text = "界面", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                ThemeSection()
            }
        }

        // 调试
        item {
            MiuixSmallTitle(text = "调试", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefSwitch(
                    key = Preferences.VERBOSE_LOG,
                    title = "详细日志",
                    summary = "输出高频日志 (这可能会暴露你的隐私信息）",
                    icon = MaterialSymbols.Outlined.Frame_bug,
                )
                PrefSwitch(
                    key = Preferences.SHOW_STARTUP_TOAST,
                    title = "显示加载完成 Toast",
                    summary = "全部功能加载完成后显示 Toast 提示",
                    icon = MaterialSymbols.Outlined.Notifications,
                )
                PrefSwitch(
                    key = Preferences.MATCH_GENERIC_WXID_EXP,
                    title = "清理消息内容微信 ID 前缀时允许非标准 ID",
                    summary = "允许处理不带 'wxid_' 前缀的微信 ID, 可能导致误伤消息原始内容 (实验性)",
                    icon = MaterialSymbols.Outlined.Rule_settings,
                )
            }
        }

        // 兼容
        item {
            MiuixSmallTitle(text = "兼容", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefSwitch(
                    key = Preferences.NO_DEX_RESOLVE,
                    title = "禁用版本适配",
                    summary = "不弹出 DEX 查找对话框，未适配功能将不会被加载",
                    icon = MaterialSymbols.Outlined.Block,
                )
                PrefArrow(
                    title = "重置适配信息",
                    summary = "清除 DEX 缓存, 等待下次启动时重新适配",
                    icon = MaterialSymbols.Outlined.Build_circle,
                    onClick = { ResetDexCache.onClick(context) },
                )
                PrefSwitch(
                    key = Preferences.RESET_DEX_ON_HOT_UPDATE,
                    title = "宿主热更新时重新适配",
                    summary = "宿主热更新时是否重置 DEX 缓存, 可能导致频繁重新适配 (实验性)",
                    icon = MaterialSymbols.Outlined.Auto_delete,
                )
            }
        }

        // 配置
        item {
            MiuixSmallTitle(text = "配置", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(
                    title = "导出配置",
                    summary = "将模块配置导出为 JSON",
                    icon = MaterialSymbols.Outlined.Upload,
                    onClick = { exportConfig(context) },
                )
                PrefArrow(
                    title = "导入配置",
                    summary = "从 JSON 导入模块配置; JSON 中的配置将会与现有配置合并, 覆盖所有已存在的配置",
                    icon = MaterialSymbols.Outlined.Download,
                    onClick = { importConfig(context) },
                )
                PrefArrow(
                    title = "清除配置",
                    summary = "清除全部模块配置 (警告: 此操作不可逆!)",
                    icon = MaterialSymbols.Outlined.Delete_forever,
                    onClick = { showClearConfirm = true },
                )
            }
        }

        // 更新
        item {
            MiuixSmallTitle(text = "更新", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(
                    title = "检查更新",
                    summary = "立即检查模块是否有新版本并自动下载",
                    icon = MaterialSymbols.Outlined.Update,
                    onClick = {
                        checkForUpdate(
                            onAvailable = { updateInfo = it },
                            onError = { updateError = it },
                        )
                    },
                )
            }
        }

        // 关于
        item {
            MiuixSmallTitle(text = "关于", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(title = "版本", summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", icon = MaterialSymbols.Outlined.Label)
                PrefArrow(title = "构建提交时间", summary = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true), icon = MaterialSymbols.Outlined.Build_circle)
                PrefArrow(
                    title = "提示",
                    summary = "牙膏要一点一点挤, 显卡要一刀一刀切, PPT 要一张一张放, 代码要一行一行写, 单个功能预计自出现在 commit 之日起, 三年内开发完毕",
                    icon = MaterialSymbols.Outlined.Lightbulb_2,
                )
                PrefArrow(
                    title = "捐赠",
                    summary = "支持项目开发 (模块完全开源免费, 捐赠无特权)",
                    icon = MaterialSymbols.Outlined.Volunteer_activism,
                    onClick = {
                        context.startActivity(Intent().apply {
                            setClassName(HostInfo.packageName, "${PackageNames.WECHAT}.plugin.collect.reward.ui.QrRewardSelectMoneyUI")
                            putExtra("key_qrcode_url", "m0n#Z7LGW*s4AVH!z'd(?)")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    },
                )
                PrefArrow(
                    title = "开放源代码许可",
                    summary = "本项目使用的开放源代码库许可",
                    icon = MaterialSymbols.Outlined.License,
                    onClick = onOpenLicense,
                )
                PrefArrow(
                    title = "GitHub",
                    summary = "Ujhhgtg/WeKit",
                    icon = GitHubIcon,
                    onClick = { "https://github.com/Ujhhgtg/WeKit".toUri().openInSystem(context, true) })
                PrefArrow(
                    title = "Telegram",
                    summary = "Telegram 超级群组",
                    icon = TelegramIcon,
                    onClick = { "https://t.me/+7j5dJ6g16B43OWVl".toUri().openInSystem(context, true) })
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}
// ---------------------------------------------------------------------------
//  界面 (theme) settings — drives ThemeSettings, which re-themes the UI live
// ---------------------------------------------------------------------------

/** A miuix dropdown bound to an enum's entries. */
@Composable
private fun <T> EnumDropdown(
    title: String,
    entries: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelected: (T) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    WindowDropdownPreference(
        title = title,
        summary = summary,
        items = entries.map(labelOf),
        selectedIndex = entries.indexOf(selected).coerceAtLeast(0),
        enabled = enabled,
        startAction = icon?.let { { PrefIcon(it) } },
        onSelectedIndexChange = { onSelected(entries[it]) },
    )
}

@Composable
private fun ThemeSection() {
    EnumDropdown(
        title = "主题模式",
        entries = AppThemeMode.entries,
        selected = ThemeSettings.themeMode,
        labelOf = { it.displayName },
        onSelected = { ThemeSettings.updateThemeMode(it) },
        icon = MaterialSymbols.Outlined.Brightness_medium,
    )

    var customColor by remember { mutableStateOf(ThemeSettings.customColor) }
    SwitchPreference(
        title = "自定义颜色",
        summary = "使用调色板样式生成配色, 而非 Miuix 默认蓝",
        startAction = { PrefIcon(MaterialSymbols.Outlined.Palette) },
        checked = customColor,
        onCheckedChange = {
            customColor = it
            ThemeSettings.updateCustomColor(it)
        },
    )

    var showColorPicker by remember { mutableStateOf(false) }
    SeedColorPickerDialog(show = showColorPicker, onDismiss = { showColorPicker = false })

    AnimatedVisibility(visible = customColor) {
        Column {
            var dynamicWallpaper by remember { mutableStateOf(ThemeSettings.dynamicWallpaper) }
            SwitchPreference(
                title = "动态壁纸取色",
                summary = "使用系统壁纸的强调色作为种子\n需系统 Android SDK >= 31",
                startAction = { PrefIcon(MaterialSymbols.Outlined.Wallpaper) },
                checked = dynamicWallpaper,
                onCheckedChange = {
                    dynamicWallpaper = it
                    ThemeSettings.updateDynamicWallpaper(it)
                },
            )
            AnimatedVisibility(visible = !dynamicWallpaper) {
                BasicComponent(
                    title = "种子颜色",
                    summary = "点击选择配色的种子颜色",
                    startAction = { PrefIcon(MaterialSymbols.Outlined.Colorize) },
                    onClick = { showColorPicker = true },
                    endActions = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(ThemeSettings.seedColor)),
                        )
                    },
                )
            }
            EnumDropdown(
                title = "调色板样式",
                entries = AppPaletteStyle.entries,
                selected = ThemeSettings.paletteStyle,
                labelOf = { it.displayName },
                onSelected = {
                    ThemeSettings.updatePaletteStyle(it)
                    // Keep the stored spec valid for the new style.
                    if (!it.supportsSpec2025 && ThemeSettings.colorSpec == AppColorSpec.SPEC_2025) {
                        ThemeSettings.updateColorSpec(AppColorSpec.SPEC_2021)
                    }
                },
                icon = MaterialSymbols.Outlined.Style,
            )
            val spec2025Supported = ThemeSettings.paletteStyle.supportsSpec2025
            EnumDropdown(
                title = "颜色规格",
                entries = if (spec2025Supported) AppColorSpec.entries else listOf(AppColorSpec.SPEC_2021),
                selected = ThemeSettings.effectiveColorSpec,
                labelOf = { it.displayName },
                onSelected = { ThemeSettings.updateColorSpec(it) },
                enabled = spec2025Supported,
                summary = if (!spec2025Supported) "当前调色板样式仅支持 Material 3 (2021)" else null,
                icon = MaterialSymbols.Outlined.Contrast,
            )

            var applyToWechat by remember { mutableStateOf(ThemeSettings.applyToWechat) }
            SwitchPreference(
                title = "同时对微信生效",
                summary = "将自定义配色应用到微信本身",
                startAction = { PrefIcon(MaterialSymbols.Outlined.Sync) },
                checked = applyToWechat,
                onCheckedChange = {
                    applyToWechat = it
                    ThemeSettings.updateApplyToWechat(it)
                    CoroutineScope(Dispatchers.Main).launch { showToastSuspend("重启微信生效") }
                },
            )
        }
    }
}

/** miuix color-picker dialog for the custom seed color; commits to ThemeSettings on confirm. */
@Composable
private fun SeedColorPickerDialog(show: Boolean, onDismiss: () -> Unit) {
    var picked by remember(show) { mutableStateOf(Color(ThemeSettings.seedColor)) }

    WindowDialog(show = show, title = "自定义颜色", onDismissRequest = onDismiss) {
        Column {
            ColorPicker(
                color = picked,
                onColorChanged = { picked = it },
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = "重置",
                    onClick = { picked = Color(ThemeSettings.DEFAULT_SEED_COLOR) },
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(20.dp))
                TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = "确定",
                    onClick = {
                        ThemeSettings.updateSeedColor(picked.toArgb())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Preference helper composables
// ---------------------------------------------------------------------------

@Composable
private fun PrefSwitch(
    key: String,
    title: String,
    summary: String,
    icon: ImageVector,
) {
    var checked by remember { mutableStateOf(WePrefs.getBoolOrFalse(key)) }
    SwitchPreference(
        title = title,
        summary = summary,
        startAction = { PrefIcon(icon) },
        checked = checked,
        onCheckedChange = {
            checked = it
            WePrefs.putBool(key, it)
        },
    )
}

@Composable
private fun PrefArrow(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
) {
    if (onClick == null) {
        // Informational row: no trailing arrow, no ripple.
        BasicComponent(
            title = title,
            summary = summary,
            startAction = icon?.let { { PrefIcon(it) } },
        )
    } else {
        ArrowPreference(
            title = title,
            summary = summary,
            startAction = icon?.let { { PrefIcon(it) } },
            onClick = onClick,
        )
    }
}

@Composable
private fun PrefIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground,
    )
}
// ---------------------------------------------------------------------------
//  Config import / export / clear / update / search (migrated verbatim)
// ---------------------------------------------------------------------------

private fun exportConfig(context: Context) {
    TransparentActivity.launch(context) {
        val exportLauncher = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val exportJson = run {
                    val map = WePrefs.default.getAll()
                    val jsonObject = buildJsonObject {
                        for ((key, value) in map) {
                            when (value) {
                                is Boolean -> put(key, value)
                                is Int -> put(key, value)
                                is Long -> put(key, value)
                                is Float -> put(key, value)
                                is Double -> put(key, value)
                                is String -> put(key, value)
                                is Set<*> -> put(key, buildJsonArray {
                                    @Suppress("UNCHECKED_CAST")
                                    (value as Set<String>).forEach { add(it) }
                                })

                                null -> put(key, JsonNull)
                            }
                        }
                    }
                    DefaultJson.encodeToString(jsonObject)
                }
                runCatching {
                    HostInfo.application.contentResolver.openOutputStream(uri, "w")!!.use { fos ->
                        fos.writer().use { it.write(exportJson) }
                    }
                }.onFailure {
                    showToastSuspend("导出失败!")
                    WeLogger.e("WePrefs", "failed to export", it)
                }.onSuccess { showToastSuspend("导出成功") }
                withContext(Dispatchers.Main) { finish() }
            }
        }
        exportLauncher.launch("wekit_prefs_backup.json")
    }
}

private fun importConfig(context: Context) {
    TransparentActivity.launch(context) {
        val importLauncher = registerForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                finish()
                return@registerForActivityResult
            }
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    val jsonString = LauncherUI.getInstance()!!.contentResolver.openInputStream(uri)?.use { fis ->
                        fis.reader().readText()
                    } ?: return@launch
                    val jsonObject = DefaultJson.parseToJsonElement(jsonString).jsonObject
                    for ((key, element) in jsonObject) {
                        when (element) {
                            is JsonNull -> WePrefs.default.remove(key)
                            is JsonPrimitive -> when {
                                element.isString -> WePrefs.default.putString(key, element.content)
                                element.booleanOrNull != null && (element.content == "true" || element.content == "false") ->
                                    WePrefs.putBool(key, element.boolean)

                                element.longOrNull != null && element.intOrNull == null ->
                                    WePrefs.putLong(key, element.long)

                                element.intOrNull != null -> WePrefs.putInt(key, element.int)
                                element.floatOrNull != null -> WePrefs.putFloat(key, element.float)
                            }

                            is JsonArray -> WePrefs.default.putStringSet(
                                key,
                                element.mapTo(HashSet()) { it.jsonPrimitive.content }
                            )

                            else -> Unit
                        }
                    }
                }.onFailure {
                    showToastSuspend("导入失败!")
                    WeLogger.e("WePrefs", "failed to import", it)
                }.onSuccess { showToastSuspend("导入成功") }
                withContext(Dispatchers.Main) { finish() }
            }
        }
        importLauncher.launch(arrayOf("application/json"))
    }
}

private fun checkForUpdate(
    onAvailable: (UpdateResult.UpdateAvailable) -> Unit,
    onError: (String) -> Unit,
) {
    CoroutineScope(Dispatchers.Main).launch {
        showToastSuspend("正在检查更新...")
        when (val result = AppUpdater.checkForUpdate()) {
            UpdateResult.UpToDate -> showToastSuspend("已是最新版本")
            is UpdateResult.UpdateAvailable -> onAvailable(result)
            is UpdateResult.Error -> {
                WeLogger.e("AppUpdater", "failed to check for updates", result.cause)
                onError(result.cause.message ?: "未知错误")
            }
        }
    }
}

// ---------------------------------------------------------------------------
//  Dialogs (miuix WindowDialog)
// ---------------------------------------------------------------------------

@Composable
private fun ClearConfigDialog(show: Boolean, onDismiss: () -> Unit) {
    MiuixConfirmDialog(
        show = show,
        title = "清除模块配置",
        message = "确定清除配置? (警告: 此操作不可逆!)",
        confirmText = "清除",
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            CoroutineScope(Dispatchers.IO).launch {
                showToastSuspend("正在清除...")
                WePrefs.default.clear()
                showToastSuspend("清除成功!")
            }
        },
    )
}

@Composable
private fun UpdateAvailableDialog(
    info: UpdateResult.UpdateAvailable?,
    onDismiss: () -> Unit,
    context: Context,
) {
    MiuixConfirmDialog(
        show = info != null,
        title = "检测到新版本",
        message = if (info != null) {
            "当前版本: ${BuildConfig.VERSION_NAME}\n新版本: ${info.info.versionName}\n是否下载并安装?"
        } else "",
        confirmText = "确定",
        onDismiss = onDismiss,
        onConfirm = {
            val target = info ?: return@MiuixConfirmDialog
            onDismiss()
            CoroutineScope(Dispatchers.Default).launch {
                AppUpdater.downloadAndInstall(context, target.info)
            }
        },
    )
}

@Composable
private fun UpdateErrorDialog(message: String?, onDismiss: () -> Unit) {
    MiuixMessageDialog(
        show = message != null,
        title = "检查更新失败",
        message = "错误信息: ${message.orEmpty()}",
        dismissText = "关闭",
        onDismiss = onDismiss,
    )
}

/** Two-button (cancel / confirm) miuix dialog. */
@Composable
private fun MiuixConfirmDialog(
    show: Boolean,
    title: String,
    message: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    dismissText: String = "取消",
) {
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column {
            Text(text = message)
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(text = dismissText, onClick = onDismiss, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                )
            }
        }
    }
}

/** Single-button (dismiss only) miuix dialog. */
@Composable
private fun MiuixMessageDialog(
    show: Boolean,
    title: String,
    message: String,
    dismissText: String,
    onDismiss: () -> Unit,
) {
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column {
            Text(text = message)
            Spacer(Modifier.height(20.dp))
            TextButton(
                text = dismissText,
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}


// ---------------------------------------------------------------------------
//  Open-source license screen
// ---------------------------------------------------------------------------

@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val libraries = remember {
        Libs.Builder().withJson(AboutLibrariesProvider.ABOUT_LIBRARIES_JSON).build().libraries
    }

    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val filtered = remember(query, libraries) {
        if (query.isBlank()) libraries
        else libraries.filter { lib ->
            lib.name.contains(query, ignoreCase = true) ||
                    lib.developers.any { it.name?.contains(query, ignoreCase = true) == true } ||
                    lib.description?.contains(query, ignoreCase = true) == true
        }
    }

    MiuixListScaffold(
        title = "开放源代码库",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = MaterialSymbols.Outlined.Arrow_back,
                    contentDescription = "返回",
                    tint = MiuixTheme.colorScheme.onBackground,
                )
            }
        },
    ) {
        item {
            TextField(
                state = queryState,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth(),
                label = "搜索库",
                leadingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Outlined.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { queryState.clearText() }) {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Close,
                                contentDescription = "清除",
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                },
            )
        }

        item {
            MiuixSmallTitle(
                text = if (query.isBlank()) "${libraries.size} 个库" else "${filtered.size}/${libraries.size} 个库",
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "找不到「$query」的结果",
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        } else {
            items(filtered, key = { it.uniqueId }) { library ->
                LibraryRow(library, modifier = Modifier.padding(top = 12.dp))
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryRow(library: Library, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = library.name,
                    fontWeight = FontWeight.SemiBold,
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                library.artifactVersion?.let { version ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = version,
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            val author = library.developers.firstOrNull()?.name ?: library.organization?.name
            if (!author.isNullOrBlank()) {
                Text(
                    text = author,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            library.description?.takeIf { it.isNotBlank() }?.let { desc ->
                Text(
                    text = desc,
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (library.licenses.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    library.licenses.forEach { license ->
                        Text(
                            text = license.name,
                            fontSize = 12.sp,
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MiuixTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
