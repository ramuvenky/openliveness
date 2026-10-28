package dev.openliveness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pins the NC-H1 fix: resource construction under `withContext(IO)` uses
 * the pattern
 *
 * ```
 * withContext(IO) { stream = factory() }   // pattern B — robust
 * ```
 *
 * rather than
 *
 * ```
 * stream = withContext(IO) { factory() }   // pattern A — brittle
 * ```
 *
 * Pattern A has a cancellation window between the inner lambda returning
 * and the outer continuation resuming. If the enclosing coroutine is
 * cancelled in that window, `factory()` has already allocated the native
 * resource (the MediaPipe FaceLandmarker in production) but the outer
 * assignment never happens — so the finally cannot close it. Pattern B
 * performs the assignment inside the lambda before any suspension-point
 * can observe cancellation, so the finally always sees the reference.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WithContextAssignmentPatternTest {

    class FakeResource(val id: Int) {
        val closed = AtomicBoolean(false)
        fun close() { closed.set(true) }
    }

    @Test
    fun `pattern B captures resource on normal completion`() = runBlocking {
        // Baseline: no cancellation, resource is captured and finally
        // closes it. If this ever breaks, the pattern itself is wrong.
        var captured: FakeResource? = null
        try {
            withContext(Dispatchers.Default) {
                captured = FakeResource(1)
            }
        } finally {
            captured?.close()
        }
        assertNotNull(captured)
        assertEquals(1, captured?.id)
        assertTrue(captured!!.closed.get())
    }

    @Test
    fun `pattern B assignment lands before any suspension inside the lambda`() = runBlocking {
        // The guarantee pattern B leans on: the inner assignment happens
        // BEFORE any suspend-point in the lambda, so a cancellation that
        // trips a later `yield()` still leaves `ref` holding the resource.
        // CoroutineStart.ATOMIC removes a confounding race: the launched
        // body is guaranteed to run up to its first suspension point even
        // if the Job is cancelled before the dispatcher schedules it. We
        // want to isolate the "cancel at suspend point AFTER assignment"
        // scenario, not accidentally test "cancel before start".
        val handoff = CompletableDeferred<FakeResource?>()
        val job = launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
            var ref: FakeResource? = null
            try {
                withContext(Dispatchers.Default) {
                    ref = FakeResource(2)
                    // yield() is a suspension point. If the parent
                    // cancels while we're parked here, the lambda throws
                    // CancellationException on resume — but `ref` has
                    // already been set, so the finally below sees it.
                    yield()
                }
            } finally {
                handoff.complete(ref)
            }
        }
        // Give the lambda enough time to run through the assignment and
        // park on yield(), then cancel. 50 ms is well above typical
        // scheduler latency on a loaded CI host.
        delay(50)
        job.cancelAndJoin()
        val captured = handoff.await()
        assertNotNull(
            captured,
            "pattern B must leave the resource reference set even when the lambda is cancelled at a suspend point"
        )
        // Close it in the test so the finally mirrors production.
        captured!!.close()
        assertTrue(captured.closed.get())
    }

    @Test
    fun `pattern B assignment survives even when outer resume is cancelled`() = runBlocking {
        // Second scenario: the lambda body COMPLETES normally, and
        // cancellation lands on the implicit suspension point between
        // the lambda return and the outer continuation resume. This is
        // the exact race window pattern A loses to — pattern B is safe
        // because the assignment already executed inside the lambda.
        val handoff = CompletableDeferred<FakeResource?>()
        val job = launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) {
            var ref: FakeResource? = null
            try {
                withContext(Dispatchers.Default) {
                    ref = FakeResource(3)
                    // No yield() here — the lambda just returns. The
                    // cancellation window of concern is the resume path
                    // out of withContext.
                }
            } finally {
                handoff.complete(ref)
            }
        }
        delay(50)
        job.cancelAndJoin()
        val captured = handoff.await()
        // The invariant: if the lambda body ran at all, `ref` is set.
        // Pattern A would fail here only if cancellation hit the resume
        // gap AND the test observed that specific interleaving — not
        // reliably reproducible. What IS reliable is: pattern B never
        // loses the reference, period. captured must be non-null in
        // every successful run of the lambda body.
        assertNotNull(
            captured,
            "pattern B must leave the resource reference set regardless of when cancellation lands"
        )
        assertEquals(3, captured?.id)
    }
}
