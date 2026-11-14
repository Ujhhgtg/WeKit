package dev.ujhhgtg.wekit.loader.utils

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.hookBeforeDirectly
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders

/**
 * ColorOS/OPlus monospace workaround.
 *
 * The ROM forces every *system* typeface — including the generic `monospace` family that
 * `androidx.compose.ui.text.font.FontFamily.Monospace` resolves to — through its OPlus Sans
 * variable font. The font files and the Typeface resolution stay untouched (a typeface still
 * reports the `monospace` family); the substitution happens when a typeface is put on a Paint:
 * ```
 * Paint.setTypeface -> PaintExtImpl.injectedByOemOS -> PaintExtImpl.replaceTypeface
 *   -> PaintExtImpl.flipTypeface(Typeface) -> OplusFontManager.flipTypeface
 *   -> OplusFontUtils.flipTypeface(ITypefaceExt, Paint)
 *        -> shouldReplaceToOSans ? replaceTypefaceWithVariation : flipTypeface
 * ```
 *
 * [OplusFontUtils.replaceTypefaceWithVariation] is the OPlus Sans variation substitution point, so
 * skipping it for monospace families keeps monospace intact while every other typeface keeps the
 * ROM's OPlus Sans rendering. The ROM's theme-font (flip font) path is deliberately left alone.
 *
 * Hooking the OPlus class (instead of a generic `android.graphics.*ExtImpl` slot) keeps this a
 * no-op on every other OEM/ROM: the class is absent, so no hook is installed and there is no
 * per-call overhead.
 */
object OplusFontCompat {
    private const val TAG = "OplusFontCompat"

    fun init() {
        val fontUtils = "com.oplus.util.OplusFontUtils".toClassOrNull(ClassLoaders.BOOT) ?: return
        fontUtils
            .reflekt()
            .firstMethodOrNull {
                name = "replaceTypefaceWithVariation"
                parameters(Typeface::class, Paint::class)
            }
            ?.hookBeforeDirectly {
                val typeface = args[0] as? Typeface ?: return@hookBeforeDirectly
                if (typeface.isMonospaceFamily()) result = typeface
            }

        WeLogger.i(TAG, "installed OPlus monospace typeface fix")
    }

    private fun Typeface.isMonospaceFamily(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            systemFontFamilyName?.contains("monospace", ignoreCase = true) == true
        } else {
            this == Typeface.MONOSPACE
        }
}
