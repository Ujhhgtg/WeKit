package dev.ujhhgtg.wekit.bootstrap.ui.content

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
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
fun rememberMaterial3BlurBackdrop(): LayerBackdrop? {
    if (!isRenderEffectSupported()) return null
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
fun Modifier.m3AppBarBlur(backdrop: LayerBackdrop?): Modifier {
    if (backdrop == null) return this
    if (isRuntimeShaderSupported()) {
        return progressiveTextureBlur(
            backdrop = backdrop,
            shape = RectangleShape,
            gradient = ProgressiveBlur.Top.copy(curve = 2.2f),
            blurRadius = 10f,
            colors =
                BlurColors(
                    blendColors =
                        listOf(
                            BlendColorEntry(
                                color = MaterialTheme.colorScheme.surfaceContainer.copy(0.3f),
                            ),
                        ),
                ),
        )
    }
    return textureBlur(
        backdrop = backdrop,
        shape = RectangleShape,
        blurRadius = 25f,
        colors =
            BlurColors(
                blendColors =
                    listOf(
                        BlendColorEntry(
                            color = MaterialTheme.colorScheme.surfaceContainer.copy(0.8f),
                        ),
                    ),
            ),
    )
}

fun Modifier.m3BackdropLayer(backdrop: LayerBackdrop?): Modifier =
    if (backdrop != null) layerBackdrop(backdrop) else this
