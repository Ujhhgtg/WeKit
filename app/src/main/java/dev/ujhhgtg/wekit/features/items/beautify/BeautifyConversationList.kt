package dev.ujhhgtg.wekit.features.items.beautify

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.reflected.ReflectedField
import dev.ujhhgtg.wekit.features.api.ui.WeConversationListViewApi
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.preferences.WePrefs.Companion.prefOption
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

@Feature(
    name = "美化对话列表",
    categories = ["聊天", "界面美化"],
    description = "为主页会话列表提供卡片布局、圆角头像、未读突出和分隔线设置",
)
object BeautifyConversationList : ClickableFeature() {

    private const val TAG = "BeautifyConversationList"

    private var presetName by prefOption(
        "beautify_conversation_list_preset",
        ConversationListPreset.COMFORT_CARD.name,
    )
    private var roundAvatarsEnabled by prefOption("beautify_conversation_list_round_avatars", true)
    private var highlightUnreadEnabled by prefOption("beautify_conversation_list_highlight_unread", true)
    private var hideDividersEnabled by prefOption("beautify_conversation_list_hide_dividers", true)

    private val selectedPreset: ConversationListPreset
        get() = ConversationListPreset.entries.firstOrNull { it.name == presetName }
            ?: ConversationListPreset.COMFORT_CARD

    private data class AvatarVisualState(
        val view: WeakReference<ImageView>,
        val outlineProvider: ViewOutlineProvider,
        val clipToOutline: Boolean,
        val moduleOutlineProvider: ViewOutlineProvider,
    )

    private data class RowVisualState(
        var baselineBackground: Drawable?,
        var baselinePaddingLeft: Int,
        var baselinePaddingTop: Int,
        var baselinePaddingRight: Int,
        var baselinePaddingBottom: Int,
        var moduleBackground: Drawable? = null,
        var avatar: AvatarVisualState? = null,
    )

    private sealed interface UnreadAccessor {
        data class Field(val get: (Any) -> Any?) : UnreadAccessor
        data object Missing : UnreadAccessor
    }

    private val rowStates = WeakHashMap<View, RowVisualState>()
    private val unreadAccessorCache = ConcurrentHashMap<Class<*>, UnreadAccessor>()
    private val unreadFailuresLogged = ConcurrentHashMap.newKeySet<Class<*>>()

    private val bindListener = WeConversationListViewApi.IBindViewListener { _, row, conversation ->
        applyRowVisuals(row, conversation)
    }

    override fun onEnable() {
        WeConversationListViewApi.addListener(bindListener)
        updateDividerRequest()
        WeConversationListViewApi.refresh()
    }

    override fun onDisable() {
        WeConversationListViewApi.removeListener(bindListener)
        WeConversationListViewApi.removeDividerOwner(this)
        rowStates.clear()
        unreadAccessorCache.clear()
        unreadFailuresLogged.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var draftPreset by remember { mutableStateOf(selectedPreset) }
            var draftRoundAvatars by remember { mutableStateOf(roundAvatarsEnabled) }
            var draftHighlightUnread by remember { mutableStateOf(highlightUnreadEnabled) }
            var draftHideDividers by remember { mutableStateOf(hideDividersEnabled) }

            AlertDialogContent(
                title = { Text("美化对话列表") },
                text = {
                    DefaultColumn {
                        ConversationListPreset.entries.forEach { preset ->
                            val label = when (preset) {
                                ConversationListPreset.COMFORT_CARD -> "舒适卡片"
                                ConversationListPreset.COMPACT_ROUNDED -> "紧凑圆角"
                                ConversationListPreset.MINIMAL_LIST -> "简洁列表"
                            }
                            ListItem(
                                modifier = Modifier.clickable { draftPreset = preset },
                                headlineContent = { Text(label) },
                                trailingContent = {
                                    RadioButton(
                                        selected = draftPreset == preset,
                                        onClick = { draftPreset = preset },
                                    )
                                },
                            )
                        }
                        ListItem(
                            modifier = Modifier.clickable { draftRoundAvatars = !draftRoundAvatars },
                            headlineContent = { Text("圆角头像") },
                            trailingContent = {
                                Switch(
                                    checked = draftRoundAvatars,
                                    onCheckedChange = { draftRoundAvatars = it },
                                )
                            },
                        )
                        ListItem(
                            modifier = Modifier.clickable { draftHighlightUnread = !draftHighlightUnread },
                            headlineContent = { Text("突出未读会话") },
                            trailingContent = {
                                Switch(
                                    checked = draftHighlightUnread,
                                    onCheckedChange = { draftHighlightUnread = it },
                                )
                            },
                        )
                        ListItem(
                            modifier = Modifier.clickable { draftHideDividers = !draftHideDividers },
                            headlineContent = { Text("隐藏分隔线") },
                            trailingContent = {
                                Switch(
                                    checked = draftHideDividers,
                                    onCheckedChange = { draftHideDividers = it },
                                )
                            },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(onClick = {
                        presetName = draftPreset.name
                        roundAvatarsEnabled = draftRoundAvatars
                        highlightUnreadEnabled = draftHighlightUnread
                        hideDividersEnabled = draftHideDividers
                        updateDividerRequest()
                        WeConversationListViewApi.refresh()
                        onDismiss()
                    }) {
                        Text("确定")
                    }
                },
            )
        }
    }

    private fun applyRowVisuals(row: View, conversation: Any) {
        val state = rowStates.getOrPut(row) {
            RowVisualState(
                baselineBackground = row.background,
                baselinePaddingLeft = row.paddingLeft,
                baselinePaddingTop = row.paddingTop,
                baselinePaddingRight = row.paddingRight,
                baselinePaddingBottom = row.paddingBottom,
            )
        }
        restoreRowBaseline(row, state)
        clearAvatarState(state)

        val preset = selectedPreset
        val unread = highlightUnreadEnabled && isUnread(conversation)
        val palette = conversationListPalette(preset, row.context.isDarkMode)
        val background = buildRowBackground(row.context, preset, palette, unread)
        state.moduleBackground = background
        row.background = background
        row.setPadding(
            state.baselinePaddingLeft,
            state.baselinePaddingTop,
            state.baselinePaddingRight,
            state.baselinePaddingBottom,
        )

        if (roundAvatarsEnabled) installAvatarOutline(row, state, preset)
    }

    private fun restoreRowBaseline(row: View, state: RowVisualState) {
        if (row.background === state.moduleBackground) {
            row.background = state.baselineBackground
            row.setPadding(
                state.baselinePaddingLeft,
                state.baselinePaddingTop,
                state.baselinePaddingRight,
                state.baselinePaddingBottom,
            )
        } else {
            state.baselineBackground = row.background
            state.baselinePaddingLeft = row.paddingLeft
            state.baselinePaddingTop = row.paddingTop
            state.baselinePaddingRight = row.paddingRight
            state.baselinePaddingBottom = row.paddingBottom
        }
    }

    private fun clearAvatarState(state: RowVisualState) {
        val avatarState = state.avatar ?: return
        val avatar = avatarState.view.get()
        if (avatar?.outlineProvider === avatarState.moduleOutlineProvider) {
            avatar.outlineProvider = avatarState.outlineProvider
            avatar.clipToOutline = avatarState.clipToOutline
            avatar.invalidateOutline()
        }
        state.avatar = null
    }

    private fun buildRowBackground(
        context: Context,
        preset: ConversationListPreset,
        palette: ConversationListPalette,
        unread: Boolean,
    ): Drawable {
        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(preset.rowRadiusDp, context.resources.displayMetrics.density).toFloat()
            setColor(if (unread) palette.unreadBackgroundColor else palette.backgroundColor)
            setStroke(1.coerceAtLeast(1), palette.strokeColor)
        }
        val horizontalInset = dpToPx(preset.horizontalInsetDp, context.resources.displayMetrics.density)
        val verticalInset = dpToPx(preset.verticalInsetDp, context.resources.displayMetrics.density)
        val inset = InsetDrawable(card, horizontalInset, verticalInset, horizontalInset, verticalInset)
        return RippleDrawable(ColorStateList.valueOf(palette.rippleColor), inset, null)
    }

    private fun isUnread(conversation: Any): Boolean {
        val modelClass = conversation.javaClass
        val accessor = unreadAccessorCache.computeIfAbsent(modelClass, ::findUnreadAccessor)
        if (accessor === UnreadAccessor.Missing) return false
        return try {
            isUnreadConversation(((accessor as UnreadAccessor.Field).get(conversation) as? Number)?.toInt() ?: return false)
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

    private fun installAvatarOutline(row: View, state: RowVisualState, preset: ConversationListPreset) {
        val avatar = findAvatarCandidate(row) ?: return
        val provider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0,
                    0,
                    view.width,
                    view.height,
                    dpToPx(preset.avatarRadiusDp, view.resources.displayMetrics.density).toFloat(),
                )
            }
        }
        state.avatar = AvatarVisualState(
            WeakReference(avatar),
            avatar.outlineProvider,
            avatar.clipToOutline,
            provider,
        )
        avatar.outlineProvider = provider
        avatar.clipToOutline = true
        avatar.invalidateOutline()
    }

    private fun findAvatarCandidate(root: View): ImageView? {
        var best: ImageView? = null
        var bestScore: Float? = null
        val stack = ArrayDeque<Pair<View, Int>>()
        stack += root to 0
        while (stack.isNotEmpty()) {
            val (view, depth) = stack.removeLast()
            if (view.visibility != View.VISIBLE) continue
            if (view is ImageView && view.isLaidOut) {
                val score = avatarCandidateScore(
                    AvatarCandidateMetrics(view.width, view.height, depth),
                    view.resources.displayMetrics.density,
                )
                if (score != null && (bestScore == null || score > bestScore)) {
                    best = view
                    bestScore = score
                }
            }
            if (depth < 8 && view is ViewGroup) {
                for (index in 0 until view.childCount) stack += view.getChildAt(index) to depth + 1
            }
        }
        return best
    }

    private fun updateDividerRequest() {
        WeConversationListViewApi.setDividerHidden(owner = this, hidden = isEnabled && hideDividersEnabled)
    }
}
