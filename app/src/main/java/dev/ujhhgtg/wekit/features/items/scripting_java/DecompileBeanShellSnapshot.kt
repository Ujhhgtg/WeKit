package dev.ujhhgtg.wekit.features.items.scripting_java

import android.content.Context
import dev.ujhhgtg.wekit.activity.TransparentActivity
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.utils.registerBshSnapshotDecompileLaunchers

@Feature(name = "反编译 BeanShell 快照", categories = ["脚本 (Java)"], description = "不知道这是干啥的就别管了")
object DecompileBeanShellSnapshot : ClickableFeature() {

    override val noSwitchWidget = true

    override fun onClick(context: Context) {
        TransparentActivity.launch(context) {
            val selectFileLauncher = registerBshSnapshotDecompileLaunchers { finish() }
            selectFileLauncher.launch("*/*")
        }
    }
}
