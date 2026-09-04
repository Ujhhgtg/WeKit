package dev.ujhhgtg.wekit.features.items.chat

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import dev.ujhhgtg.wekit.utils.android.isDarkMode
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Shared visual treatment for floating chat cards.
 *
 * Light mode keeps WeChat's own backgrounds intact. Dark mode needs an explicit surface and a
 * hairline border because Android elevation is barely visible on near-black chat backgrounds.
 */
object FloatingChatCardVisuals {

    private const val DARK_SURFACE_COLOR = 0xFF242424.toInt()
    private const val DARK_STROKE_COLOR = 0x24FFFFFF
    private const val DARK_STROKE_WIDTH_DP = 1

    private data class AppliedStyle(val cornerRadiusDp: Int, val strokeWidthPx: Int)

    private val originalBackgrounds = WeakHashMap<View, Drawable?>()
    private val appliedBackgrounds = WeakHashMap<View, Drawable>()
    private val appliedStyles = WeakHashMap<View, AppliedStyle>()

    fun applyDarkSurface(view: View, cornerRadiusDp: Int) {
        if (!view.context.isDarkMode) {
            restoreOriginalBackground(view)
            return
        }

        if (!originalBackgrounds.containsKey(view)) {
            originalBackgrounds[view] = view.background
        }

        val density = view.resources.displayMetrics.density
        val strokeWidthPx = (DARK_STROKE_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
        val style = AppliedStyle(cornerRadiusDp, strokeWidthPx)
        val appliedBackground = appliedBackgrounds[view]
        if (appliedStyles[view] == style && view.background === appliedBackground) return

        val radiusPx = cornerRadiusDp * density
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(DARK_SURFACE_COLOR)
            setStroke(strokeWidthPx, DARK_STROKE_COLOR)
        }
        view.background = background
        appliedBackgrounds[view] = background
        appliedStyles[view] = style
    }

    private fun restoreOriginalBackground(view: View) {
        if (!originalBackgrounds.containsKey(view)) return
        val appliedBackground = appliedBackgrounds[view]
        if (view.background === appliedBackground) {
            view.background = originalBackgrounds[view]
        }
        originalBackgrounds.remove(view)
        appliedBackgrounds.remove(view)
        appliedStyles.remove(view)
    }
}
