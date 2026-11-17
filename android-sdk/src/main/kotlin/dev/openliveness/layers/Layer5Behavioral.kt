package dev.openliveness.layers

import kotlin.math.sqrt

/**
 * Layer 5 behavioral biometrics — micro-movement score + microsaccade
 * detection. Pure consumer of the MediaPipe landmark stream Layer 2 is
 * already running; see docs/Spec.md §1480-1530 for the authoritative
 * Python reference this file mirrors.
 *
 * Driven frame-by-frame via [update]. Both outputs are read from the
 * public properties after each update: callers typically snapshot them
 * at the end of a session (when CDLSession builds its LayerScores).
 *
 * Not thread-safe. One Layer5Behavioral instance per session; drive from
 * the same coroutine that pumps the landmark stream (same discipline as
 * Layer2Runner / the gesture detectors).
 *
 * **Numeric parity with the Python reference matters.** The server
 * re-runs the same math from `layer_scores` before issuing a JWT, so a
 * drift between this file and `core/algorithms/layer5_behavioral.py`
 * (Phase 5) makes attestations fail server-side verification. The
 * iOS mirror (Sources/OpenLiveness/Layers/Layer5Behavioral.swift) must
 * stay in lockstep — any threshold tweak here needs the sibling edit.
 */
class Layer5Behavioral {

    companion object {
        /** 60 frames ≈ 2s at 30fps — Spec.md §1487. */
        const val MICRO_MOVEMENT_WINDOW_FRAMES = 60

        /** Nose-bridge landmarks: stable under facial expressions, so
         *  their displacement reflects involuntary head micro-tremor
         *  rather than smile/blink motion. Spec.md §1488. */
        val NOSE_BRIDGE_INDICES = intArrayOf(6, 8, 9, 10)

        /** MediaPipe left-iris landmarks (468 center, 469-472 perimeter).
         *  The Python reference in Spec.md §1515-1521 computes velocity
         *  from the left-iris centroid only; the right iris (473-477) is
         *  listed in the comment but not used in the detector. We mirror
         *  the Python literal — using both eyes here would make the
         *  server's re-run disagree with the client's score. If Phase 5
         *  changes the Python to use both eyes, this needs to flip too. */
        val IRIS_LEFT_INDICES = intArrayOf(468, 469, 470, 471, 472)

        /** Spec.md §1523. Velocity measured in normalized image-space
         *  units per frame (not per second — the Python ref also skips
         *  fps normalization, so the threshold is implicitly a per-frame
         *  value at the ~30fps capture rate Layer 2 targets).
         *
         *  As of the Nov 2026 reweight, velocity is **head-relative**:
         *  per-frame iris centroid delta minus per-frame nose-bridge
         *  centroid delta. A rigid-body photo translation moves iris and
         *  nose together, so the head-relative velocity is ≈ 0 and the
         *  detector doesn't fire. For a stationary head the nose delta
         *  is ≈ 0 and the head-relative velocity equals the absolute
         *  velocity, so this threshold keeps its original meaning on
         *  genuine saccades. The Python mirror in
         *  `core/algorithms/layer5_behavioral.py` applies the same
         *  subtraction — drift between the two silently breaks the
         *  server's `layer_scores` re-run. */
        const val MICROSACCADE_VELOCITY_THRESHOLD = 0.005f

        /** Spec.md §1524-1529 counts the number of positions `i` where
         *  BOTH velocities[i] AND velocities[i+1] exceed the threshold,
         *  then reports detected when count ≥ 2. Equivalent to "three
         *  consecutive high-velocity frames" — a run of 3 produces 2
         *  qualifying pairs (0,1) and (1,2). Shorter runs (one brief
         *  jitter spike) don't fire, which is the point: real saccades
         *  cluster, isolated spikes are tracking noise. */
        const val MICROSACCADE_MIN_PAIR_COUNT = 2

        // Score branches from Spec.md §1503-1506. Kept as named constants
        // so the mapping is visible and testable without re-deriving the
        // Python if-ladder. The server's weighted-score re-run uses the
        // same cutoffs; a drift here is a silent score mismatch.
        const val DISPLACEMENT_SUSPICIOUS_UPPER = 0.0005f
        const val DISPLACEMENT_BORDERLINE_UPPER = 0.001f
        const val DISPLACEMENT_NATURAL_UPPER = 0.015f

        const val SCORE_SUSPICIOUS = 0.1f
        const val SCORE_BORDERLINE = 0.5f
        const val SCORE_NATURAL = 1.0f
        const val SCORE_EXCESSIVE = 0.6f

        /** Landmarks sampled for the rigid-body variance check. Chosen to
         *  span face regions that move differentially on a live user
         *  (eyes blink, mouth speaks, brow raises, iris darts) but
         *  translate as a rigid body on a held photo. Keep this list in
         *  sync with VARIANCE_SAMPLE_INDICES in
         *  `core/algorithms/layer5_behavioral.py` — the server re-runs
         *  the same math from `layer_scores` and a drift silently makes
         *  attestations fail verification. */
        val VARIANCE_SAMPLE_INDICES = intArrayOf(
            1,           // nose tip
            6, 8, 9, 10, // nose bridge
            13, 14,      // upper + lower lip center
            33, 133,     // left eye outer + inner corner
            263, 362,    // right eye outer + inner corner
            61, 291,     // mouth corners
            152,         // chin
            234, 454,    // face anchors (cheeks)
            468, 473,    // left + right iris centers
        )

        /** Threshold ABOVE which per-landmark motion variance indicates a
         *  photo-attack signature rather than a live face. MediaPipe's
         *  tracker thrashes when a 2D printed photo is waved in front of
         *  the camera — the mesh snaps between inconsistent fits each
         *  frame because there is no coherent 3D geometry to lock onto,
         *  producing chaotic per-landmark deltas. A live face, in
         *  contrast, moves differentially but the per-landmark motion
         *  vectors stay close to their mean because the underlying rigid
         *  skull constrains them.
         *
         *  This inverts the earlier (Nov 2026 initial deployment) model
         *  which assumed photos would exhibit LOW variance (rigid-body
         *  translation). On-device N=14 calibration showed the opposite:
         *  live variance peaked at 2.38e-5 while photo variance bottomed
         *  at 5.57e-5. The 3e-5 threshold has ~1.3× margin above live-max
         *  and ~1.9× margin below photo-min.
         *
         *  Only consulted when nose-bridge displacement lands ABOVE
         *  [VARIANCE_GATE_DISPLACEMENT_FLOOR] — see that constant for
         *  why the floor exists. */
        const val LIVE_VARIANCE_UPPER = 3e-5f

        /** Displacement floor below which the variance gate is NOT
         *  consulted. Live users holding mostly still accumulate small
         *  nose-bridge drift (camera noise + micro-tremor) that can push
         *  mean displacement into the [SUSPICIOUS_UPPER, BORDERLINE_UPPER]
         *  band with variance anywhere from 9.66e-7 up to the live-max
         *  2.38e-5. Only firing the variance gate when displacement
         *  clears 0.002 carves out the still-live-with-drift case; above
         *  the floor, variance has more signal because the subject is
         *  unambiguously in motion and photo-attack thrashing becomes
         *  distinguishable from live-face articulation. */
        const val VARIANCE_GATE_DISPLACEMENT_FLOOR = 0.002f
    }

    /**
     * Compact per-frame nose-bridge snapshot. Storing just the 4 (x,y)
     * pairs we read — a FaceLandmark allocation per index per frame would
     * burn ~16 KB of GC pressure across the 60-frame window at 30fps,
     * which showed up as ~2% jitter in the Layer2Runner profile. Using a
     * flat FloatArray(8) is a hundred bytes amortized.
     */
    private val noseBridgeHistory: ArrayDeque<FloatArray> = ArrayDeque()
    private var previousIrisLeftCenterX: Float = 0f
    private var previousIrisLeftCenterY: Float = 0f
    private var previousNoseBridgeCenterX: Float = 0f
    private var previousNoseBridgeCenterY: Float = 0f
    private var hasPreviousIris: Boolean = false

    /** Flat (x, y) snapshot of the last frame's VARIANCE_SAMPLE_INDICES
     *  values so we can compute per-landmark motion vectors without
     *  storing the whole FaceLandmarks list (holding 478 landmarks per
     *  frame × a 60-frame window is ~230 KB of GC pressure). */
    private val varianceSamplesPrevious: FloatArray =
        FloatArray(VARIANCE_SAMPLE_INDICES.size * 2)
    private var hasVariancePrev: Boolean = false

    /** Rolling window of per-pair motion-variance values. Kept to the
     *  same length as noseBridgeHistory so both signals share a window. */
    private val varianceHistory: ArrayDeque<Float> = ArrayDeque()

    /** True when velocities[i-1] exceeded threshold on the LAST update() —
     *  the Python iterates adjacent pairs; we fold that into O(1) state
     *  by remembering just the prior frame's over-threshold bit. */
    private var previousVelocityOverThreshold: Boolean = false

    /** Running tally of qualifying (velocities[i], velocities[i+1]) pairs
     *  per Spec.md §1524. Flagged as detected when it crosses the min. */
    private var qualifyingPairCount: Int = 0

    /**
     * Score in [0.0, 1.0] from Spec.md §1500-1506. Zero until the window
     * has at least 2 frames of nose-bridge history (no displacement
     * computable from a single frame). Callers that read this before any
     * [update] call see the 0.0 initial — matches the stub shape the
     * orchestrator previously consumed.
     */
    var behavioralScore: Float = 0f
        private set

    /**
     * Spec.md §1529 — true once two qualifying velocity pairs have been
     * observed in this session. Latches to true for the lifetime of the
     * instance; a real eye's involuntary saccades don't un-happen. A new
     * session gets a new Layer5Behavioral (or an explicit [reset]).
     */
    var microsaccadeDetected: Boolean = false
        private set

    /**
     * Mean motion variance across VARIANCE_SAMPLE_INDICES over the sliding
     * window. Folded into [behavioralScore] via [scoreForMeanDisplacement]
     * as a rigid-body discriminator, but only consulted when nose-bridge
     * displacement indicates motion is present — a still subject (live or
     * photo) skips the gate entirely. Also logged via CDLSession for
     * post-mortem telemetry. Returns `Float.MAX_VALUE` before the window
     * has ≥ 1 pair, which the ladder reads as "variance evidence not yet
     * available, fall through to displacement-only branches".
     */
    val motionVariance: Float
        get() {
            if (varianceHistory.isEmpty()) return Float.MAX_VALUE
            var sum = 0f
            for (v in varianceHistory) sum += v
            return sum / varianceHistory.size
        }

    /** Mean nose-bridge displacement over the sliding window — the
     *  primary input to the score ladder. Logged alongside [motionVariance]
     *  via CDLSession so on-device sessions produce the (meanDisp,
     *  motionVariance) pair needed to tune the rigid-body gate's
     *  thresholds. Zero before the window has ≥ 2 frames. */
    var meanDisplacement: Float = 0f
        private set

    /**
     * Feed one frame. Idempotent on the "insufficient data" states —
     * callers can safely drive this on every frame of the capture
     * pipeline, including before Layer 2 completes.
     */
    fun update(landmarks: FaceLandmarks) {
        pushNoseBridgeFrame(landmarks)
        updateMicrosaccadeState(landmarks)
        updateMotionVariance(landmarks)
        recomputeBehavioralScore()
    }

    fun reset() {
        noseBridgeHistory.clear()
        previousIrisLeftCenterX = 0f
        previousIrisLeftCenterY = 0f
        previousNoseBridgeCenterX = 0f
        previousNoseBridgeCenterY = 0f
        hasPreviousIris = false
        previousVelocityOverThreshold = false
        qualifyingPairCount = 0
        behavioralScore = 0f
        meanDisplacement = 0f
        microsaccadeDetected = false
        hasVariancePrev = false
        varianceHistory.clear()
    }

    private fun pushNoseBridgeFrame(landmarks: FaceLandmarks) {
        val flat = FloatArray(NOSE_BRIDGE_INDICES.size * 2)
        for (k in NOSE_BRIDGE_INDICES.indices) {
            val lm = landmarks[NOSE_BRIDGE_INDICES[k]]
            flat[k * 2]     = lm.x
            flat[k * 2 + 1] = lm.y
        }
        noseBridgeHistory.addLast(flat)
        while (noseBridgeHistory.size > MICRO_MOVEMENT_WINDOW_FRAMES) {
            noseBridgeHistory.removeFirst()
        }
    }

    private fun updateMicrosaccadeState(landmarks: FaceLandmarks) {
        var irisSumX = 0f
        var irisSumY = 0f
        for (idx in IRIS_LEFT_INDICES) {
            irisSumX += landmarks[idx].x
            irisSumY += landmarks[idx].y
        }
        val irisCenterX = irisSumX / IRIS_LEFT_INDICES.size
        val irisCenterY = irisSumY / IRIS_LEFT_INDICES.size

        var noseSumX = 0f
        var noseSumY = 0f
        for (idx in NOSE_BRIDGE_INDICES) {
            noseSumX += landmarks[idx].x
            noseSumY += landmarks[idx].y
        }
        val noseCenterX = noseSumX / NOSE_BRIDGE_INDICES.size
        val noseCenterY = noseSumY / NOSE_BRIDGE_INDICES.size

        if (!hasPreviousIris) {
            previousIrisLeftCenterX = irisCenterX
            previousIrisLeftCenterY = irisCenterY
            previousNoseBridgeCenterX = noseCenterX
            previousNoseBridgeCenterY = noseCenterY
            hasPreviousIris = true
            return
        }

        // Head-relative iris velocity: subtract the nose-bridge delta so
        // a rigid-body photo translation (iris and nose moving together)
        // cancels to 0 and doesn't fire the detector. The Python mirror
        // in `core/algorithms/layer5_behavioral.py` applies the same
        // subtraction — a drift here silently breaks the server re-run.
        val irisDx = irisCenterX - previousIrisLeftCenterX
        val irisDy = irisCenterY - previousIrisLeftCenterY
        val noseDx = noseCenterX - previousNoseBridgeCenterX
        val noseDy = noseCenterY - previousNoseBridgeCenterY
        val relDx = irisDx - noseDx
        val relDy = irisDy - noseDy
        val velocity = sqrt(relDx * relDx + relDy * relDy)
        val overThresholdNow = velocity > MICROSACCADE_VELOCITY_THRESHOLD

        // Python §1524-1526: count positions i where velocities[i] AND
        // velocities[i+1] both exceed threshold. We're at velocity index
        // `n` on this call; the pair we just completed is (n-1, n). If
        // both ends are over threshold, it's a qualifying pair.
        if (overThresholdNow && previousVelocityOverThreshold) {
            qualifyingPairCount++
            if (qualifyingPairCount >= MICROSACCADE_MIN_PAIR_COUNT) {
                microsaccadeDetected = true
            }
        }
        previousVelocityOverThreshold = overThresholdNow
        previousIrisLeftCenterX = irisCenterX
        previousIrisLeftCenterY = irisCenterY
        previousNoseBridgeCenterX = noseCenterX
        previousNoseBridgeCenterY = noseCenterY
    }

    private fun recomputeBehavioralScore() {
        if (noseBridgeHistory.size < 2) return

        var displacementSum = 0f
        var pairCount = 0
        val iter = noseBridgeHistory.iterator()
        var prev = iter.next()
        while (iter.hasNext()) {
            val cur = iter.next()
            var frameDispSum = 0f
            for (k in NOSE_BRIDGE_INDICES.indices) {
                val dx = cur[k * 2]     - prev[k * 2]
                val dy = cur[k * 2 + 1] - prev[k * 2 + 1]
                frameDispSum += sqrt(dx * dx + dy * dy)
            }
            // Python §1498 appends `np.mean(frame_disp)` — the per-frame
            // mean across the 4 nose-bridge indices. Dividing by the
            // index count here matches that reduction exactly, so the
            // per-pair contribution and the final mean-of-means stay
            // numerically identical to the Python ref.
            displacementSum += frameDispSum / NOSE_BRIDGE_INDICES.size
            prev = cur
            pairCount++
        }
        val meanDisp = displacementSum / pairCount
        meanDisplacement = meanDisp
        behavioralScore = scoreForMeanDisplacement(meanDisp, motionVariance)
    }

    /** Per-pair motion-variance accumulator for the rigid-body check.
     *  Called from [update] after [pushNoseBridgeFrame] so the window
     *  lengths stay in sync. */
    private fun updateMotionVariance(landmarks: FaceLandmarks) {
        val n = VARIANCE_SAMPLE_INDICES.size
        val cur = FloatArray(n * 2)
        for (k in 0 until n) {
            val lm = landmarks[VARIANCE_SAMPLE_INDICES[k]]
            cur[k * 2]     = lm.x
            cur[k * 2 + 1] = lm.y
        }
        if (!hasVariancePrev) {
            System.arraycopy(cur, 0, varianceSamplesPrevious, 0, cur.size)
            hasVariancePrev = true
            return
        }
        // Compute per-landmark motion (dx, dy), their mean (μx, μy), then
        // variance = mean of ||(dx - μx, dy - μy)||². On a live face the
        // rigid skull constrains per-landmark motion so residuals stay
        // small; on a photo being waved in front of the camera MediaPipe's
        // tracker snaps between inconsistent fits each frame and residuals
        // balloon. See LIVE_VARIANCE_UPPER for the empirical threshold.
        var sumDx = 0f
        var sumDy = 0f
        val deltas = FloatArray(n * 2)
        for (k in 0 until n) {
            val dx = cur[k * 2]     - varianceSamplesPrevious[k * 2]
            val dy = cur[k * 2 + 1] - varianceSamplesPrevious[k * 2 + 1]
            deltas[k * 2]     = dx
            deltas[k * 2 + 1] = dy
            sumDx += dx
            sumDy += dy
        }
        val meanDx = sumDx / n
        val meanDy = sumDy / n
        var sumSq = 0f
        for (k in 0 until n) {
            val rx = deltas[k * 2]     - meanDx
            val ry = deltas[k * 2 + 1] - meanDy
            sumSq += rx * rx + ry * ry
        }
        val variance = sumSq / n
        varianceHistory.addLast(variance)
        while (varianceHistory.size > MICRO_MOVEMENT_WINDOW_FRAMES) {
            varianceHistory.removeFirst()
        }
        System.arraycopy(cur, 0, varianceSamplesPrevious, 0, cur.size)
    }

    /** Score ladder combining nose-bridge displacement with per-landmark
     *  motion variance. `internal` so a regression test can pin each
     *  branch without constructing full frame sequences.
     *
     *  Branch order:
     *
     *   1. `meanDisp > NATURAL_UPPER`      → EXCESSIVE (too much motion)
     *   2. `meanDisp < SUSPICIOUS_UPPER`   → NATURAL (still subject —
     *      L2 challenges catch still-photo cases, so L5 explicitly does
     *      not try to split live-still from photo-still)
     *   3. `meanDisp < BORDERLINE_UPPER`   → BORDERLINE (drift-level
     *      motion, variance gate NOT consulted — live-still-with-drift
     *      users fall here and we don't want to false-reject them)
     *   4. `meanDisp < VARIANCE_GATE_DISPLACEMENT_FLOOR` → NATURAL
     *      (subject moving a bit but not clearly enough to trust the
     *      variance gate; see constant doc for the empirical reason)
     *   5. `motionVariance > LIVE_VARIANCE_UPPER` → SUSPICIOUS (subject
     *      is unambiguously in motion AND per-landmark deltas are
     *      chaotic — MediaPipe's tracker thrashing on a 2D photo being
     *      waved in front of the camera)
     *   6. otherwise                       → NATURAL
     *
     *  Calibration source (Nov 2026 on-device N=14): live variance
     *  peaked at 2.38e-5, photo variance bottomed at 5.57e-5. Threshold
     *  3e-5 sits with ~1.3× margin above live-max and ~1.9× margin
     *  below photo-min. */
    internal fun scoreForMeanDisplacement(meanDisp: Float, motionVariance: Float): Float = when {
        meanDisp > DISPLACEMENT_NATURAL_UPPER -> SCORE_EXCESSIVE
        meanDisp < DISPLACEMENT_SUSPICIOUS_UPPER -> SCORE_NATURAL
        meanDisp < DISPLACEMENT_BORDERLINE_UPPER -> SCORE_BORDERLINE
        meanDisp < VARIANCE_GATE_DISPLACEMENT_FLOOR -> SCORE_NATURAL
        motionVariance > LIVE_VARIANCE_UPPER -> SCORE_SUSPICIOUS
        else -> SCORE_NATURAL
    }

    // Test-only visibility onto buffer size — pins the sliding-window
    // invariant without exposing the internal FloatArray format.
    internal val historySizeForTest: Int get() = noseBridgeHistory.size
}
