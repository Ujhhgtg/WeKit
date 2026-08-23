package dev.ujhhgtg.wekit.features.items.entertain

import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeAlertDialogApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.utils.collections.emptyHashSet
import dev.ujhhgtg.wekit.utils.openInSystem
import dev.ujhhgtg.wekit.utils.serialization.JsonToXmlConverter
import org.json.JSONObject

object TrollBan : ClickableFeature() {

    override val technicalId = "防止封号"
    override val nameRes = R.string.feature_troll_ban_name
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val descriptionRes = R.string.feature_troll_ban_description

    override val noSwitchWidget = true

    const val URL: String = "https://www.bilibili.com/video/BV1GJ411x7h7"

    override fun onClick(context: ComponentActivity) {
        val json = JSONObject()
        val json2 = JSONObject()
        val json3 = JSONObject()
        json3.put("type", 5)
        json3.put("title", "微信安全提醒")
        val json4 = JSONObject()
        val json5 = JSONObject()
        val json6 = JSONObject()
        json6.put("title", "微信安全提醒")
        json6.put(
            "digest",
            "该微信号因使用外挂、模拟器等非官方客户端程序或其他违规技术（请卸载停用违规内容，若继续使用将升级至永久限制），当前无法使用所有社交场景。该限制为临时限制。\n\n你可以点击“详情”查看更多信息，进行安全验证以继续使用该功能。"
        )
        json6.put("url", URL)
        json5.put("item", json6)
        json4.put("category", json5)
        json3.put("mmreader", json4)
        json2.put("appmsg", json3)
        json.put("msg", json2)
        val conv = JsonToXmlConverter(json, emptyHashSet(), emptyHashSet())
        WeMessageApi.createSimpleMsgInfoAndInsert(318767153, "weixin", conv.toString(), System.currentTimeMillis())
        val message = listOf(
            "该微信号因使用了微信外挂、非官方客户端或模拟器，被限制登录，请尽快卸载对应的非法软件。若后续仍继续使用将永久限制登录。如需继续使用，请轻触 “确定” 申请解除限制。",
            "该账号违反了《微信个人账号使用规范》，请轻触 “确定” 了解详情后，继续登录微信。",
            "你的账号可能有安全风险，为了你的账号安全，暂时无法在新设备登录，你可以在常用手机登录微信，或者轻触「了解详情」查看更多信息。",
            "账号状态异常，本次登录已失效。请尝试重新登录，并根据弹窗提示操作。"
        ).random()
        WeAlertDialogApi.showAlertDialog(context, message, onClickOk = {
            URL.toUri().openInSystem(context, false)
        })
    }
}
