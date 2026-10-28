package dev.openliveness

import dev.openliveness.layers.ClosableGate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the gate that replaced the earlier AtomicBoolean pattern in
 * MediaPipeLandmarkStream. The TOCTOU bug the gate exists to prevent
 * is a close() that lands between `ifOpen`'s check and its work; this
 * test battery pins both the single-threaded correctness guarantees
 * (open→work→close→no-work, idempotency) and the serialization under
 * concurrent close+ifOpen.
 */
class ClosableGateTest {

    @Test
    fun `ifOpen runs block when gate is open`() {
        val gate = ClosableGate()
        var ran = false
        val result = gate.ifOpen { ran = true }
        assertTrue(ran)
        assertTrue(result)
        assertFalse(gate.isClosed)
    }

    @Test
    fun `ifOpen does not run block after close`() {
        val gate = ClosableGate()
        gate.close { /* cleanup */ }
        var ran = false
        val result = gate.ifOpen { ran = true }
        assertFalse(ran)
        assertFalse(result)
        assertTrue(gate.isClosed)
    }

    @Test
    fun `close runs cleanup exactly once`() {
        val gate = ClosableGate()
        val cleanupCount = AtomicInteger(0)
        val firstResult = gate.close { cleanupCount.incrementAndGet() }
        val secondResult = gate.close { cleanupCount.incrementAndGet() }
        val thirdResult = gate.close { cleanupCount.incrementAndGet() }
        // Idempotency: cleanup fires once; subsequent close() returns
        // false and skips the lambda. This is what makes MediaPipe's
        // native close() safe to call from both the error path (inside
        // analyze) and the normal path (CDLSession.finally).
        assertEquals(1, cleanupCount.get())
        assertTrue(firstResult)
        assertFalse(secondResult)
        assertFalse(thirdResult)
    }

    @Test
    fun `ifOpen never races with close under contention`() {
        // The actual TOCTOU scenario: a close() lands between a reader's
        // "is it open" check and the resource use. If the gate is wrong,
        // some iterations will see ran=true AFTER cleanupSeen=true, which
        // in production would be a detectAsync on a torn-down FaceLandmarker.
        // Repeat enough iterations that a buggy implementation would hit
        // the race window at least once on commodity hardware.
        repeat(200) {
            val gate = ClosableGate()
            val startSignal = CountDownLatch(1)
            val cleanupSeen = AtomicInteger(0)
            val closedWhenRan = AtomicInteger(0)
            val runCount = AtomicInteger(0)
            val pool = Executors.newFixedThreadPool(4)
            try {
                val readers = (1..3).map {
                    pool.submit {
                        startSignal.await()
                        gate.ifOpen {
                            // If a buggy gate let ifOpen's block run after
                            // cleanup already ran, cleanupSeen would be 1
                            // during this block.
                            if (cleanupSeen.get() != 0) closedWhenRan.incrementAndGet()
                            runCount.incrementAndGet()
                        }
                    }
                }
                val closer = pool.submit {
                    startSignal.await()
                    gate.close { cleanupSeen.incrementAndGet() }
                }
                startSignal.countDown()
                readers.forEach { it.get(2, TimeUnit.SECONDS) }
                closer.get(2, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }
            assertEquals(
                0, closedWhenRan.get(),
                "ifOpen's block ran after cleanup — TOCTOU regression"
            )
        }
    }

    @Test
    fun `close that fires before any ifOpen still runs cleanup`() {
        val gate = ClosableGate()
        val cleanupCount = AtomicInteger(0)
        val closed = gate.close { cleanupCount.incrementAndGet() }
        assertTrue(closed)
        assertEquals(1, cleanupCount.get())
        // Further ifOpen is a no-op.
        var ran = false
        gate.ifOpen { ran = true }
        assertFalse(ran)
    }

    @Test
    fun `ifOpen is not blocked by in-progress cleanup`() {
        // NC-H3: previously close() held the monitor during cleanup(),
        // so any concurrent ifOpen() waited for native MediaPipe close
        // to drain — tens of ms per frame worker, pile-up on the camera
        // analyzer thread. The current design flips the flag inside the
        // monitor and runs cleanup outside, so a late-arriving ifOpen
        // observes closed=true and returns immediately without blocking.
        val gate = ClosableGate()
        val cleanupStarted = CountDownLatch(1)
        val cleanupMayFinish = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val closer = pool.submit {
                gate.close {
                    cleanupStarted.countDown()
                    // Model a slow JNI teardown. If a buggy gate held
                    // the monitor across this call, ifOpen below would
                    // sit here waiting instead of returning promptly.
                    cleanupMayFinish.await()
                }
            }
            assertTrue(
                cleanupStarted.await(1, TimeUnit.SECONDS),
                "cleanup lambda should start promptly after close()"
            )
            val probe = pool.submit(Callable<Triple<Boolean, Boolean, Long>> {
                val t0 = System.nanoTime()
                var blockRan = false
                val ran = gate.ifOpen { blockRan = true }
                val elapsedNanos = System.nanoTime() - t0
                Triple(ran, blockRan, elapsedNanos)
            })
            val (ran, blockRan, elapsed) = probe.get(500, TimeUnit.MILLISECONDS)
            assertFalse(ran, "ifOpen must report closed while cleanup is running")
            assertFalse(blockRan, "ifOpen's block must not execute after close flipped the flag")
            // 100 ms is a generous upper bound — if the monitor were
            // being held across cleanup, probe would wait on cleanupMayFinish.
            assertTrue(
                elapsed < TimeUnit.MILLISECONDS.toNanos(100),
                "ifOpen must not block on in-progress cleanup — took ${elapsed}ns"
            )
            cleanupMayFinish.countDown()
            closer.get(1, TimeUnit.SECONDS)
        } finally {
            pool.shutdownNow()
        }
    }
}
