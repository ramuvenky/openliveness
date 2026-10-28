package dev.openliveness

import dev.openliveness.layers.TimestampMonotonizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The monotonizer exists because MediaPipe LIVE_STREAM mode rejects a
 * non-monotonic ms timestamp with a native assertion that tears down
 * the whole analyzer. Nanos→ms division is the realistic source of
 * duplicates (two frames < 1 ms apart collapse). These tests pin both
 * the strictly-increasing output invariant and the bump-size (one ms,
 * below the gesture detectors' temporal resolution).
 */
class TimestampMonotonizerTest {

    @Test
    fun `increasing inputs pass through unchanged`() {
        val m = TimestampMonotonizer()
        assertEquals(100L, m.advance(100L))
        assertEquals(133L, m.advance(133L))
        assertEquals(166L, m.advance(166L))
    }

    @Test
    fun `duplicate input is bumped by one ms`() {
        val m = TimestampMonotonizer()
        m.advance(100L)
        assertEquals(101L, m.advance(100L),
            "duplicate ms must be bumped so MediaPipe accepts the next frame")
    }

    @Test
    fun `regressed input is bumped above last output`() {
        val m = TimestampMonotonizer()
        m.advance(1_000L)
        assertEquals(1_001L, m.advance(500L),
            "a late-arriving earlier timestamp must still be strictly > last")
    }

    @Test
    fun `consecutive duplicates each bump by one`() {
        val m = TimestampMonotonizer()
        m.advance(100L)
        assertEquals(101L, m.advance(100L))
        assertEquals(102L, m.advance(100L))
        assertEquals(103L, m.advance(100L))
    }

    @Test
    fun `output is strictly increasing across many calls`() {
        val m = TimestampMonotonizer()
        // Mixed sequence: increases, duplicates, regressions. The
        // output stream must always be strictly monotonic — that's the
        // only guarantee MediaPipe actually cares about.
        val inputs = longArrayOf(10, 10, 10, 33, 33, 20, 100, 50, 50, 50, 200)
        var last = Long.MIN_VALUE
        for (raw in inputs) {
            val next = m.advance(raw)
            assertTrue(next > last, "output must be strictly increasing: $next after $last")
            last = next
        }
    }

    @Test
    fun `first call accepts the raw value even if negative`() {
        val m = TimestampMonotonizer()
        // ImageProxy timestamps are elapsedRealtimeNanos which is
        // non-negative, but the monotonizer is pure — any first input
        // just passes through so we don't inadvertently coerce.
        assertEquals(-5L, m.advance(-5L))
        assertEquals(0L, m.advance(0L))
    }
}
