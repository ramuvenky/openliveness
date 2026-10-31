package dev.openliveness.layers

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One POS-rPPG session's final result. Mirrors the Python reference's
 * return dict in Spec §1394-1399, plus `snrDb` for diagnostics so the
 * sample app can display a quality readout and server-side triage can
 * disambiguate "no signal" (low SNR) from "wrong-band pulse" (high
 * SNR but out-of-range bpm).
 */
data class Layer3Result(
    val bpm: Float,
    val pulseDetected: Boolean,
    val bpmInRange: Boolean,
    val confidence: Float,
    val snrDb: Float,
)

/**
 * Phase 4 Step 4 — Plane-Orthogonal-to-Skin (POS) rPPG per Wang et al.
 * 2015, Spec §1356-1405.
 *
 * Collects per-frame mean R/G/B samples from [SkinRegionExtractor] via
 * [RGBSampleSink]; at [compute] projects to the skin-orthogonal plane,
 * runs a real-signal DFT, and reports bpm + confidence over the
 * physiological band 0.7-3.0 Hz (42-180 BPM; valid-user window 50-110).
 *
 * **Deviation from Spec Python reference**: the Spec's Step-4 Butterworth
 * `filtfilt` bandpass is omitted. The subsequent FFT peak-finding
 * already restricts to [LOW_FREQ_HZ, HIGH_FREQ_HZ], and SNR is computed
 * only across in-band bins — so a bandpass-then-FFT is mathematically
 * equivalent to a windowed-FFT with the out-of-band bins ignored (and
 * the latter avoids reproducing scipy's filtfilt edge-handling in
 * Kotlin, which has no stable numerical reference). The server verifies
 * the output scores, not the intermediate time-domain signal, so the
 * deviation is numerically invisible to the attestation chain. A DC
 * subtract before the FFT handles the slow-drift (sub-0.7-Hz) baseline
 * the Butterworth would also remove.
 *
 * **Threading**: [onSample] is invoked on CameraX's analyzer executor
 * (same thread as [MediaPipeLandmarkStream.analyze]); [compute] is
 * called from CDLSession's coroutine at session end. Both halves
 * synchronize on `this` — contested-case cost is sub-microsecond on
 * JVM biased locks, and the sample path is uncontested in practice
 * (compute never fires until after the stream is drained).
 *
 * **iOS mirror**: `Sources/OpenLiveness/Layers/Layer3RPPG.swift`
 * (Phase 4 iOS, pending Apple Dev enrollment) must reproduce the POS
 * projection + DFT peak-finding bit-for-bit — any deviation would show
 * up as a decision-boundary split between the two platforms on the
 * same user. Numeric constants here (SNR thresholds, confidence
 * ramp, bpm window) are the shared ground truth across all three
 * (Android, iOS, Phase-5 Python).
 */
class Layer3RPPG : RGBSampleSink {

    companion object {
        /** Nominal capture rate; actual rate is derived from timestamps
         *  on [compute] and used for the frequency axis so a 24fps
         *  thermal-throttled stream doesn't shift the bpm estimate by
         *  20%. The nominal is used only as the pre-session cap and
         *  the fallback when timestamps collapse. */
        const val NOMINAL_FS: Float = 30f

        /** Spec §1385 — valid pulse band. Values outside are either DC
         *  drift / lighting flicker (sub-0.7-Hz) or AC-hum harmonics /
         *  motion artefact (above 3.0-Hz). */
        const val LOW_FREQ_HZ: Float = 0.7f
        const val HIGH_FREQ_HZ: Float = 3.0f

        /** Spec §1397 — valid-user bpm window. 50 is the resting floor
         *  for adults; 110 is a very generous ceiling that still
         *  excludes motion artefacts (which pile up at the Nyquist
         *  edge). */
        const val BPM_IN_RANGE_LO: Float = 50f
        const val BPM_IN_RANGE_HI: Float = 110f

        /** Spec §1396 + §1403-1404. In low light the camera noise floor
         *  rises, so we both relax the detection threshold AND multiply
         *  the confidence down — the signal is less reliable even when
         *  it clears the (lower) bar. */
        const val SNR_THRESHOLD_DB_NORMAL: Float = 3.0f
        const val SNR_THRESHOLD_DB_LOW_LIGHT: Float = 2.0f
        const val LOW_LIGHT_CONFIDENCE_SCALE: Float = 0.7f

        /** Spec §1360 — minimum 10 seconds at 30fps = 300 samples. In
         *  low light we require 20s (§1403) because the extra window
         *  compensates for the lower per-frame SNR via longer
         *  integration. */
        const val MIN_SAMPLES_NORMAL: Int = 300
        const val MIN_SAMPLES_LOW_LIGHT: Int = 600

        /** Hard cap — stop collecting after 30s even if the session
         *  runs longer, so a stuck sampler doesn't eat unbounded heap.
         *  30s window is already well past the point of diminishing
         *  returns for rPPG SNR (the pulse estimate stabilises by ~15s
         *  on a well-lit face). */
        const val MAX_SAMPLES: Int = 900

        /** SNR → confidence ramp denominator (Spec §1398). 10 dB above
         *  the detection threshold maps to confidence=1.0; the ramp is
         *  linear in dB. Snapshotted here so the ladder is visible
         *  without re-deriving the Python expression. */
        const val CONFIDENCE_RAMP_DB: Float = 10f

        /** Physical sanity bounds on the derived capture rate. A session
         *  that reports fs outside this window is almost certainly a
         *  wall-clock bug (duplicate timestamps packed into a tiny span,
         *  or a bimodal stream where early frames landed before the
         *  camera actually started) — null is safer than feeding a
         *  garbage frequency axis into the DFT.
         *
         *  Lower bound = NOMINAL_FS * 0.5 (15 Hz) is already below any
         *  CameraX ImageAnalysis default; upper bound = NOMINAL_FS * 3
         *  (90 Hz) covers 60-fps streams with jitter headroom. */
        const val MIN_PHYSICAL_FS: Float = NOMINAL_FS * 0.5f
        const val MAX_PHYSICAL_FS: Float = NOMINAL_FS * 3f

        /**
         * Pure confidence mapping: dB SNR → [0, 1]. Anchored to the
         * ACTIVE threshold (the one `pulseDetected` is compared against)
         * so a low-light session's confidence can't go positive while
         * pulseDetected is false. The LOW_LIGHT_CONFIDENCE_SCALE then
         * damps the result (§1405) because low-light SNR is less
         * trustworthy at any absolute dB level.
         *
         * `internal` so a JVM unit test can pin the mapping directly
         * without reconstructing a full RGB session.
         */
        @JvmStatic
        internal fun confidenceForSnr(snrDb: Float, lowLight: Boolean): Float {
            val threshold = if (lowLight) SNR_THRESHOLD_DB_LOW_LIGHT else SNR_THRESHOLD_DB_NORMAL
            var c = ((snrDb - threshold) / CONFIDENCE_RAMP_DB).coerceIn(0f, 1f)
            if (lowLight) c *= LOW_LIGHT_CONFIDENCE_SCALE
            return c
        }
    }

    private val reds = ArrayList<Float>(MAX_SAMPLES)
    private val greens = ArrayList<Float>(MAX_SAMPLES)
    private val blues = ArrayList<Float>(MAX_SAMPLES)
    private var firstTimestampSec: Double = 0.0
    private var lastTimestampSec: Double = 0.0

    /** Latches true the first time [compute] runs. Freezes [onSample]
     *  so a late analyzer-thread frame arriving after the orchestrator
     *  has already read scores can't change a future [compute] call's
     *  output. The latch guards the SAMPLE BUFFER, not the result:
     *  callers may legitimately re-invoke [compute] with a different
     *  `lowLight` and expect a correspondingly-different answer; what
     *  must stay stable across those calls is the underlying R/G/B
     *  sequence. Volatile + guarded by `synchronized(this)` on the
     *  write side; the read in [onSample] is a cheap short-circuit
     *  before the lock. */
    private val finalised = AtomicBoolean(false)

    override fun onSample(sample: RGBSample) {
        if (finalised.get()) return
        synchronized(this) {
            if (finalised.get()) return
            val n = reds.size
            if (n >= MAX_SAMPLES) return
            if (n == 0) firstTimestampSec = sample.timestampSeconds
            lastTimestampSec = sample.timestampSeconds
            reds.add(sample.red)
            greens.add(sample.green)
            blues.add(sample.blue)
        }
    }

    /** Sample count, exposed for progress UIs and tests. */
    val sampleCount: Int get() = synchronized(this) { reds.size }

    fun reset() = synchronized(this) {
        reds.clear()
        greens.clear()
        blues.clear()
        firstTimestampSec = 0.0
        lastTimestampSec = 0.0
        finalised.set(false)
    }

    /**
     * Finalise and return the rPPG result, or null if the session
     * doesn't have enough samples / the signal is degenerate. The first
     * call latches [finalised] so no subsequent [onSample] can leak a
     * late analyzer-thread frame into the buffer; subsequent calls
     * recompute off the frozen buffer (so a caller can e.g. compare
     * normal vs low-light interpretations of the same capture without
     * tripping the race). Callers that want to re-use this instance
     * for a new session must [reset] first.
     *
     * @param lowLight read from [AmbientConditions.lowLightDetected].
     *   Shifts both the min-sample bar and the detection threshold.
     */
    fun compute(lowLight: Boolean = false): Layer3Result? {
        val minSamples = if (lowLight) MIN_SAMPLES_LOW_LIGHT else MIN_SAMPLES_NORMAL
        // Only latch when the buffer has enough samples to actually
        // commit to a session. An insufficient-samples null is a soft
        // "not yet" — a legitimate flow is reset() → wait for more
        // frames → compute() again. If we latched here, a caller who
        // probed compute() too early would silently freeze the sink
        // and every subsequent onSample would be a no-op.
        val snap = snapshotIfSufficient(minSamples) ?: return null
        val n = snap.reds.size

        // Derive actual capture rate from timestamps. Guards against:
        //  - Both timestamps identical (one-shot sampler bug) → fs=0 →
        //    fallback to nominal
        //  - Negative spread (clock jump) → fallback to nominal
        val duration = snap.lastTs - snap.firstTs
        val fs = if (duration > 0.0 && n > 1) {
            ((n - 1).toFloat() / duration.toFloat()).coerceAtLeast(1f)
        } else {
            NOMINAL_FS
        }
        // Reject physically-impossible rates. A stream that reports e.g.
        // 500 Hz is almost always a timestamp-collapse bug (duplicates
        // packed into a sub-second span) and would shift the frequency
        // axis so that in-band pulses map to the wrong bpm. Return null
        // rather than silently reporting an attacker-friendly 180 BPM.
        if (fs !in MIN_PHYSICAL_FS..MAX_PHYSICAL_FS) return null

        // Step 1: Normalise each channel by its mean. A zero/negative
        // mean (black frame or outlier noise) collapses the division
        // to NaN or an inverted-sign ratio — null out instead.
        val meanR = snap.reds.average().toFloat()
        val meanG = snap.greens.average().toFloat()
        val meanB = snap.blues.average().toFloat()
        if (meanR <= 0f || meanG <= 0f || meanB <= 0f) return null

        val nr = FloatArray(n) { snap.reds[it] / meanR }
        val ng = FloatArray(n) { snap.greens[it] / meanG }
        val nb = FloatArray(n) { snap.blues[it] / meanB }

        // Step 2: Project onto skin-orthogonal plane. Coefficients are
        // Wang et al. 2015's optimum for separating hemoglobin
        // modulation (which lives mostly in G) from skin-tone drift
        // (R-heavy) and specular highlights (B-heavy).
        val s1 = FloatArray(n) { 3f * nr[it] - 2f * ng[it] }
        val s2 = FloatArray(n) { 1.5f * nr[it] + ng[it] - 1.5f * nb[it] }

        // Step 3: Remove specular noise via alpha-weighted subtraction.
        // A zero std on S2 means S2 was constant across the whole
        // window — physically impossible on a real face capture, so
        // treat it as a tracking failure and bail.
        val stdS1 = std(s1)
        val stdS2 = std(s2)
        if (stdS2 <= 0f) return null
        val alpha = stdS1 / stdS2
        val p = FloatArray(n) { s1[it] - alpha * s2[it] }

        // DC-subtract before the DFT so the k=0 bin and nearby low-
        // frequency leakage don't swamp the in-band search. The Spec's
        // Butterworth filter would do the same; we fold it into one
        // line here.
        val meanP = p.average().toFloat()
        for (i in 0 until n) p[i] = p[i] - meanP

        // Step 4+5 combined: real-signal DFT power spectrum, masked to
        // the valid band. The DFT is O(N²) but N ≤ 900 and this runs
        // ONCE per session (at /complete), so the stall is ~45ms worst
        // case on a mid-range phone — invisible against the WS round-
        // trip the orchestrator is about to make.
        val powers = powerSpectrum(p)
        val binResolution = fs / n
        val loBin = (LOW_FREQ_HZ / binResolution).toInt().coerceAtLeast(1)
        val hiBin = (HIGH_FREQ_HZ / binResolution).toInt().coerceAtMost(powers.size - 1)
        if (loBin > hiBin) return null

        var peakBin = loBin
        var peakPower = powers[loBin]
        var totalPower = 0f
        for (i in loBin..hiBin) {
            if (powers[i] > peakPower) {
                peakPower = powers[i]
                peakBin = i
            }
            totalPower += powers[i]
        }
        val meanPower = totalPower / (hiBin - loBin + 1)
        val peakFreq = peakBin * binResolution
        val bpm = peakFreq * 60f

        // Step 6: SNR in dB — guard the denominator so a near-zero
        // signal doesn't blow up to +Inf (which would latch
        // pulseDetected=true + confidence=1.0 on a static image).
        val snrDb = 10f * log10((peakPower / max(meanPower, 1e-10f)).toDouble()).toFloat()

        val threshold = if (lowLight) SNR_THRESHOLD_DB_LOW_LIGHT else SNR_THRESHOLD_DB_NORMAL
        return Layer3Result(
            bpm = bpm,
            pulseDetected = snrDb > threshold,
            bpmInRange = bpm in BPM_IN_RANGE_LO..BPM_IN_RANGE_HI,
            confidence = confidenceForSnr(snrDb, lowLight),
            snrDb = snrDb,
        )
    }

    // Regular class (not data class): FloatArrays break structural
    // equals/hashCode on a data class (ref-equality instead of content-
    // equality). We only ever snapshot-then-read, never compare — the
    // generated methods are both wrong and unused, so skip them.
    private class Snapshot(
        val reds: FloatArray,
        val greens: FloatArray,
        val blues: FloatArray,
        val firstTs: Double,
        val lastTs: Double,
    )

    private fun snapshotIfSufficient(minSamples: Int): Snapshot? = synchronized(this) {
        if (reds.size < minSamples) return@synchronized null
        val snap = Snapshot(
            reds = reds.toFloatArray(),
            greens = greens.toFloatArray(),
            blues = blues.toFloatArray(),
            firstTs = firstTimestampSec,
            lastTs = lastTimestampSec,
        )
        // Latch INSIDE the lock + after the copy so no analyzer-thread
        // onSample can slip a sample between the toFloatArray() and the
        // flag flip and then wrongly believe its sample was captured.
        finalised.set(true)
        snap
    }

    private fun std(x: FloatArray): Float {
        val mean = x.sum() / x.size
        var sumSq = 0.0
        for (v in x) {
            val d = (v - mean).toDouble()
            sumSq += d * d
        }
        return sqrt(sumSq / x.size).toFloat()
    }

    /**
     * Real-input DFT power spectrum. Returns `|X[k]|²` for k in
     * `[0, n/2]`. O(N²) — intentionally straightforward since this
     * runs once per session, not per frame. Accumulates in double to
     * avoid catastrophic cancellation on long signals; casts to float
     * at the end since the downstream peak-finding doesn't need the
     * extra precision.
     *
     * `internal` so a JVM unit test can pin the FFT math against a
     * known sinusoid without reconstructing a full RGB session.
     */
    internal fun powerSpectrum(x: FloatArray): FloatArray {
        val n = x.size
        val half = n / 2 + 1
        val out = FloatArray(half)
        for (k in 0 until half) {
            var re = 0.0
            var im = 0.0
            val wk = -2.0 * PI * k / n
            for (t in 0 until n) {
                val angle = wk * t
                re += x[t] * cos(angle)
                im += x[t] * sin(angle)
            }
            out[k] = (re * re + im * im).toFloat()
        }
        return out
    }
}
