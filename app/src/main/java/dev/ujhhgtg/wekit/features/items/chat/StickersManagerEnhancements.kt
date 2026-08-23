package dev.ujhhgtg.wekit.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.mm.api.IEmojiInfo
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.findViewsWhich
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.LinkedList

object StickersManagerEnhancements : SwitchFeature() {

    override val technicalId = "「添加的单个表情」管理器增强"
    override val nameRes = R.string.feature_stickers_manager_enhancements_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_stickers_manager_enhancements_description

    private const val TAG = "StickersManagerEnhancements"

    // Tag used to detect already-injected button groups (avoids duplicates on config changes)
    private const val INJECTED_TAG = "wekit_stickers_manager_btns"

    override fun onEnable() {
        "com.tencent.mm.plugin.emoji.ui.EmojiCustomUI".toClass().reflekt()
            .firstMethod { name = "initView" }.hookAfter {
                val activity = thisObject as Activity
                runCatching { injectButtons(activity) }
                    .onFailure { e -> WeLogger.e(TAG, "failed to inject sticker manager buttons", e) }
            }
    }

    private fun injectButtons(activity: Activity) {
        // ── locate views structurally ─────────────────────────────────────────────

        // The grid of emoji items — the only RecyclerView in this activity.
        val recyclerView = activity.reflekt().firstField {
            type = "androidx.recyclerview.widget.RecyclerView"
        }.get()!! as ViewGroup

        // The toolbar FrameLayout is the only FrameLayout that is a direct child of the
        // same ConstraintLayout parent as the RecyclerView.
        val constraintParent = recyclerView.parent as ViewGroup
        val bottomBar = constraintParent
            .findViewWhich { it is FrameLayout }!! as FrameLayout

        // Guard against duplicate injection (e.g. orientation change).
        if (bottomBar.findViewWithTag<View>(INJECTED_TAG) != null) return

        val textViews = bottomBar.findViewsWhich { it is TextView }.map { it as TextView }.toList()

        val moveTv = textViews.first()
        val deleteTv = textViews.last()

        // Capture the original "删除" text now so updateFooter can restore it for 0-selection.
        val baseDeleteText = deleteTv.text.toString()

        // ── build and insert the button group ────────────────────────────────────

        // Create a button styled to match 「移动」 (same text size, color, and padding).
        fun makeBtn(label: String): TextView = TextView(activity).apply {
            text = label
            @Suppress("DEPRECATION")
            textSize = (moveTv.textSize * 0.8 / activity.resources.displayMetrics.scaledDensity).toFloat()
            setTextColor(moveTv.currentTextColor)
            gravity = Gravity.CENTER_VERTICAL
            val hPad = moveTv.paddingStart
            setPadding(hPad, 0, hPad, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val btnSelectAll = makeBtn(activity.localizedChatString(R.string.chat_sticker_manager_select_all))
        val btnSelectNone = makeBtn(activity.localizedChatString(R.string.chat_sticker_manager_select_none))
        val btnInvert = makeBtn(activity.localizedChatString(R.string.chat_sticker_manager_invert))
        val btnExport = makeBtn(activity.localizedChatString(R.string.chat_sticker_manager_export))

        val parentContainer = moveTv.parent as ViewGroup
        val index = parentContainer.indexOfChild(moveTv)
        val originalLp = moveTv.layoutParams

        if (originalLp.width > 0) {
            originalLp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        }

        parentContainer.removeViewAt(index)

        val wrapper = LinearLayout(activity).apply {
            tag = INJECTED_TAG
            orientation = LinearLayout.HORIZONTAL
            layoutParams = originalLp
        }

        moveTv.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        wrapper.addView(moveTv)
        wrapper.addView(btnSelectAll)
        wrapper.addView(btnSelectNone)
        wrapper.addView(btnInvert)
        wrapper.addView(btnExport)

        parentContainer.addView(wrapper, index)

        // ── wire up click handlers ────────────────────────────────────────────────

        val activityRef = WeakReference(activity)
        val recyclerRef = WeakReference(recyclerView)
        val mpfRef = WeakReference(deleteTv)
        val mpgRef: WeakReference<View> = WeakReference(moveTv)

        btnSelectAll.setOnClickListener {
            val act = activityRef.get() ?: return@setOnClickListener
            runCatching {
                doSelectAll(act, recyclerRef, mpfRef, mpgRef, baseDeleteText)
            }.onFailure { e -> WeLogger.e(TAG, "failed to select all", e) }
        }

        btnSelectNone.setOnClickListener {
            val act = activityRef.get() ?: return@setOnClickListener
            runCatching {
                doSelectNone(act, recyclerRef, mpfRef, mpgRef, baseDeleteText)
            }.onFailure { e -> WeLogger.e(TAG, "failed to select none", e) }
        }

        btnInvert.setOnClickListener {
            val act = activityRef.get() ?: return@setOnClickListener
            runCatching {
                doInvert(act, recyclerRef, mpfRef, mpgRef, baseDeleteText)
            }.onFailure { e -> WeLogger.e(TAG, "failed to invert selection", e) }
        }

        btnExport.setOnClickListener {
            val act = activityRef.get() ?: return@setOnClickListener
            runCatching {
                doExport(act)
            }.onFailure { e -> WeLogger.e(TAG, "failed to export selection", e) }
        }
    }

    // ── selection helpers ─────────────────────────────────────────────────────────

    /**
     * Extract all EmojiInfo instances from the adapter's items ArrayList.
     *
     * The adapter (ax1.e / c22.e) extends tp.y / lr.z, which declares an ArrayList<qp.u0>.
     * qp.u0 is the base type; qp.g is an emoji item (has an IEmojiInfo field), and qp.z
     * is the add-button (no IEmojiInfo field). We filter to just the qp.g instances by
     * checking for an IEmojiInfo-typed field.
     */
    private fun adapterEmojiItems(adapter: Any): List<Any> {
        val items = adapter.reflekt()
            .firstField { type = ArrayList::class; superclass() }
            .get()!! as ArrayList<*>
        val iEmojiInfoClass = IEmojiInfo::class.java
        return items.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            // qp.g has exactly one IEmojiInfo-typed field
            val field = item.javaClass.declaredFields
                .firstOrNull { iEmojiInfoClass.isAssignableFrom(it.type) }
                ?: return@mapNotNull null
            field.isAccessible = true
            field.get(item) // returns the EmojiInfo instance
        }
    }

    /**
     * The activity's own selection ArrayList — the only ArrayList field declared directly on
     * EmojiCustomUI (not inherited). Reflekt defaults to declared-only (superclass = false).
     */
    @Suppress("UNCHECKED_CAST")
    private fun activitySelectionList(activity: Activity): ArrayList<Any> {
        return activity.reflekt()
            .firstField { type = ArrayList::class }.get()!! as ArrayList<Any>
    }

    /**
     * Sync the adapter's internal selected-md5 LinkedList from the new selection, then redraw.
     * Mirrors ax1.e.G() / c22.e.J().
     */
    @SuppressLint("NotifyDataSetChanged")
    @Suppress("UNCHECKED_CAST")
    private fun syncAdapter(adapter: Any, selection: ArrayList<Any>) {
        val linkedList = adapter.reflekt()
            .firstField { type = LinkedList::class }.get()!! as LinkedList<Any>
        linkedList.clear()
        selection.forEach { emojiInfo ->
            runCatching { (emojiInfo as IEmojiInfo).md5 }
                .getOrNull()
                ?.let { linkedList.add(it) }
        }
        adapter.reflekt().invokeMethod("notifyDataSetChanged", superclass = true)
    }

    /**
     * Update delete/move button states to mirror WeChat's k7() logic.
     * Uses the captured base text to avoid any further resource lookups.
     */
    @SuppressLint("SetTextI18n")
    private fun updateFooter(
        selection: ArrayList<Any>,
        mpfRef: WeakReference<TextView>,
        mpgRef: WeakReference<View>,
        baseDeleteText: String
    ) {
        val deleteTv = mpfRef.get() ?: return
        val moveTv = mpgRef.get() ?: return
        val size = selection.size
        if (size > 0) {
            deleteTv.text = "$baseDeleteText ($size)"
            deleteTv.isEnabled = true
            moveTv.isEnabled = true
        } else {
            deleteTv.text = baseDeleteText
            deleteTv.isEnabled = false
            moveTv.isEnabled = false
        }
    }

    // ── button actions ────────────────────────────────────────────────────────────

    private fun doSelectAll(
        activity: Activity,
        recyclerRef: WeakReference<ViewGroup>,
        mpfRef: WeakReference<TextView>,
        mpgRef: WeakReference<View>,
        baseDeleteText: String
    ) {
        val adapter = recyclerRef.get()?.reflekt()?.invokeMethod("getAdapter", superclass = true) ?: return
        val selection = activitySelectionList(activity)
        val allEmojis = adapterEmojiItems(adapter)

        selection.clear()
        allEmojis.forEach { if (!selection.contains(it)) selection.add(it) }

        syncAdapter(adapter, selection)
        updateFooter(selection, mpfRef, mpgRef, baseDeleteText)
    }

    private fun doSelectNone(
        activity: Activity,
        recyclerRef: WeakReference<ViewGroup>,
        mpfRef: WeakReference<TextView>,
        mpgRef: WeakReference<View>,
        baseDeleteText: String
    ) {
        val adapter = recyclerRef.get()?.reflekt()?.invokeMethod("getAdapter", superclass = true) ?: return
        val selection = activitySelectionList(activity)

        selection.clear()

        syncAdapter(adapter, selection)
        updateFooter(selection, mpfRef, mpgRef, baseDeleteText)
    }

    private fun doInvert(
        activity: Activity,
        recyclerRef: WeakReference<ViewGroup>,
        mpfRef: WeakReference<TextView>,
        mpgRef: WeakReference<View>,
        baseDeleteText: String
    ) {
        val adapter = recyclerRef.get()?.reflekt()?.invokeMethod("getAdapter", superclass = true) ?: return
        val selection = activitySelectionList(activity)
        val allEmojis = adapterEmojiItems(adapter)

        // Build an O(1) lookup of currently-selected md5s.
        val selectedMd5s: Set<String> = selection.mapNotNull {
            runCatching { (it as IEmojiInfo).md5 }.getOrNull()
        }.toHashSet()

        selection.clear()
        allEmojis.forEach { info ->
            val md5 = runCatching { (info as IEmojiInfo).md5 }.getOrNull()
            if (md5 != null && md5 !in selectedMd5s) selection.add(info)
        }

        syncAdapter(adapter, selection)
        updateFooter(selection, mpfRef, mpgRef, baseDeleteText)
    }

    private fun doExport(activity: Activity) {
        val selection = activitySelectionList(activity)

        // Build an O(1) lookup of currently-selected md5s.
        val selectedMd5s: Set<String> = selection.mapNotNull {
            runCatching { (it as IEmojiInfo).md5 }.getOrNull()
        }.toHashSet()

        val baseName = System.currentTimeMillis().toString()

        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend(localizedChatString(R.string.chat_sticker_manager_exporting))
            selectedMd5s.forEachIndexed { index, md5 ->
                WeMessageApi.saveStickerByMd5(md5, "sticker_${baseName}_$index.gif")
            }
            showToastSuspend(localizedChatQuantity(R.plurals.chat_sticker_manager_exported, selectedMd5s.size, selectedMd5s.size, "/sdcard/Download/WeKit"))
        }
    }
}
