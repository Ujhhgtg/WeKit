package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import org.luckypray.dexkit.DexKitBridge

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

    private val methodRiskFileFlag by dexMethod {
        matcher {
            declaredClass {
                usingEqStrings(
                    "uuid",
                    "sfn",
                    "fold-reduce",
                    "show-h5",
                    "sec-ctrl-flag",
                    "clip-len",
                    "share-tip-url",
                    "media-to-emoji",
                    "block-range",
                    "bubble-type",
                    "preview-type",
                    "url-click-type",
                    "risk-file-flag",
                    "risk-file-md5-list",
                    "risk-warning-url",
                    "unread-media-expired"
                )
            }
            paramCount = 0
            returnType("java.lang.Integer")
        }
    }

    override fun onEnable() {
        methodRiskFileFlag.hookBefore {
            result = 0
        }
    }
}
