package dev.ujhhgtg.wekit.ui.content

import android.os.Build
import android.view.RoundedCorner
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The miuix navigation3 spring easing, ported verbatim from the miuix fork's
 * `NavTransitionEasing(response = 0.8, damping = 0.95)`. It is the analytic solution of an
 * under-damped spring expressed as an [Easing], which is what gives the Miuix predictive-back
 * transition its distinctive feel.
 *
 * Shared by [SettingsActivity][dev.ujhhgtg.wekit.activity.settings.SettingsActivity] and
 * [WeAgentSettingsActivity][dev.ujhhgtg.wekit.activity.agent.WeAgentSettingsActivity].
 */
internal val NavAnimationEasing: Easing = run {
    val response = 0.8
    val damping = 0.95
    val omega = 2.0 * PI / response
    val k = omega * omega
    val c = damping * 4.0 * PI / response
    val w = sqrt(4.0 * k - c * c) / 2.0
    val r = -c / 2.0
    val c2 = r / w
    Easing { fraction ->
        val t = fraction.toDouble()
        val decay = exp(r * t)
        (decay * (-cos(w * t) + c2 * sin(w * t)) + 1.0).toFloat()
    }
}

/** Device hardware corner radius (API 31+), or 32 dp as a sane squircle fallback. */
@Composable
internal fun deviceCornerRadiusDp(): Dp {
    val view = LocalView.current
    val density = LocalDensity.current
    return remember(view) {
        val px = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius ?: 0
        else 0
        if (px > 0) with(density) { px.toDp() } else 32.dp
    }
}

/**
 * A screen-stack navigator that reproduces the miuix predictive-back transition at **every
 * depth**, not just the first push. The top two entries of [stack] are the only ones composed at
 * a time.
 *
 * Transition mechanics (driven by an [Animatable] `p`):
 * - `p = 0` → the top screen fully covers the screen beneath it.
 * - `p = 1` → the top screen is fully off-screen to the right.
 * - **Background layer** parallaxes from `-width/4` at `p=0` and is dimmed up to 50% black.
 * - **Top layer** slides from `+width` at `p=1`, clipped at the device corner radius only while
 *   a navigation transition is in progress.
 *
 * ## Interactions
 * - **Push** `s`: add to stack → snap `p` to 1 (new screen off-screen) → animate to 0 (slide in).
 * - **Pop** / programmatic: animate `p` to 1 (top screen slides out) → remove → snap `p` to 0.
 * - **Predictive back gesture**: seek `p` 1:1 with gesture progress; on commit animate to 1 then
 *   remove (smooth spring completion); on cancel spring `p` back to 0.
 *
 * When only the root entry remains, back is delegated to [onExitRoot] (typically `finish()`).
 *
 * @param T Screen descriptor type; must be a stable value type (data class / data object).
 * @param stack Mutable state list; first element is the root, last is the visible top screen.
 *              Mutate only through [push] / [pop] supplied to [content].
 * @param onExitRoot Called when back is pressed (or popped) while only the root remains.
 * @param content Renders one screen, receives [push] and [pop] callbacks.
 */
@Composable
fun <T : Any> MiuixStackNavigator(
    stack: SnapshotStateList<T>,
    onExitRoot: () -> Unit,
    content: @Composable (screen: T, push: (T) -> Unit, pop: () -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val cornerRadius = deviceCornerRadiusDp()
    val p = remember { Animatable(0f) }
    var animating by remember { mutableStateOf(false) }
    var backGestureInProgress by remember { mutableStateOf(false) }
    val spec = tween<Float>(500, easing = NavAnimationEasing)

    fun push(s: T) {
        if (animating) return
        animating = true
        scope.launch {
            stack.add(s)
            p.snapTo(1f)              // new screen starts off-screen to the right
            p.animateTo(0f, spec)     // slide in
            animating = false
        }
    }

    fun pop() {
        if (animating) return
        if (stack.size <= 1) {
            onExitRoot(); return
        }
        animating = true
        scope.launch {
            p.animateTo(1f, spec)         // slide out
            stack.removeAt(stack.lastIndex)
            p.snapTo(0f)                  // reset for next transition
            animating = false
        }
    }

    // Predictive-back: seek p 1:1 while the gesture is live; on commit, animate the
    // slide-out spring before removing (smooth); on cancel, spring p back to 0.
    PredictiveBackHandler(enabled = stack.size > 1 && !animating) { events ->
        backGestureInProgress = true
        try {
            events.collect { e -> p.snapTo(e.progress) }
            // Gesture committed — complete the spring slide-out, then remove.
            animating = true
            p.animateTo(1f, spec)
            if (stack.size > 1) stack.removeAt(stack.lastIndex)
            p.snapTo(0f)
            animating = false
        } catch (e: CancellationException) {
            // Gesture cancelled — spring back to fully-covered.
            p.animateTo(0f, spec)
            throw e
        } finally {
            backGestureInProgress = false
            animating = false
        }
    }

    val depth = stack.size
    val navigationInProgress = animating || backGestureInProgress
    Box(modifier = Modifier.fillMaxSize()) {
        // Render the WHOLE stack in a keyed loop, each entry pinned to its index. push()/pop()
        // only add/remove at the end, so an existing screen never changes call site — its
        // remembered state and, crucially, any captured GraphicsLayer (miuix layerBackdrop blur)
        // stay attached. This is why we don't use movableContentOf here: moving a subtree that
        // records into a graphics layer leaves the layer released/stale, and the next draw pass
        // throws — the screen lays out (so taps still land) but paints nothing.
        //
        // Only the top two entries are styled/animated; anything deeper is fully occluded by the
        // opaque screen above it but stays composed so its state survives a pop back down to it.
        val pv = if (depth > 1) p.value else 0f
        stack.forEachIndexed { index, screen ->
            val isTop = index == depth - 1
            val isBelowTop = index == depth - 2

            key(screen) {
                val tapBlocker = remember { MutableInteractionSource() }
                val modifier = when {
                    // Top layer (only when something's stacked): slide from the right +
                    // squircle clip; swallows taps so the layer beneath isn't clickable through it.
                    isTop && depth > 1 -> Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = size.width * pv }
                        .squircleClip(if (navigationInProgress) cornerRadius else 0.dp)
                        .background(MiuixTheme.colorScheme.background)
                        .clickable(
                            interactionSource = tapBlocker,
                            indication = null,
                            onClick = {},
                        )

                    // The layer directly beneath the top: parallax left.
                    isBelowTop -> Modifier
                        .fillMaxSize()
                        .graphicsLayer { translationX = -size.width * 0.25f * (1f - pv) }

                    // The sole root (depth 1) or a deeper, fully-occluded layer.
                    else -> Modifier.fillMaxSize()
                }

                Box(modifier = modifier) {
                    content(screen, ::push, ::pop)
                    // Dim the below-top layer up to 50% black as the top slides away.
                    if (isBelowTop && pv < 1f) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .graphicsLayer { alpha = 0.5f * (1f - pv) }
                                .background(Color.Black),
                        )
                    }
                }
            }
        }
    }
}
