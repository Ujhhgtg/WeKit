package moe.ouom.wekit.ui.content

import android.content.Context
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.R
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.ui.utils.showComposeDialog
import moe.ouom.wekit.utils.common.Utils.openUrl
import moe.ouom.wekit.utils.formatEpoch

class MainSettingsDialog(context: Context) : BasePrefDialog(context, "WeKit") {

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
            key = Constants.ENABLE_LOG_PREF_KEY,
            title = "日志记录",
            summary = "反馈问题前必须开启日志记录",
            iconName = "list_alt_24px"
        )

        addSwitchPreference(
            key = Constants.VERBOSE_LOG_PREF_KEY,
            title = "详细日志",
            summary = "输出高频日志 (这可能会暴露你的隐私信息）",
            iconName = "frame_bug_24px"
        )

        val dependentKey = addSwitchPreference(
            key = Constants.DB_VERBOSE_LOG_PREF_KEY,
            title = "数据库详细日志",
            summary = "输出完整的数据库插入事件详情（ContentValues）",
            iconName = "database_upload_24px"
        )

        // 数据库详细日志依赖于详细日志
        setDependency(
            dependentKey = dependentKey,
            dependencyKey = Constants.VERBOSE_LOG_PREF_KEY,
            enableWhen = true
        )

        // ==========================================
        // 兼容 (Compatibility)
        // ==========================================
        addCategory("兼容")

        // 使用 addSelectPreference 替代手动实现
        addSelectPreference(
            key = Constants.HOOK_PRIORITY_PREF_KEY,
            title = "XC_MethodHook 优先级",
            summary = "当前设定", // 当配置的值不在 map 中时，会显示 "当前设定: [值]"
            options = priorityMap,
            defaultValue = 50,
            iconName = "low_priority_24px"
            // 因为 key 已经包含了前缀 PrekCfgXXX，所以必须设为 true
        )

        addSwitchPreference(
            key = Constants.DISABLE_DEX_LOCATE_PREF_KEY,
            title = "禁用版本适配",
            summary = "开启后不会弹出 DEX 查找对话框，未适配功能将不会被加载",
            iconName = "block_24px"
        )

        // ==========================================
        // 关于 (About)
        // ==========================================
        addCategory("关于")
        addPreference(title = "版本", summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        addPreference("构建时间", formatEpoch(BuildConfig.BUILD_TIMESTAMP, true))
        addPreference(
            "提示",
            "牙膏要一点一点挤, 显卡要一刀一刀切, PPT 要一张一张放, 代码要一行一行写, 单个功能预计自出现在 commit 之日起, 三年内开发完毕"
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
            onClick = { openUrl(context, "https://github.com/Ujhhgtg/WeKit") }
        )
        addPreference(
            title = "Telegram",
            summary = "@ouom_pub",
            iconName = "ic_telegram",
            onClick = { openUrl(context, "https://t.me/ouom_pub") }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LibraryCard(library: Library) {
    // AboutLibraries usually stores the creator in developers or organization
    val authorName = library.developers.joinToString { it.name ?: "" }
        .takeIf { it.isNotBlank() } ?: library.organization?.name

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header: Library Name and Version Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = library.name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                library.artifactVersion?.let { version ->
                    SuggestionChip(
                        onClick = { },
                        label = { Text(version) },
                        shape = MaterialTheme.shapes.medium,
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        border = null,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }

            // Author / Organization
            authorName?.let { author ->
                Text(
                    text = "by $author",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Description
            library.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Licenses
            if (library.licenses.isNotEmpty()) {
                // FlowRow allows dual-licenses to wrap neatly to the next line
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    library.licenses.forEach { license ->
                        AssistChip(
                            onClick = { },
                            label = { Text(license.name) },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                            ),
                            border = null,
                            shape = MaterialTheme.shapes.large
                        )
                    }
                }
            }
        }
    }
}