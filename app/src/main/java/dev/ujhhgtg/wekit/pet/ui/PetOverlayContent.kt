package dev.ujhhgtg.wekit.pet.ui

import android.view.MotionEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ujhhgtg.wekit.pet.PetService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Transient per-gesture touch state for the pet (tap vs long-press vs drag). */
private class PetDragTracker {
    var downRawX = 0f
    var downRawY = 0f
    var moved = false
}

/**
 * The pet overlay content: a spritesheet-rendered pet with a reaction/status
 * bubble above it. A tap pets the pet; a long-press opens the info/treat panel;
 * a drag reports the running offset so the window controller can reposition it.
 * The bubble sits in a fixed-height slot so its appearance never shifts the sprite.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun PetOverlayContent(
    onDragStart: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val entry = PetService.petEntry.value
    val phase = PetService.phase.value
    val animationStartedAt = PetService.animationStartedAt.value
    val bubble = PetService.reactionBubble.value ?: PetService.bubble.value
    val panelOpen = PetService.panelOpen.value
    val displaySize = PetService.displaySize.value

    val touchSlop = LocalViewConfiguration.current.touchSlop
    val tracker = remember { PetDragTracker() }
    val scope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }

    if (entry == null) return

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (panelOpen) {
            PetInfoPanel()
        } else {
            // Fixed-height bubble slot (keeps the sprite stable when the bubble appears).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (!bubble.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(14.dp),
                        shadowElevation = 4.dp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(
                            text = bubble,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        PetSprite(
            entry = entry,
            phase = phase,
            animationStartedAt = animationStartedAt,
            height = displaySize.dp,
            modifier = Modifier
                .pointerInteropFilter { event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            tracker.downRawX = event.rawX
                            tracker.downRawY = event.rawY
                            tracker.moved = false
                            onDragStart()
                            longPressJob = scope.launch {
                                delay(500)
                                PetService.setPanelOpen(true)
                            }
                            true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = event.rawX - tracker.downRawX
                            val dy = event.rawY - tracker.downRawY
                            if (!tracker.moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                                tracker.moved = true
                            }
                            if (tracker.moved) {
                                longPressJob?.cancel()
                                onDrag(dx, dy)
                            }
                            true
                        }

                        MotionEvent.ACTION_UP -> {
                            longPressJob?.cancel()
                            if (tracker.moved) onDragEnd() else PetService.pet()
                            true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            longPressJob?.cancel()
                            if (tracker.moved) onDragEnd()
                            true
                        }

                        else -> false
                    }
                },
        )
    }
}

/** The pet info/treat panel shown on long-press. */
@Composable
private fun PetInfoPanel() {
    val affinity = PetService.affinityView.value
    val treatCount = PetService.treatCount.value
    val name = PetService.petDisplayName.value
    val size = PetService.displaySize.value

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier = Modifier.padding(8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = { PetService.setPanelOpen(false) }) {
                    Text("关闭", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(4.dp))

            if (affinity != null) {
                Text(
                    text = "${affinity.rankEmoji} ${affinity.rank}",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp,
                )
                Text(
                    text = "亲密度 ${affinity.points} · 摸头 ${affinity.pets} · 投喂 ${affinity.feeds}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "小鱼干 $treatCount",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(12.dp))
                Button(onClick = { PetService.feed() }) {
                    Text("投喂", fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "大小 $size",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { PetService.setSize(size - 16) }) {
                    Text("缩小", fontSize = 13.sp)
                }
                TextButton(onClick = { PetService.setSize(size + 16) }) {
                    Text("放大", fontSize = 13.sp)
                }
            }

            TextButton(onClick = { PetService.openRename() }) {
                Text("改名", fontSize = 13.sp)
            }
        }
    }
}

/** Focusable rename dialog shown in a separate overlay window. */
@Composable
fun RenameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialName) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 12.dp,
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "给宠物改名",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("名字") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { onConfirm(text) }) {
                        Text("确定", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
