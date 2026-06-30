package dev.ujhhgtg.wekit.ui.content

import android.content.Context
import android.content.Intent
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_circle
import com.composables.icons.materialsymbols.outlined.Add_circle
import com.composables.icons.materialsymbols.outlined.Auto_delete
import com.composables.icons.materialsymbols.outlined.Block
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Call
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Comedy_mask
import com.composables.icons.materialsymbols.outlined.Contact_page
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Frame_bug
import com.composables.icons.materialsymbols.outlined.Fullscreen
import com.composables.icons.materialsymbols.outlined.Imagesearch_roller
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.License
import com.composables.icons.materialsymbols.outlined.Lightbulb_2
import com.composables.icons.materialsymbols.outlined.Movie
import com.composables.icons.materialsymbols.outlined.Newspaper
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Package_2
import com.composables.icons.materialsymbols.outlined.Payments
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Terminal
import com.composables.icons.materialsymbols.outlined.Update
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Volunteer_activism
import com.composables.icons.materialsymbols.outlined.Wand_stars
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.activity.StandardActivity
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.constants.Preferences
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.debug.ResetDexCache
import dev.ujhhgtg.wekit.features.items.easter_egg.AprilFools
import dev.ujhhgtg.wekit.features.items.easter_egg.isAprilFools
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.aboutlibraries.AboutLibrariesScreen
import dev.ujhhgtg.wekit.ui.content.aboutlibraries.LibrariesPanel
import dev.ujhhgtg.wekit.ui.utils.AppTheme
import dev.ujhhgtg.wekit.ui.utils.GitHubIcon
import dev.ujhhgtg.wekit.ui.utils.TelegramIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AppUpdater
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.UpdateResult
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
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
import java.time.LocalDate

class MainSettingsScreen : BasePrefsScreen(BuildConfig.TAG) {

    override fun initPreferences() {
        addFakeSearchBar { context ->
            showSearchDialog(context)
        }

        if (LocalDate.now().isAprilFools) {
            addCategory("???")
            addPreference(
                title = "🏳",
                summary = "投降喵投降喵",
                onClick = {
                    WePrefs.putBool(AprilFools.KEY_SURRENDER, true)
                    showToast("重启生效")
                }
            )
        }

        addCategory("功能")
        val categories = listOf(
            "聊天" to MaterialSymbols.Outlined.Chat,
            "联系人与群组" to MaterialSymbols.Outlined.Contacts,
            "红包与支付" to MaterialSymbols.Outlined.Payments,
            "朋友圈" to MaterialSymbols.Outlined.Camera,
            "系统与隐私" to MaterialSymbols.Outlined.Wand_stars,
            "音视频通话" to MaterialSymbols.Outlined.Call,
            "通知" to MaterialSymbols.Outlined.Notifications,
            "界面美化" to MaterialSymbols.Outlined.Imagesearch_roller,
            "公众号" to MaterialSymbols.Outlined.Newspaper,
            "小程序" to MaterialSymbols.Outlined.Package_2,
            "视频号" to MaterialSymbols.Outlined.Movie,
            "个人资料" to MaterialSymbols.Outlined.Account_circle,
            "调试" to MaterialSymbols.Outlined.Bug_report,
            "脚本 (JS)" to MaterialSymbols.Outlined.Terminal,
            "脚本 (Java)" to MaterialSymbols.Outlined.Terminal,
            "娱乐" to MaterialSymbols.Outlined.Comedy_mask,
            "首页右上角菜单" to MaterialSymbols.Outlined.Add_circle,
            "联系人详情页面" to MaterialSymbols.Outlined.Contact_page
        )
        categories.forEach { (name, icon) ->
            addPreference(
                title = name, icon = icon,
                onClick = {
                    CategorySettingsScreen(name).show(it)
                }
            )
        }

        addCategory("界面")
        addSwitchPreference(
            key = Preferences.USE_ACTIVITY_INSTEAD_OF_DIALOG,
            title = "使用全屏配置 UI",
            summary = "使用 Activity 而非 Dialog 作为模块 UI 容器",
            icon = MaterialSymbols.Outlined.Fullscreen
        )

        addCategory("调试")
        addSwitchPreference(
            key = Preferences.VERBOSE_LOG,
            title = "详细日志",
            summary = "输出高频日志 (这可能会暴露你的隐私信息）",
            icon = MaterialSymbols.Outlined.Frame_bug
        )
        addSwitchPreference(
            key = Preferences.SHOW_STARTUP_TOAST,
            title = "显示加载完成 Toast",
            summary = "全部功能加载完成后显示 Toast 提示",
            icon = MaterialSymbols.Outlined.Notifications
        )

        addCategory("兼容")
        addSwitchPreference(
            key = Preferences.NO_DEX_RESOLVE,
            title = "禁用版本适配",
            summary = "不弹出 DEX 查找对话框，未适配功能将不会被加载",
            icon = MaterialSymbols.Outlined.Block
        )
        addPreference(
            title = "重置适配信息",
            summary = "清除 DEX 缓存, 等待下次启动时重新适配",
            icon = MaterialSymbols.Outlined.Build_circle,
            onClick = { ResetDexCache.onClick(it) }
        )
        addSwitchPreference(
            key = Preferences.RESET_DEX_ON_HOT_UPDATE,
            title = "宿主热更新时重新适配",
            summary = "宿主热更新时是否重置 DEX 缓存, 可能导致频繁重新适配 (实验性)",
            icon = MaterialSymbols.Outlined.Auto_delete
        )

        addCategory("配置")
        addPreference(
            title = "导出配置",
            summary = "将模块配置导出为 JSON",
            icon = MaterialSymbols.Outlined.Upload,
            onClick = { context ->
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

                            withContext(Dispatchers.Main) {
                                finish()
                            }
                        }
                    }
                    exportLauncher.launch("wekit_prefs_backup.json")
                }
            }
        )
        addPreference(
            title = "导入配置",
            summary = "从 JSON 导入模块配置; JSON 中的配置将会与现有配置合并, 覆盖所有已存在的配置",
            icon = MaterialSymbols.Outlined.Download,
            onClick = { context ->
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

                            withContext(Dispatchers.Main) {
                                finish()
                            }
                        }
                    }
                    importLauncher.launch(arrayOf("application/json"))
                }
            }
        )
        addPreference(
            title = "清除配置",
            summary = "清除全部模块配置 (警告: 此操作不可逆!)",
            icon = MaterialSymbols.Outlined.Delete_forever,
            onClick = {
                showComposeDialog(it) {
                    AlertDialogContent(
                        title = { Text("清除模块配置") },
                        text = { Text("确定清除配置? (警告: 此操作不可逆!)") },
                        dismissButton = { TextButton(onDismiss) { Text("取消") } },
                        confirmButton = {
                            Button(onClick = {
                                onDismiss()
                                CoroutineScope(Dispatchers.IO).launch {
                                    showToastSuspend("正在清除...")
                                    WePrefs.default.clear()
                                    showToastSuspend("清除成功!")
                                }
                            }) { Text("清除") }
                        })
                }
            }
        )

        addCategory("更新")
        addPreference(
            title = "检查更新",
            summary = "立即检查模块是否有新版本并自动下载",
            icon = MaterialSymbols.Outlined.Update,
            onClick = {
                CoroutineScope(Dispatchers.Main).launch {
                    showToastSuspend("正在检查更新...")
                    when (val result = AppUpdater.checkForUpdate()) {
                        UpdateResult.UpToDate -> showToastSuspend("已是最新版本")
                        is UpdateResult.UpdateAvailable -> {
                            showComposeDialog(it) {
                                AlertDialogContent(
                                    title = { Text("检测到新版本") },
                                    text = {
                                        Text(
                                            "当前版本: ${BuildConfig.VERSION_NAME}\n新版本: ${result.info.versionName}\n" +
                                                    "是否下载并安装?"
                                        )
                                    },
                                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                                    confirmButton = {
                                        Button(onClick = {
                                            onDismiss()
                                            CoroutineScope(Dispatchers.Default).launch {
                                                AppUpdater.downloadAndInstall(it, result.info)
                                            }
                                        }) { Text("确定") }
                                    }
                                )
                            }
                        }
                        is UpdateResult.Error -> {
                            WeLogger.e("AppUpdater", "failed to check for updates", result.cause)
                            showComposeDialog(it) {
                                AlertDialogContent(
                                    title = { Text("检查更新失败") },
                                    text = { Text("错误信息: ${result.cause.message}") },
                                    confirmButton = { TextButton(onDismiss) { Text("关闭") } },
                                )
                            }
                        }
                    }
                }
            }
        )

        addCategory("关于")
        addPreference(
            title = "版本",
            summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            icon = MaterialSymbols.Outlined.Label,
        )
        addPreference(
            "构建时间",
            formatEpoch(BuildConfig.BUILD_TIMESTAMP, true),
            icon = MaterialSymbols.Outlined.Build_circle
        )
        addPreference(
            "提示",
            "牙膏要一点一点挤, 显卡要一刀一刀切, PPT 要一张一张放, 代码要一行一行写, 单个功能预计自出现在 commit 之日起, 三年内开发完毕",
            icon = MaterialSymbols.Outlined.Lightbulb_2
        )
        addPreference(
            "捐赠",
            "支持项目开发 (模块完全开源免费, 捐赠无特权)",
            onClick = {
                it.startActivity(Intent().apply {
                    setClassName(HostInfo.packageName, "${PackageNames.WECHAT}.plugin.collect.reward.ui.QrRewardSelectMoneyUI")
                    putExtra("key_qrcode_url", "m0n#Z7LGW*s4AVH!z'd(?)")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            },
            icon = MaterialSymbols.Outlined.Volunteer_activism
        )
        addPreference(
            title = "开放源代码许可",
            summary = "本项目使用的开放源代码库许可",
            icon = MaterialSymbols.Outlined.License,
            onClick = {
                if (!Preferences.useActivityInsteadOfDialog) {
                    showComposeDialog(it) {
                        LibrariesPanel()
                    }
                } else {
                    StandardActivity.launch(it) {
                        setContent {
                            CompositionLocalProvider(
                                LocalContext provides this,
                                LocalActivity provides this
                            ) {
                                AppTheme {
                                    AboutLibrariesScreen { finish() }
                                }
                            }
                        }
                    }
                }
            }
        )
        addPreference(
            title = "GitHub",
            summary = "Ujhhgtg/WeKit",
            icon = GitHubIcon,
            onClick = { "https://github.com/Ujhhgtg/WeKit".toUri().openInSystem(it, true) }
        )
        addPreference(
            title = "Telegram",
            summary = "Telegram 超级群组",
            icon = TelegramIcon,
            onClick = { "https://t.me/+4XsfR-SWAtk1NGRl".toUri().openInSystem(it, true) }
        )
    }
}

private fun showSearchDialog(context: Context) {
    showComposeDialog(context) {
        var searchQuery by remember { mutableStateOf("") }
        val focusRequester = remember { FocusRequester() }

        // Filter target switchable/clickable hook items
        val searchableItems = remember {
            dev.ujhhgtg.wekit.features.core.FeaturesProvider.ALL_HOOK_ITEMS.filterIsInstance<SwitchFeature>()
        }

        val filteredItems = remember(searchQuery) {
            if (searchQuery.isBlank()) emptyList()
            else searchableItems.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }

        val switchStates = remember { mutableStateMapOf<String, Boolean>() }

        AlertDialogContent(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f),
            title = {
                CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyLarge) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        placeholder = { Text("输入功能名称...") },
                        leadingIcon = {
                            Icon(
                                imageVector = MaterialSymbols.Outlined.Search,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = MaterialSymbols.Outlined.Close,
                                        contentDescription = "Clear text"
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp)
                    )
                }

                // Automatically requests focus and opens software keyboard on launch
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                }
            },
            text = {
                if (filteredItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (searchQuery.isBlank()) "输入关键词即可实时过滤结果" else "未匹配到任何相关功能",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        filteredItems.forEach { item ->
                            val configKey = item.name
                            val checked = switchStates[configKey] ?: WePrefs.getBoolOrFalse(configKey)

                            DisposableEffect(configKey) {
                                item.setToggleCompletionCallback {
                                    switchStates[configKey] = item.isEnabled
                                }
                                onDispose {}
                            }

                            when (item) {
                                is ClickableFeature -> {
                                    HookClickableRow(
                                        title = item.name,
                                        summary = item.description,
                                        showSwitch = !item.noSwitchWidget,
                                        checked = checked,
                                        onCheckedChange = { requested ->
                                            if (item.onBeforeToggle(requested, context)) {
                                                WePrefs.putBool(configKey, requested)
                                                item.isEnabled = requested
                                                switchStates[configKey] = requested
                                            }
                                        },
                                        onClick = { context ->
                                            item.onClick(context)
                                        }
                                    )
                                }
                                is SwitchFeature -> {
                                    HookSwitchRow(
                                        title = item.name,
                                        summary = item.description,
                                        checked = checked,
                                        onCheckedChange = { requested ->
                                            if (item.onBeforeToggle(requested, context)) {
                                                WePrefs.putBool(configKey, requested)
                                                item.isEnabled = requested
                                                switchStates[configKey] = requested
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        )
    }
}
