package dev.ujhhgtg.wekit.ui.content

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_circle
import com.composables.icons.materialsymbols.outlined.Block
import com.composables.icons.materialsymbols.outlined.Bug_report
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Camera
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Edit_document
import com.composables.icons.materialsymbols.outlined.Frame_bug
import com.composables.icons.materialsymbols.outlined.Imagesearch_roller
import com.composables.icons.materialsymbols.outlined.License
import com.composables.icons.materialsymbols.outlined.Lightbulb_2
import com.composables.icons.materialsymbols.outlined.Movie
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Package_2
import com.composables.icons.materialsymbols.outlined.Payments
import com.composables.icons.materialsymbols.outlined.Terminal
import com.composables.icons.materialsymbols.outlined.Update
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Volunteer_activism
import com.composables.icons.materialsymbols.outlined.Wand_stars
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.tencent.mm.ui.LauncherUI
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.StubFragmentActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.constants.PreferenceKeys
import dev.ujhhgtg.wekit.hooks.items.easter_egg.AprilFools
import dev.ujhhgtg.wekit.hooks.items.easter_egg.isAprilFools
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.utils.GitHubIcon
import dev.ujhhgtg.wekit.ui.utils.TelegramIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.DefaultJson
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.openInSystem
import dev.ujhhgtg.wekit.utils.showToast
import dev.ujhhgtg.wekit.utils.showToastSuspend
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

class MainSettingsDialog(context: Context) : BasePrefsDialog(context, BuildConfig.TAG) {

    override fun initPreferences() {
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
            "通知" to MaterialSymbols.Outlined.Notifications,
            "界面美化" to MaterialSymbols.Outlined.Imagesearch_roller,
            "小程序" to MaterialSymbols.Outlined.Package_2,
            "视频号" to MaterialSymbols.Outlined.Movie,
            "个人资料" to MaterialSymbols.Outlined.Account_circle,
            "调试" to MaterialSymbols.Outlined.Bug_report,
            "脚本" to MaterialSymbols.Outlined.Terminal
        )
        categories.forEach { (name, icon) ->
            addPreference(
                title = name, icon = icon,
                onClick = {
                    CategorySettingsDialog(context, name).show()
                })
        }

        addCategory("调试")
        addSwitchPreference(
            key = PreferenceKeys.VERBOSE_LOG,
            title = "详细日志",
            summary = "输出高频日志 (这可能会暴露你的隐私信息）",
            icon = MaterialSymbols.Outlined.Frame_bug
        )
        addSwitchPreference(
            key = PreferenceKeys.SHOW_TOAST_ON_STARTUP_COMPLETE,
            title = "显示加载完成 Toast",
            summary = "全部功能加载完成后显示 Toast 提示",
            icon = MaterialSymbols.Outlined.Notifications
        )
        addPreference(
            title = "刷新日志文件缓冲区",
            summary = "立即将缓冲区中的所有日志写入日志文件",
            icon = MaterialSymbols.Outlined.Edit_document,
            onClick = {
                CoroutineScope(Dispatchers.IO).launch {
                    WeLogger.flush()
                    showToastSuspend("已刷新")
                }
            }
        )

        addCategory("兼容")
        addSwitchPreference(
            key = PreferenceKeys.NO_DEX_RESOLVE,
            title = "禁用版本适配",
            summary = "开启后不会弹出 DEX 查找对话框，未适配功能将不会被加载",
            icon = MaterialSymbols.Outlined.Block
        )
        addCategory("配置")
        addPreference(
            title = "导出配置",
            summary = "将模块配置导出为 JSON",
            icon = MaterialSymbols.Outlined.Upload,
            onClick = {
                StubFragmentActivity.launch(HostInfo.application) {
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
            onClick = {
                StubFragmentActivity.launch(HostInfo.application) {
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
                showComposeDialog(context) {
                    AlertDialogContent(title = { Text("清除模块配置") },
                        text = { Text("确定清除配置? (警告: 此操作不可逆!)") },
                        dismissButton = { TextButton(onDismiss) { Text("取消") } },
                        confirmButton = { Button(onClick = {
                            onDismiss()
                            CoroutineScope(Dispatchers.IO).launch {
                                showToastSuspend("正在清除...")
                                WePrefs.default.clear()
                                showToastSuspend("清除成功!")
                            }
                        }) { Text("清除") } })
                }
            }
        )

        addCategory("关于")
        addPreference(
            title = "版本",
            summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            icon = MaterialSymbols.Outlined.Update,
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
                context.startActivity(Intent().apply {
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
                showComposeDialog(context) {
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        tonalElevation = 6.dp
                    ) {
                        val libraries by produceLibraries(R.raw.aboutlibraries)
                        val libraryList = libraries?.libraries ?: emptyList()
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(libraryList) { library ->
                                LibraryCard(library = library)
                            }
                        }
                    }
                }
            }
        )
        addPreference(
            title = "GitHub",
            summary = "修改于 Ujhhgtg/WeKit (原始: cwuom/WeKit)",
            icon = GitHubIcon,
            onClick = { "https://github.com/Ujhhgtg/WeKit".toUri().openInSystem(context, true) }
        )
        addPreference(
            title = "Telegram",
            summary = "@ujhhgtg_wekit_ci",
            icon = TelegramIcon,
            onClick = { "https://t.me/ujhhgtg_wekit_ci".toUri().openInSystem(context, true) }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryCard(library: Library) {
    val authorName = library.developers.joinToString { it.name ?: "" }
        .takeIf { it.isNotBlank() } ?: library.organization?.name

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                library.artifactVersion?.let { version ->
                    Text(
                        text = version.take(20).let { if (version.length > 15) "$it…" else it },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            authorName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            library.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (library.licenses.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    library.licenses.forEach { license ->
                        Text(
                            text = license.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }
        }
    }
}
