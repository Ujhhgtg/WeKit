package dev.ujhhgtg.wekit.features.items.beautify

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toDrawable
import dev.ujhhgtg.reflekt.reflected.ReflectedField
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.items.chat.ConversationGrouping
import dev.ujhhgtg.wekit.features.items.chat.SwipeConversationOperations
import dev.ujhhgtg.wekit.data.KvStore.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.content.m3.SegmentedColumn
import dev.ujhhgtg.wekit.ui.content.m3.SwitchWidget
import dev.ujhhgtg.wekit.ui.utils.dpToPx
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import dev.ujhhgtg.wekit.utils.android.runOnUiThread
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

object BeautifyConversationList : ClickableFeature() {

    override val technicalId = "美化对话列表"
    override val nameRes = R.string.feature_beautify_conversation_list_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT, FeatureCategoryIds.BEAUTIFY)
    override val descriptionRes = R.string.feature_beautify_conversation_list_description

    private const val TAG = "BeautifyConversationList"
    private const val PLACED_TOP_BIT = 0x4000000000000000L

    private var layoutEnabled by prefOption("beautify_conversation_list_layout_enabled", true)
    private var highlightUnreadEnabled by prefOption("beautify_conversation_list_highlight_unread", false)
    private var hideDividersEnabled by prefOption("beautify_conversation_list_hide_dividers", true)

    val isLayoutBeautificationEnabled: Boolean
        get() = isEnabled && layoutEnabled

    /** Surface geometry in row coordinates, also used to clip the swipe menu's child views. */
    data class IslandShape(
        val horizontalInset: Int,
        val topInset: Int,
        val radius: Float,
        val first: Boolean,
        val last: Boolean,
    )

    fun islandShape(row: View): IslandShape? =
        if (isLayoutBeautificationEnabled) rows[row]?.island?.shape else null

    private class BackgroundState(var original: Drawable?, val applied: Drawable)

    private class RowState(row: View) {
        val paddingLeft = row.paddingLeft
        val paddingTop = row.paddingTop
        val paddingRight = row.paddingRight
        val paddingBottom = row.paddingBottom
        var gapTop = 0
        var island: IslandBackground? = null
    }

    // Values never retain their View keys, so recycled rows and old activities can be collected.
    private val backgrounds = WeakHashMap<View, BackgroundState>()
    private val rows = WeakHashMap<View, RowState>()
    private val footerColors = WeakHashMap<View, Int>()

    private sealed interface UnreadAccessor {
        data class Field(val get: (Any) -> Any?) : UnreadAccessor
        data object Missing : UnreadAccessor
    }

    private val unreadAccessorCache = ConcurrentHashMap<Class<*>, UnreadAccessor>()
    private val unreadFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()
    private val pinFlagFields = ConcurrentHashMap<Class<*>, ReflectedField<Any>>()

    private val bindListener = WeConversationListViewApi.IBindViewListener { _, row, conversation, context ->
        bindRow(row, conversation, context)
    }

    override fun onEnable() {
        LinearLayout::class.reflekt().firstMethod {
            name = "onMeasure"
            parameters(Int::class, Int::class)
        }.hookBefore {
            val state = rows[thisObject as View] ?: return@hookBefore
            val gap = state.gapTop
            if (gap == 0) return@hookBefore
            val heightSpec = args[1] as Int
            val height = View.MeasureSpec.getSize(heightSpec)
            if (height > 0 && View.MeasureSpec.getMode(heightSpec) == View.MeasureSpec.EXACTLY) {
                // ConversationFolderItemView replaces its incoming height with the holder's
                // fixed height before calling this superclass. Add the gap here so the native
                // children still receive their full height after subtracting our extra padding.
                args[1] = View.MeasureSpec.makeMeasureSpec(
                    height + gap, View.MeasureSpec.EXACTLY,
                )
            }
        }
        // WeChat also replaces row backgrounds after its click animation, outside adapter binding.
        // Keep our surface installed while remembering the host's latest background for restoration.
        View::class.reflekt().firstMethod {
            name = "setBackgroundDrawable"
            parameters(Drawable::class)
        }.hookBefore {
            val state = backgrounds[thisObject as View] ?: return@hookBefore
            if (args[0] !== state.applied) {
                state.original = args[0] as Drawable?
                args[0] = state.applied
            }
        }
        WeConversationListViewApi.addListener(bindListener)
        refreshAppearance()
    }

    override fun onDisable() {
        WeConversationListViewApi.removeListener(bindListener)
        runOnUiThread {
            restoreAppearance()
            WeConversationListViewApi.removeDividerOwner(this)
        }
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && layoutEnabled) ConversationGrouping.showFloatingTabsRecommendation(context)
        return true
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var layout by remember { mutableStateOf(layoutEnabled) }
            var highlightUnread by remember { mutableStateOf(highlightUnreadEnabled) }
            var hideDividers by remember { mutableStateOf(hideDividersEnabled) }

            AlertDialogContent(
                title = { Text(stringResource(R.string.beautify_conversation_list_title)) },
                text = {
                    SegmentedColumn(contentPadding = PaddingValues(0.dp)) {
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.beautify_conversation_layout),
                                description = stringResource(R.string.beautify_conversation_layout_summary),
                                checked = layout,
                                onCheckedChange = {
                                    layout = it
                                    layoutEnabled = it
                                    refreshAppearance()
                                    if (it && isEnabled) ConversationGrouping.showFloatingTabsRecommendation(context)
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.beautify_conversation_highlight_unread),
                                checked = highlightUnread,
                                onCheckedChange = {
                                    highlightUnread = it
                                    highlightUnreadEnabled = it
                                    refreshAppearance()
                                },
                            )
                        }
                        item {
                            SwitchWidget(
                                title = stringResource(R.string.beautify_conversation_hide_dividers),
                                checked = hideDividers,
                                onCheckedChange = {
                                    hideDividers = it
                                    hideDividersEnabled = it
                                    refreshAppearance()
                                },
                            )
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
                },
            )
        }
    }

    private fun refreshAppearance() {
        runOnUiThread {
            restoreAppearance()
            // Islands supply their own inset, one-pixel separators. Native separators would
            // leave seams between the rows that make up the continuous surface.
            WeConversationListViewApi.setDividerHidden(
                this, isEnabled && (layoutEnabled || hideDividersEnabled),
            )
        }
    }

    private fun bindRow(
        row: View,
        conversation: Any,
        context: WeConversationListViewApi.BindContext,
    ) {
        val unread = highlightUnreadEnabled && isUnread(conversation)
        if (!isLayoutBeautificationEnabled) {
            if (unread) {
                val previous = backgrounds[row]
                val original = if (previous != null) previous.original else row.background
                val highlight = (if (row.context.isDarkMode) 0x243CB371 else 0x143CB371).toDrawable()
                replaceBackground(row, LayerDrawable(arrayOf(original ?: ColorDrawable(), highlight)))
            } else {
                restoreBackground(row)
            }
            return
        }

        val state = rows.getOrPut(row) { RowState(row) }
        val island = state.island ?: IslandBackground(row.context).also { state.island = it }
        val pinned = isPinned(conversation)
        val gapBefore = context.previousConversation?.let { isPinned(it) != pinned } == true
        val gapAfter = context.nextConversation?.let { isPinned(it) != pinned } == true
        state.gapTop = if (gapBefore) island.islandGap else 0
        island.update(
            dark = row.context.isDarkMode,
            first = context.position == 0 || gapBefore,
            last = context.position == context.itemCount - 1 || gapAfter,
            gapBefore = gapBefore,
            unread = unread,
            hideDivider = hideDividersEnabled,
        )
        replaceBackground(row, island.drawable)
        val inset = 12.dpToPx(row.context)
        // The background's inter-island gap occupies added row space, not the content's height.
        // Recycled boundary rows reset both their padding and measurement compensation on bind.
        row.setPadding(
            state.paddingLeft + inset, state.paddingTop + state.gapTop,
            state.paddingRight + inset, state.paddingBottom,
        )
        SwipeConversationOperations.refreshIslandShape(row)

        val content = row as ViewGroup
        for (index in 0 until content.childCount) {
            val child = content.getChildAt(index)
            if (child.background != null) replaceBackground(child, Color.TRANSPARENT.toDrawable())
        }
        // The folded-pinned banner has a second background behind its label and arrow.
        val foldedBanner = content.getChildAt(1) as? ViewGroup
        val foldedLabel = foldedBanner?.getChildAt(1)
        if (foldedLabel?.background != null) {
            replaceBackground(foldedLabel, Color.TRANSPARENT.toDrawable())
        }

        val container = WeConversationListViewApi.currentContainer() ?: return
        paintEmptyFooters(container)
    }

    private fun paintEmptyFooters(container: View) {
        // The list itself must keep WeChat's background and its recent-page alpha animation.
        val color = if (container.context.isDarkMode) 0xFF101012.toInt() else 0xFFF0F0F3.toInt()
        if (footerColors[container] == color) return

        val footers = WeConversationListViewApi.emptyFooterViews(container)
        if (footers.isEmpty()) return
        fun paint(view: View) {
            val applied = backgrounds[view]?.applied as LayerDrawable?
            if (applied == null) {
                // View.setBackgroundColor mutates a bare ColorDrawable in place. A one-layer
                // wrapper routes host recoloring through our existing background hook instead,
                // keeping the filler styled without polling or another global hook.
                replaceBackground(view, LayerDrawable(arrayOf(color.toDrawable())))
            } else {
                (applied.getDrawable(0) as ColorDrawable).color = color
            }
        }
        for (footer in footers) {
            paint(footer)
            paint(footer.getChildAt(0))
        }
        footerColors[container] = color
    }

    private fun replaceBackground(view: View, drawable: Drawable) {
        val previous = backgrounds.remove(view)
        val original = if (previous != null) previous.original else view.background
        backgrounds[view] = BackgroundState(original, drawable)
        if (view.background !== drawable) view.background = drawable
    }

    private fun restoreBackground(view: View) {
        val state = backgrounds.remove(view) ?: return
        view.background = state.original
    }

    private fun restoreAppearance() {
        footerColors.clear()
        val snapshots = backgrounds.entries.map { it.key to it.value.original }
        backgrounds.clear()
        snapshots.forEach { (view, background) -> view.background = background }
        val rowSnapshots = rows.entries.map { it.key to it.value }
        rows.clear()
        rowSnapshots.forEach { (view, state) ->
            view.setPadding(state.paddingLeft, state.paddingTop, state.paddingRight, state.paddingBottom)
            SwipeConversationOperations.refreshIslandShape(view)
        }
    }

    /** Pinned and ordinary conversations form separate islands in the visible adapter order. */
    private class IslandBackground(context: Context) {
        private val surface = GradientDrawable()
        private val mask = GradientDrawable().apply { setColor(Color.WHITE) }
        private val divider = ColorDrawable()
        private val page = ColorDrawable()
        private val radius = 20.dpToPx(context).toFloat()
        private val horizontalInset = 12.dpToPx(context)
        val islandGap = 8.dpToPx(context)
        private var hasGapBefore = false
        var shape: IslandShape? = null
            private set
        private val surfaceLayers = LayerDrawable(arrayOf(surface, divider)).apply {
            setLayerHeight(1, 1)
            setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
            // Host nickname column starts at 16dp + 56dp avatar slot + 4dp gap.
            setLayerInsetRelative(1, 76.dpToPx(context), 0, 0, 0)
        }
        private val ripple = RippleDrawable(ColorStateList.valueOf(0), surfaceLayers, mask)
        val drawable = LayerDrawable(arrayOf(
            page,
            InsetDrawable(ripple, horizontalInset, 0, horizontalInset, 0),
        ))

        fun update(
            dark: Boolean,
            first: Boolean,
            last: Boolean,
            gapBefore: Boolean,
            unread: Boolean,
            hideDivider: Boolean,
        ) {
            shape = IslandShape(horizontalInset, if (gapBefore) islandGap else 0, radius, first, last)
            if (hasGapBefore != gapBefore) {
                // Put the full gap before the new island. The preceding pinned row keeps its
                // native height, which WeChat also uses as the start of its collapse animation.
                drawable.setDrawable(1, InsetDrawable(
                    ripple,
                    horizontalInset, if (gapBefore) islandGap else 0,
                    horizontalInset, 0,
                ))
                hasGapBefore = gapBefore
            }
            val top = if (first) radius else 0f
            val bottom = if (last) radius else 0f
            val corners = floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom)
            surface.cornerRadii = corners
            mask.cornerRadii = corners
            page.color = if (dark) 0xFF101012.toInt() else 0xFFF0F0F3.toInt()
            surface.setColor(when {
                unread && dark -> 0xFF20372C.toInt()
                unread -> 0xFFECF6EF.toInt()
                dark -> 0xFF1C1C1E.toInt()
                else -> Color.WHITE
            })
            divider.color = when {
                last || hideDivider -> Color.TRANSPARENT
                dark -> 0xFF303033.toInt()
                else -> 0xFFEDEDEF.toInt()
            }
            ripple.setColor(ColorStateList.valueOf(if (dark) 0x20FFFFFF else 0x14000000))
        }
    }

    private fun isPinned(conversation: Any): Boolean {
        val field = pinFlagFields.computeIfAbsent(conversation.javaClass) { modelClass ->
            @Suppress("UNCHECKED_CAST")
            (modelClass.reflekt().firstField {
                name = "field_flag"
                superclass()
            } as ReflectedField<Any>)
        }
        // Read the bound model every time so pin/unpin updates need no storage query or cache reset.
        return field.get(conversation) as Long and PLACED_TOP_BIT != 0L
    }

    private fun isUnread(conversation: Any): Boolean {
        val modelClass = conversation.javaClass
        val accessor = unreadAccessorCache.computeIfAbsent(modelClass, ::findUnreadAccessor)
        if (accessor === UnreadAccessor.Missing) return false
        return try {
            val unreadCount = ((accessor as UnreadAccessor.Field).get(conversation) as? Number)
                ?.toInt() ?: return false
            unreadCount > 0
        } catch (error: Exception) {
            logUnreadFailureOnce(modelClass, "could not read field_unReadCount", error)
            false
        }
    }

    private fun findUnreadAccessor(modelClass: Class<*>): UnreadAccessor = try {
        val field = modelClass.reflekt().firstFieldOrNull {
            name = "field_unReadCount"
            superclass()
        } ?: run {
            logUnreadFailureOnce(modelClass, "field_unReadCount is absent", null)
            return UnreadAccessor.Missing
        }
        @Suppress("UNCHECKED_CAST")
        val accessor = field as ReflectedField<Any>
        UnreadAccessor.Field { conversation -> accessor.get(conversation) }
    } catch (error: Exception) {
        logUnreadFailureOnce(modelClass, "could not resolve field_unReadCount", error)
        UnreadAccessor.Missing
    }

    private fun logUnreadFailureOnce(modelClass: Class<*>, message: String, error: Exception?) {
        if (!unreadFailuresLogged.add(modelClass)) return
        if (error == null) WeLogger.w(TAG, "$message on ${modelClass.name}")
        else WeLogger.w(TAG, "$message on ${modelClass.name}", error)
    }

}
