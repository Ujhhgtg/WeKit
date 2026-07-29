package dev.ujhhgtg.wekit.ui.content.nukex

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.Surface
import androidx.activity.BackEventCompat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max

private enum class RevealPhase {
    Revealing,
    Concealing,
    PredictiveConcealing,
}

@Stable
class NukeRevealState internal constructor(
    private val scope: CoroutineScope,
    initiallyRevealed: Boolean,
) {
    val progress = Animatable(if (initiallyRevealed) 1f else 0f)

    var origin by mutableStateOf(Offset.Zero)
        private set

    var isRevealed by mutableStateOf(initiallyRevealed)
        private set

    var blocksInput by mutableStateOf(false)
        private set

    private var phase: RevealPhase? = null
    private var transitionJob: Job? = null
    private var predictiveStartProgress = 1f
    private var viewportSize = IntSize.Zero
    private var buttonNavigationBackOrigin: Offset? = null
    private var generation = 0L

    private val revealSpring = spring<Float>(
        dampingRatio = 0.68f,
        stiffness = 170f,
    )

    fun reveal(
        from: Offset,
        installDestination: () -> Unit,
    ) {
        if (isRevealed || phase == RevealPhase.Revealing) return
        origin = from
        launchTransition(RevealPhase.Revealing) {
            progress.snapTo(0f)
            installDestination()
            isRevealed = true
            withFrameNanos { }
            progress.animateTo(1f, revealSpring)
            progress.snapTo(1f)
            blocksInput = false
        }
    }

    fun conceal(
        from: Offset? = null,
        clearDestination: () -> Unit,
    ) {
        if (!isRevealed || phase == RevealPhase.Concealing) return
        origin = from ?: origin
        launchTransition(RevealPhase.Concealing) {
            finishConceal(clearDestination)
        }
    }

    suspend fun predictiveConceal(
        events: Flow<BackEventCompat>,
        optimizeExitOrigin: Boolean = false,
        clearDestination: () -> Unit,
    ) {
        if (!isRevealed || phase == RevealPhase.PredictiveConcealing) return
        transitionJob?.cancelAndJoin()
        phase = RevealPhase.PredictiveConcealing
        blocksInput = true
        predictiveStartProgress = progress.value.coerceIn(0f, 1f)
        val entryOrigin = origin
        var hasGestureOrigin = false

        try {
            events.collect { event ->
                if (phase == RevealPhase.PredictiveConcealing) {
                    if (
                        optimizeExitOrigin &&
                        !hasGestureOrigin &&
                        event.swipeEdge != BackEventCompat.EDGE_NONE
                    ) {
                        origin = Offset(
                            x = when (event.swipeEdge) {
                                BackEventCompat.EDGE_LEFT -> 0f
                                BackEventCompat.EDGE_RIGHT -> viewportSize.width.toFloat()
                                else -> entryOrigin.x
                            },
                            y = event.touchY.coerceIn(0f, viewportSize.height.toFloat()),
                        )
                        hasGestureOrigin = true
                    }
                    progress.snapTo(
                        predictiveStartProgress *
                            (1f - event.progress.coerceIn(0f, 1f))
                    )
                }
            }
            if (optimizeExitOrigin && !hasGestureOrigin) {
                buttonNavigationBackOrigin?.let { origin = it }
            }
            finishConceal(clearDestination)
            phase = null
        } catch (cancelled: CancellationException) {
            launchTransition(RevealPhase.Revealing) {
                progress.animateTo(1f, revealSpring)
                progress.snapTo(1f)
                origin = entryOrigin
                blocksInput = false
            }
            throw cancelled
        }
    }

    internal fun updateViewportSize(size: IntSize) {
        viewportSize = size
    }

    internal fun updateButtonNavigationBackOrigin(origin: Offset?) {
        buttonNavigationBackOrigin = origin
    }

    private suspend fun finishConceal(clearDestination: () -> Unit) {
        coroutineScope {
            val springJob = launch {
                progress.animateTo(0f, revealSpring)
            }
            while (springJob.isActive && progress.value > 0.005f) {
                withFrameNanos { }
            }
            springJob.cancelAndJoin()
        }
        progress.snapTo(0f)
        isRevealed = false
        clearDestination()
        blocksInput = false
    }

    private fun launchTransition(
        nextPhase: RevealPhase,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        transitionJob?.cancel()
        generation += 1
        val currentGeneration = generation
        phase = nextPhase
        blocksInput = true
        transitionJob = scope.launch {
            try {
                block()
            } finally {
                if (generation == currentGeneration) {
                    phase = null
                }
            }
        }
    }
}

@Composable
fun rememberNukeRevealState(
    initiallyRevealed: Boolean = false,
): NukeRevealState {
    val scope = rememberCoroutineScope()
    return remember(scope) { NukeRevealState(scope, initiallyRevealed) }
}

internal class NukeRevealStackEntry<T>(
    val destination: T,
    val revealState: NukeRevealState,
)

/**
 * A reveal-backed route stack. Each route owns its reveal state so popping a nested destination
 * exposes the page immediately below it instead of rebuilding the root page.
 */
@Stable
class NukeRevealStackState<T> internal constructor(
    private val scope: CoroutineScope,
) {
    internal val entries = mutableStateListOf<NukeRevealStackEntry<T>>()
    private var viewportSize = IntSize.Zero
    private var buttonNavigationBackOrigin: Offset? = null

    val canPop: Boolean
        get() = entries.isNotEmpty()

    fun push(destination: T, from: Offset) {
        val entry = NukeRevealStackEntry(destination, NukeRevealState(scope, initiallyRevealed = false))
        // A route can be pushed after this navigator has already received layout/insets. Propagate
        // that context before the first reveal so optimized exit never falls back to the center.
        entry.revealState.updateViewportSize(viewportSize)
        entry.revealState.updateButtonNavigationBackOrigin(buttonNavigationBackOrigin)
        entries += entry
        entry.revealState.reveal(from) {}
    }

    internal fun updateViewportSize(size: IntSize) {
        viewportSize = size
        entries.forEach { entry -> entry.revealState.updateViewportSize(size) }
    }

    internal fun updateButtonNavigationBackOrigin(origin: Offset?) {
        buttonNavigationBackOrigin = origin
        entries.forEach { entry -> entry.revealState.updateButtonNavigationBackOrigin(origin) }
    }

    fun pop(
        from: Offset? = null,
        optimizeExitOrigin: Boolean = false,
    ) {
        val entry = entries.lastOrNull() ?: return
        entry.revealState.conceal(
            from = from.takeIf { optimizeExitOrigin },
        ) {
            entries.remove(entry)
        }
    }

    suspend fun predictivePop(
        events: Flow<BackEventCompat>,
        optimizeExitOrigin: Boolean = false,
    ) {
        val entry = entries.lastOrNull() ?: return
        entry.revealState.predictiveConceal(
            events = events,
            optimizeExitOrigin = optimizeExitOrigin,
        ) {
            entries.remove(entry)
        }
    }
}

@Composable
fun <T> rememberNukeRevealStackState(): NukeRevealStackState<T> {
    val scope = rememberCoroutineScope()
    return remember(scope) { NukeRevealStackState(scope) }
}

/**
 * Stack-capable counterpart to [NukeRevealNavigator]. Routes are drawn as layered circular
 * reveals, preserving the same motion contract for root and nested destinations.
 */
@Composable
fun <T> NukeRevealStackNavigator(
    state: NukeRevealStackState<T>,
    modifier: Modifier = Modifier,
    base: @Composable () -> Unit,
    revealed: @Composable (T) -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val displayRotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val navigationBars = WindowInsets.navigationBars
    val buttonNavigation = remember(context, configuration) {
        resolveButtonNavigation(context)
    }
    val buttonNavigationBackOrigin = buttonNavigation?.backOrigin(
        viewportSize = containerSize,
        navigationBarLeft = navigationBars.getLeft(density, layoutDirection),
        navigationBarRight = navigationBars.getRight(density, layoutDirection),
        navigationBarBottom = navigationBars.getBottom(density),
        layoutDirection = layoutDirection,
        displayRotation = displayRotation,
    )
    SideEffect {
        state.updateButtonNavigationBackOrigin(buttonNavigationBackOrigin)
    }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerSize = size
                state.updateViewportSize(size)
            }
    ) {
        base()
        state.entries.forEach { entry ->
            val revealState = entry.revealState
            if (revealState.isRevealed || revealState.progress.value > 0f) {
                val resolvedOrigin = if (revealState.origin == Offset.Zero) {
                    Offset(containerSize.width / 2f, containerSize.height / 2f)
                } else {
                    revealState.origin
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            clip = true
                            shape = CircularRevealShape(
                                progress = revealState.progress.value,
                                origin = resolvedOrigin,
                            )
                        }
                ) {
                    revealed(entry.destination)
                }
            }
        }
        if (state.entries.lastOrNull()?.revealState?.blocksInput == true) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }
    }
}

@Composable
fun NukeRevealNavigator(
    state: NukeRevealState,
    modifier: Modifier = Modifier,
    base: @Composable () -> Unit,
    revealed: @Composable () -> Unit,
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val displayRotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val navigationBars = WindowInsets.navigationBars
    val buttonNavigation = remember(context, configuration) {
        resolveButtonNavigation(context)
    }
    val buttonNavigationBackOrigin = buttonNavigation?.backOrigin(
        viewportSize = containerSize,
        navigationBarLeft = navigationBars.getLeft(density, layoutDirection),
        navigationBarRight = navigationBars.getRight(density, layoutDirection),
        navigationBarBottom = navigationBars.getBottom(density),
        layoutDirection = layoutDirection,
        displayRotation = displayRotation,
    )
    SideEffect {
        state.updateButtonNavigationBackOrigin(buttonNavigationBackOrigin)
    }
    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged {
                containerSize = it
                state.updateViewportSize(it)
            }
    ) {
        base()
        if (state.isRevealed || state.progress.value > 0f) {
            val resolvedOrigin = if (state.origin == Offset.Zero) {
                Offset(containerSize.width / 2f, containerSize.height / 2f)
            } else {
                state.origin
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        clip = true
                        shape = CircularRevealShape(
                            progress = state.progress.value,
                            origin = resolvedOrigin,
                        )
                    }
            ) {
                revealed()
            }
        }
        if (state.blocksInput) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    }
            )
        }
    }
}

private enum class NavigationBarButtonGroup {
    Start,
    Center,
    End,
}

private data class ButtonNavigation(
    val mode: Int,
    val backGroup: NavigationBarButtonGroup,
) {
    fun backOrigin(
        viewportSize: IntSize,
        navigationBarLeft: Int,
        navigationBarRight: Int,
        navigationBarBottom: Int,
        layoutDirection: LayoutDirection,
        displayRotation: Int,
    ): Offset? {
        if (viewportSize.width <= 0 || viewportSize.height <= 0) return null
        if (navigationBarLeft <= 0 && navigationBarRight <= 0 && navigationBarBottom <= 0) {
            return null
        }

        val startFraction = if (mode == NAVIGATION_MODE_TWO_BUTTON) {
            TWO_BUTTON_BACK_FRACTION
        } else {
            THREE_BUTTON_BACK_FRACTION
        }
        val logicalFraction = when (backGroup) {
            NavigationBarButtonGroup.Start -> startFraction
            NavigationBarButtonGroup.Center -> 0.5f
            NavigationBarButtonGroup.End -> 1f - startFraction
        }

        val hasBottomBar = navigationBarBottom >= max(navigationBarLeft, navigationBarRight)
        return if (hasBottomBar && navigationBarBottom > 0) {
            val physicalFraction = if (layoutDirection == LayoutDirection.Ltr) {
                logicalFraction
            } else {
                1f - logicalFraction
            }
            Offset(
                x = viewportSize.width * physicalFraction,
                y = viewportSize.height - navigationBarBottom / 2f,
            )
        } else {
            val navigationBarWidth = max(navigationBarLeft, navigationBarRight)
            if (navigationBarWidth <= 0) return null
            val reverseVerticalOrder =
                (layoutDirection == LayoutDirection.Rtl) xor
                    (displayRotation == Surface.ROTATION_90)
            val physicalFraction = if (reverseVerticalOrder) {
                1f - logicalFraction
            } else {
                logicalFraction
            }
            Offset(
                x = if (navigationBarLeft >= navigationBarRight) {
                    navigationBarLeft / 2f
                } else {
                    viewportSize.width - navigationBarRight / 2f
                },
                y = viewportSize.height * physicalFraction,
            )
        }
    }
}

private fun resolveButtonNavigation(context: Context): ButtonNavigation? {
    val mode = resolveNavigationMode(context) ?: return null
    if (mode != NAVIGATION_MODE_THREE_BUTTON && mode != NAVIGATION_MODE_TWO_BUTTON) return null

    val defaultLayout = resolveDefaultNavigationBarLayout(context, mode)
    val configuredLayout = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
        runCatching {
            Settings.Secure.getString(context.contentResolver, SYSTEM_UI_NAV_BAR_LAYOUT)
        }.getOrNull()
    } else {
        null
    }
    val layout = configuredLayout?.takeIf { it.isNotBlank() } ?: defaultLayout
    val groups = layout.split(';')
    val resolvedGroups = if (groups.size == 3) groups else defaultLayout.split(';')
    if (resolvedGroups.size != 3) return null
    val backGroupIndex = resolvedGroups.indexOfFirst { group ->
        group.split(',').any { buttonSpec ->
            buttonSpec.substringBefore('[').substringBefore('(').trim() == "back"
        }
    }
    val backGroup = when (backGroupIndex) {
        0 -> NavigationBarButtonGroup.Start
        1 -> NavigationBarButtonGroup.Center
        2 -> NavigationBarButtonGroup.End
        else -> return null
    }
    val resolvedBackGroup = if (
        mode == NAVIGATION_MODE_THREE_BUTTON &&
        configuredLayout.isNullOrBlank() &&
        resolveNavigationBarButtonOrderReversed(context)
    ) {
        when (backGroup) {
            NavigationBarButtonGroup.Start -> NavigationBarButtonGroup.End
            NavigationBarButtonGroup.Center -> NavigationBarButtonGroup.Center
            NavigationBarButtonGroup.End -> NavigationBarButtonGroup.Start
        }
    } else {
        backGroup
    }
    return ButtonNavigation(mode = mode, backGroup = resolvedBackGroup)
}

private fun resolveNavigationBarButtonOrderReversed(context: Context): Boolean {
    val miuiFirstKey = runCatching {
        Settings.System.getString(
            context.contentResolver,
            MIUI_SCREEN_KEY_ORDER,
        )
    }.getOrNull()
        ?.split(' ')
        ?.firstOrNull { it.isNotBlank() }
        ?.toIntOrNull()
    if (miuiFirstKey != null) {
        return miuiFirstKey == MIUI_BACK_KEY_CODE
    }

    fun readKnownOrder(read: () -> String?): Int? = runCatching(read)
        .getOrNull()
        ?.toIntOrNull()
        ?.takeIf {
            it == NAVIGATION_BAR_BUTTON_ORDER_DEFAULT ||
                it == NAVIGATION_BAR_BUTTON_ORDER_REVERSED
        }

    val secureOrder = readKnownOrder {
        Settings.Secure.getString(
            context.contentResolver,
            NAVIGATION_BAR_BUTTON_ORDER,
        )
    }
    val globalOrder = readKnownOrder {
        Settings.Global.getString(
            context.contentResolver,
            NAVIGATION_BAR_BUTTON_ORDER,
        )
    }
    return (secureOrder ?: globalOrder) == NAVIGATION_BAR_BUTTON_ORDER_REVERSED
}

private fun resolveNavigationMode(context: Context): Int? {
    val interactionModeId = context.resources.getIdentifier(
        "config_navBarInteractionMode",
        "integer",
        "android",
    )
    if (interactionModeId != 0) {
        return runCatching { context.resources.getInteger(interactionModeId) }.getOrNull()
    }
    if (Build.VERSION.SDK_INT != Build.VERSION_CODES.P) return null

    val quickstepAvailable = runCatching {
        val recentsComponentId = context.resources.getIdentifier(
            "config_recentsComponentName",
            "string",
            "android",
        )
        val recentsPackage = context.resources.getString(recentsComponentId)
            .substringBefore('/')
            .takeIf { it.isNotBlank() }
            ?: return@runCatching false
        context.packageManager.resolveService(
            Intent(QUICKSTEP_SERVICE_ACTION).setPackage(recentsPackage),
            0,
        ) != null
    }.getOrDefault(false)
    val swipeUpEnabled = runCatching {
        Settings.Secure.getInt(
            context.contentResolver,
            SWIPE_UP_TO_SWITCH_APPS_ENABLED,
            0,
        ) != 0
    }.getOrDefault(false)
    return if (quickstepAvailable && swipeUpEnabled) {
        NAVIGATION_MODE_TWO_BUTTON
    } else {
        NAVIGATION_MODE_THREE_BUTTON
    }
}

private fun resolveDefaultNavigationBarLayout(context: Context, mode: Int): String {
    val resourceNames = if (mode == NAVIGATION_MODE_TWO_BUTTON) {
        listOf("config_navBarLayoutQuickstep")
    } else {
        listOf("config_secNavBarLayout", "config_navBarLayout")
    }
    return runCatching {
        val systemUiContext = context.createPackageContext(
            SYSTEM_UI_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        )
        resourceNames.firstNotNullOfOrNull { resourceName ->
            val resourceId = systemUiContext.resources.getIdentifier(
                resourceName,
                "string",
                SYSTEM_UI_PACKAGE,
            )
            resourceId.takeIf { it != 0 }?.let(systemUiContext.resources::getString)
        }
    }.getOrNull() ?: if (mode == NAVIGATION_MODE_TWO_BUTTON) {
        DEFAULT_TWO_BUTTON_LAYOUT
    } else {
        DEFAULT_THREE_BUTTON_LAYOUT
    }
}

private const val NAVIGATION_MODE_THREE_BUTTON = 0
private const val NAVIGATION_MODE_TWO_BUTTON = 1
private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
private const val SYSTEM_UI_NAV_BAR_LAYOUT = "sysui_nav_bar"
private const val QUICKSTEP_SERVICE_ACTION = "android.intent.action.QUICKSTEP_SERVICE"
private const val SWIPE_UP_TO_SWITCH_APPS_ENABLED = "swipe_up_to_switch_apps_enabled"
private const val NAVIGATION_BAR_BUTTON_ORDER = "navigationbar_key_order"
private const val NAVIGATION_BAR_BUTTON_ORDER_DEFAULT = 0
private const val NAVIGATION_BAR_BUTTON_ORDER_REVERSED = 1
private const val MIUI_SCREEN_KEY_ORDER = "screen_key_order"
private const val MIUI_BACK_KEY_CODE = 3
private const val THREE_BUTTON_BACK_FRACTION = 0.25f
private const val TWO_BUTTON_BACK_FRACTION = 0.193f
private const val DEFAULT_THREE_BUTTON_LAYOUT =
    "left[.5W],back[1WC];home;recent[1WC],right[.5W]"
private const val DEFAULT_TWO_BUTTON_LAYOUT = "back[1.7WC];home;contextual[1.7WC]"

private class CircularRevealShape(
    private val progress: Float,
    private val origin: Offset,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        if (progress <= 0f) return Outline.Generic(Path())
        val radius = hypot(
            max(origin.x, size.width - origin.x),
            max(origin.y, size.height - origin.y),
        ) * progress
        return Outline.Generic(
            Path().apply {
                addOval(
                    Rect(
                        left = origin.x - radius,
                        top = origin.y - radius,
                        right = origin.x + radius,
                        bottom = origin.y + radius,
                    )
                )
            }
        )
    }
}
