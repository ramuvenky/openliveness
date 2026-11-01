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
         *  value at the ~30fps capture rate Layer 2 targets). */
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
    private var hasPreviousIris: Boolean = false

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
     * Feed one frame. Idempotent on the "insufficient data" states —
     * callers can safely drive this on every frame of the capture
     * pipeline, including before Layer 2 completes.
     */
    fun update(landmarks: FaceLandmarks) {
        pushNoseBridgeFrame(landmarks)
        updateMicrosaccadeState(landmarks)
        recomputeBehavioralScore()
    }

    fun reset() {
        noseBridgeHistory.clear()
        previousIrisLeftCenterX = 0f
        previousIrisLeftCenterY = 0f
        hasPreviousIris = false
        previousVelocityOverThreshold = false
        qualifyingPairCount = 0
        behavioralScore = 0f
        microsaccadeDetected = false
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
        var sumX = 0f
        var sumY = 0f
        for (idx in IRIS_LEFT_INDICES) {
            sumX += landmarks[idx].x
            sumY += landmarks[idx].y
        }
        val centerX = sumX / IRIS_LEFT_INDICES.size
        val centerY = sumY / IRIS_LEFT_INDICES.size

        if (!hasPreviousIris) {
            previousIrisLeftCenterX = centerX
            previousIrisLeftCenterY = centerY
            hasPreviousIris = true
            return
        }

        val dx = centerX - previousIrisLeftCenterX
        val dy = centerY - previousIrisLeftCenterY
        val velocity = sqrt(dx * dx + dy * dy)
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
        previousIrisLeftCenterX = centerX
        previousIrisLeftCenterY = centerY
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
        behavioralScore = scoreForMeanDisplacement(meanDisp)
    }

    /** Score ladder from Spec.md §1503-1506. `internal` so a regression
     *  test can pin each branch without constructing full frame sequences. */
    internal fun scoreForMeanDisplacement(meanDisp: Float): Float = when {
        meanDisp < DISPLACEMENT_SUSPICIOUS_UPPER -> SCORE_SUSPICIOUS
        meanDisp < DISPLACEMENT_BORDERLINE_UPPER -> SCORE_BORDERLINE
        meanDisp <= DISPLACEMENT_NATURAL_UPPER   -> SCORE_NATURAL
        else                                      -> SCORE_EXCESSIVE
    }

    // Test-only visibility onto buffer size — pins the sliding-window
    // invariant without exposing the internal FloatArray format.
    internal val historySizeForTest: Int get() = noseBridgeHistory.size
}
