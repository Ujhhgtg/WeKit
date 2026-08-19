package dev.ujhhgtg.wekit.ui.content

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.withTranslation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import top.yukonga.miuix.kmp.blur.Backdrop

/**
 * A [Backdrop] that samples an arbitrary native Android [View] — not the Compose tree.
 *
 * The miuix-blur backdrop effects (blur / lens / vibrancy) apply a `RenderEffect` to a
 * [GraphicsLayer] whose contents must be recorded *from within Compose*. The built-in
 * `LayerBackdrop` therefore only ever contains Compose-drawn pixels, which is useless when the
 * glass floats as an overlay on top of WeChat's own native views: there is nothing Compose-drawn
 * behind it, so the layer is empty and the glass shows nothing.
 *
 * This backdrop instead records `sourceView` (WeChat's ViewPager) into the layer whenever the
 * source content redraws, so the real chat / contacts / discover content shows through the glass.
 * It cannot capture hardware surfaces (SurfaceView / TextureView — e.g. video calls or Channels),
 * which draw blank behind the bar; that is an accepted limitation of View.draw().
 */
@Composable
fun rememberViewBackdrop(
    sourceView: View,
    lifecycleOwner: LifecycleOwner,
): ViewBackdrop {
    val graphicsLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val backdrop = remember(graphicsLayer) { ViewBackdrop(graphicsLayer) }
    backdrop.updateEnvironment(sourceView, density, layoutDirection)

    // Ask the glass to re-capture whenever WeChat's own content is about to redraw — a scroll, a
    // tab switch (setCurrentItem scrolls the pager), an incoming message, etc. `bumpGeneration` writes
    // a snapshot state that `drawBackdrop` reads, which is what actually forces the backdrop draw node
    // to re-run its layer recording; a plain View.invalidate() only recomposites the cached render
    // nodes and would NOT re-run the capture, so a settled static tab froze on its last frame.
    //
    // The `isDirty` gate is what keeps this from looping at 60fps: our own re-capture dirties the
    // overlay ComposeView, not `sourceView`, so `sourceView.isDirty` is true only when WeChat's
    // content genuinely needs to redraw. When WeChat is idle nothing dirties the source, we skip the
    // bump, and the glass costs nothing.
    DisposableEffect(sourceView, lifecycleOwner) {
        val observer = sourceView.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (sourceView.isDirty) {
                backdrop.bumpGeneration()
            }
            true
        }
        val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom,
            oldLeft, oldTop, oldRight, oldBottom ->
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
                backdrop.bumpGeneration()
            }
        }
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = backdrop.onSourceAttached()

            override fun onViewDetachedFromWindow(v: View) = backdrop.invalidateSource()
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> backdrop.startLifecycle()
                Lifecycle.Event.ON_STOP -> backdrop.prepareLifecycle(false)
                Lifecycle.Event.ON_DESTROY -> {
                    backdrop.prepareLifecycle(false)
                    backdrop.invalidateSource()
                }
                else -> Unit
            }
        }
        backdrop.prepareLifecycle(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        observer.addOnPreDrawListener(listener)
        sourceView.addOnLayoutChangeListener(layoutListener)
        sourceView.addOnAttachStateChangeListener(attachListener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        onDispose {
            (if (observer.isAlive) observer else sourceView.viewTreeObserver)
                .removeOnPreDrawListener(listener)
            sourceView.removeOnLayoutChangeListener(layoutListener)
            sourceView.removeOnAttachStateChangeListener(attachListener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            backdrop.prepareLifecycle(false)
        }
    }

    return backdrop
}

@Stable
class ViewBackdrop internal constructor(
    private val graphicsLayer: GraphicsLayer
) : Backdrop {

    internal var sourceView: View? = null
    internal var density: Density = Density(1f)
    internal var layoutDirection: LayoutDirection = LayoutDirection.Ltr

    // Bumped whenever the source content redraws. Read inside drawBackdrop so the draw phase
    // subscribes to it: a change re-runs the backdrop draw node's layer recording (and thus our
    // recapture) rather than just recompositing stale render nodes.
    private var generation by mutableIntStateOf(0)
    private val captureState = ViewBackdropCaptureState()
    private var captureInProgress = false
    private var lifecycleStarted = false
    private var sourceIdentity: ViewBackdropCaptureIdentity? = null
    private val windowIdentityState = ViewBackdropWindowIdentityState()

    // Coordinate lookups are done against the source view's window position, so the offset the
    // effect needs doesn't depend on Compose recomposition.
    override val isCoordinatesDependent: Boolean = true

    // When the blur pipeline records into a downscaled layer, the alignment translate is done in
    // downscaled space and rounded to an even pixel; the leftover fraction is reported here so the
    // node's drawUpscaledLayer can shift the upscaled result back to sub-pixel-correct alignment
    // (mirrors LayerBackdrop). At downscaleFactor == 1 these stay 0.
    override var offsetResidualX: Float = 0f
        private set
    override var offsetResidualY: Float = 0f
        private set

    internal fun updateEnvironment(
        view: View,
        density: Density,
        layoutDirection: LayoutDirection,
    ) {
        if (sourceView !== view) {
            captureState.invalidate()
            sourceIdentity = ViewBackdropCaptureIdentity(view)
            windowIdentityState.reset()
            generation++
        }
        sourceView = view
        this.density = density
        this.layoutDirection = layoutDirection
    }

    internal fun bumpGeneration() {
        generation++
    }

    internal fun onSourceAttached() {
        bumpGeneration()
    }

    internal fun invalidateSource() {
        captureState.invalidate()
        offsetResidualX = 0f
        offsetResidualY = 0f
        windowIdentityState.reset()
        generation++
    }

    internal fun prepareLifecycle(started: Boolean) {
        lifecycleStarted = started
    }

    internal fun startLifecycle() {
        lifecycleStarted = true
        bumpGeneration()
    }

    private fun currentCaptureKey(view: View): ViewBackdropCaptureKey? {
        if (!lifecycleStarted || !view.isAttachedToWindow) return null
        val windowIdentity = windowIdentityState.update(view.windowToken) {
            captureState.invalidate()
            offsetResidualX = 0f
            offsetResidualY = 0f
        }
            ?: return null
        return ViewBackdropCaptureKey(
            source = sourceIdentity!!,
            window = windowIdentity,
            generation = generation,
            width = view.width,
            height = view.height,
            scrollX = view.scrollX,
            scrollY = view.scrollY,
            density = density.density,
            fontScale = density.fontScale,
            layoutDirection = if (layoutDirection == LayoutDirection.Ltr) 0 else 1,
        )
    }

    /**
     * Records the current pixels of [sourceView] into the backing layer. Returns true if a capture
     * was performed (so the caller can repaint), false if the view isn't ready yet.
     */
    internal fun recordSource(): Boolean {
        val view = sourceView ?: return false
        val width = view.width
        val height = view.height
        if (width <= 0 || height <= 0) return false

        graphicsLayer.record(density, layoutDirection, IntSize(width, height)) {
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                // Replicate the scroll translation the parent normally applies when it draws this
                // view. WeChat's WxViewPager selects the visible tab by scrolling itself
                // (scrollTo(clientWidth * position, 0)) — pages are laid out side by side and only
                // the pager's own scrollX chooses which one shows. That scroll offset is applied by
                // the *parent's* draw traversal, NOT by the public View.draw(canvas) we call here, so
                // without this translate every page draws at its raw layout x and only page 0 (home)
                // fits in the layer. This is why the glass was stuck on the home tab.
                nativeCanvas.withTranslation(-view.scrollX.toFloat(), -view.scrollY.toFloat()) {
                    view.draw(nativeCanvas)
                }
            }
        }
        return true
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?,
        // The blur pipeline records the backdrop into a layer downscaled by this factor (1/2/4/…)
        // and upscales the blurred result afterwards. We MUST honor it: the layer we draw into here
        // is already downscaled, so drawing the full-res capture 1:1 overfills it and the later
        // upscale magnifies the content by exactly `downscaleFactor` (the "zoomed backdrop" bug).
        // Scale the capture down by 1/downscaleFactor so it fits, exactly like LayerBackdrop.
        downscaleFactor: Int,
    ) {
        @Suppress("UNUSED_EXPRESSION") generation
        val view = sourceView ?: return noCapture()
        val key = currentCaptureKey(view) ?: return noCapture()
        val barCoordinates = coordinates ?: return noCapture()

        // Capture the source here, during the overlay's own draw. viewParent draws the source
        // (mViewPager) before this ComposeView, so its display list is already the current frame —
        // unlike an OnPreDrawListener, which would capture the previous frame and freeze the glass
        // on a settled static tab. If the source isn't ready yet, keep whatever was last recorded.
        if (captureInProgress) return noCapture()
        when (captureState.decide(key)) {
            ViewBackdropCaptureDecision.CAPTURE -> {
                captureInProgress = true
                try {
                    if (recordSource()) captureState.captureSucceeded(key)
                } finally {
                    captureInProgress = false
                }
            }
            ViewBackdropCaptureDecision.REUSE,
            ViewBackdropCaptureDecision.SKIP -> Unit
        }
        if (!captureState.canDrawFor(key)) return noCapture()

        // Position of the bar (this consumer) relative to the captured source view, i.e. how far
        // into the source the region behind the bar sits. We translate the layer by -offset so that
        // region aligns under the bar.
        val barInWindow = barCoordinates.positionInWindow()
        val viewLocation = IntArray(2).also { view.getLocationInWindow(it) }
        val offsetX = barInWindow.x - viewLocation[0]
        val offsetY = barInWindow.y - viewLocation[1]

        if (downscaleFactor > 1) {
            // Alignment happens in downscaled space; round to an even pixel (matching LayerBackdrop's
            // even-snap for stable box-filter sampling) and report the leftover as a residual so the
            // node's upscale pass restores sub-pixel-correct placement.
            val inv = 1f / downscaleFactor
            val scaledX = offsetX * inv
            val scaledY = offsetY * inv
            val roundedX = kotlin.math.round(scaledX * 0.5f).toInt().toFloat() * 2f
            val roundedY = kotlin.math.round(scaledY * 0.5f).toInt().toFloat() * 2f
            offsetResidualX = (scaledX - roundedX) * downscaleFactor
            offsetResidualY = (scaledY - roundedY) * downscaleFactor
            translate(-roundedX, -roundedY) {
                scale(inv, inv, Offset.Zero) {
                    drawLayer(graphicsLayer)
                }
            }
        } else {
            offsetResidualX = 0f
            offsetResidualY = 0f
            translate(-offsetX, -offsetY) {
                drawLayer(graphicsLayer)
            }
        }
    }

    private fun DrawScope.noCapture() {
        offsetResidualX = 0f
        offsetResidualY = 0f
    }
}
