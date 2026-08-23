package dev.ujhhgtg.wekit.features.items.moments

import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.constants.PackageNames
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.Intent
import dev.ujhhgtg.wekit.utils.android.baseActivity
import java.lang.reflect.Method

object OpenDetailsOnItemClick : SwitchFeature(), IResolveDex {

    override val technicalId = "点击空白处查看详情"
    override val nameRes = R.string.feature_open_details_on_item_click_name
    override val categoryIds = listOf(FeatureCategoryIds.MOMENTS)
    override val descriptionRes = R.string.feature_open_details_on_item_click_description

    private const val TAG = "OpenDetailsOnItemClick"
    private const val DETAIL_UI = "${PackageNames.WECHAT}.plugin.sns.ui.SnsCommentDetailUI"

    /**
     * 朋友圈条目根 View (`ImproveTimelineItemClick`, 一个 LinearLayout 子类)。
     * 头像/昵称/图片/视频/评论按钮/地点/卡片等子 View 都注册了自己的点击监听, 正文是 longClickable,
     * 都会自行消费触摸; 只有它们都没吃掉的"空白"触摸才会冒泡到根 View。
     */
    private val classImproveTimelineItem by dexClass {
        matcher {
            usingEqStrings("com.tencent.mm.plugin.sns.ui.improve.item.click.ImproveTimelineItemClick")
        }
    }

    /**
     * `WxRecyclerAdapter$bindViewListener$2.onClick` — 微信自己挂在条目根 View 上的点击监听。
     *
     * 不能自己往根 View 上 setOnClickListener: `WxRecyclerAdapter.onBindViewHolder` 的最后一步
     * 是 `bindViewListener(itemView, item, position)`, 它每次绑定都会无条件重装 touch/click/longClick
     * 三个监听器, 把之前设的全部覆盖掉。所以直接 hook 它装上去的那个监听器, 在它派发完之后接着走。
     */
    private val methodItemClickListenerOnClick by dexMethod {
        matcher {
            name = "onClick"
            paramTypes("android.view.View")
            returnType(Void.TYPE)
            usingEqStrings($$"com/tencent/mm/view/recyclerview/WxRecyclerAdapter$bindViewListener$2")
        }
    }

    /** `ImproveSnsInfo.getLocalIDString()` — 返回 `sns_table_<rowid>` (广告为 `ad_table_`) */
    private val methodGetLocalIdString by dexMethod {
        matcher {
            paramCount(0)
            returnType("java.lang.String")
            usingEqStrings(
                "getLocalIDString",
                "com.tencent.mm.plugin.sns.ui.improve.model.ImproveSnsInfo"
            )
        }
    }

    private lateinit var getImproveListItemMethod: Method
    private lateinit var getImproveSnsInfoMethod: Method
    private lateinit var getUserNameMethod: Method

    override fun onEnable() {
        // 该监听器是所有 WxRecyclerAdapter 列表共用的, 所以要按条目类型过滤
        methodItemClickListenerOnClick.hookAfter {
            val itemView = args.getOrNull(0) as? View ?: return@hookAfter
            if (!classImproveTimelineItem.clazz.isInstance(itemView)) return@hookAfter
            openDetails(itemView)
        }
    }

    private fun openDetails(itemView: View) {
        val activity = itemView.context.baseActivity ?: return
        val improveSnsInfoClass = methodGetLocalIdString.method.declaringClass

        if (!::getImproveListItemMethod.isInitialized) {
            getImproveListItemMethod = itemView.reflekt()
                .firstMethod {
                    name = "getImproveListItem"
                    parameters()
                    superclass()
                }.self.makeAccessible()
        }
        val listItem = getImproveListItemMethod.invoke(itemView) ?: return

        if (!::getImproveSnsInfoMethod.isInitialized) {
            getImproveSnsInfoMethod = listItem.reflekt()
                .firstMethod {
                    parameters()
                    superclass()
                    returnType { it isSubclassOf improveSnsInfoClass }
                }.self.makeAccessible()
        }
        val snsInfo = getImproveSnsInfoMethod.invoke(listItem) ?: return

        val localId = methodGetLocalIdString.method.invoke(snsInfo) as? String
        if (localId.isNullOrBlank()) {
            WeLogger.w(TAG, "no local id on ${snsInfo.javaClass.name}, not opening details")
            return
        }

        if (!::getUserNameMethod.isInitialized) {
            getUserNameMethod = snsInfo.reflekt()
                .firstMethod {
                    name = "getUserName"
                    parameters()
                    superclass()
                }.self.makeAccessible()
        }

        activity.startActivity(Intent {
            setClassName(PackageNames.WECHAT, DETAIL_UI)
            putExtra("INTENT_TALKER", getUserNameMethod.invoke(snsInfo) as? String)
            putExtra("INTENT_SNS_LOCAL_ID", localId)
            putExtra("INTENT_FROMGALLERY", false)
            putExtra("INTENT_NEED_RPT_FEED", true)
        })
    }
}
