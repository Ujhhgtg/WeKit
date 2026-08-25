package dev.ujhhgtg.wekit.features.items.system

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

object RemoveQrCodeScanLimit : SwitchFeature() {

    override val technicalId = "移除二维码扫描限制"
    override val nameRes = R.string.feature_remove_qr_code_scan_limit_name
    override val categoryIds = listOf(FeatureCategoryIds.SYSTEM_PRIVACY)
    override val descriptionRes = R.string.feature_remove_qr_code_scan_limit_description

    private enum class ScanScene(val source: Int, val a8KeyScene: Int) {
        CAMERA(0, 4), // 相机扫描
        ALBUM(1, 34), // 相册选择
        PICTURE_LONG_PRESS(4, 37) // 长按图片
    }

    override fun onEnable() {
        QrCodeRecord.methodQBarString.hookBefore {
            val (sourceIndex, a8KeySceneIndex) = if (QrCodeRecord.methodQBarString.method.parameterCount == 16) 3 to 4 else 2 to 3
            val source = args[sourceIndex] as Int
            val a8KeyScene = args[a8KeySceneIndex] as Int
            val matchedScene =
                ScanScene.entries.find { it.source == source && it.a8KeyScene == a8KeyScene }
            if (matchedScene == ScanScene.ALBUM || matchedScene == ScanScene.PICTURE_LONG_PRESS) {
                args[sourceIndex] = ScanScene.CAMERA.source
                args[a8KeySceneIndex] = ScanScene.CAMERA.a8KeyScene
            }
        }
    }
}
