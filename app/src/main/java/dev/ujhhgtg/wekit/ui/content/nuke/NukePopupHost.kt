package dev.ujhhgtg.wekit.ui.content.nuke

import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun NukePopupHost(
    visible: Boolean,
    contentMounted: Boolean,
    useDialogHost: Boolean,
    anchorBounds: IntRect,
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    backEnabled: Boolean,
    onBackProgressed: ((Float) -> Unit)?,
    onBackCancelled: () -> Unit,
    onBackCompleted: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (useDialogHost) {
        NukeDialogPopupHost(
            visible = visible,
            contentMounted = contentMounted,
            anchorBounds = anchorBounds,
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = onDismissRequest,
            backEnabled = backEnabled,
            onBackProgressed = onBackProgressed,
            onBackCancelled = onBackCancelled,
            onBackCompleted = onBackCompleted,
            content = content,
        )
    } else if (contentMounted) {
        Popup(
            popupPositionProvider = popupPositionProvider,
            onDismissRequest = onDismissRequest,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = true,
                usePlatformDefaultWidth = false,
            ),
            content = content,
        )
    }
}

@Composable
private fun NukeDialogPopupHost(
    visible: Boolean,
    contentMounted: Boolean,
    anchorBounds: IntRect,
    popupPositionProvider: PopupPositionProvider,
    onDismissRequest: () -> Unit,
    backEnabled: Boolean,
    onBackProgressed: ((Float) -> Unit)?,
    onBackCancelled: () -> Unit,
    onBackCompleted: () -> Unit,
    content: @Composable () -> Unit,
) {
    val dimProgress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    var dialogMounted by remember { mutableStateOf(visible || contentMounted) }
    var dimAnimationJob by remember { mutableStateOf<Job?>(null) }
    var dimExitStarted by remember { mutableStateOf(false) }
    val currentContentMounted by rememberUpdatedState(contentMounted)
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnBackProgressed by rememberUpdatedState(onBackProgressed)
    val currentOnBackCancelled by rememberUpdatedState(onBackCancelled)
    val currentOnBackCompleted by rememberUpdatedState(onBackCompleted)
    val dimColor = NukeTheme.colors.windowDimming

    fun startDimEnter() {
        dimExitStarted = false
        dimAnimationJob?.cancel()
        dimAnimationJob = coroutineScope.launch {
            dimProgress.animateTo(1f, NukePopupMotionSpecs.dimEnter)
        }
    }

    fun startDimExit(): Job {
        if (dimExitStarted) {
            return checkNotNull(dimAnimationJob)
        }
        dimExitStarted = true
        dimAnimationJob?.cancel()
        return coroutineScope.launch {
            dimProgress.animateTo(0f, NukePopupMotionSpecs.dimExit)
        }.also { dimAnimationJob = it }
    }

    if (!dialogMounted && !visible) return

    LaunchedEffect(visible) {
        if (visible) {
            dialogMounted = true
            startDimEnter()
        } else {
            startDimExit().join()
            snapshotFlow { currentContentMounted }.first { mounted -> !mounted }
            dialogMounted = false
        }
    }

    if (!dialogMounted) return

    Dialog(
        onDismissRequest = { currentOnDismissRequest() },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        NukeRemovePlatformDialogDefaultEffects()

        val navigationEventState = rememberNavigationEventState(
            currentInfo = NavigationEventInfo.None
        )
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = backEnabled,
            onBackCancelled = {
                currentOnBackCancelled()
                if (currentOnBackProgressed != null) {
                    startDimEnter()
                }
            },
            onBackCompleted = {
                startDimExit()
                currentOnBackCompleted()
            },
        )
        LaunchedEffect(navigationEventState) {
            snapshotFlow { navigationEventState.transitionState }
                .collect { transitionState ->
                    if (
                        transitionState is NavigationEventTransitionState.InProgress &&
                        transitionState.direction ==
                        NavigationEventTransitionState.TRANSITIONING_BACK &&
                        currentOnBackProgressed != null
                    ) {
                        val progress = transitionState.latestEvent.progress.coerceIn(0f, 1f)
                        dimAnimationJob?.cancel()
                        dimAnimationJob = null
                        dimExitStarted = false
                        dimProgress.snapTo(1f - progress)
                        currentOnBackProgressed?.invoke(progress)
                    }
                }
        }

        var hostPositionInWindow by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dimProgress.value }
                    .background(dimColor)
            )
            Layout(
                content = {
                    Box(
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onTap = {})
                        }
                    ) {
                        content()
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coordinates ->
                        hostPositionInWindow = coordinates.positionInWindow()
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { currentOnDismissRequest() }
                        )
                    },
            ) { measurables, constraints ->
                val placeable = measurables.single().measure(
                    constraints.copy(minWidth = 0, minHeight = 0)
                )
                val hostSize = IntSize(constraints.maxWidth, constraints.maxHeight)
                val popupPosition = popupPositionProvider.calculatePosition(
                    anchorBounds = anchorBounds,
                    windowSize = hostSize,
                    layoutDirection = layoutDirection,
                    popupContentSize = IntSize(placeable.width, placeable.height),
                )
                val adjustedPosition = IntOffset(
                    x = popupPosition.x - hostPositionInWindow.x.toInt(),
                    y = popupPosition.y - hostPositionInWindow.y.toInt(),
                )

                layout(hostSize.width, hostSize.height) {
                    placeable.place(adjustedPosition)
                }
            }
        }
    }
}

@Composable
private fun NukeRemovePlatformDialogDefaultEffects() {
    val parent = LocalView.current.parent
    DisposableEffect(parent) {
        val window = (parent as? DialogWindowProvider)?.window
        window?.setWindowAnimations(0)
        window?.setDimAmount(0f)
        window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        onDispose {}
    }
}
