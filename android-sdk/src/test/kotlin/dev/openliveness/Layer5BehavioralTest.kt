package dev.openliveness

import dev.openliveness.layers.FaceLandmark
import dev.openliveness.layers.FaceLandmarks
import dev.openliveness.layers.Layer5Behavioral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 4 Step 1 — Layer 5 behavioral biometrics unit tests. Mirrors the
 * Python reference in docs/Spec.md §1480-1530. The iOS sibling test file
 * (Sources/OpenLivenessTests/Layer5BehavioralTests.swift, due in Phase 4
 * iOS mirror) must assert the same numeric branches so a threshold tweak
 * on one platform trips its sibling rather than drifting the server's
 * weighted-score re-run.
 */
class Layer5BehavioralTest {

    // Builds a 478-point landmark array with only the indices Layer 5
    // actually reads (nose bridge + left iris). All other points stay at
    // (0, 0) — they're never looked at by Layer5Behavioral.
    private fun landmarksWith(
        noseBridge: FloatArray = floatArrayOf(0.50f, 0.30f, 0.50f, 0.32f, 0.50f, 0.34f, 0.50f, 0.36f),
        irisLeft: FloatArray = floatArrayOf(0.40f, 0.50f, 0.41f, 0.50f, 0.40f, 0.51f, 0.39f, 0.50f, 0.40f, 0.49f),
    ): FaceLandmarks {
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        // Nose bridge indices [6, 8, 9, 10].
        pts[6]  = FaceLandmark(noseBridge[0], noseBridge[1])
        pts[8]  = FaceLandmark(noseBridge[2], noseBridge[3])
        pts[9]  = FaceLandmark(noseBridge[4], noseBridge[5])
        pts[10] = FaceLandmark(noseBridge[6], noseBridge[7])
        // Left iris indices [468..472].
        for (k in 0 until 5) {
            pts[468 + k] = FaceLandmark(irisLeft[k * 2], irisLeft[k * 2 + 1])
        }
        return FaceLandmarks(pts)
    }

    // Returns a nose-bridge FloatArray with all 4 points translated by
    // (dx, dy) from the canonical neutral layout. Each point moves by the
    // same delta, so the per-frame mean displacement equals sqrt(dx² + dy²).
    private fun noseBridgeShifted(dx: Float, dy: Float): FloatArray = floatArrayOf(
        0.50f + dx, 0.30f + dy,
        0.50f + dx, 0.32f + dy,
        0.50f + dx, 0.34f + dy,
        0.50f + dx, 0.36f + dy,
    )

    // MARK: - Initial state

    @Test
    fun `initial state is zero score and not detected`() {
        val l = Layer5Behavioral()
        assertEquals(0f, l.behavioralScore, 0f)
        assertFalse(l.microsaccadeDetected)
        assertEquals(0, l.historySizeForTest)
    }

    @Test
    fun `single update does not compute a score`() {
        // Score needs ≥2 frames in the nose-bridge history to form a pair
        // and compute displacement. One frame → score stays at initial 0.
        val l = Layer5Behavioral()
        l.update(landmarksWith())
        assertEquals(0f, l.behavioralScore, 0f)
        assertEquals(1, l.historySizeForTest)
    }

    // MARK: - Score ladder (direct)

    @Test
    fun `score ladder suspicious branch`() {
        // meanDisp < 0.0005 → 0.1
        val l = Layer5Behavioral()
        assertEquals(0.1f, l.scoreForMeanDisplacement(0f), 0f)
        assertEquals(0.1f, l.scoreForMeanDisplacement(0.0004f), 0f)
    }

    @Test
    fun `score ladder borderline branch`() {
        // 0.0005 ≤ meanDisp < 0.001 → 0.5
        val l = Layer5Behavioral()
        assertEquals(0.5f, l.scoreForMeanDisplacement(0.0005f), 0f)
        assertEquals(0.5f, l.scoreForMeanDisplacement(0.0009f), 0f)
    }

    @Test
    fun `score ladder natural branch`() {
        // 0.001 ≤ meanDisp ≤ 0.015 → 1.0
        val l = Layer5Behavioral()
        assertEquals(1.0f, l.scoreForMeanDisplacement(0.001f), 0f)
        assertEquals(1.0f, l.scoreForMeanDisplacement(0.015f), 0f)
    }

    @Test
    fun `score ladder excessive branch`() {
        // meanDisp > 0.015 → 0.6
        val l = Layer5Behavioral()
        assertEquals(0.6f, l.scoreForMeanDisplacement(0.0151f), 0f)
        assertEquals(0.6f, l.scoreForMeanDisplacement(1.0f), 0f)
    }

    // MARK: - End-to-end score

    @Test
    fun `static image yields suspicious score`() {
        // A still photo held in front of the camera produces identical
        // nose-bridge coordinates frame after frame → mean displacement
        // ≈ 0 → score 0.1. This is the primary presentation-attack
        // signal we're preserving.
        val l = Layer5Behavioral()
        repeat(5) { l.update(landmarksWith()) }
        assertEquals(0.1f, l.behavioralScore, 1e-6f)
    }

    @Test
    fun `natural micro-movement yields natural score`() {
        // Alternate between two positions with a 0.005 shift → per-pair
        // displacement = 0.005, well inside [0.001, 0.015] → score 1.0.
        val l = Layer5Behavioral()
        val a = landmarksWith(noseBridge = noseBridgeShifted(0f, 0f))
        val b = landmarksWith(noseBridge = noseBridgeShifted(0.005f, 0f))
        for (i in 0 until 10) {
            l.update(if (i % 2 == 0) a else b)
        }
        assertEquals(1.0f, l.behavioralScore, 1e-6f)
    }

    @Test
    fun `excessive movement yields excessive score`() {
        // Alternate with a 0.02 shift → displacement = 0.02 > 0.015 →
        // score 0.6. Someone bobbing their head aggressively (or a
        // deepfake with over-amplified micro-motion) lands here.
        val l = Layer5Behavioral()
        val a = landmarksWith(noseBridge = noseBridgeShifted(0f, 0f))
        val b = landmarksWith(noseBridge = noseBridgeShifted(0.02f, 0f))
        for (i in 0 until 10) {
            l.update(if (i % 2 == 0) a else b)
        }
        assertEquals(0.6f, l.behavioralScore, 1e-6f)
    }

    @Test
    fun `borderline movement yields borderline score`() {
        // 0.0007 shift → displacement in [0.0005, 0.001) → score 0.5.
        val l = Layer5Behavioral()
        val a = landmarksWith(noseBridge = noseBridgeShifted(0f, 0f))
        val b = landmarksWith(noseBridge = noseBridgeShifted(0.0007f, 0f))
        for (i in 0 until 10) {
            l.update(if (i % 2 == 0) a else b)
        }
        assertEquals(0.5f, l.behavioralScore, 1e-6f)
    }

    // MARK: - Microsaccade

    @Test
    fun `microsaccade not detected on static iris`() {
        // Identical iris landmarks every frame → velocity 0 → never fires.
        val l = Layer5Behavioral()
        repeat(30) { l.update(landmarksWith()) }
        assertFalse(l.microsaccadeDetected)
    }

    @Test
    fun `microsaccade not detected on isolated spike`() {
        // One high-velocity transition surrounded by stillness → no two
        // adjacent velocities over threshold → zero qualifying pairs.
        // This is the noise-rejection branch — a tracking jitter spike
        // mustn't masquerade as a saccade.
        val l = Layer5Behavioral()
        val still = landmarksWith()
        val jumped = landmarksWith(
            irisLeft = floatArrayOf(
                0.50f, 0.50f, 0.51f, 0.50f, 0.50f, 0.51f, 0.49f, 0.50f, 0.50f, 0.49f,
            ),
        )
        l.update(still)
        l.update(jumped) // one velocity over threshold
        l.update(still) // velocity back to low — pair (over, under) does not qualify
        l.update(still)
        assertFalse(l.microsaccadeDetected)
    }

    @Test
    fun `microsaccade detected on three consecutive high-velocity frames`() {
        // Four iris positions all separated by ≥0.005 produce 3
        // over-threshold velocities. Pairs (v0,v1) and (v1,v2) both
        // qualify → count reaches 2 → latch flips to true.
        val l = Layer5Behavioral()
        val positions = listOf(
            floatArrayOf(0.40f, 0.50f, 0.41f, 0.50f, 0.40f, 0.51f, 0.39f, 0.50f, 0.40f, 0.49f),
            floatArrayOf(0.42f, 0.50f, 0.43f, 0.50f, 0.42f, 0.51f, 0.41f, 0.50f, 0.42f, 0.49f),
            floatArrayOf(0.44f, 0.50f, 0.45f, 0.50f, 0.44f, 0.51f, 0.43f, 0.50f, 0.44f, 0.49f),
            floatArrayOf(0.46f, 0.50f, 0.47f, 0.50f, 0.46f, 0.51f, 0.45f, 0.50f, 0.46f, 0.49f),
        )
        for (iris in positions) {
            l.update(landmarksWith(irisLeft = iris))
        }
        assertTrue(l.microsaccadeDetected)
    }

    @Test
    fun `microsaccade latches true after detection`() {
        // Detection is a session-level signal — once two qualifying pairs
        // land, the flag stays set even if the eye becomes still again.
        // This matches the Python ref's "returns bool, never un-detected".
        val l = Layer5Behavioral()
        val positions = listOf(
            floatArrayOf(0.40f, 0.50f, 0.41f, 0.50f, 0.40f, 0.51f, 0.39f, 0.50f, 0.40f, 0.49f),
            floatArrayOf(0.42f, 0.50f, 0.43f, 0.50f, 0.42f, 0.51f, 0.41f, 0.50f, 0.42f, 0.49f),
            floatArrayOf(0.44f, 0.50f, 0.45f, 0.50f, 0.44f, 0.51f, 0.43f, 0.50f, 0.44f, 0.49f),
            floatArrayOf(0.46f, 0.50f, 0.47f, 0.50f, 0.46f, 0.51f, 0.45f, 0.50f, 0.46f, 0.49f),
        )
        for (iris in positions) l.update(landmarksWith(irisLeft = iris))
        assertTrue(l.microsaccadeDetected)
        // Now feed many still frames — flag must stay true.
        repeat(50) { l.update(landmarksWith()) }
        assertTrue(l.microsaccadeDetected)
    }

    // MARK: - Window invariant

    @Test
    fun `nose bridge history caps at window size`() {
        // Push 500 frames; buffer must never exceed MICRO_MOVEMENT_WINDOW_FRAMES.
        // Protects against a long-running session leaking memory via the
        // sliding window.
        val l = Layer5Behavioral()
        repeat(500) { l.update(landmarksWith()) }
        assertEquals(Layer5Behavioral.MICRO_MOVEMENT_WINDOW_FRAMES, l.historySizeForTest)
    }

    // MARK: - Reset

    @Test
    fun `reset clears all state`() {
        // After an in-progress session, reset() must bring the instance
        // back to its constructor state so the same object can serve a
        // follow-on capture (CDLSession reuses the orchestrator across
        // retries).
        val l = Layer5Behavioral()
        val positions = listOf(
            floatArrayOf(0.40f, 0.50f, 0.41f, 0.50f, 0.40f, 0.51f, 0.39f, 0.50f, 0.40f, 0.49f),
            floatArrayOf(0.42f, 0.50f, 0.43f, 0.50f, 0.42f, 0.51f, 0.41f, 0.50f, 0.42f, 0.49f),
            floatArrayOf(0.44f, 0.50f, 0.45f, 0.50f, 0.44f, 0.51f, 0.43f, 0.50f, 0.44f, 0.49f),
            floatArrayOf(0.46f, 0.50f, 0.47f, 0.50f, 0.46f, 0.51f, 0.45f, 0.50f, 0.46f, 0.49f),
        )
        for (iris in positions) l.update(landmarksWith(irisLeft = iris))
        assertTrue(l.microsaccadeDetected)
        assertTrue(l.historySizeForTest > 0)

        l.reset()
        assertEquals(0f, l.behavioralScore, 0f)
        assertFalse(l.microsaccadeDetected)
        assertEquals(0, l.historySizeForTest)

        // Post-reset, a fresh static sequence must land on suspicious score
        // (0.1), proving the previous session's state is fully gone.
        repeat(5) { l.update(landmarksWith()) }
        assertEquals(0.1f, l.behavioralScore, 1e-6f)
    }
}
