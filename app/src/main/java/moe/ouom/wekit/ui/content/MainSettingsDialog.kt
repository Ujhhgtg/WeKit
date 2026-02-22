package moe.ouom.wekit.ui.content

import android.content.Context
import moe.ouom.wekit.BuildConfig
import moe.ouom.wekit.constants.Constants
import moe.ouom.wekit.utils.common.Utils.jumpUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainSettingsDialog(context: Context) : BaseRikkaDialog(context, "WeKit") {
    // 定义优先级 映射关系 (值 -> 显示文本)
    private val priorityMap = mapOf(
        10000 to "高优先级",
        50 to "智能",
        -10000 to "低优先级"
    )

    override fun initPreferences() {
        addCategory("功能")
        val categories = listOf(
            "聊天与消息" to "chat_24px",
            "联系人" to "contacts_24px",
            "朋友圈" to "camera_24px",
            "小程序" to "package_2_24px",
            "优化与修复" to "wand_stars_24px",
            "美化" to "imagesearch_roller_24px",
            "视频号" to "movie_24px",
            "调试" to "bug_report_24px",
            "娱乐" to "comedy_mask_24px",
            "自动化" to "terminal_24px",
        )
        categories.forEach { (name, iconName) ->
            addPreference(
                title = name, iconName = iconName,
                onClick = { _, _ ->
                    CategorySettingsDialog(context, name).show()
                })
        }

        addCategory("调试")
        addSwitchPreference(
            key = Constants.PrekEnableLog,
            title = "日志记录",
            summary = "反馈问题前必须开启日志记录",
            iconName = "list_alt_24px",
            useFullKey = true
        )

        addSwitchPreference(
            key = Constants.PrekVerboseLog,
            title = "详细日志",
            summary = "输出高频日志 (这可能会暴露你的隐私信息）",
            iconName = "frame_bug_24px",
            useFullKey = true
        )

        val dbVerboseLogView = addSwitchPreference(
            key = Constants.PrekDatabaseVerboseLog,
            title = "数据库详细日志",
            summary = "输出完整的数据库插入事件详情（ContentValues）",
            iconName = "database_upload_24px",
            useFullKey = true
        )

        // 数据库详细日志依赖于详细日志
        setDependency(
            dependentView = dbVerboseLogView,
            dependencyKey = Constants.PrekVerboseLog,
            enableWhen = true,
            useFullKey = true
        )

        // ==========================================
        // 兼容 (Compatibility)
        // ==========================================
        addCategory("兼容")

        // 使用 addSelectPreference 替代手动实现
        val priorityKey = "${Constants.PrekCfgXXX}wekit_hook_priority"

        addSelectPreference(
            key = priorityKey,
            title = "XC_MethodHook 优先级",
            summary = "当前设定", // 当配置的值不在 map 中时，会显示 "当前设定: [值]"
            options = priorityMap,
            defaultValue = 50,
            iconName = "low_priority_24px",
            useFullKey = true // 因为 key 已经包含了前缀 PrekCfgXXX，所以必须设为 true
        )

        addSwitchPreference(
            key = Constants.PrekDisableVersionAdaptation,
            title = "禁用版本适配",
            summary = "开启后不会弹出 DEX 查找对话框，未适配功能将不会被加载",
            iconName = "block_24px",
            useFullKey = true
        )

        // ==========================================
        // 关于 (About)
        // ==========================================
        addCategory("关于")
        addPreference(title = "版本", summary = BuildConfig.VERSION_NAME)

        val buildTimeStr = try {
            val date = Date(BuildConfig.BUILD_TIMESTAMP)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
        } catch (_: Exception) {
            "N/A"
        }
        addPreference(title = "构建时间", summary = buildTimeStr)
        addPreference("构建 UUID", BuildConfig.BUILD_UUID)

        addPreference(
            title = "GitHub",
            summary = "修改于 Ujhhgtg/WeKit (原始: cwuom/WeKit)",
            iconName = "ic_github",
            onClick = { _, _ -> jumpUrl(context, "https://github.com/Ujhhgtg/WeKit") }
        )
        addPreference(
            title = "Telegram",
            summary = "@ouom_pub",
            iconName = "ic_telegram",
            onClick = { _, _ -> jumpUrl(context, "https://t.me/ouom_pub") }
        )
    }
}