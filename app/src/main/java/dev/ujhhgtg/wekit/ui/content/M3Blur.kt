package dev.ujhhgtg.wekit.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.ProgressiveBlur
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.progressiveTextureBlur
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRenderEffectSupported

@Composable
fun rememberMaterial3BlurBackdrop(enabled: Boolean = true): LayerBackdrop? {
    if (!enabled || !isRenderEffectSupported()) return null
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

@Composable
fun LayerBackdrop?.m3AppBarColor(): Color =
    if (this != null) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer

@Composable
fun Modifier.m3AppBarBlur(
    backdrop: LayerBackdrop?,
    enabled: Boolean = true,
    progressive: Boolean = true,
    blurRadius: Float = 25f,
    blendAlpha: Float = 0.8f,
    shape: Shape = RectangleShape,
): Modifier {
    if (!enabled || backdrop == null) return this
    if (progressive && isRuntimeShaderSupported()) {
        return then(
            Modifier.progressiveTextureBlur(
                backdrop = backdrop,
                shape = shape,
                gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
                blurRadius = 10f,
                colors = BlurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MaterialTheme.colorScheme.surfaceContainer.copy(0.3f)),
                    ),
                ),
            ),
        )
    }
    val blendColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = blendAlpha)
    return then(
        Modifier.textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            colors = BlurColors(blendColors = listOf(BlendColorEntry(color = blendColor))),
        ),
    )
}

/** Records the modified content into [backdrop] so an app-bar blur layer can sample it. */
fun Modifier.m3BackdropLayer(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) layerBackdrop(backdrop) else this
