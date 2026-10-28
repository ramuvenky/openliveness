package dev.openliveness

import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Pins the H2 fix: a throwing integrator-supplied `onProgress` callback
 * must not kill the session. CDLSession wraps calls through
 * [invokeProgressCallbackSafely] and logs the returned Throwable.
 *
 * CancellationException is specifically re-thrown because Kotlin
 * structured concurrency relies on it to unwind nested suspends;
 * swallowing it would leave the parent scope believing the child
 * completed normally and the whole suspend tree would hang.
 */
class ProgressCallbackSafetyTest {

    @Test
    fun `null callback returns null and does not throw`() {
        val result = invokeProgressCallbackSafely(null, 0.5f, "layer2_active_liveness")
        assertNull(result)
    }

    @Test
    fun `successful callback returns null`() {
        var seen: Pair<Float, String>? = null
        val cb: (Float, String) -> Unit = { p, l -> seen = p to l }
        val result = invokeProgressCallbackSafely(cb, 0.5f, "layer2_active_liveness")
        assertNull(result)
        assertEquals(0.5f to "layer2_active_liveness", seen)
    }

    @Test
    fun `throwing callback is caught and returned`() {
        val boom = RuntimeException("View not attached")
        val cb: (Float, String) -> Unit = { _, _ -> throw boom }
        val result = invokeProgressCallbackSafely(cb, 0.5f, "layer2_active_liveness")
        assertSame(boom, result,
            "integrator exception must be returned not rethrown — CDLSession must survive it")
    }

    @Test
    fun `error subclass is also caught`() {
        // Error (not Exception) is caught too. A main-thread violation
        // from Android used to surface as an Error in some SDK versions;
        // either way, we're not propagating.
        val boom = AssertionError("state check failed")
        val cb: (Float, String) -> Unit = { _, _ -> throw boom }
        val result = invokeProgressCallbackSafely(cb, 1.0f, "layer2_active_liveness")
        assertSame(boom, result)
    }

    @Test
    fun `CancellationException is re-thrown not swallowed`() {
        // Structured concurrency guarantee: swallowing cancellation
        // would leave the parent scope hung waiting for a child that
        // believes itself still running.
        val cb: (Float, String) -> Unit = { _, _ -> throw CancellationException("parent scope cancelled") }
        assertThrows(CancellationException::class.java) {
            invokeProgressCallbackSafely(cb, 0.5f, "layer2_active_liveness")
        }
    }

    @Test
    fun `callback is invoked with exact progress and layer values`() {
        // The server reads these strings; a silent bump would mean the
        // browser UI gets a different value than the WebSocket.
        val calls = mutableListOf<Pair<Float, String>>()
        val cb: (Float, String) -> Unit = { p, l -> calls += p to l }
        invokeProgressCallbackSafely(cb, 0.333f, "layer2_active_liveness")
        invokeProgressCallbackSafely(cb, 0.666f, "layer2_active_liveness")
        invokeProgressCallbackSafely(cb, 1.0f, "layer2_active_liveness")
        assertEquals(3, calls.size)
        assertEquals(0.333f, calls[0].first)
        assertEquals(1.0f, calls[2].first)
    }
}
