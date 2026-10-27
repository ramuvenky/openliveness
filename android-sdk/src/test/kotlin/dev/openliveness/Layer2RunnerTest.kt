package dev.openliveness

import dev.openliveness.layers.Challenge
import dev.openliveness.layers.ChallengeSequencer
import dev.openliveness.layers.FaceLandmark
import dev.openliveness.layers.FaceLandmarks
import dev.openliveness.layers.LandmarkStream
import dev.openliveness.layers.Layer2Runner
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 3 Step 4 — Layer2Runner tests. The runner is the piece that
 * couples the pure sequencer (Step 2) to the camera-and-WebSocket world
 * in CDLSession. We fake both the landmark stream and the WS emitter so
 * the frame-pump + processing_update emission can be exercised without
 * a camera or a server. Mirrors ios-sdk/Tests/Layer2RunnerTests.swift.
 */
class Layer2RunnerTest {

    /** Scripted stream: yields a pre-recorded list of frames once,
     *  then returns null — represents a camera that ran out of frames. */
    private class ScriptedStream(frames: List<Pair<FaceLandmarks, Double>>) : LandmarkStream {
        private val queue = ArrayDeque(frames)
        override suspend fun nextFrame(): Pair<FaceLandmarks, Double>? =
            if (queue.isEmpty()) null else queue.removeFirst()
    }

    private fun neutralFace(): MutableList<FaceLandmark> {
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        pts[1]   = FaceLandmark(0.50f, 0.50f)
        pts[33]  = FaceLandmark(0.40f, 0.50f)
        pts[133] = FaceLandmark(0.50f, 0.50f)
        pts[145] = FaceLandmark(0.45f, 0.50f)
        pts[159] = FaceLandmark(0.45f, 0.45f)
        pts[234] = FaceLandmark(0.30f, 0.50f)
        pts[454] = FaceLandmark(0.70f, 0.50f)
        return pts
    }
    private fun lm(pts: List<FaceLandmark>) = FaceLandmarks(pts)
    private fun closedEye(): FaceLandmarks {
        val p = neutralFace().also { it[159] = FaceLandmark(0.45f, 0.49f) }
        return lm(p)
    }
    private fun leftGaze(): FaceLandmarks {
        val p = neutralFace().also { it[1] = FaceLandmark(0.42f, 0.50f) }
        return lm(p)
    }

    // MARK: - Happy path

    @Test
    fun `run completes and emits terminal progress`() = runBlocking {
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK))
        val stream = ScriptedStream(listOf(
            closedEye() to 0.0,
            closedEye() to 0.033,
            lm(neutralFace()) to 0.066
        ))
        val calls = mutableListOf<Pair<Float, String>>()
        val runner = Layer2Runner(seq, stream) { p, l -> calls += p to l }

        val result = runner.run()

        assertTrue(result.completed)
        assertEquals(1, result.stepsCompleted)
        assertEquals(1, result.totalSteps)
        // One emit — the finished-and-completed emit — at progress 1.0.
        // The sequencer collapses step N completion with run completion
        // onto a single Finished event when N is the last step; the
        // runner must therefore not double-fire a stepCompleted emit.
        assertEquals(1, calls.size)
        assertEquals(1.0f, calls[0].first)
        assertEquals(Layer2Runner.WIRE_LAYER_LABEL, calls[0].second)
    }

    // MARK: - Stream drains before completion

    @Test
    fun `stream ending before completion yields not-completed result`() = runBlocking {
        // Script runs out mid-sequence. The runner must still return a
        // terminal ChallengeResult (completed=false, score=0) instead of
        // hanging — otherwise CDLSession.start would never submit the
        // attestation.
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK))
        val stream = ScriptedStream(listOf(closedEye() to 0.0))
        val calls = mutableListOf<Pair<Float, String>>()
        val runner = Layer2Runner(seq, stream) { p, l -> calls += p to l }

        val result = runner.run()

        assertFalse(result.completed)
        assertEquals(0.0f, result.score)
        assertEquals(0, result.stepsCompleted)
        // No emits — nothing completed and the sequencer never fired
        // Finished. We don't emit a fake progress line for the aborted
        // run; the server would misread it as "almost done".
        assertEquals(0, calls.size)
    }

    // R-6 — camera drops mid-run after the user already finished a step.
    // Previously the runner's stream-end fallback returned stepsCompleted=0
    // and elapsedSeconds=0 regardless of how far the user got, because
    // sequencer.result is only populated on Finished. Lock in the real
    // diagnostic values so a server-side regression investigation can
    // tell "camera died at step 1" from "user never started".
    @Test
    fun `stream ending after a step records real steps and elapsed`() = runBlocking {
        val seq = ChallengeSequencer.of(listOf(Challenge.BLINK, Challenge.LOOK_LEFT))
        // Complete the BLINK step, then stream dies before LOOK_LEFT.
        val stream = ScriptedStream(listOf(
            closedEye() to 0.0,
            closedEye() to 0.033,
            lm(neutralFace()) to 0.066,     // BLINK fires here
            leftGaze() to 0.1               // stream ends after this frame
        ))
        val calls = mutableListOf<Pair<Float, String>>()
        val runner = Layer2Runner(seq, stream) { p, l -> calls += p to l }

        val result = runner.run()

        assertFalse(result.completed)
        assertEquals(0.0f, result.score)
        assertEquals(1, result.stepsCompleted,
            "stream-end fallback must report real stepsCompleted, not 0")
        assertEquals(2, result.totalSteps)
        // elapsedSeconds should be lastTimestamp - firstTimestamp = 0.1.
        assertEquals(0.1, result.elapsedSeconds, 1e-6,
            "stream-end fallback must report real elapsed, not 0")
        // The step-completed emit happened mid-stream, so progress 0.5 was
        // sent to the WS before the stream died. The fallback doesn't emit
        // a trailing progress — that's a UI concern the caller handles.
        assertEquals(1, calls.size)
        assertEquals(0.5f, calls[0].first, 1e-6f)
    }

    // MARK: - Wire label

    @Test
    fun `emits stable wire label`() {
        // The server slices current_layer to 40 chars and the browser
        // dispatches UI on this exact string. Guard against a quiet
        // rename of the constant that would silently break the browser.
        assertEquals("layer2_active_liveness", Layer2Runner.WIRE_LAYER_LABEL)
    }
}
