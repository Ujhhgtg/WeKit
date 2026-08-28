package dev.ujhhgtg.wekit.ui.content

internal class ViewBackdropCaptureIdentity(val value: Any) {
    override fun equals(other: Any?): Boolean =
        other is ViewBackdropCaptureIdentity && value === other.value

    override fun hashCode(): Int = System.identityHashCode(value)
}

internal class ViewBackdropWindowIdentityState {
    private var lastWindowIdentity: ViewBackdropCaptureIdentity? = null

    fun update(windowToken: Any?, invalidateCapture: () -> Unit): ViewBackdropCaptureIdentity? {
        if (windowToken == null) {
            if (lastWindowIdentity != null) invalidateCapture()
            lastWindowIdentity = null
            return null
        }

        val windowIdentity = ViewBackdropCaptureIdentity(windowToken)
        if (lastWindowIdentity != null && lastWindowIdentity != windowIdentity) {
            invalidateCapture()
        }
        lastWindowIdentity = windowIdentity
        return windowIdentity
    }

    fun reset() {
        lastWindowIdentity = null
    }
}

internal data class ViewBackdropCaptureKey(
    val source: ViewBackdropCaptureIdentity,
    val window: ViewBackdropCaptureIdentity,
    val generation: Int,
    val width: Int,
    val height: Int,
    val scrollX: Int,
    val scrollY: Int,
    val density: Float,
    val fontScale: Float,
    val layoutDirection: Int,
) {
    fun hasSameSourceWindow(other: ViewBackdropCaptureKey): Boolean =
        source == other.source && window == other.window
}

internal enum class ViewBackdropCaptureDecision {
    CAPTURE,
    REUSE,
    SKIP,
}

internal class ViewBackdropCaptureState {
    private var capturedKey: ViewBackdropCaptureKey? = null
    private var attemptedKey: ViewBackdropCaptureKey? = null
    private var hasValidCapture = false

    fun decide(key: ViewBackdropCaptureKey): ViewBackdropCaptureDecision = when {
        hasValidCapture && capturedKey == key -> ViewBackdropCaptureDecision.REUSE
        attemptedKey == key -> ViewBackdropCaptureDecision.SKIP
        else -> {
            attemptedKey = key
            ViewBackdropCaptureDecision.CAPTURE
        }
    }

    fun captureSucceeded(key: ViewBackdropCaptureKey) {
        attemptedKey = key
        capturedKey = key
        hasValidCapture = true
    }

    fun canDrawFor(key: ViewBackdropCaptureKey): Boolean =
        hasValidCapture && capturedKey?.hasSameSourceWindow(key) == true

    fun invalidate() {
        capturedKey = null
        attemptedKey = null
        hasValidCapture = false
    }
}
