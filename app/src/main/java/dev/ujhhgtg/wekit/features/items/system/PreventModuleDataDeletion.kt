package dev.ujhhgtg.wekit.features.items.system

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeVfsApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.HostInfo
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import kotlin.io.path.div

object PreventModuleDataDeletion : SwitchFeature() {
    override val technicalId = "阻止微信清理模块数据"
    override val nameRes = R.string.feature_prevent_module_data_deletion_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_prevent_module_data_deletion_description

    // WeChat's VFS canonicalizes base paths (/data/user/0 -> /data/data), so compare canonically
    private val protectedRoots by lazy {
        listOf(
                KnownPaths.moduleRoot,
                KnownPaths.downloads,
                KnownPaths.internalStorage /
                    "Android" /
                    "data" /
                    HostInfo.packageName /
                    "Layout Inspect",
            )
            .map { it.toFile().canonicalPath }
    }

    override fun onEnable() {
        WeVfsApi.methodNativeEntryDelete.hookBefore {
            val relPath = args[0] as String
            val basePath = WeVfsApi.fieldNativeEntryBasePath.field.get(thisObject) as String

            val path = if (relPath.isEmpty()) basePath else "$basePath/$relPath"
            if (protectedRoots.any { path == it || path.startsWith("$it/") }) {
                result = true
            }
        }
    }
}
