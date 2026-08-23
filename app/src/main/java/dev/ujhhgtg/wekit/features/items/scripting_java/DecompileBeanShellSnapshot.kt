package dev.ujhhgtg.wekit.features.items.scripting_java

import androidx.activity.ComponentActivity
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.registerBshSnapshotDecompileLaunchers

object DecompileBeanShellSnapshot : ClickableFeature() {

    override val technicalId = "反编译 BeanShell 快照"
    override val nameRes = R.string.feature_decompile_bean_shell_snapshot_name
    override val categoryIds = listOf(FeatureCategoryIds.SCRIPTING_JAVA)
    override val descriptionRes = R.string.feature_decompile_bean_shell_snapshot_description

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        TransparentActivity.launch(context) {
            val selectFileLauncher = registerBshSnapshotDecompileLaunchers { finish() }
            selectFileLauncher.launch("*/*")
        }
    }
}
