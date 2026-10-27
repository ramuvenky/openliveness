package dev.openliveness.layers

/**
 * Enforces strictly-increasing output for MediaPipe LIVE_STREAM mode,
 * which rejects a non-monotonic timestamp with a native assertion.
 *
 * The camera stream's nanosecond timestamps are monotonic, but we hand
 * MediaPipe milliseconds (its own API unit), so two frames captured
 * < 1 ms apart collapse to the same millisecond. In normal 30 fps
 * operation the gap is ~33 ms so this is defensive — but a lab
 * harness pumping frames faster than realtime (and a MediaPipe build
 * that enforces strict increase) tripped on an identical-ms pair in
 * practice.
 *
 * On a duplicate or regressed input we bump to `last + 1` ms. One ms
 * is well below the gesture detectors' temporal resolution so the
 * nudge is detector-invisible.
 */
internal class TimestampMonotonizer {
    private var last: Long = Long.MIN_VALUE

    /** Returns [raw] if strictly greater than the previous output, else last+1. */
    fun advance(raw: Long): Long {
        val next = if (raw > last) raw else last + 1
        last = next
        return next
    }
}
