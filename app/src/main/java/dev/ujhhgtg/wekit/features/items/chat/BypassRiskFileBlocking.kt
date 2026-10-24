package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.dexkit.sql.sqlMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
/**
 * 绕过被投诉风险文件的接收/打开拦截。
 *
 * 微信把风险标志写在消息 msgSource 的
 * `<msgsource><sec_msg_node risk-file-flag="1"/></msgsource>` 里,
 * 旧下载页 (AppAttachNewDownloadUI) 与新文件页 (AppAttachDataUIC)
 * 都通过 sec_msg_node 模型类唯一的无参 Integer 获取器读取该标志,
 * 命中后分别显示 "无法接收" (R.string.m7h) 与 "无法打开" (R.string.m7g)。
 * 这里直接把该获取器返回 0, 所有消费点都会认为文件未被标记为风险文件。
 */
object BypassRiskFileBlocking : SwitchFeature(), IResolveDex {

    override val technicalId = "解除风险文件拦截"
    override val nameRes = R.string.feature_bypass_risk_file_blocking_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_bypass_risk_file_blocking_description

    private val methodRiskFileFlag by dexMethod(
        sqlMethod {
            returnType = "java.lang.Integer"
            paramCount = 0
            declaredClass {
                usingEqStrings("uuid")
                usingEqStrings("sfn")
                usingEqStrings("fold-reduce")
                usingEqStrings("show-h5")
                usingEqStrings("sec-ctrl-flag")
                usingEqStrings("clip-len")
                usingEqStrings("share-tip-url")
                usingEqStrings("media-to-emoji")
                usingEqStrings("block-range")
                usingEqStrings("bubble-type")
                usingEqStrings("preview-type")
                usingEqStrings("url-click-type")
                usingEqStrings("risk-file-flag")
                usingEqStrings("risk-file-md5-list")
                usingEqStrings("risk-warning-url")
                usingEqStrings("unread-media-expired")
            }
        },
    )

    override fun onEnable() {
        methodRiskFileFlag.hookBefore {
            result = 0
        }
    }
}
