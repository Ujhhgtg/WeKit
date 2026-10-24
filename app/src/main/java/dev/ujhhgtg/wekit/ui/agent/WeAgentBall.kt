package dev.ujhhgtg.wekit.ui.agent

import android.view.MotionEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat
import com.composables.icons.materialsymbols.outlined.Error
import dev.ujhhgtg.wekit.features.api.agent.WeAgentService.BallState
import kotlin.math.abs

/** Transient per-gesture touch state for the ball (kept across pointer events via `remember`). */
private class BallDragTracker {
    var downRawX = 0f
    var downRawY = 0f
    var moved = false
}

/**
 * The floating ball (§1.2). Touch is handled here in Compose (not via a View `OnTouchListener`,
 * which never receives the events since Compose consumes them): a press that stays within the touch
 * slop is a tap → [onClick]; movement past the slop is a drag that reports the running offset from
 * the press point (in absolute screen pixels) via [onDrag], so the window controller can reposition
 * the overlay. Using absolute `rawX/rawY` (not per-event deltas) avoids the feedback loop where
 * moving the window would itself shift subsequent pointer coordinates.
 *
 * Visuals reflect the 4-state machine: Idle static, Running breathing, PendingApproval badge, Error
 * warning tint.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WeAgentBall(
    state: BallState,
    onClick: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val container =
        when (state) {
            BallState.ERROR -> MaterialTheme.colorScheme.errorContainer
            BallState.PENDING_APPROVAL -> MaterialTheme.colorScheme.tertiaryContainer
            else -> MaterialTheme.colorScheme.primaryContainer
        }
    val content =
        when (state) {
            BallState.ERROR -> MaterialTheme.colorScheme.onErrorContainer
            BallState.PENDING_APPROVAL -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        }

    // Breathing animation while running.
    val transition = rememberInfiniteTransition(label = "ball")
    val pulse =
        transition.animateFloat(
            initialValue = 1f,
            targetValue = if (state == BallState.RUNNING) 0.55f else 1f,
            animationSpec =
                infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
            label = "pulse",
        )

    val touchSlop = LocalViewConfiguration.current.touchSlop
    val tracker = remember { BallDragTracker() }

    Box(contentAlignment = Alignment.Center) {
        Surface(
            color = container,
            shape = CircleShape,
            shadowElevation = 6.dp,
            modifier =
                Modifier.size(52.dp)
                    .clip(CircleShape)
                    .graphicsLayer { alpha = if (state == BallState.RUNNING) pulse.value else 1f }
                    .pointerInteropFilter { event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                tracker.downRawX = event.rawX
                                tracker.downRawY = event.rawY
                                tracker.moved = false
                                onDragStart()
                                true
                            }

                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - tracker.downRawX
                                val dy = event.rawY - tracker.downRawY
                                if (
                                    !tracker.moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)
                                ) {
                                    tracker.moved = true
                                }
                                if (tracker.moved) onDrag(dx, dy)
                                true
                            }

                            MotionEvent.ACTION_UP -> {
                                if (tracker.moved) onDragEnd() else onClick()
                                true
                            }

                            MotionEvent.ACTION_CANCEL -> {
                                if (tracker.moved) onDragEnd()
                                true
                            }

                            else -> false
                        }
                    },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector =
                        if (state == BallState.ERROR) MaterialSymbols.Outlined.Error
                        else MaterialSymbols.Outlined.Chat,
                    contentDescription = "WeAgent",
                    tint = content,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        // Pending-approval badge.
        if (state == BallState.PENDING_APPROVAL) {
            Surface(
                color = MaterialTheme.colorScheme.error,
                shape = CircleShape,
                modifier = Modifier.size(14.dp).align(Alignment.TopEnd),
            ) {}
        }
    }
}
