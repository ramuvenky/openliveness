package dev.openliveness

import dev.openliveness.layers.BlinkDetector
import dev.openliveness.layers.FaceLandmark
import dev.openliveness.layers.FaceLandmarks
import dev.openliveness.layers.GazeDetector
import dev.openliveness.layers.GazeDirection
import dev.openliveness.layers.NodDetector
import dev.openliveness.layers.SmileDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 3 Step 1 — gesture-detector unit tests. Mirrors
 * ios-sdk/Tests/Layer2DetectorsTests.swift so a numeric drift between the
 * two platforms (thresholds, frame counts, calibration logic) trips its
 * sibling test rather than divergent server-side scoring.
 */
class Layer2ActiveLivenessTest {

    private fun neutralFace(): MutableList<FaceLandmark> {
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        pts[1]   = FaceLandmark(0.50f, 0.50f) // nose tip
        pts[10]  = FaceLandmark(0.50f, 0.20f) // forehead
        pts[33]  = FaceLandmark(0.40f, 0.50f) // left eye outer
        pts[61]  = FaceLandmark(0.40f, 0.60f) // left mouth corner
        pts[133] = FaceLandmark(0.50f, 0.50f) // right eye outer (eye_width = 0.10)
        pts[145] = FaceLandmark(0.45f, 0.50f) // right eye lower lid
        pts[152] = FaceLandmark(0.50f, 0.80f) // chin
        pts[159] = FaceLandmark(0.45f, 0.45f) // right eye upper lid (EAR = 0.5, open)
        pts[234] = FaceLandmark(0.30f, 0.50f) // face left anchor
        pts[291] = FaceLandmark(0.60f, 0.60f) // right mouth corner (mouth_width = 0.20)
        pts[454] = FaceLandmark(0.70f, 0.50f) // face right anchor (face_width = 0.40)
        return pts
    }

    private fun lm(pts: List<FaceLandmark>): FaceLandmarks = FaceLandmarks(pts)

    // MARK: - Model

    @Test
    fun `FaceLandmarks accepts exactly 478 points`() {
        val f = lm(neutralFace())
        assertEquals(478, f.points.size)
        assertEquals(0.50f, f[1].x)
    }

    @Test
    fun `FaceLandmarks rejects wrong count`() {
        val bad = List(477) { FaceLandmark(0f, 0f) }
        var threw = false
        try { FaceLandmarks(bad) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw, "FaceLandmarks must reject count != 478 so an upstream FaceMesh-config regression is loud, not silent")
    }

    // R-1 safe factory — a face-not-detected tick (0 points) must return
    // null so an adapter can simply drop the frame instead of unwinding
    // the capture pipeline with an exception.
    @Test
    fun `FaceLandmarks tryFrom returns null for empty or partial input`() {
        assertEquals(null, FaceLandmarks.tryFrom(emptyList()))
        assertEquals(null, FaceLandmarks.tryFrom(List(100) { FaceLandmark(0f, 0f) }))
        val ok = FaceLandmarks.tryFrom(neutralFace())
        assertTrue(ok != null, "exact-count input must build successfully")
    }

    // MARK: - Blink

    @Test
    fun `blink fires on close then reopen`() {
        val d = BlinkDetector()
        repeat(2) { assertFalse(d.update(lm(neutralFace()))) }
        val closed = neutralFace().also { it[159] = FaceLandmark(0.45f, 0.49f) }
        repeat(2) { assertFalse(d.update(lm(closed))) }
        assertTrue(d.update(lm(neutralFace())))
    }

    @Test
    fun `blink does not fire without reopen`() {
        // Static closed-eye photo — reopen gate blocks the replay attack.
        val d = BlinkDetector()
        val closed = neutralFace().also { it[159] = FaceLandmark(0.45f, 0.49f) }
        repeat(10) { assertFalse(d.update(lm(closed))) }
    }

    // R-2 presentation-attack guard — a sustained closed-eye photo
    // briefly nudged off-camera (eye_width == 0 → face lost) must NOT
    // fire on reacquisition. Without the latch reset, the hostile path
    // would have been: closed, closed, LOST, open → fire. We lock the
    // reset here so a regression that drops the reset re-opens the
    // trivially-exploitable path.
    @Test
    fun `blink does not fire after face-loss gap followed by open eye`() {
        val d = BlinkDetector()
        val closed = neutralFace().also { it[159] = FaceLandmark(0.45f, 0.49f) }
        // Latch arms (closed for ≥2 frames).
        assertFalse(d.update(lm(closed)))
        assertFalse(d.update(lm(closed)))
        // Face-tracking loss — zero eye width simulates camera off-face.
        val lost = neutralFace().also {
            it[33]  = FaceLandmark(0.50f, 0.50f)
            it[133] = FaceLandmark(0.50f, 0.50f)
        }
        assertFalse(d.update(lm(lost)))
        // Reacquisition on an open eye: must NOT fire because the latch
        // reset when the face was lost. A real blink needs a fresh
        // closed→open transition after reacquisition.
        assertFalse(d.update(lm(neutralFace())))
    }

    @Test
    fun `blink ignores single-frame noise`() {
        val d = BlinkDetector()
        val dip = neutralFace().also { it[159] = FaceLandmark(0.45f, 0.49f) }
        assertFalse(d.update(lm(dip)))
        assertFalse(d.update(lm(neutralFace())))
        assertFalse(d.update(lm(neutralFace())))
    }

    // MARK: - Gaze

    @Test
    fun `gaze LEFT fires after 3 consecutive frames`() {
        val d = GazeDetector(GazeDirection.LEFT)
        val left = neutralFace().also { it[1] = FaceLandmark(0.42f, 0.50f) }
        assertFalse(d.update(lm(left)))
        assertFalse(d.update(lm(left)))
        assertTrue(d.update(lm(left)))
    }

    @Test
    fun `gaze RIGHT fires after 3 consecutive frames`() {
        val d = GazeDetector(GazeDirection.RIGHT)
        val right = neutralFace().also { it[1] = FaceLandmark(0.58f, 0.50f) }
        assertFalse(d.update(lm(right)))
        assertFalse(d.update(lm(right)))
        assertTrue(d.update(lm(right)))
    }

    @Test
    fun `gaze LEFT rejects rightward offset`() {
        // Sequencer-bug guard: directional detectors must not cross-fire.
        val d = GazeDetector(GazeDirection.LEFT)
        val right = neutralFace().also { it[1] = FaceLandmark(0.58f, 0.50f) }
        repeat(10) { assertFalse(d.update(lm(right))) }
    }

    @Test
    fun `gaze consecutive-frame counter resets on non-matching frame`() {
        val d = GazeDetector(GazeDirection.LEFT)
        val left = neutralFace().also { it[1] = FaceLandmark(0.42f, 0.50f) }
        assertFalse(d.update(lm(left)))
        assertFalse(d.update(lm(neutralFace()))) // reset
        assertFalse(d.update(lm(left)))
        assertFalse(d.update(lm(left)))
        assertTrue(d.update(lm(left)))
    }

    // MARK: - Smile

    @Test
    fun `smile requires calibration then fires above threshold`() {
        val d = SmileDetector()
        repeat(10) { assertFalse(d.update(lm(neutralFace()))) }
        assertEquals(0.20f, d.neutralMouthWidth!!, 1e-6f)
        val smile = neutralFace().also { it[291] = FaceLandmark(0.65f, 0.60f) }
        repeat(4) { assertFalse(d.update(lm(smile))) }
        assertTrue(d.update(lm(smile)))
    }

    // R-3 calibration-corruption guard — zero-width frames (face lost
    // during the warmup) MUST NOT be sampled into the neutral baseline.
    // Previous behavior would have averaged 0.0 into the mean, dragging
    // the trigger (neutral * 1.2) toward 0 and firing on any normal
    // mouth motion — a false-pass path we want to lock closed.
    @Test
    fun `smile skips zero-width calibration samples`() {
        val d = SmileDetector()
        // 5 real neutral frames + 5 face-lost frames: calibration still
        // needs 5 more real frames before fire path is live, and the
        // baseline must equal 0.20 (clean), not an average that
        // includes 0.0s.
        repeat(5) { d.update(lm(neutralFace())) }
        val lost = neutralFace().also {
            it[61]  = FaceLandmark(0.50f, 0.50f)
            it[291] = FaceLandmark(0.50f, 0.50f) // mouth_width = 0
        }
        repeat(5) { d.update(lm(lost)) } // dropped — must not advance calibration
        // neutralMouthWidth is still null (only 5 real samples, need 10).
        assertEquals(null, d.neutralMouthWidth)
        repeat(5) { d.update(lm(neutralFace())) }
        assertEquals(0.20f, d.neutralMouthWidth!!, 1e-6f)
    }

    @Test
    fun `smile does not fire below threshold`() {
        val d = SmileDetector()
        repeat(10) { d.update(lm(neutralFace())) }
        // mouth_width = 0.23, below 0.20 * 1.2 = 0.24.
        val mild = neutralFace().also { it[291] = FaceLandmark(0.63f, 0.60f) }
        repeat(10) { assertFalse(d.update(lm(mild))) }
    }

    // MARK: - Nod

    // H-4 — SmileDetector baseline uses the MEDIAN of calibration samples,
    // not the mean. An eager user who smiles for a minority of the warmup
    // window (e.g., 3 of 10 frames) would drag the mean up and lock
    // themselves out of ever clearing the 1.2× trigger. Median shrugs off
    // minority outliers and stays at the user's real neutral width.
    //
    // Scenario: 7 neutral frames at mouth_width 0.20 + 3 early-smile
    // frames at 0.30 during warmup.
    //   mean baseline   = (0.20*7 + 0.30*3) / 10 = 0.23 → trigger 0.276
    //   median baseline = 0.20                        → trigger 0.240
    // A legitimate 5-frame smile at mouth_width 0.26 clears median
    // (fires), does NOT clear mean (locked out).
    @Test
    fun `smile uses median baseline that ignores minority outliers`() {
        val d = SmileDetector()
        // 7 neutral calibration frames.
        repeat(7) { d.update(lm(neutralFace()), 0.0) }
        // 3 early-smile calibration frames (user eager; mouth_width=0.30).
        val early = neutralFace().also { it[291] = FaceLandmark(0.70f, 0.60f) }
        repeat(3) { d.update(lm(early), 0.0) }
        assertEquals(0.20f, d.neutralMouthWidth!!, 1e-6f)

        // A legitimate smile at mouth_width 0.26 (0.20 * 1.3 — above the
        // median trigger of 0.24, below the mean trigger of 0.276).
        val smile = neutralFace().also { it[291] = FaceLandmark(0.66f, 0.60f) }
        repeat(4) { assertFalse(d.update(lm(smile), 0.0)) }
        assertTrue(d.update(lm(smile), 0.0),
            "smile at 1.3× must fire with median baseline (would be locked out by mean)")
    }

    @Test
    fun `nod fires when pitch range exceeds threshold within one second`() {
        val d = NodDetector()
        assertFalse(d.update(lm(neutralFace()), 0.0))
        val down = neutralFace().also { it[10] = FaceLandmark(0.50f, 0.40f) }
        assertTrue(d.update(lm(down), 0.3))
    }

    @Test
    fun `nod does not fire if span is outside window`() {
        val d = NodDetector()
        assertFalse(d.update(lm(neutralFace()), 0.0))
        val down = neutralFace().also { it[10] = FaceLandmark(0.50f, 0.40f) }
        assertFalse(d.update(lm(down), 1.5))
    }

    // R-4 unbounded-buffer guard (same-timestamp storm). A hostile or
    // buggy stream repeating the same timestamp would keep every sample
    // inside the 1-second window forever (cutoff = t - 1 < t). The
    // MAX_SAMPLES=240 cap must trim regardless. Push 1000 frames and
    // confirm the buffer never exceeds the cap.
    @Test
    fun `nod buffer stays capped under same-timestamp storm`() {
        val d = NodDetector()
        repeat(1000) { d.update(lm(neutralFace()), 5.0) }
        assertTrue(d.bufferSizeForTest <= 240,
            "nod buffer must respect MAX_SAMPLES cap, got ${d.bufferSizeForTest}")
    }

    // H-3 — pitch is normalized by face_width so detection is distance-
    // invariant. A "far user" (small face in frame) has a tiny raw y
    // delta between neutral and nod positions; without normalization the
    // raw delta fails to clear the 0.15 threshold and the detector
    // becomes unusable at normal arm's-length distances. After the fix,
    // the same relative nod magnitude fires at any face scale.
    //
    // Scenario: shrink the whole face uniformly so face_width = 0.10
    // (quarter-size relative to the standard 0.40 fixture). A quarter-
    // scale nod has a raw y delta of ~0.025 — well under the 0.15
    // threshold if interpreted as raw y units. Normalized by
    // face_width=0.10 it becomes 0.25 → fires.
    @Test
    fun `nod normalization makes detection distance-invariant`() {
        val d = NodDetector()
        val farNeutral = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        farNeutral[10]  = FaceLandmark(0.50f, 0.45f)  // forehead
        farNeutral[152] = FaceLandmark(0.50f, 0.55f)  // chin
        farNeutral[234] = FaceLandmark(0.45f, 0.50f)  // face left anchor
        farNeutral[454] = FaceLandmark(0.55f, 0.50f)  // face right (face_width=0.10)
        assertFalse(d.update(lm(farNeutral), 0.0))
        val farDown = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        farDown[10]  = FaceLandmark(0.50f, 0.475f) // nod moves forehead down 0.025 raw-y
        farDown[152] = FaceLandmark(0.50f, 0.55f)
        farDown[234] = FaceLandmark(0.45f, 0.50f)
        farDown[454] = FaceLandmark(0.55f, 0.50f)
        assertTrue(d.update(lm(farDown), 0.3),
            "small face (face_width=0.10) with raw-y delta 0.025 must fire post-H3 (normalized=0.25 > 0.15)")
    }

    // H-3 companion — degenerate frames (face lost, zero face_width) are
    // dropped. Without the guard we'd divide by zero and either NaN-up
    // the buffer or inject bogus pitch samples that could prematurely
    // trigger the window-span check.
    @Test
    fun `nod drops zero face-width frames`() {
        val d = NodDetector()
        val lost = neutralFace().also {
            it[234] = FaceLandmark(0.50f, 0.50f)
            it[454] = FaceLandmark(0.50f, 0.50f) // face_width = 0
        }
        repeat(20) { d.update(lm(lost), 0.0) }
        assertEquals(0, d.bufferSizeForTest,
            "zero-face-width frames must not be sampled into the pitch buffer")
    }

    // R-4 companion — backward-moving timestamps (resumed app, clock
    // jump). cutoff = t_latest - 1s < any earlier t, so every older
    // sample stays "fresh" by window check. MAX_SAMPLES must still trim.
    @Test
    fun `nod buffer stays capped under backward-moving timestamps`() {
        val d = NodDetector()
        // Start at t=1000, walk back to t=0 across 1000 frames.
        var t = 1000.0
        repeat(1000) {
            d.update(lm(neutralFace()), t)
            t -= 1.0
        }
        assertTrue(d.bufferSizeForTest <= 240,
            "nod buffer must respect MAX_SAMPLES under backward timestamps, got ${d.bufferSizeForTest}")
    }
}
