package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.data
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature

/**
 * 反安全消息。
 *
 * 让微信无视消息 msgsource 中的 sec_msg_node "安全" 属性 (sfn=1 / bubble-type=2 /
 * flag 1048576), 长按任何消息 — 无论谁发送、是否带标记 — 都显示正常、完整菜单。
 * 与"安全消息发送"同时开启时: 消息照常带标记发出 (对方菜单只剩"删除"),
 * 但自己本地长按显示完整菜单。
 *
 * 微信侧的判定链 (各版本混淆名不同):
 * - 原始 sfn 检查: MsgSourceHelper 中解析 `.msgsource.sec_msg_node.sfn == 1`
 *   (8.0.65 为 zt0.t9.A, 8.0.67+ 各版本为同类的 B 方法);
 * - 完整判定: 菜单构建方 (viewitems) 调用的入口, 内部调用原始 sfn 检查
 *   (8.0.65 为 zt0.t9.z, 8.0.67+ 为 A: bubble-type==2 || flag || 原始检查)。
 *
 * 两个方法都强制返回 false: 完整判定覆盖菜单与其他消费点, 原始检查覆盖
 * 直接绕过完整判定的调用方 (如文本选择路径 com.tencent.mm.ui.chatting.x3)。
 * 全部 8.0.65–8.0.77 均存在, 按项目约定不设 allowFailure。
 */
object AntiSecMsg : SwitchFeature(), IResolveDex {

    override val technicalId = "反安全消息"
    override val nameRes = R.string.feature_anti_sec_msg_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_anti_sec_msg_description

    // 原始 sfn 检查: static (msgInfo) -> boolean, 含唯一的
    // ".msgsource.sec_msg_node.sfn" 字符串字面量
    private val methodRawSfnCheck by dexMethod {
        matcher {
            usingEqStrings(".msgsource.sec_msg_node.sfn")
            paramCount = 1
            returnType("boolean")
        }
    }

    // 完整判定: 同类、static (msgInfo) -> boolean、内部调用原始 sfn 检查
    // (8.0.65 为 z, 8.0.67+ 为 A, 方法名不参与匹配)
    private val methodFullSecCheck by dexMethod {
        matcher {
            declaredClass(methodRawSfnCheck.data.declaredClassName)
            paramCount = 1
            returnType("boolean")

            addInvoke {
                declaredClass = methodRawSfnCheck.data.declaredClassName
                name = methodRawSfnCheck.data.name
            }
        }
    }

    override fun onEnable() {
        methodFullSecCheck.hookBefore {
            result = false
        }

        methodRawSfnCheck.hookBefore {
            result = false
        }
    }
}
