package dev.ujhhgtg.wekit.features.items.shortvideos

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

object RemoveCommentAds : SwitchFeature(), IResolveDex {

    override val technicalId = "移除评论区广告"
    override val nameRes = R.string.feature_remove_comment_ads_name
    override val categoryIds = listOf(FeatureCategoryIds.CHANNELS)
    override val descriptionRes = R.string.feature_remove_comment_ads_description

    /**
     * 广告角标文案。8.0.65-8.0.76 评论区广告项右上角均为该文案
     * （资源字符串 ck3/mhz）。仅用于旧版转换器的兜底识别，
     * 广告位若下发自定义角标文案则可能识别不到。
     */
    private const val AD_TAG_TEXT = "广告"

    /**
     * 已被本特性隐藏的条目视图。RecyclerView 复用条目时会把它
     * 重新绑给普通评论，届时需要恢复可见，否则普通评论会被一起隐藏。
     * 注意 RecyclerView 不会折叠 GONE 条目（仍按原尺寸参与测量），
     * 所以隐藏时必须同时把 LayoutParams 高度压成 0，恢复时还原 wrap_content。
     */
    private val hiddenItemViews: MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap())

    /**
     * 新版评论广告转换器的绑定方法：`onBindViewHolder(SimpleViewHolder, FinderFeedComment,
     * int, int, boolean, List)`。真实类名与方法名都被混淆，但绑定方法体本身携带稳定的
     * 自追踪字符串 `com/tencent/mm/plugin/finder/convert/comment/FinderAdCommentConvert`
     * （8.0.65-8.0.76 一致）。该转换器绑定的条目全是广告；
     * 带该字符串的 6 参数 void 方法在全 dex 内唯一，可直接内联命中。
     */
    private val methodBindAdComment by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("com/tencent/mm/plugin/finder/convert/comment/FinderAdCommentConvert")
            returnType("void")
            paramCount(6)
        }
    }

    /**
     * 旧版评论转换器 `com.tencent.mm.plugin.finder.convert.FinderFeedCommentConvert`
     * 的绑定方法（同为混淆类，方法体携带同名自追踪字符串）。它在部分界面仍会渲染
     * 广告评论（广告标记 k1()），需要按可见角标区分普通评论与广告。
     * 真实 dex 中前两个参数（ViewHolder 与评论数据）的类名是混淆的、各版本不一致，
     * 因此前两位置使用通配符 null；第 3-6 位固定为 int/int/boolean/List，
     * 足以排除类内同带该字符串的 handleFollowPatBtn
     * （(ViewHolder, View, View, 评论, FinderJumpInfo, String)）。
     */
    private val methodBindOldComment by dexMethod(allowFailure = true) {
        matcher {
            usingEqStrings("com/tencent/mm/plugin/finder/convert/FinderFeedCommentConvert")
            returnType("void")
            paramTypes(null, null, "int", "int", "boolean", "java.util.List")
        }
    }

    override fun onEnable() {
        if (!methodBindAdComment.isPlaceholder) {
            methodBindAdComment.hookAfter {
                hideItem(args.getOrNull(0))
            }
        }
        if (!methodBindOldComment.isPlaceholder) {
            methodBindOldComment.hookAfter {
                val itemView = holderItemView(args.getOrNull(0))
                if (itemView == null) {
                    WeLogger.d(
                        "RemoveCommentAds",
                        "old convert: cannot get itemView from ${args.getOrNull(0)?.javaClass?.name}"
                    )
                    return@hookAfter
                }
                if (hasVisibleAdTag(itemView)) {
                    hideItemView(itemView)
                    WeLogger.d("RemoveCommentAds", "hidden old-convert comment ad")
                } else if (hiddenItemViews.remove(itemView)) {
                    restoreItemView(itemView)
                }
            }
        }
    }

    private fun hideItem(holder: Any?) {
        val itemView = holderItemView(holder)
        if (itemView == null) {
            WeLogger.d(
                "RemoveCommentAds",
                "ad convert: cannot get itemView from ${holder?.javaClass?.name}"
            )
            return
        }
        hideItemView(itemView)
        WeLogger.d("RemoveCommentAds", "hidden finder comment ad")
    }

    private fun hideItemView(itemView: View) {
        hiddenItemViews += itemView
        itemView.visibility = View.GONE
        val layoutParams = itemView.layoutParams
        if (layoutParams != null && layoutParams.height != 0) {
            layoutParams.height = 0
            itemView.layoutParams = layoutParams
        }
    }

    private fun restoreItemView(itemView: View) {
        itemView.visibility = View.VISIBLE
        val layoutParams = itemView.layoutParams
        if (layoutParams != null && layoutParams.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
            itemView.layoutParams = layoutParams
        }
    }

    private fun holderItemView(holder: Any?): View? {
        if (holder == null) return null
        return runCatching {
            holder.reflekt()
                .firstField { name = "itemView"; superclass(true) }
                .get() as? View
        }.getOrNull()
    }

    /** 条目内是否存在可见的「广告」角标（用于区分旧版转换器中的广告评论）。 */
    private fun hasVisibleAdTag(root: View): Boolean {
        if (root !is ViewGroup) return false
        val stack = ArrayDeque<View>()
        stack.add(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            if (view is TextView &&
                view.text?.toString() == AD_TAG_TEXT &&
                view.isVisibleInHierarchy()
            ) {
                return true
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) stack.add(view.getChildAt(i))
            }
        }
        return false
    }

    /** 从自身到根逐级检查，避免命中父容器已 GONE 的隐藏角标。 */
    private fun View.isVisibleInHierarchy(): Boolean {
        var current: View? = this
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            current = current.parent as? View
        }
        return true
    }
}
