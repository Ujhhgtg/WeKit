package dev.ujhhgtg.wekit.features.items.scripting_java

import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.registerBshSnapshotDecompileLaunchers

@Feature(
    id = "反编译 BeanShell 快照",
    nameRes = "feature_decompile_bean_shell_snapshot_name",
    categoryIds = [FeatureCategoryIds.SCRIPTING_JAVA],
    descriptionRes = "feature_decompile_bean_shell_snapshot_description",
)
object DecompileBeanShellSnapshot : ClickableFeature() {

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val selectFileLauncher = registerBshSnapshotDecompileLaunchers { finish() }
            selectFileLauncher.launch("*/*")
        }
    }
}
