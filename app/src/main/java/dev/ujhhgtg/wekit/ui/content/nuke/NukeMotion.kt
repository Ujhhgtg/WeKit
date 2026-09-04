package dev.ujhhgtg.wekit.ui.content.nuke

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

enum class NukePopupMotionFamily {
    Enter,
    Exit,
}

enum class NukePopupAnimationMode {
    Vanilla,
    ExitAlignedToEnter,
    EnterAlignedToExit;

    val enterFamily: NukePopupMotionFamily
        get() = when (this) {
            Vanilla, ExitAlignedToEnter -> NukePopupMotionFamily.Enter
            EnterAlignedToExit -> NukePopupMotionFamily.Exit
        }

    val exitFamily: NukePopupMotionFamily
        get() = when (this) {
            Vanilla, EnterAlignedToExit -> NukePopupMotionFamily.Exit
            ExitAlignedToEnter -> NukePopupMotionFamily.Enter
        }

    val supportsPredictiveExit: Boolean
        get() = exitFamily == NukePopupMotionFamily.Exit

    companion object {
        fun fromName(value: String?): NukePopupAnimationMode =
            entries.find { it.name == value } ?: Vanilla
    }
}

@Immutable
data class NukePopupMotionConfig(
    val animationMode: NukePopupAnimationMode = NukePopupAnimationMode.Vanilla,
    val useDialogHost: Boolean = false,
    val predictiveExit: Boolean = false,
)

@Immutable
data class NukePopupPanelMotionValues(
    val scaleX: Float,
    val scaleY: Float,
    val alpha: Float,
    val edgeProgress: Float,
    val edgeThicknessProgress: Float,
)

@Immutable
object NukePopupMotionSpecs {
    const val ExitDurationMillis = 120

    private val SinOutEasing = Easing { fraction ->
        sin((fraction * PI / 2).toFloat())
    }

    val dimEnter: FiniteAnimationSpec<Float>
        get() = tween(durationMillis = 300, easing = SinOutEasing)

    val dimExit: FiniteAnimationSpec<Float>
        get() = tween(durationMillis = 150, easing = SinOutEasing)

    fun predictiveExit(fromProgress: Float): FiniteAnimationSpec<Float> =
        tween(
            durationMillis = (
                ExitDurationMillis * (1f - fromProgress.coerceIn(0f, 1f))
            ).roundToInt().coerceAtLeast(1)
        )

    val predictiveReset: FiniteAnimationSpec<Float>
        get() = spring(dampingRatio = 0.78f, stiffness = 500f)

    fun enter(
        mode: NukePopupAnimationMode,
        transformOrigin: TransformOrigin,
    ): EnterTransition = when (mode.enterFamily) {
        NukePopupMotionFamily.Enter -> fadeIn(tween(durationMillis = 90))
        NukePopupMotionFamily.Exit -> fadeIn(tween(durationMillis = ExitDurationMillis)) +
            scaleIn(
                initialScale = 0.82f,
                transformOrigin = transformOrigin,
                animationSpec = tween(durationMillis = ExitDurationMillis),
            )
    }

    fun exit(
        mode: NukePopupAnimationMode,
        transformOrigin: TransformOrigin,
    ): ExitTransition = when (mode.exitFamily) {
        NukePopupMotionFamily.Enter -> fadeOut(tween(durationMillis = 90))
        NukePopupMotionFamily.Exit -> fadeOut(tween(durationMillis = ExitDurationMillis)) +
            scaleOut(
                targetScale = 0.82f,
                transformOrigin = transformOrigin,
                animationSpec = tween(durationMillis = ExitDurationMillis),
            )
    }
}

@Composable
fun AnimatedVisibilityScope.nukePopupPanelMotionValues(
    mode: NukePopupAnimationMode,
): NukePopupPanelMotionValues {
    val scaleX by transition.animateFloat(
        transitionSpec = {
            spring(dampingRatio = 0.5f, stiffness = 1_500f)
        },
        label = "NukePopupPanelScaleX",
    ) { state ->
        0.94f + 0.06f * mode.panelMotionProgress(state)
    }
    val scaleY by transition.animateFloat(
        transitionSpec = {
            spring(dampingRatio = 0.75f, stiffness = 400f)
        },
        label = "NukePopupPanelScaleY",
    ) { state ->
        0.82f + 0.18f * mode.panelMotionProgress(state)
    }
    val alpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 120) },
        label = "NukePopupPanelAlpha",
    ) { state ->
        mode.panelMotionProgress(state)
    }
    val edgeProgress by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 150) },
        label = "NukePopupPanelFluidEdgeColor",
    ) { state ->
        mode.panelMotionProgress(state)
    }
    val edgeThicknessProgress by transition.animateFloat(
        transitionSpec = {
            spring(dampingRatio = 0.5f, stiffness = 1_500f)
        },
        label = "NukePopupPanelFluidEdgeThickness",
    ) { state ->
        mode.panelMotionProgress(state)
    }

    return NukePopupPanelMotionValues(
        scaleX = scaleX,
        scaleY = scaleY,
        alpha = alpha,
        edgeProgress = edgeProgress,
        edgeThicknessProgress = edgeThicknessProgress,
    )
}

private fun NukePopupAnimationMode.panelMotionProgress(state: EnterExitState): Float =
    when (state) {
        EnterExitState.PreEnter ->
            if (enterFamily == NukePopupMotionFamily.Enter) 0f else 1f
        EnterExitState.Visible -> 1f
        EnterExitState.PostExit ->
            if (exitFamily == NukePopupMotionFamily.Enter) 0f else 1f
    }

@Immutable
object NukeMotionSpecs {
    val settingsEnter: EnterTransition
        get() = expandVertically(
            expandFrom = Alignment.Top,
            animationSpec = spring(dampingRatio = 0.72f, stiffness = 360f),
        ) + fadeIn(
            animationSpec = tween(durationMillis = 150, delayMillis = 20),
        )

    val settingsExit: ExitTransition
        get() = shrinkVertically(
            shrinkTowards = Alignment.Top,
            animationSpec = spring(dampingRatio = 1f, stiffness = 700f),
        ) + fadeOut(
            animationSpec = tween(durationMillis = 110),
        )
}

@Composable
fun NukeAnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: EnterTransition = NukeMotionSpecs.settingsEnter,
    exit: ExitTransition = NukeMotionSpecs.settingsExit,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = enter,
        exit = exit,
        content = content,
    )
}
