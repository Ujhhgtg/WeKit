package dev.ujhhgtg.wekit.ui.content.nukex

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun NukeDialogSurface(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    actions: @Composable RowScope.(dismiss: () -> Unit) -> Unit,
    content: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    val colors = NukeTheme.colors
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var dismissing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        visible = true
    }

    fun requestDismiss() {
        if (dismissing) return
        dismissing = true
        scope.launch {
            visible = false
            delay(180.milliseconds)
            onDismiss()
        }
    }

    val scaleX by animateFloatAsState(
        targetValue = if (visible) 1f else 0.94f,
        animationSpec = if (visible) {
            keyframes {
                durationMillis = 300
                0.94f at 0
                1.025f at 110
                0.995f at 210
                1f at 300
            }
        } else {
            keyframes {
                durationMillis = 150
                1f at 0
                1.012f at 45
                0.94f at 150
            }
        },
        label = "NukeDialogScaleX",
    )
    val scaleY by animateFloatAsState(
        targetValue = if (visible) 1f else 0.92f,
        animationSpec = if (visible) {
            keyframes {
                durationMillis = 300
                0.92f at 0
                1.04f at 110
                0.99f at 210
                1f at 300
            }
        } else {
            keyframes {
                durationMillis = 150
                1f at 0
                1.018f at 45
                0.92f at 150
            }
        },
        label = "NukeDialogScaleY",
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = if (visible) {
            keyframes {
                durationMillis = 160
                0f at 0
                1f at 80
                1f at 160
            }
        } else {
            tween(150)
        },
        label = "NukeDialogAlpha",
    )
    val edgeColor by animateColorAsState(
        targetValue = if (visible) {
            colors.textSecondary.copy(alpha = 0.045f)
        } else {
            colors.accent.copy(alpha = 0.16f)
        },
        animationSpec = tween(180),
        label = "NukeDialogFluidEdgeColor",
    )
    val edgePadding by animateDpAsState(
        targetValue = if (visible) 1.dp else 2.dp,
        animationSpec = tween(180),
        label = "NukeDialogFluidEdgePadding",
    )

    Dialog(
        onDismissRequest = ::requestDismiss,
        properties = properties,
    ) {
        Column(
            modifier
                .fillMaxWidth()
                .graphicsLayer {
                    this.alpha = alpha
                    this.scaleX = scaleX
                    this.scaleY = scaleY
                }
                .clip(NukeSquircleShape(22.dp))
                .background(edgeColor)
                .padding(edgePadding)
                .clip(NukeSquircleShape(22.dp))
                .background(colors.surface)
                .padding(20.dp),
        ) {
            NukeText(
                text = title,
                modifier = Modifier.fillMaxWidth(),
                color = colors.textPrimary,
                fontSize = 18,
                lineHeight = 24,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Spacer(Modifier.height(12.dp))
            content(::requestDismiss)
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions(::requestDismiss)
            }
        }
    }
}

@Composable
fun NukeSimpleDialog(
    title: String,
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String = "取消",
    confirmText: String = "确定",
    onConfirm: () -> Unit = {},
) {
    NukeDialogSurface(
        title = title,
        onDismiss = onDismiss,
        modifier = modifier,
        actions = { dismiss ->
            NukeButton(
                text = dismissText,
                modifier = Modifier.weight(1f),
                onClick = dismiss,
            )
            NukeButton(
                text = confirmText,
                modifier = Modifier.weight(1f),
                primary = true,
                onClick = {
                    onConfirm()
                    dismiss()
                },
            )
        },
    ) {
        NukeText(
            text = message,
            color = NukeTheme.colors.textSecondary,
            fontSize = 13,
            lineHeight = 19,
        )
    }
}

@Composable
fun NukeDialogSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    NukeText(
        text = text,
        modifier = modifier,
        color = NukeTheme.colors.textPrimary,
        fontSize = 13,
        lineHeight = 18,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(8.dp))
}
