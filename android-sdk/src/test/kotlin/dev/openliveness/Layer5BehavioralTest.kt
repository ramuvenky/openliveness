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

    // Canonical neutral positions for every landmark Layer 5 reads. Mirrors
    // the fixture generator's `_CANONICAL_LANDMARKS` in
    // `core/tests/golden/generate_golden_fixtures.py`. We populate every
    // VARIANCE_SAMPLE_INDICES entry so the rigid-body vs articulated
    // discrimination has meaningful data to work with — a prior test shape
    // that left non-nose indices at (0,0) produced artificially low
    // variance that would false-fire the rigid-body branch.
    private val canonical: Map<Int, Pair<Float, Float>> = mapOf(
        1   to Pair(0.50f, 0.45f), // nose tip
        6   to Pair(0.50f, 0.40f), 8 to Pair(0.50f, 0.42f), 9 to Pair(0.50f, 0.44f),
        10  to Pair(0.50f, 0.30f), // forehead (per NodDetector)
        13  to Pair(0.50f, 0.60f), 14 to Pair(0.50f, 0.62f),
        33  to Pair(0.35f, 0.50f), 133 to Pair(0.45f, 0.50f),
        263 to Pair(0.65f, 0.50f), 362 to Pair(0.55f, 0.50f),
        61  to Pair(0.40f, 0.65f), 291 to Pair(0.60f, 0.65f),
        152 to Pair(0.50f, 0.80f),
        234 to Pair(0.25f, 0.50f), 454 to Pair(0.75f, 0.50f),
        468 to Pair(0.40f, 0.50f), 469 to Pair(0.41f, 0.50f),
        470 to Pair(0.40f, 0.51f), 471 to Pair(0.39f, 0.50f), 472 to Pair(0.40f, 0.49f),
        473 to Pair(0.60f, 0.50f),
    )

    // Builds a 478-point landmark array seeded with the canonical layout.
    // Override noseBridge and irisLeft to inject per-frame motion for the
    // gesture-under-test; indices not overridden stay at their canonical
    // neutral and thus translate rigidly across frames unless the caller
    // also perturbs them.
    private fun landmarksWith(
        noseBridge: FloatArray = floatArrayOf(
            canonical[6]!!.first,  canonical[6]!!.second,
            canonical[8]!!.first,  canonical[8]!!.second,
            canonical[9]!!.first,  canonical[9]!!.second,
            canonical[10]!!.first, canonical[10]!!.second,
        ),
        irisLeft: FloatArray = floatArrayOf(
            canonical[468]!!.first, canonical[468]!!.second,
            canonical[469]!!.first, canonical[469]!!.second,
            canonical[470]!!.first, canonical[470]!!.second,
            canonical[471]!!.first, canonical[471]!!.second,
            canonical[472]!!.first, canonical[472]!!.second,
        ),
        articulationJitter: FloatArray? = null, // size 2*VARIANCE_SAMPLE_INDICES per landmark
    ): FaceLandmarks {
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        for ((idx, xy) in canonical) {
            pts[idx] = FaceLandmark(xy.first, xy.second)
        }
        pts[6]  = FaceLandmark(noseBridge[0], noseBridge[1])
        pts[8]  = FaceLandmark(noseBridge[2], noseBridge[3])
        pts[9]  = FaceLandmark(noseBridge[4], noseBridge[5])
        pts[10] = FaceLandmark(noseBridge[6], noseBridge[7])
        for (k in 0 until 5) {
            pts[468 + k] = FaceLandmark(irisLeft[k * 2], irisLeft[k * 2 + 1])
        }
        if (articulationJitter != null) {
            val sampleIndices = Layer5Behavioral.VARIANCE_SAMPLE_INDICES
            for (k in sampleIndices.indices) {
                val idx = sampleIndices[k]
                val cur = pts[idx]
                pts[idx] = FaceLandmark(
                    cur.x + articulationJitter[k * 2],
                    cur.y + articulationJitter[k * 2 + 1],
                )
            }
        }
        return FaceLandmarks(pts)
    }

    // Returns a nose-bridge FloatArray with all 4 points translated by
    // (dx, dy) from the canonical neutral layout. Each point moves by the
    // same delta, so the per-frame mean displacement equals sqrt(dx² + dy²).
    private fun noseBridgeShifted(dx: Float, dy: Float): FloatArray = floatArrayOf(
        canonical[6]!!.first  + dx, canonical[6]!!.second  + dy,
        canonical[8]!!.first  + dx, canonical[8]!!.second  + dy,
        canonical[9]!!.first  + dx, canonical[9]!!.second  + dy,
        canonical[10]!!.first + dx, canonical[10]!!.second + dy,
    )

    // Builds a frame where EVERY sampled landmark translates by (dx, 0)
    // (rigid head-motion component — gives the nose-bridge its
    // displacement signal), then overlays per-landmark articulation
    // jitter on non-nose-bridge landmarks. Mirrors `_translated_frames`
    // in test_layer5_behavioral.py so variance under the same
    // (step, articulation) is numerically comparable between sides.
    private fun translatedFrame(
        dx: Float,
        articulationJitter: FloatArray,
    ): FaceLandmarks {
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        for ((idx, xy) in canonical) {
            pts[idx] = FaceLandmark(xy.first + dx, xy.second)
        }
        val sampleIndices = Layer5Behavioral.VARIANCE_SAMPLE_INDICES
        for (k in sampleIndices.indices) {
            val idx = sampleIndices[k]
            val cur = pts[idx]
            pts[idx] = FaceLandmark(
                cur.x + articulationJitter[k * 2],
                cur.y + articulationJitter[k * 2 + 1],
            )
        }
        return FaceLandmarks(pts)
    }

    // Generates a per-landmark articulation-jitter vector. `amplitude` is
    // the stdev of independent per-landmark offsets — values like 0.003
    // keep per-pair variance safely below LIVE_VARIANCE_UPPER=3e-5 (live
    // articulated face signature), while values like 0.01 push variance
    // well above it (waved-photo tracker-thrash signature). Seeded so
    // tests stay deterministic.
    //
    // Nose-bridge landmarks (6, 8, 9, 10) get ZERO jitter so tests can
    // control the nose-bridge displacement signal independently of the
    // articulation signal — otherwise a 0.003-amplitude jitter would
    // inflate the per-frame nose displacement past the BORDERLINE band
    // and make the ladder's displacement branch unpredictable. In a real
    // live face the nose bridge is relatively stable while eyes/mouth/brow
    // move differentially; zeroing jitter on the nose bridge matches that.
    private fun articulationFor(amplitude: Float, seed: Long): FloatArray {
        val rng = java.util.Random(seed)
        val indices = Layer5Behavioral.VARIANCE_SAMPLE_INDICES
        val out = FloatArray(indices.size * 2)
        for (k in indices.indices) {
            val lid = indices[k]
            if (lid == 6 || lid == 8 || lid == 9 || lid == 10) continue
            out[k * 2]     = (rng.nextGaussian().toFloat()) * amplitude
            out[k * 2 + 1] = (rng.nextGaussian().toFloat()) * amplitude
        }
        return out
    }

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

    // Shorthand so the ladder tests read as (displacement, variance) →
    // expected branch without the Layer5Behavioral().scoreFor... noise
    // on every line.
    private fun score(meanDisp: Float, motionVariance: Float): Float =
        Layer5Behavioral().scoreForMeanDisplacement(meanDisp, motionVariance)

    @Test
    fun `score ladder low displacement bypasses variance gate and scores natural`() {
        // The still-but-live fix: a user who holds the phone very still
        // produces nose-bridge displacement < SUSPICIOUS_UPPER (0.0005).
        // The variance gate is *skipped* in this branch — a still live
        // user (variance ≈ 7.67e-7) and a still held photo are
        // indistinguishable here, and L2 challenges catch the still-
        // photo case because it can't complete look_left. The previous
        // SCORE_SUSPICIOUS (0.1) in this branch was the source of the
        // on-device live-user false reject.
        assertEquals(1.0f, score(0f, 0f), 0f)
        assertEquals(1.0f, score(0.0004f, 1e-10f), 0f)
        // Even with "plenty of variance", low displacement → natural.
        assertEquals(1.0f, score(0.0004f, 1.0f), 0f)
    }

    @Test
    fun `score ladder high displacement with high variance scores suspicious`() {
        // Waved-photo signature: nose-bridge moves clearly (displacement
        // ≥ VARIANCE_GATE_DISPLACEMENT_FLOOR = 0.002) AND per-landmark
        // variance is ABOVE LIVE_VARIANCE_UPPER (3e-5) — MediaPipe's
        // tracker thrashes on a 2D photo waved in front of the camera,
        // producing chaotic per-landmark deltas. Nov 2026 N=14
        // calibration: photo variance bottomed at 5.57e-5, well above
        // the 3e-5 threshold.
        assertEquals(0.1f, score(0.003f, 5.57e-5f), 0f)  // field-measured photo min
        assertEquals(0.1f, score(0.0093f, 6.19e-5f), 0f) // field-measured session-14 photo
        assertEquals(0.1f, score(0.01f, 2.81e-4f), 0f)   // field-measured photo max
    }

    @Test
    fun `score ladder motion below gate floor scores natural`() {
        // Live-still-with-drift protection: a mostly-still live user can
        // accumulate 0.0005..0.002 nose-bridge drift over the 60-frame
        // window. The variance gate is skipped here so the branch is
        // variance-agnostic — these users must be routed to NATURAL
        // regardless of what their variance happens to be.
        assertEquals(1.0f, score(0.0015f, 1e-10f), 0f)
        assertEquals(1.0f, score(0.0019f, 1.0f), 0f)
    }

    @Test
    fun `score ladder high displacement with low variance scores natural`() {
        // Live face moving freely: per-landmark motion vectors stay
        // close to their mean because the rigid skull constrains them.
        // Variance below LIVE_VARIANCE_UPPER → NATURAL. Nov 2026 N=14
        // calibration: live variance peaked at 2.38e-5, below 3e-5.
        assertEquals(1.0f, score(0.005f, 2.38e-5f), 0f) // field-measured live max
        assertEquals(1.0f, score(0.003f, 1.0e-6f), 0f)
    }

    @Test
    fun `score ladder borderline displacement scores borderline ignoring variance`() {
        // Preserved from the old ladder: displacement in [0.0005, 0.001)
        // lands in BORDERLINE (0.5). Variance gate is NOT consulted in
        // this branch — a live user with barely-perceptible movement
        // scores the same regardless of their per-landmark variance.
        assertEquals(0.5f, score(0.0005f, 1e-5f), 0f)
        assertEquals(0.5f, score(0.0009f, 0.1f), 0f)
        assertEquals(0.5f, score(0.0007f, 1e-10f), 0f)
    }

    @Test
    fun `score ladder excessive displacement scores excessive even with low variance`() {
        // Excessive branch short-circuits before the variance gate — a
        // user bobbing their head aggressively (or an attack with over-
        // amplified motion) is "too much motion to be natural" regardless
        // of whether the motion is rigid or articulated.
        assertEquals(0.6f, score(0.0151f, 0f), 0f)
        assertEquals(0.6f, score(0.0151f, 1.0f), 0f)
        assertEquals(0.6f, score(1.0f, 1e-10f), 0f)
    }

    // MARK: - End-to-end score

    @Test
    fun `still subject yields natural score (L2 catches still photos)`() {
        // A still subject produces identical nose-bridge coordinates
        // frame after frame → meanDisp ≈ 0 → the ladder routes to
        // NATURAL via the low-displacement bypass. This test used to
        // expect SUSPICIOUS (0.1) but that branch was the on-device
        // live-user false-reject source (variance 7.67e-7 still-live
        // was indistinguishable from a tripod photo). A tripod photo
        // can't complete look_left either, so L2 is the correct gate
        // for that attack and L5 doesn't need to penalise stillness.
        val l = Layer5Behavioral()
        repeat(5) { l.update(landmarksWith()) }
        assertEquals(1.0f, l.behavioralScore, 1e-6f)
    }

    @Test
    fun `waved photo in middle displacement band scores suspicious`() {
        // The attack signature this gate catches: a 2D printed photo
        // waved in front of the camera. The whole face translates
        // (rigid head-motion component drives the nose-bridge
        // displacement) AND MediaPipe's tracker thrashes trying to fit
        // a 3D mesh to the 2D surface, producing chaotic per-landmark
        // deltas with variance above the 3e-5 threshold. Nov 2026
        // session-14 field case: meanDisp = 9.33e-3, variance = 6.19e-5,
        // previously scored L5 = 1.0 and leaked through the pre-
        // inversion low-variance gate.
        val l = Layer5Behavioral()
        for (i in 0 until 60) {
            val dx = 0.003f * (if (i % 2 == 0) 1f else -1f)
            val j = articulationFor(amplitude = 0.01f, seed = i.toLong())
            l.update(translatedFrame(dx = dx, articulationJitter = j))
        }
        assertEquals(0.1f, l.behavioralScore, 1e-6f)
    }

    @Test
    fun `natural articulated micro-movement yields natural score`() {
        // Live face: whole face translates by ~0.005 per frame (head
        // motion) AND non-nose landmarks get independent mild jitter
        // (articulation=0.003 keeps variance well below LIVE_VARIANCE_UPPER=3e-5
        // — the rigid skull constrains per-landmark motion vectors
        // close to their mean). The ladder sees displacement in the
        // NATURAL band and variance below the gate → NATURAL.
        val l = Layer5Behavioral()
        for (i in 0 until 60) {
            val dx = 0.005f * (if (i % 2 == 0) 1f else -1f)
            val j = articulationFor(amplitude = 0.003f, seed = i.toLong())
            l.update(translatedFrame(dx = dx, articulationJitter = j))
        }
        assertEquals(1.0f, l.behavioralScore, 1e-6f)
    }

    @Test
    fun `excessive movement yields excessive score`() {
        // Alternate with a 0.02 shift → displacement > 0.015 → score 0.6.
        // The EXCESSIVE branch short-circuits the variance gate, so this
        // test doesn't need articulation to produce the expected branch.
        val l = Layer5Behavioral()
        val a = landmarksWith(noseBridge = noseBridgeShifted(0f, 0f))
        val b = landmarksWith(noseBridge = noseBridgeShifted(0.02f, 0f))
        for (i in 0 until 10) {
            l.update(if (i % 2 == 0) a else b)
        }
        assertEquals(0.6f, l.behavioralScore, 1e-6f)
    }

    @Test
    fun `borderline articulated movement yields borderline score`() {
        // Alternate between 0 and 0.0007 nose shift → per-pair
        // displacement = 0.0007 ∈ [0.0005, 0.001). Branch 3 (BORDERLINE)
        // fires before the variance gate is consulted, so the result is
        // 0.5 regardless of articulation amplitude.
        val l = Layer5Behavioral()
        for (i in 0 until 60) {
            val dx = if (i % 2 == 0) 0f else 0.0007f
            val j = articulationFor(amplitude = 0.003f, seed = i.toLong())
            l.update(landmarksWith(
                noseBridge = noseBridgeShifted(dx, 0f),
                articulationJitter = j,
            ))
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
    fun `microsaccade not detected when face translates rigidly`() {
        // Photo-attack regression (Nov 2026): when the whole face
        // translates — iris AND nose-bridge moving together with no
        // relative eye motion — the head-relative velocity is 0 and
        // the detector must stay false, even though the absolute iris
        // delta is many times the threshold. Pre-fix detector read
        // rigid-body motion as saccades and attested L5 = 1.0 on a
        // printed-photo attack held in a moving hand.
        val l = Layer5Behavioral()
        val bigJump = Layer5Behavioral.MICROSACCADE_VELOCITY_THRESHOLD * 5f
        for (i in 0 until 4) {
            val shift = bigJump * i
            l.update(
                landmarksWith(
                    noseBridge = noseBridgeShifted(shift, 0f),
                    irisLeft = floatArrayOf(
                        0.40f + shift, 0.50f,
                        0.41f + shift, 0.50f,
                        0.40f + shift, 0.51f,
                        0.39f + shift, 0.50f,
                        0.40f + shift, 0.49f,
                    ),
                )
            )
        }
        assertFalse(l.microsaccadeDetected)
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

        // Post-reset, a fresh static sequence must land on natural score
        // (1.0 via the low-displacement bypass), proving the previous
        // session's state is fully gone — a stale non-zero history would
        // produce a different mean displacement and a different branch.
        repeat(5) { l.update(landmarksWith()) }
        assertEquals(1.0f, l.behavioralScore, 1e-6f)
    }
}
