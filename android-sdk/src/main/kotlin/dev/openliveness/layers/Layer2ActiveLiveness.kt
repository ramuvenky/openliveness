package dev.openliveness.layers

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Normalized MediaPipe Face Mesh landmark. x and y are in [0, 1] (image
 * space); z is pseudo-depth and unused by the current detectors — kept so
 * future detectors (e.g., a true 3D pitch estimate for NOD) can read it
 * without a landmark-model change.
 */
data class FaceLandmark(val x: Float, val y: Float, val z: Float = 0f)

/**
 * 478-point MediaPipe Face Mesh output for a single frame. The count is
 * enforced because every detector in this file reads specific indices
 * (e.g., 159, 145, 1, 234, 454) and silently clipping to fewer landmarks
 * would make the gesture detectors produce correlated garbage instead of
 * failing loudly.
 *
 * The direct constructor throws on bad input. Camera / MediaPipe adapters
 * that may legitimately see empty-face frames should use [tryFrom] so a
 * face-not-detected tick becomes a null (dropped frame) rather than an
 * exception that unwinds the capture pipeline. The direct constructor
 * stays for callers that have already filtered.
 */
class FaceLandmarks(val points: List<FaceLandmark>) {
    companion object {
        const val EXPECTED_COUNT = 478

        /**
         * Safe factory for stream adapters: returns null when the input
         * doesn't look like a MediaPipe face mesh output (empty, partial,
         * or wrong model). Mirrors iOS FaceLandmarks.validating.
         */
        fun tryFrom(points: List<FaceLandmark>): FaceLandmarks? =
            if (points.size == EXPECTED_COUNT) FaceLandmarks(points) else null
    }
    init {
        require(points.size == EXPECTED_COUNT) {
            "FaceMesh expects $EXPECTED_COUNT landmarks, got ${points.size}"
        }
    }
    operator fun get(i: Int): FaceLandmark = points[i]
}

private fun distance2D(a: FaceLandmark, b: FaceLandmark): Float {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/**
 * Common surface for every gesture detector so ChallengeSequencer can
 * drive them polymorphically. The timestamp arg is required by the
 * interface so the sequencer can pass it uniformly; frame-counted
 * detectors (blink, gaze, smile) ignore it. Keeping a single signature
 * avoids the alternative of a type-switch inside the sequencer, which
 * would scatter per-detector knowledge.
 *
 * Not thread-safe by design: each detector carries mutable state (frame
 * counters, buffers) and is only ever driven by a single Layer2Runner
 * instance on one coroutine. Sharing a detector across runners races.
 */
interface GestureDetector {
    fun update(landmarks: FaceLandmarks, timestamp: Double): Boolean
    fun reset()
}

/**
 * BLINK — EAR = distance(159, 145) / distance(33, 133). Fires when EAR
 * drops below 0.2 for 2+ consecutive frames (eye closed) and then rises
 * back above 0.25 (eye reopened). The reopen gate is what makes this
 * resistant to a static closed-eyes photo: a replay never triggers the
 * closed→open transition.
 *
 * Note on repeated firing: `closedFrames` and `waitingForReopen` both
 * reset after a fire, so a sustained close→open→close→open stream can
 * satisfy a *second* blink step after another full cycle. The sequencer
 * never puts two blink steps in a row, so this is dormant today; a
 * future gesture list that does must add a cooldown or require a
 * neutral intermediate frame.
 */
class BlinkDetector : GestureDetector {
    companion object {
        const val CLOSED_THRESHOLD = 0.2f
        const val OPEN_THRESHOLD = 0.25f
        const val MIN_CLOSED_FRAMES = 2
    }

    private var closedFrames = 0
    private var waitingForReopen = false

    // Convenience for frame-counted detectors: callers that don't track
    // time can skip the timestamp arg. The sequencer always uses the
    // 2-arg interface form so the ignored arg stays consistent.
    fun update(landmarks: FaceLandmarks): Boolean = update(landmarks, 0.0)

    override fun update(landmarks: FaceLandmarks, timestamp: Double): Boolean {
        val eyeWidth = distance2D(landmarks[33], landmarks[133])
        if (eyeWidth <= 0f) {
            // Face-tracking loss (0-width eye = degenerate landmarks). Clear
            // the latch: holding `waitingForReopen` across lost frames would
            // let a lost-then-reacquired tracking gap fire a false blink on
            // the next open eye, which a sustained-closed-eye presentation
            // attack could exploit by nudging the camera off the face briefly.
            closedFrames = 0
            waitingForReopen = false
            return false
        }
        val ear = distance2D(landmarks[159], landmarks[145]) / eyeWidth

        if (waitingForReopen) {
            if (ear > OPEN_THRESHOLD) {
                waitingForReopen = false
                closedFrames = 0
                return true
            }
            return false
        }

        if (ear < CLOSED_THRESHOLD) {
            closedFrames++
            if (closedFrames >= MIN_CLOSED_FRAMES) waitingForReopen = true
        } else {
            closedFrames = 0
        }
        return false
    }

    override fun reset() {
        closedFrames = 0
        waitingForReopen = false
    }
}

enum class GazeDirection { LEFT, RIGHT }

/**
 * LOOK_LEFT / LOOK_RIGHT — offset = (landmark[1].x − face_center_x) / face_width.
 * Face anchors are landmarks[234] and [454]; nose tip is landmark[1].
 * Fires after 3+ consecutive frames with offset past ±0.15 in the chosen
 * direction. 3 frames (≈100 ms at 30fps) filters out micro-head-sway
 * without rejecting an actual deliberate turn.
 *
 * Note on repeated firing: `consecutiveFrames` resets to 0 after a fire,
 * so holding a sustained turn can satisfy a *second* identical step after
 * another 3 frames. The sequencer never puts two same-direction steps in
 * a row, so this is dormant today; a future gesture list that does must
 * add a cooldown or require a neutral intermediate frame.
 */
class GazeDetector(val direction: GazeDirection) : GestureDetector {
    companion object {
        const val OFFSET_THRESHOLD = 0.15f
        const val MIN_CONSECUTIVE_FRAMES = 3
    }

    private var consecutiveFrames = 0

    fun update(landmarks: FaceLandmarks): Boolean = update(landmarks, 0.0)

    override fun update(landmarks: FaceLandmarks, timestamp: Double): Boolean {
        val faceCenterX = (landmarks[234].x + landmarks[454].x) / 2f
        val faceWidth = abs(landmarks[454].x - landmarks[234].x)
        if (faceWidth <= 0f) return false
        val offset = (landmarks[1].x - faceCenterX) / faceWidth

        val meets = when (direction) {
            GazeDirection.LEFT  -> offset < -OFFSET_THRESHOLD
            GazeDirection.RIGHT -> offset >  OFFSET_THRESHOLD
        }

        if (meets) {
            consecutiveFrames++
            if (consecutiveFrames >= MIN_CONSECUTIVE_FRAMES) {
                consecutiveFrames = 0
                return true
            }
        } else {
            consecutiveFrames = 0
        }
        return false
    }

    override fun reset() { consecutiveFrames = 0 }
}

/**
 * SMILE — current_mouth_width > neutral_mouth_width × 1.2 for 5+ frames.
 * mouth_width = distance(landmark[61], landmark[291]) (mouth corners).
 * neutral is calibrated as the MEDIAN mouth_width across the first 10
 * frames. Median instead of mean (H-4) so an eager user who smiles for a
 * minority of the warmup window can't drag the baseline up and lock
 * themselves out of ever firing — the median picks the relaxed middle
 * value and ignores the smile outliers. Callers should still prompt
 * users to relax during warmup; this is a hardening, not a license to
 * skip the UX cue.
 */
class SmileDetector : GestureDetector {
    companion object {
        const val WIDTH_MULTIPLIER = 1.2f
        const val MIN_CONSECUTIVE_FRAMES = 5
        const val CALIBRATION_FRAMES = 10
    }

    private val neutralSamples = mutableListOf<Float>()
    private var consecutiveFrames = 0

    val neutralMouthWidth: Float?
        get() = if (neutralSamples.size >= CALIBRATION_FRAMES) median(neutralSamples) else null

    private fun median(xs: List<Float>): Float {
        val sorted = xs.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2]
            else (sorted[n / 2 - 1] + sorted[n / 2]) / 2f
    }

    fun update(landmarks: FaceLandmarks): Boolean = update(landmarks, 0.0)

    override fun update(landmarks: FaceLandmarks, timestamp: Double): Boolean {
        val mouthWidth = distance2D(landmarks[61], landmarks[291])

        if (neutralSamples.size < CALIBRATION_FRAMES) {
            // Skip degenerate frames (face lost, zero-width mouth). Including
            // them would drag the baseline toward 0, after which the 1.2×
            // trigger would fire on any normal mouth motion — a false pass.
            if (mouthWidth <= 0f) return false
            neutralSamples.add(mouthWidth)
            return false
        }

        val neutral = neutralMouthWidth ?: return false
        if (neutral <= 0f) return false
        if (mouthWidth > neutral * WIDTH_MULTIPLIER) {
            consecutiveFrames++
            if (consecutiveFrames >= MIN_CONSECUTIVE_FRAMES) {
                consecutiveFrames = 0
                return true
            }
        } else {
            consecutiveFrames = 0
        }
        return false
    }

    override fun reset() {
        neutralSamples.clear()
        consecutiveFrames = 0
    }
}

/**
 * NOD — pitch proxy = (chin.y − forehead.y) / face_width. Fires when
 * (max − min) of the proxy within a 1-second window exceeds the threshold.
 * We use a vertical-delta proxy rather than a true atan2 pitch because
 * MediaPipe's z coordinate is a weak depth estimate on 2D camera feeds.
 *
 * Face-width normalization (H-3): the raw y-delta scales with the user's
 * distance from the camera — a close user's face spans ~0.6 of image
 * height, a far user's ~0.2, so a fixed-threshold-in-raw-coords clears
 * trivially for close users and may never clear for far ones. Dividing
 * by face_width (horizontal anchor-to-anchor) cancels the distance factor
 * because both numerator and denominator shrink together as the user
 * moves away. The threshold is now interpreted as "fraction of face
 * width"; frames with a degenerate face_width (<=0) are dropped so we
 * don't divide by zero.
 */
class NodDetector : GestureDetector {
    companion object {
        /** Threshold in units of face-widths. ~0.15 face-widths of
         *  normalized pitch-proxy change inside the 1s window ≈ a
         *  deliberate head nod across user-camera distances. */
        const val PITCH_DELTA_THRESHOLD = 0.15f
        const val WINDOW_SECONDS = 1.0

        /**
         * Hard cap on buffer size. The window filter alone is not enough:
         * non-monotonic or backward-moving timestamps (resumed app, clock
         * jump, same-timestamp frame storm) can keep every sample under
         * the cutoff forever. 240 ≈ 4s at 60fps, comfortably past the 1s
         * window; large enough that normal operation never trims.
         */
        const val MAX_SAMPLES = 240
    }

    private data class Sample(val timestamp: Double, val pitch: Float)
    // ArrayDeque instead of ArrayList: `removeFirst()` is O(1), which
    // matters under adversarial cap-trim pressure (same-timestamp storm,
    // backward-walking timestamps). On ArrayList, `removeAt(0)` is O(n)
    // and the cap-trim loop degraded to O(n²) per burst.
    private val buffer: ArrayDeque<Sample> = ArrayDeque()

    override fun update(landmarks: FaceLandmarks, timestamp: Double): Boolean {
        // Face width = horizontal distance between the two face anchors
        // (same reference used by GazeDetector for consistency). Drop
        // degenerate frames (face lost) so a zero-width frame doesn't
        // divide by zero or inject a bogus sample into the window.
        val faceWidth = abs(landmarks[454].x - landmarks[234].x)
        if (faceWidth <= 0f) return false
        val pitch = (landmarks[152].y - landmarks[10].y) / faceWidth
        buffer.addLast(Sample(timestamp, pitch))
        val cutoff = timestamp - WINDOW_SECONDS
        buffer.removeAll { it.timestamp < cutoff }
        // Belt-and-suspenders bound after the window filter so a hostile
        // or buggy stream can't retain unbounded samples (see MAX_SAMPLES).
        // O(1) per removal on ArrayDeque.
        while (buffer.size > MAX_SAMPLES) buffer.removeFirst()

        val minP = buffer.minOfOrNull { it.pitch } ?: return false
        val maxP = buffer.maxOfOrNull { it.pitch } ?: return false
        if (maxP - minP > PITCH_DELTA_THRESHOLD) {
            buffer.clear()
            return true
        }
        return false
    }

    override fun reset() { buffer.clear() }

    // Test-only hook: pins that the cap holds under adversarial timestamp
    // inputs. `internal` keeps this off the public ABI while still visible
    // to the test module. Not for production reads — the buffer's shape
    // is an implementation detail that may change (e.g., ring buffer).
    internal val bufferSizeForTest: Int get() = buffer.size
}
