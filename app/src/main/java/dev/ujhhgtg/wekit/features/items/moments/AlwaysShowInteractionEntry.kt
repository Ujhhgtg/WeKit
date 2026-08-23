package dev.ujhhgtg.wekit.features.items.moments

import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.android.Intent
import dev.ujhhgtg.wekit.utils.android.baseActivity

/**
 * 朋友圈信息流顶部的互动消息气泡由 ImproveHeaderUIC.onNotifyChange 驱动:
 * 有未读互动时显示「N 条新消息」, 只有已读互动时显示「朋友的互动消息」,
 * 一条互动都没有时直接隐藏。这里把最后一种情况也改成显示。
 */
object AlwaysShowInteractionEntry : SwitchFeature(), IResolveDex {

    override val technicalId = "常驻互动消息入口"
    override val nameRes = R.string.feature_always_show_interaction_entry_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_always_show_interaction_entry_description

    private const val HEADER_UIC =
        "com.tencent.mm.plugin.sns.ui.improve.component.header.ImproveHeaderUIC"

    private const val MSG_UI_WITH_ALL = "${PackageNames.WECHAT}.plugin.sns.ui.SnsMsgUIWithAll"

    /**
     * 上一次 onNotifyChange 的结论: 一条互动消息都没有。
     * onNotifyChange$1 只按游标条数分流, count > 0 走 $1$1, count == 0 走 $1$4,
     * 因此这两个分支就是完整的信号。
     */
    @Volatile
    private var noInteractionMessages = false

    /** ImproveHeaderUIC$onNotifyChange$1$1 — 有互动消息, 显示气泡并写入文案 */
    private val methodShowNotifyBar by dexMethod {
        matcher {
            usingEqStrings(
                "invokeSuspend",
                $$"com.tencent.mm.plugin.sns.ui.improve.component.header.ImproveHeaderUIC$onNotifyChange$1$1"
            )
        }
    }

    /** ImproveHeaderUIC$onNotifyChange$1$4 — 没有互动消息, 隐藏气泡 */
    private val methodHideNotifyBar by dexMethod {
        matcher {
            usingEqStrings(
                "invokeSuspend",
                $$"com.tencent.mm.plugin.sns.ui.improve.component.header.ImproveHeaderUIC$onNotifyChange$1$4"
            )
        }
    }

    private val methodGetNotifyLayout by dexMethod {
        matcher { usingEqStrings($$"access$getMsgNotifyLayout", HEADER_UIC) }
    }

    private val methodGetNotifyContent by dexMethod {
        matcher { usingEqStrings($$"access$getMsgNotifyContent", HEADER_UIC) }
    }

    private val methodGetNotifyImg by dexMethod {
        matcher { usingEqStrings($$"access$getMsgNotifyImg", HEADER_UIC) }
    }

    /** ImproveHeaderUIC.gotoNotifyMsgUI() — 点击入口后的跳转 */
    private val methodGotoNotifyMsgUI by dexMethod {
        matcher {
            paramCount(0)
            returnType(Void.TYPE)
            usingEqStrings("gotoNotifyMsgUI", HEADER_UIC)
        }
    }

    private val headerUicClass: Class<*>
        get() = methodGetNotifyLayout.method.declaringClass

    override fun onEnable() {
        methodHideNotifyBar.hookAfter {
            noInteractionMessages = true
            val headerUic = extractHeaderUic(thisObject ?: return@hookAfter) ?: return@hookAfter
            notifyLayout(headerUic).isVisible = true
            notifyContent(headerUic).text = localizedMomentsString(R.string.moments_interaction_idle)
            // 一条互动消息都没有, 没有可展示的头像
            notifyImg(headerUic).isVisible = false
        }

        methodShowNotifyBar.hookAfter {
            noInteractionMessages = false
            val headerUic = extractHeaderUic(thisObject ?: return@hookAfter) ?: return@hookAfter
            // 恢复上面隐藏掉的头像
            notifyImg(headerUic).isVisible = true
        }

        // 微信默认进「最近消息」(SnsMsgUIWithRelevance), 一条互动都没有时那页是空的。
        // 这种情况直接进它底部「全部互动消息」按钮打开的 SnsMsgUIWithAll。
        methodGotoNotifyMsgUI.hookBefore {
            if (!noInteractionMessages) return@hookBefore
            val headerUic = thisObject ?: return@hookBefore
            val activity = notifyLayout(headerUic).context.baseActivity ?: return@hookBefore

            activity.startActivity(Intent {
                setClassName(PackageNames.WECHAT, MSG_UI_WITH_ALL)
                putExtra("sns_msg_force_show_all", true)
                putExtra("sns_msg_comment_list_scene", 1)
                putExtra("sns_msg_can_update_to_read", false)
            })
            // 跳过原方法, 它还会把入口气泡藏起来
            result = null
        }
    }

    /** 协程闭包持有外层 ImproveHeaderUIC 的引用 */
    private fun extractHeaderUic(closure: Any): Any? =
        closure.reflekt().firstFieldOrNull { type = headerUicClass }?.get()

    private fun notifyLayout(headerUic: Any) =
        methodGetNotifyLayout.method.invoke(null, headerUic) as View

    private fun notifyContent(headerUic: Any) =
        methodGetNotifyContent.method.invoke(null, headerUic) as TextView

    private fun notifyImg(headerUic: Any) =
        methodGetNotifyImg.method.invoke(null, headerUic) as View
}
