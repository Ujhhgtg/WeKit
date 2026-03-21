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
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import dev.ujhhgtg.wekit.BuildConfig
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.StubFragmentActivity
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.constants.PreferenceKeys
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.DefaultJson
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.RuntimeConfig
import dev.ujhhgtg.wekit.utils.ToastUtils.showToast
import dev.ujhhgtg.wekit.utils.ToastUtils.showToastSuspend
import dev.ujhhgtg.wekit.utils.formatEpoch
import dev.ujhhgtg.wekit.utils.logging.WeLogger
import dev.ujhhgtg.wekit.utils.openInSystem
import dev.ujhhgtg.wekit.utils.updates.UpdateChecker
import dev.ujhhgtg.wekit.utils.updates.UpdateDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

class MainSettingsDialog(context: Context) : BasePrefsDialog(context, BuildConfig.TAG) {

    // 定义优先级 映射关系 (值 -> 显示文本)
    private val priorityMap = mapOf(
        10000 to "高优先级",
        50 to "智能",
        -10000 to "低优先级"
    )

    override fun initPreferences() {
        addCategory("功能")
        val categories = listOf(
            "聊天" to "chat_24px",
            "联系人与群组" to "contacts_24px",
            "红包与支付" to "payments_24px",
            "朋友圈" to "camera_24px",
            "系统与隐私" to "wand_stars_24px",
            "通知" to "notifications_24px",
            "界面美化" to "imagesearch_roller_24px",
            "小程序" to "package_2_24px",
            "视频号" to "movie_24px",
            "个人资料" to "account_circle_24px",
            "调试" to "bug_report_24px",
            "脚本" to "terminal_24px",
        )
        categories.forEach { (name, iconName) ->
            addPreference(
                title = name, iconName = iconName,
                onClick = {
                    CategorySettingsDialog(context, name).show()
                })
        }

        addCategory("调试")
        addSwitchPreference(
            key = PreferenceKeys.ENABLE_LOG,
            title = "日志记录",
            summary = "反馈问题前必须开启日志记录",
            iconName = "list_alt_24px"
        )

        addSwitchPreference(
            key = PreferenceKeys.VERBOSE_LOG,
            title = "详细日志",
            summary = "输出高频日志 (这可能会暴露你的隐私信息）",
            iconName = "frame_bug_24px"
        )

        val dependentKey = addSwitchPreference(
            key = PreferenceKeys.DB_VERBOSE_LOG,
            title = "数据库详细日志",
            summary = "输出完整的数据库插入事件详情（ContentValues）",
            iconName = "database_upload_24px"
        )

        // 数据库详细日志依赖于详细日志
        setDependency(
            dependentKey = dependentKey,
            dependencyKey = PreferenceKeys.VERBOSE_LOG,
            enableWhen = true
        )

        // ==========================================
        // 兼容 (Compatibility)
        // ==========================================
        addCategory("兼容")

        // 使用 addSelectPreference 替代手动实现
        addSelectPreference(
            key = PreferenceKeys.HOOK_PRIORITY,
            title = "XC_MethodHook 优先级",
            summary = "当前设定", // 当配置的值不在 map 中时，会显示 "当前设定: [值]"
            options = priorityMap,
            defaultValue = 50,
            iconName = "low_priority_24px"
        )

        addSwitchPreference(
            key = PreferenceKeys.NO_DEX_RESOLVE,
            title = "禁用版本适配",
            summary = "开启后不会弹出 DEX 查找对话框，未适配功能将不会被加载",
            iconName = "block_24px"
        )

        addCategory("备份与恢复")
        addPreference(
            title = "导出配置",
            summary = "将模块配置导出为 JSON",
            iconName = "upload_24px",
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
            iconName = "download_24px",
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
                                val jsonString = RuntimeConfig.getLauncherUiActivity()!!.contentResolver.openInputStream(uri)?.use { fis ->
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
        var confirmDeletion = false
        addPreference(
            title = "清除配置",
            summary = "清除全部模块配置 (警告: 此操作不可逆!)",
            iconName = "delete_forever_24px",
            onClick = {
                if (!confirmDeletion) {
                    showToast("再次点击以确认清除!")
                    confirmDeletion = true
                    return@addPreference
                }

                runBlocking(Dispatchers.IO) {
                    WePrefs.default.clear()
                    showToastSuspend("清除成功!")
                }
            }
        )

        addCategory("关于")
        addPreference(
            title = "版本 (点击检查更新)",
            summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            iconName = "update_24px",
            onClick = {
                CoroutineScope(Dispatchers.Main).launch {
                    showToastSuspend("正在检查更新...")
                    val update = runCatching { UpdateChecker.checkForUpdate() }.getOrElse { e ->
                        WeLogger.e("UpdateChecker", "failed to check for updates", e)
                        showComposeDialog(context) {
                            AlertDialogContent(
                                title = { Text("检查更新失败") },
                                text = {
                                    Text(
                                        "错误信息: ${e.message}\n" +
                                                "是否尝试直接下载并安装最新版本?"
                                    )
                                },
                                dismissButton = { TextButton(dismiss) { Text("取消") } },
                                confirmButton = {
                                    Button(onClick = {
                                        dismiss()
                                        CoroutineScope(Dispatchers.Main).launch {
                                            UpdateDownloader.downloadAndInstall(context, UpdateChecker.DOWNLOAD_URL)
                                        }
                                    }) { Text("确定") }
                                }
                            )
                        }
                        return@launch
                    }
                    if (update == null) {
                        showToastSuspend("已是最新版本")
                        return@launch
                    }
                    showComposeDialog(context) {
                        AlertDialogContent(
                            title = { Text("检测到新版本") },
                            text = {
                                Text(
                                    "当前版本: ${BuildConfig.GIT_HASH} → 新版本: ${update.latestSha}\n" +
                                            "提交消息:\n" +
                                            "${update.commitMessage.prependIndent("  ")}\n" +
                                            "是否下载并安装?"
                                )
                            },
                            dismissButton = { TextButton(dismiss) { Text("取消") } },
                            confirmButton = {
                                Button(onClick = {
                                    dismiss()
                                    CoroutineScope(Dispatchers.Main).launch {
                                        UpdateDownloader.downloadAndInstall(context, update.downloadUrl)
                                    }
                                }) { Text("确定") }
                            }
                        )
                    }
                }
            }
        )
        addPreference("构建时间",
            formatEpoch(BuildConfig.BUILD_TIMESTAMP, true),
            iconName = "build_circle_24px")
        addPreference(
            "提示",
            "牙膏要一点一点挤, 显卡要一刀一刀切, PPT 要一张一张放, 代码要一行一行写, 单个功能预计自出现在 commit 之日起, 三年内开发完毕",
            iconName = "lightbulb_2_24px"
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
            iconName = "volunteer_activism_24px"
        )
        addPreference(
            title = "开放源代码许可",
            summary = "本项目使用的开放源代码库许可",
            iconName = "license_24px",
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
            iconName = "ic_github",
            onClick = { "https://github.com/Ujhhgtg/WeKit".toUri().openInSystem(context, true) }
        )
        addPreference(
            title = "Telegram",
            summary = "@ujhhgtg_wekit_ci",
            iconName = "ic_telegram",
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
