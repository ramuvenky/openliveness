package dev.openliveness.layers

/**
 * Frames of (landmarks, timestamp) that Layer2Runner consumes. The real
 * production impl wraps MediaPipe Face Mesh (Phase 3 Step 5); test fakes
 * drive the same interface so the runner can be exercised deterministically
 * without a camera. `timestamp` is seconds since an arbitrary epoch; the
 * sequencer only looks at deltas, so the epoch choice is irrelevant as
 * long as a single stream uses one monotonic reference.
 */
interface LandmarkStream {
    /**
     * Returns the next frame, or null when the stream has ended. May
     * throw on camera / inference failure — the runner treats a throw
     * as a terminal error and bubbles it up rather than silently
     * stopping, so a user sees "camera failed" rather than "challenge
     * not completed".
     */
    suspend fun nextFrame(): LandmarkFrame?

    /**
     * Release underlying resources (MediaPipe FaceLandmarker, result
     * channel, etc.). Default no-op so pure in-memory test fakes don't
     * have to implement it. Idempotent; callers invoke in a `finally`
     * after `Layer2Runner.run()` returns.
     */
    fun close() {}
}

/**
 * Callback shape the runner uses to push a `processing_update` WS frame
 * each time a challenge step completes. Pulling this out of the runner
 * decouples WS encoding/transport from the sequencer-driving logic,
 * which lets tests observe progress without a WebSocket harness.
 */
typealias ProcessingUpdateEmitter = (progress: Float, currentLayer: String) -> Unit
