package dev.ujhhgtg.wekit.features.api.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContextWrapper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.core.ApiFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import java.util.concurrent.CopyOnWriteArrayList

object WeContactHeaderApi : ApiFeature(), IResolveDex {

    override val technicalId = "联系人详情头部扩展"
    override val nameRes = R.string.feature_we_contact_header_api_name
    override val categoryIds = listOf(FeatureCategoryIds.API)

    fun interface Provider {
        /** Return null when this profile should not have an extra row. */
        fun getHeaderText(activity: Activity): String?
    }

    private val providers = CopyOnWriteArrayList<Provider>()
    private const val ROW_TAG = "wekit_contact_header_row"

    private val bindHeader by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.plugin.profile.ui.NormalProfileHeaderPreference"
            paramTypes(View::class.java)
            returnType = "void"
            usingStrings("[onBindView] never attach!")
        }
    }

    fun addProvider(provider: Provider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: Provider) {
        providers.remove(provider)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onEnable() {
        bindHeader.hookAfter {
            val root = args[0] as View
            // The host only creates its ViewHolder after the preference is attached.
            if (root.tag == null) return@hookAfter
            root.findViewsWhich { it.tag == ROW_TAG }.toList().forEach {
                (it.parent as ViewGroup).removeView(it)
            }
            if (providers.isEmpty()) return@hookAfter

            var context = root.context
            while (context is ContextWrapper && context !is Activity) context = context.baseContext
            val activity = context as Activity
            val header = thisObject as View.OnLongClickListener

            // The native ViewHolder binds the header itself to the nickname, alias,
            // WeChat number and location TextViews. These share one vertical container.
            // Use that actual listener identity, not translated text or obfuscated IDs.
            val templates = root.findViewsWhich { view ->
                view is TextView && view.reflekt().getField("mListenerInfo", true)?.let {
                    it.reflekt().getField("mOnLongClickListener") === header
                } == true
            }.map { it as TextView }.toList()
            val parent = templates.map { it.parent }.distinct().single() as LinearLayout
            check(parent.orientation == LinearLayout.VERTICAL)
            val template = templates.first()
            val touch = template.reflekt().getField("mListenerInfo", true)!!
                .reflekt().getField("mOnTouchListener") as View.OnTouchListener

            for (provider in providers) {
                val headerText = provider.getHeaderText(activity) ?: continue
                val row = TextView(template.context).apply {
                    id = View.generateViewId()
                    tag = ROW_TAG
                    layoutParams = LinearLayout.LayoutParams(template.layoutParams as LinearLayout.LayoutParams)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, template.textSize)
                    setTextColor(template.textColors)
                    typeface = template.typeface
                    gravity = template.gravity
                    includeFontPadding = template.includeFontPadding
                    textDirection = template.textDirection
                    setPaddingRelative(template.paddingStart, template.paddingTop, template.paddingEnd, template.paddingBottom)
                    setLineSpacing(template.lineSpacingExtra, template.lineSpacingMultiplier)
                    text = headerText
                    // This also sets the native touch-coordinate tag used to anchor its popup.
                    setOnTouchListener(touch)
                    setOnLongClickListener {
                        val ownId = id
                        try {
                            // Native onLongClick dispatches by the source row's ID, but
                            // reads/highlights this TextView and restores it on dismissal.
                            id = template.id
                            header.onLongClick(this)
                        } finally {
                            id = ownId
                        }
                    }
                }
                parent.addView(row)
            }
        }
    }
}
