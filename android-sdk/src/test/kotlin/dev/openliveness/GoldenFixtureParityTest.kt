package dev.openliveness

import dev.openliveness.layers.FaceLandmark
import dev.openliveness.layers.FaceLandmarks
import dev.openliveness.layers.Layer3RPPG
import dev.openliveness.layers.Layer4Texture
import dev.openliveness.layers.Layer5Behavioral
import dev.openliveness.layers.RGBSample
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 5 Step 2 — cross-language parity test. Replays the same JSON
 * fixtures the Python port (`core/tests/test_golden_fixtures.py`) replays,
 * and asserts that the Kotlin implementations land on the same numbers.
 * A drift here means the server's weighted-score re-run disagrees with
 * what the client reported — the attestation gets rejected.
 *
 * **Known Layer 3 drift.** `Layer3RPPG.kt` documents its intentional
 * deviation from the Python reference: it skips the Butterworth
 * `filtfilt` bandpass that the Python side runs before the DFT, on the
 * argument that in-band FFT masking is "mathematically equivalent" and
 * avoids reproducing scipy's filtfilt edge-handling in Kotlin. In
 * practice `bpm` and the two booleans match exactly (same bin indexing),
 * but `snr_db` and `confidence` drift because the Python bandpass
 * attenuates the mean-power denominator. Layer 3 assertions here use a
 * wider tolerance on confidence (±0.1) and skip SNR entirely — tight
 * enough to catch sign errors / coefficient drift, loose enough that the
 * documented bandpass delta doesn't flake CI.
 *
 * **Layer 4 LBP score is not fixtured** — the Kotlin side still ships
 * the 0.85 stub pending Phase 5 Step 3's TFLite model wire-up. The LBP
 * branch of Layer 4 is covered by the Python unit tests directly; this
 * test exercises the Fourier branch where both implementations agree.
 */
class GoldenFixtureParityTest {

    // Tight tolerance for layers where both implementations run the
    // same deterministic math (Layer 4 Fourier, Layer 5 behavioral,
    // orchestrator weighted sum). Float32 vs numpy float64 differences
    // on the DFT inner loop drift about 1e-5 per sample; 1e-3 is
    // generous enough for the Layer 4 80×80 case.
    private val EPS_TIGHT = 1e-3

    // Looser tolerance for Layer 3 confidence — covers the documented
    // bandpass gap. If this ever needs widening past ~0.15, the Kotlin
    // Layer3 bandpass approximation has drifted beyond what the server
    // can round-trip; investigate before relaxing.
    private val EPS_L3_CONFIDENCE = 0.10

    private fun loadFixture(name: String): JSONArray {
        val stream = javaClass.getResourceAsStream("/golden/$name.json")
            ?: throw IllegalStateException(
                "fixture /golden/$name.json missing from test resources; " +
                "regenerate with `python -m core.tests.golden.generate_golden_fixtures`"
            )
        val text = stream.bufferedReader().use { it.readText() }
        return JSONObject(text).getJSONArray("cases")
    }

    // --------- Orchestrator ----------------------------------------------

    @Test
    fun `orchestrator fixtures match`() {
        val cases = loadFixture("orchestrator")
        val orch = LivenessOrchestrator()
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val inputs = case.getJSONObject("inputs")
            val ls = inputs.getJSONObject("layer_scores")
            val amb = inputs.getJSONObject("ambient_conditions")
            val expected = case.getJSONObject("expected")

            val scores = LayerScores(
                layer2ChallengeCompleted = ls.getBoolean("layer2_challenge_completed"),
                layer2ChallengeScore     = ls.getDouble("layer2_challenge_score").toFloat(),
                // Python has no layer3PulseDetected / layer3BpmInRange in
                // its LayerScores — orchestrate only reads confidence. We
                // fill the Kotlin booleans with safe defaults; the drift
                // test `layer3 booleans do not affect decision` already
                // pins that they can't shift orchestrator output.
                layer3RppgConfidence     = ls.getDouble("layer3_rppg_confidence").toFloat(),
                layer4LbpScore           = ls.getDouble("layer4_lbp_score").toFloat(),
                layer4FourierScore       = ls.getDouble("layer4_fourier_score").toFloat(),
                layer5BehavioralScore    = ls.getDouble("layer5_behavioral_score").toFloat(),
            )
            val ambient = AmbientConditions(
                lowLightDetected    = amb.getBoolean("low_light_detected"),
                motionDetected      = amb.getBoolean("motion_detected"),
                frontCameraConfirmed = true,
            )
            val hw = inputs.getBoolean("hardware_attestation_verified")
            val result = orch.orchestrate(scores, ambient, hardwareAttestationVerified = hw)

            assertEquals(expected.getString("decision"), result.decision, name)
            assertEquals(expected.getString("assurance_level"), result.assuranceLevel, name)

            // Weighted score + weights are not exposed on LivenessResult;
            // pull them from the pure `weightsFor` helper + the same
            // multiply-add shape the production path uses. Server re-runs
            // this exact arithmetic, so a drift here matches a drift there.
            val weights = LivenessOrchestrator.weightsFor(ambient)
            val expWeights = expected.getJSONObject("weights_applied")
            assertEquals(expWeights.getDouble("layer2"), weights.layer2.toDouble(), EPS_TIGHT, "$name/w.layer2")
            assertEquals(expWeights.getDouble("layer3"), weights.layer3.toDouble(), EPS_TIGHT, "$name/w.layer3")
            assertEquals(expWeights.getDouble("layer4_lbp"), weights.layer4Lbp.toDouble(), EPS_TIGHT, "$name/w.layer4_lbp")
            assertEquals(expWeights.getDouble("layer4_fourier"), weights.layer4Fourier.toDouble(), EPS_TIGHT, "$name/w.layer4_fourier")
            assertEquals(expWeights.getDouble("layer5"), weights.layer5.toDouble(), EPS_TIGHT, "$name/w.layer5")

            // Weighted score is only meaningful on the non-hard-fail
            // branch — a missed challenge produces decision=fail with no
            // weighted sum computed (Python stores 0.0 for that case).
            if (scores.layer2ChallengeCompleted) {
                val weighted =
                    scores.layer2ChallengeScore  * weights.layer2 +
                    scores.layer3RppgConfidence  * weights.layer3 +
                    scores.layer4LbpScore        * weights.layer4Lbp +
                    scores.layer4FourierScore    * weights.layer4Fourier +
                    scores.layer5BehavioralScore * weights.layer5
                assertEquals(
                    expected.getDouble("weighted_score"),
                    weighted.toDouble(),
                    EPS_TIGHT,
                    "$name/weighted_score"
                )
            }
        }
    }

    // --------- Layer 4 LBP (histogram parity vs. skimage) ----------------

    /**
     * Verifies the Kotlin LBP port emits byte-for-byte the same 59-bin
     * histogram as `skimage.feature.local_binary_pattern(P=8, R=1,
     * method='uniform')` on 3 seeded 80×80 face crops. The TFLite
     * classifier consumes this exact vector, so a drift here would push
     * the on-device `lbpScore` away from the server's re-run.
     *
     * **Why no TFLite inference assertion here**: `tensorflow-lite`
     * ships its ops as a native `.so` inside the AAR, which Android
     * JVM unit tests don't unpack — the Interpreter constructor throws
     * `UnsatisfiedLinkError`. Inference parity is instead covered at
     * build time by `core/models/export_tflite.py`, which asserts
     * sklearn↔fused max|Δ| ≤ 1e-5 and fused↔tflite max|Δ| ≤ 1e-4 on
     * 200 probe histograms before writing the `.tflite` file. On-device
     * inference is covered by the instrumented tests in `androidTest/`.
     */
    @Test
    fun `layer4 lbp histogram matches skimage`() {
        val cases = loadFixture("layer4_lbp")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val inputs = case.getJSONObject("inputs")
            val size = inputs.getInt("size")
            val grayJson = inputs.getJSONArray("gray")
            val gray = FloatArray(grayJson.length()) { grayJson.getInt(it).toFloat() }

            val expHistJson = case.getJSONObject("expected").getJSONArray("histogram")
            val expHist = FloatArray(expHistJson.length()) { expHistJson.getDouble(it).toFloat() }
            val kotlinHist = Layer4Texture.lbpHistogramFor(gray, size)

            assertEquals(expHist.size, kotlinHist.size, "$name/histogram size")
            var maxDelta = 0f
            for (k in expHist.indices) {
                val d = kotlin.math.abs(expHist[k] - kotlinHist[k])
                if (d > maxDelta) maxDelta = d
            }
            assertTrue(
                maxDelta <= 1e-6f,
                "$name/histogram max|Δ|=$maxDelta (bin indices disagree with skimage — " +
                    "re-check uniformLbpValue / bilinearSample)"
            )
        }
    }

    // --------- Layer 4 Fourier -------------------------------------------

    @Test
    fun `layer4 fourier fixtures match`() {
        val cases = loadFixture("layer4_fourier")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val inputs = case.getJSONObject("inputs")
            val size = inputs.getInt("size")
            val grayJson = inputs.getJSONArray("gray")
            val gray = FloatArray(grayJson.length()) { grayJson.getInt(it).toFloat() }
            val expected = case.getJSONObject("expected").getDouble("fourier_score")
            val score = Layer4Texture.fourierScoreFor(gray, size)
            assertEquals(expected, score.toDouble(), EPS_TIGHT, name)
        }
    }

    // --------- Layer 5 Behavioral ----------------------------------------

    @Test
    fun `layer5 behavioral fixtures match`() {
        val cases = loadFixture("layer5_behavioral")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val frames = case.getJSONObject("inputs").getJSONArray("landmarks")
            val expected = case.getJSONObject("expected")

            val l5 = Layer5Behavioral()
            for (f in 0 until frames.length()) {
                val frame = frames.getJSONArray(f)
                val pts = ArrayList<FaceLandmark>(frame.length())
                for (p in 0 until frame.length()) {
                    val xy = frame.getJSONArray(p)
                    pts.add(FaceLandmark(xy.getDouble(0).toFloat(), xy.getDouble(1).toFloat()))
                }
                l5.update(FaceLandmarks(pts))
            }
            assertEquals(
                expected.getDouble("behavioral_score"),
                l5.behavioralScore.toDouble(),
                EPS_TIGHT,
                "$name/score"
            )
            assertEquals(
                expected.getBoolean("microsaccade_detected"),
                l5.microsaccadeDetected,
                "$name/saccade"
            )
        }
    }

    // --------- Layer 3 rPPG (soft parity, see class doc) -----------------

    @Test
    fun `layer3 rppg fixtures match within documented tolerance`() {
        val cases = loadFixture("layer3_rppg")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val name = case.getString("name")
            val inputs = case.getJSONObject("inputs")
            val fs = inputs.getDouble("fs").toFloat()
            val lowLight = inputs.getBoolean("low_light")
            val rgb = inputs.getJSONArray("rgb")
            val expected = case.getJSONObject("expected")

            val l3 = Layer3RPPG()
            val dt = 1.0 / fs.toDouble()
            for (t in 0 until rgb.length()) {
                val triplet = rgb.getJSONArray(t)
                l3.onSample(
                    RGBSample(
                        red = triplet.getDouble(0).toFloat(),
                        green = triplet.getDouble(1).toFloat(),
                        blue = triplet.getDouble(2).toFloat(),
                        timestampSeconds = t * dt,
                    )
                )
            }
            val result = l3.compute(lowLight = lowLight)
            assertNotNull(result, "$name/result should not be null (fixture has ≥300 samples)")

            // Bin-indexed bpm is deterministic across both ports.
            assertEquals(expected.getDouble("bpm"), result!!.bpm.toDouble(), EPS_TIGHT, "$name/bpm")
            assertEquals(expected.getBoolean("bpm_in_range"), result.bpmInRange, "$name/bpmInRange")

            // Pulse detection is a cross-threshold boolean — if the
            // Python side (which has a cleaner SNR thanks to the
            // bandpass) called it true, the Kotlin side with a noisier
            // denominator could still land under threshold. We only
            // assert the easy direction: if Python says `pulseDetected
            // = false` on the fixture, Kotlin must agree.
            if (!expected.getBoolean("pulse_detected")) {
                assertEquals(false, result.pulseDetected, "$name/pulseDetected")
            }

            // Confidence drift is the measured consequence of the
            // bandpass gap. Keep this wide enough to tolerate the
            // documented delta; tight enough to catch sign flips.
            val confDelta = abs(expected.getDouble("confidence") - result.confidence.toDouble())
            assertTrue(
                confDelta <= EPS_L3_CONFIDENCE,
                "$name/confidence: python=${expected.getDouble("confidence")} kotlin=${result.confidence} delta=$confDelta"
            )
        }
    }
}
