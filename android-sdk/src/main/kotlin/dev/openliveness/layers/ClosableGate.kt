package dev.openliveness.layers

/**
 * One-shot gate that serializes "do work under a resource" with "tear
 * the resource down". Replaces the earlier `AtomicBoolean` pattern in
 * MediaPipeLandmarkStream, which left a TOCTOU window: a thread calling
 * `analyze()` could read `closed=false`, be preempted, lose a race with
 * `close()`, and then invoke `landmarker.detectAsync(...)` on a torn-
 * down native object (SIGSEGV territory on the JNI boundary).
 *
 * The gate holds a monitor around both the open-check-plus-work and
 * the flag flip in close, so analyze is serialized with the transition
 * from open to closed. Cleanup itself runs OUTSIDE the monitor — the
 * flag is already flipped by then, so any arriving [ifOpen] immediately
 * sees closed=true and bails instead of blocking on a JNI call that may
 * take tens of milliseconds. See NC-H3 in the Phase-3-Step-5 review.
 *
 * Not reusable: once closed, stays closed. Reopening would reintroduce
 * the exact race this exists to prevent.
 */
internal class ClosableGate {
    private val lock = Any()
    @Volatile private var closed = false

    /**
     * Run [block] under the gate. Returns true if [block] executed,
     * false if the gate was already closed. [block] runs inside the
     * monitor so it is serialized with the open→closed transition.
     */
    fun ifOpen(block: () -> Unit): Boolean = synchronized(lock) {
        if (closed) {
            false
        } else {
            block()
            true
        }
    }

    /**
     * Flip the gate closed and run [cleanup] exactly once. Idempotent:
     * second and subsequent calls return false and do nothing. The flag
     * flip happens under the monitor; cleanup runs after the monitor is
     * released. That is sound because once the flag is flipped, any
     * [ifOpen] attempt that enters the monitor sees closed=true and
     * bails; any [ifOpen] already executing its block was serialized
     * with the flag flip, so its resource access happened-before the
     * cleanup starts.
     */
    fun close(cleanup: () -> Unit): Boolean {
        val wasOpen = synchronized(lock) {
            if (closed) {
                false
            } else {
                closed = true
                true
            }
        }
        if (wasOpen) cleanup()
        return wasOpen
    }

    val isClosed: Boolean get() = closed
}
