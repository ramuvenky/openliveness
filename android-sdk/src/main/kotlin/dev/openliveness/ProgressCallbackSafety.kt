package dev.openliveness

import kotlinx.coroutines.CancellationException

/**
 * Invoke the integrator's `onProgress` callback without letting its
 * exceptions kill the session. A thrown UI exception (main-thread
 * violation, state check, NPE on a dangling view binding) would
 * otherwise propagate up through Layer2Runner.run → runLiveness →
 * start() and surface to the caller as an opaque crash — the user sees
 * "challenge failed" with no breadcrumb explaining that their own
 * progress handler threw.
 *
 * Returns the caught [Throwable] so CDLSession can log it via
 * `android.util.Log` at the call site (keeping this helper free of
 * android.util.Log makes it JVM-unit-testable).
 *
 * **CancellationException is re-thrown.** Kotlin structured concurrency
 * relies on it to unwind nested suspends; swallowing it here would
 * leave the parent scope believing the child completed normally.
 */
internal fun invokeProgressCallbackSafely(
    callback: ((progress: Float, currentLayer: String) -> Unit)?,
    progress: Float,
    currentLayer: String,
): Throwable? {
    val cb = callback ?: return null
    return try {
        cb(progress, currentLayer)
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        e
    }
}
