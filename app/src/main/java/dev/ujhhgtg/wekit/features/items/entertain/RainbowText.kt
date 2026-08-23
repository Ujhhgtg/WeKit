package dev.ujhhgtg.wekit.features.items.entertain

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Shader
import android.text.TextPaint
import android.view.View
import android.widget.TextView
import com.tencent.mm.ui.base.NoMeasuredTextView
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import java.lang.reflect.Field
import java.util.WeakHashMap

object RainbowText : SwitchFeature() {

    override val technicalId = "彩虹文本"
    override val nameRes = R.string.feature_rainbow_text_name
    override val categoryIds = listOf(FeatureCategoryIds.ENTERTAIN)
    override val descriptionRes = R.string.feature_rainbow_text_description

    private data class TextViewAnimationState(
        val matrix: Matrix,
        val paint: TextPaint,
        val originalShader: Shader?,
        var offset: Float,
    )

    private val viewStateMap = WeakHashMap<View, TextViewAnimationState>()

    private lateinit var noMeasuredTvTextProp: Field
    private lateinit var noMeasuredTvPaintProp: Field

    private const val PIXELS_PER_FRAME = 10.0f
    private val RAINBOW_COLORS = intArrayOf(
        Color.RED, Color.YELLOW, Color.GREEN,
        Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED,
    )

    override fun onEnable() {
        TextView::class.reflekt().firstMethod { name = "onDraw" }.hookBefore {
            val textView = thisObject as TextView
            applyRainbowEffect(textView, textView.text, textView.paint)
        }

        NoMeasuredTextView::class.reflekt()
            .firstMethod { name = "onDraw" }.hookBefore {
                val view = thisObject as View

                if (!::noMeasuredTvTextProp.isInitialized) {
                    noMeasuredTvTextProp = view.reflekt().firstField { name = "mText" }.self.makeAccessible()
                    noMeasuredTvPaintProp = view.reflekt().firstField { type = TextPaint::class }.self.makeAccessible()
                }

                applyRainbowEffect(
                    view,
                    noMeasuredTvTextProp.get(view) as CharSequence,
                    noMeasuredTvPaintProp.get(view) as TextPaint,
                )
            }
    }

    override fun onDisable() {
        viewStateMap.forEach { (view, state) ->
            state.paint.shader = state.originalShader
            view.invalidate()
        }
        viewStateMap.clear()
    }

    private fun applyRainbowEffect(view: View, text: CharSequence, paint: TextPaint) {
        val width = view.measuredWidth.toFloat()
        if (width <= 0f || text.isEmpty()) return

        val state = viewStateMap.getOrPut(view) {
            TextViewAnimationState(Matrix(), paint, paint.shader, 0f)
        }
        val rainbowWidth = width.coerceAtLeast(400f)
        val shader = LinearGradient(
            0f, 0f, rainbowWidth, 0f,
            RAINBOW_COLORS, null, Shader.TileMode.REPEAT,
        )

        state.offset += PIXELS_PER_FRAME
        if (state.offset > rainbowWidth) state.offset -= rainbowWidth

        state.matrix.setTranslate(state.offset, 0f)
        shader.setLocalMatrix(state.matrix)
        paint.shader = shader

        view.postInvalidateDelayed(60)
    }
}
