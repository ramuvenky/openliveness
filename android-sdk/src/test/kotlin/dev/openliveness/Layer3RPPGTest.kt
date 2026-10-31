package dev.openliveness

import dev.openliveness.layers.Layer3RPPG
import dev.openliveness.layers.RGBSample
import kotlin.math.PI
import kotlin.math.sin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 4 Step 4 — Layer 3 POS-rPPG unit tests. Mirrors the Python
 * reference in docs/Spec.md §1356-1405.
 *
 * The pure-G-channel sinusoid tests are the load-bearing ones: they
 * exercise the POS projection + DFT math end-to-end on an analytically
 * tractable input (a pure G-only sine projects to `p = -1 - 4k·sin(ωt)`
 * after Step 2 + Step 3, so after the DC subtract the output is a clean
 * single-bin sinusoid at the drive frequency). Any arithmetic drift in
 * the projection coefficients or DFT bin indexing will shift the peak,
 * fail the bpm assertion, and point directly at the broken step.
 *
 * The iOS sibling (Sources/OpenLivenessTests/Layer3RPPGTests.swift, due
 * in Phase 4 iOS mirror) must assert the same numeric branches, so a
 * threshold / coefficient tweak on one platform trips its sibling rather
 * than drifting the server's weighted-score re-run.
 */
class Layer3RPPGTest {

    // Build a session of G-channel sinusoid samples: constant R/B, G
    // modulated at `freqHz` with `(amp/greenMean)` fractional amplitude.
    // Timestamps step by 1/fs so the computed capture rate matches `fs`
    // exactly — tests that want to verify the timestamp-derived path can
    // override `fs` without changing the sample count.
    private fun feedSineG(
        rppg: Layer3RPPG,
        n: Int,
        fs: Float = 30f,
        freqHz: Float = 1f,
        greenMean: Float = 130f,
        amp: Float = 2.6f,
        redMean: Float = 100f,
        blueMean: Float = 90f,
    ) {
        val dt = 1.0 / fs
        for (i in 0 until n) {
            val t = i * dt
            val g = greenMean + amp * sin(2.0 * PI * freqHz * t).toFloat()
            rppg.onSample(RGBSample(red = redMean, green = g, blue = blueMean, timestampSeconds = t))
        }
    }

    // MARK: - Preconditions / null paths

    @Test
    fun `empty compute returns null`() {
        val l = Layer3RPPG()
        assertNull(l.compute())
        assertNull(l.compute(lowLight = true))
    }

    @Test
    fun `insufficient samples normal returns null`() {
        val l = Layer3RPPG()
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL - 1)
        assertEquals(Layer3RPPG.MIN_SAMPLES_NORMAL - 1, l.sampleCount)
        assertNull(l.compute())
    }

    @Test
    fun `insufficient samples low-light returns null`() {
        val l = Layer3RPPG()
        // Enough for normal mode, not enough for low-light. Confirms the
        // low-light threshold is actually applied (not silently reusing
        // the normal 300-sample bar).
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_LOW_LIGHT - 1)
        assertNull(l.compute(lowLight = true))
        // Same samples DO suffice in normal mode.
        assertNotNull(l.compute(lowLight = false))
    }

    @Test
    fun `zero red mean returns null`() {
        val l = Layer3RPPG()
        // Red = 0 across the whole window → meanR = 0 → the Step-1
        // division would NaN out. Null is the contract.
        for (i in 0 until Layer3RPPG.MIN_SAMPLES_NORMAL) {
            l.onSample(RGBSample(red = 0f, green = 130f, blue = 90f, timestampSeconds = i / 30.0))
        }
        assertNull(l.compute())
    }

    @Test
    fun `constant input returns null via zero std guard`() {
        val l = Layer3RPPG()
        // Every channel is constant → s2 is constant → stdS2 = 0 → the
        // alpha division would be degenerate. Null guards the pipeline.
        for (i in 0 until Layer3RPPG.MIN_SAMPLES_NORMAL) {
            l.onSample(RGBSample(red = 100f, green = 130f, blue = 90f, timestampSeconds = i / 30.0))
        }
        assertNull(l.compute())
    }

    // MARK: - Core signal detection

    @Test
    fun `pure G-channel 1Hz sinusoid detects at 60 BPM in-range`() {
        val l = Layer3RPPG()
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL, fs = 30f, freqHz = 1f)
        val r = l.compute()
        assertNotNull(r)
        r!!
        // Bin resolution 30/300 = 0.1 Hz → 1 Hz lands on bin 10 exactly.
        // bpm = 0.1 * 10 * 60 = 60 — expect no float wobble beyond ~0.5.
        assertEquals(60f, r.bpm, 0.5f)
        assertTrue(r.pulseDetected, "pure sine should clear 3 dB threshold")
        assertTrue(r.bpmInRange, "60 BPM is inside [50, 110]")
        // SNR for a pure monochromatic peak over 24 in-band bins is
        // ~10·log10(24) ≈ 13.8 dB — well past the +10 dB ramp top → 1.0.
        assertEquals(1f, r.confidence, 0.001f)
    }

    @Test
    fun `out-of-range frequency flags bpmInRange false`() {
        val l = Layer3RPPG()
        // 2.5 Hz = 150 BPM — inside the valid detection band [0.7, 3.0 Hz]
        // but outside the valid-user window [50, 110 BPM].
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL, fs = 30f, freqHz = 2.5f)
        val r = l.compute()
        assertNotNull(r)
        r!!
        assertEquals(150f, r.bpm, 0.5f)
        assertTrue(r.pulseDetected)
        assertFalse(r.bpmInRange)
    }

    // MARK: - fs is derived from timestamps, not nominal

    @Test
    fun `fs derived from timestamps - 60Hz actual still peaks at 60 BPM`() {
        val l = Layer3RPPG()
        // 300 samples spanning 5 seconds → fs = 299/5 ≈ 59.8 Hz. If the
        // code wrongly used NOMINAL_FS = 30, the bin at the 1-Hz drive
        // would compute as bpm = 30, not 60. This test is specifically
        // the "did fs come from timestamps" assertion.
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL, fs = 60f, freqHz = 1f)
        val r = l.compute()
        assertNotNull(r)
        r!!
        assertEquals(60f, r.bpm, 1f)
    }

    // MARK: - Low-light mode scales confidence

    @Test
    fun `low-light mode scales confidence by 0_7`() {
        val l = Layer3RPPG()
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_LOW_LIGHT, fs = 30f, freqHz = 1f)
        val normal = l.compute(lowLight = false)
        val lowLight = l.compute(lowLight = true)
        assertNotNull(normal)
        assertNotNull(lowLight)
        normal!!
        lowLight!!
        // Both see the same signal → same bpm / snrDb / pulseDetected.
        assertEquals(normal.bpm, lowLight.bpm, 0.01f)
        assertEquals(normal.snrDb, lowLight.snrDb, 0.01f)
        assertTrue(normal.pulseDetected && lowLight.pulseDetected)
        // Confidence ramp hits 1.0 in normal mode on a pure sine →
        // low-light confidence should be exactly 0.7 × that.
        assertEquals(1f, normal.confidence, 0.001f)
        assertEquals(Layer3RPPG.LOW_LIGHT_CONFIDENCE_SCALE, lowLight.confidence, 0.001f)
    }

    // MARK: - State management

    @Test
    fun `MAX_SAMPLES cap prevents unbounded growth`() {
        val l = Layer3RPPG()
        // Push well past the cap. All further samples should be dropped
        // on the floor — a stuck sink can't eat heap unbounded.
        feedSineG(l, n = Layer3RPPG.MAX_SAMPLES + 500)
        assertEquals(Layer3RPPG.MAX_SAMPLES, l.sampleCount)
    }

    @Test
    fun `reset clears samples and allows reuse`() {
        val l = Layer3RPPG()
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL)
        assertEquals(Layer3RPPG.MIN_SAMPLES_NORMAL, l.sampleCount)
        assertNotNull(l.compute())
        l.reset()
        assertEquals(0, l.sampleCount)
        assertNull(l.compute())
        // After reset the same instance should accept a fresh session.
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL, freqHz = 1.5f)
        val r = l.compute()
        assertNotNull(r)
        assertEquals(90f, r!!.bpm, 0.5f)
    }

    // MARK: - DFT math pinned directly

    // MARK: - confidenceForSnr helper (anchored to active threshold)

    @Test
    fun `confidenceForSnr normal mode ramps linearly from active threshold`() {
        // Review M1: ramp must zero at the ACTIVE threshold (3 dB in
        // normal mode) and hit 1.0 at threshold+10 dB. Prior to the fix
        // this always anchored at 3 dB regardless of mode, so in low
        // light the ramp started 1 dB above detection — a session that
        // just cleared the low-light bar would still report confidence=0.
        assertEquals(0f, Layer3RPPG.confidenceForSnr(3f, lowLight = false), 1e-6f)
        assertEquals(0.5f, Layer3RPPG.confidenceForSnr(8f, lowLight = false), 1e-6f)
        assertEquals(1f, Layer3RPPG.confidenceForSnr(13f, lowLight = false), 1e-6f)
        // Below threshold → clamped to 0. Above ramp top → clamped to 1.
        assertEquals(0f, Layer3RPPG.confidenceForSnr(0f, lowLight = false), 1e-6f)
        assertEquals(1f, Layer3RPPG.confidenceForSnr(50f, lowLight = false), 1e-6f)
    }

    @Test
    fun `confidenceForSnr low-light mode anchors at low-light threshold`() {
        // Low-light threshold = 2 dB; ramp still 10 dB wide; result then
        // scaled by LOW_LIGHT_CONFIDENCE_SCALE (=0.7). A session right at
        // the low-light detection bar must report confidence=0, not a
        // bogus positive from the old normal-anchor formula.
        assertEquals(0f, Layer3RPPG.confidenceForSnr(2f, lowLight = true), 1e-6f)
        // Ramp midpoint: at (2 + 10/2) = 7 dB → raw 0.5 × 0.7 = 0.35.
        assertEquals(0.35f, Layer3RPPG.confidenceForSnr(7f, lowLight = true), 1e-6f)
        // Ramp top: at 12 dB → raw 1.0 × 0.7 = 0.7 (= LOW_LIGHT scale).
        assertEquals(Layer3RPPG.LOW_LIGHT_CONFIDENCE_SCALE,
            Layer3RPPG.confidenceForSnr(12f, lowLight = true), 1e-6f)
    }

    // MARK: - Finalise latch (idempotent compute + frozen onSample)

    @Test
    fun `compute freezes the sample buffer against late onSample`() {
        // M2+L6: Pre-fix, a late analyzer-thread frame arriving after
        // CDLSession had already read scores could grow sampleCount
        // past MIN_SAMPLES — making a next compute() report a BPM
        // shifted by a different-length DFT than the one the server
        // got told about. The latch inside snapshotAndFinalise freezes
        // the buffer at the first compute() call.
        val l = Layer3RPPG()
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL, fs = 30f, freqHz = 1f)
        val first = l.compute()
        assertNotNull(first)
        val preCount = l.sampleCount
        // Push 500 more late-arriving frames. Pre-fix, sampleCount
        // would jump by 500; post-fix it must stay at preCount.
        for (i in 0 until 500) {
            l.onSample(RGBSample(red = 100f, green = 999f, blue = 90f, timestampSeconds = 100.0 + i))
        }
        assertEquals(preCount, l.sampleCount, "post-compute onSample must be a no-op")
        // Same input + same lowLight → bitwise-equal result on the
        // second call because the sample buffer is frozen.
        val second = l.compute()
        assertEquals(first, second)
    }

    @Test
    fun `reset clears finalised latch so onSample resumes accepting`() {
        // L6: reset() must also clear the finalised flag or re-using
        // the instance after reset would produce silent zero-sample
        // sessions.
        val l = Layer3RPPG()
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL)
        assertNotNull(l.compute())
        l.reset()
        // Post-reset onSample must take effect again.
        l.onSample(RGBSample(red = 100f, green = 130f, blue = 90f, timestampSeconds = 0.0))
        assertEquals(1, l.sampleCount)
    }

    // MARK: - fs physical sanity bounds (M3)

    @Test
    fun `fs above physical cap returns null`() {
        val l = Layer3RPPG()
        // 150 Hz is above MAX_PHYSICAL_FS (90 Hz) — a timestamp-collapse
        // bug that packs 300 samples into 2 seconds. Pre-fix the DFT
        // would run with a nonsense frequency axis and silently report
        // the wrong BPM.
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL, fs = 150f, freqHz = 1f)
        assertNull(l.compute())
    }

    @Test
    fun `fs below physical floor returns null`() {
        val l = Layer3RPPG()
        // 10 Hz is below MIN_PHYSICAL_FS (15 Hz) — a stuck sampler on
        // a thermal-throttled device. Signal is unusable regardless.
        feedSineG(l, n = Layer3RPPG.MIN_SAMPLES_NORMAL, fs = 10f, freqHz = 1f)
        assertNull(l.compute())
    }

    @Test
    fun `powerSpectrum peaks at expected bin for pure sinusoid`() {
        val l = Layer3RPPG()
        val n = 128
        val targetBin = 7
        val x = FloatArray(n) { sin(2.0 * PI * targetBin * it / n).toFloat() }
        val powers = l.powerSpectrum(x)
        // DC bin must be ~0 (zero-mean sine) — not just small.
        assertTrue(powers[0] < 1e-3f, "DC bin should be ~0 for zero-mean sine, got ${powers[0]}")
        // Peak should land exactly at targetBin.
        var peakBin = 0
        var peakPower = powers[0]
        for (k in 1 until powers.size) {
            if (powers[k] > peakPower) {
                peakPower = powers[k]
                peakBin = k
            }
        }
        assertEquals(targetBin, peakBin)
        // Energy is tightly concentrated — the next-strongest bin should
        // be orders of magnitude smaller (float round-off, not leakage,
        // because targetBin divides n evenly).
        var secondPeak = 0f
        for (k in powers.indices) {
            if (k != peakBin && powers[k] > secondPeak) secondPeak = powers[k]
        }
        assertTrue(peakPower > 1e6f * secondPeak, "peak=$peakPower second=$secondPeak")
    }
}
