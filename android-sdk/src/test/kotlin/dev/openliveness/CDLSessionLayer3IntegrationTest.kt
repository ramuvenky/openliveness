package dev.openliveness

import android.content.Context
import dev.openliveness.attestation.PlayIntegrityClient
import dev.openliveness.layers.FaceLandmark
import dev.openliveness.layers.FaceLandmarks
import dev.openliveness.layers.LandmarkFrame
import dev.openliveness.layers.LandmarkStream
import dev.openliveness.layers.Layer3RPPG
import dev.openliveness.layers.RGBSample
import dev.openliveness.layers.RGBSampleSink
import io.mockk.mockk
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 4 Step 7 — end-to-end integration test for the Layer-3 chain.
 *
 * Prior tests cover each hop in isolation: Layer3RgbTapTest pins the
 * geometry, Layer3RPPGTest pins the DFT math, CDLSessionLayer3Test
 * pins the init-time wiring guard + the per-session reset. What was
 * missing was a single assertion that proves the ENTIRE pipeline
 * composes correctly: stream.nextFrame() consumption → rgbSink
 * dispatch → Layer3RPPG.onSample buffer growth → stream close →
 * CDLSession.runLiveness calling compute() → LayerScores publishing.
 * A refactor that correctly preserves each stage in isolation can
 * still silently break this chain by (e.g.) dropping the compute()
 * call site or forgetting to copy the result into layerScores — this
 * test is the tripwire for that class of regression.
 *
 * We drive runLiveness() directly (not start()) because start() opens
 * a WebSocket, and the goal here is the Layer-3 math-and-wiring path,
 * not the relay protocol. The challenge intentionally times out /
 * never fires on the neutral-face stream — that's load-bearing: a
 * passing Layer-3 result alongside a failing Layer-2 result proves
 * the two scores are published independently (an SDK bug that
 * gated Layer-3 scoring on Layer-2 completion would make this test
 * see confidence=0 even on a clean pulse signal).
 */
class CDLSessionLayer3IntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var playIntegrity: PlayIntegrityClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = mockk(relaxed = true)
        playIntegrity = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun payload() = QRPayload(
        session_id = "s1",
        challenge_id = "c1",
        challenge_sequence = listOf("blink"),
        relay_ws = "wss://unused",
        relay_http = server.url("/").toString().trimEnd('/'),
        expires_at = "2099-01-01T00:00:00Z",
        version = "1.0",
    )

    // Neutral 478-point face: eyes open, no gaze, no smile. The Layer-2
    // sequencer will never see a blink / gaze / smile fire — the stream
    // ends before the 8s sequencer timeout, so Layer2Runner returns a
    // not-completed ChallengeResult. That's the point: we're exercising
    // the Layer-3 half of the pipeline, which must publish real scores
    // regardless of Layer-2 outcome.
    private fun neutralFace(): FaceLandmarks {
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        // Minimal set of anchors referenced by the gesture detectors + the
        // Layer-5 nose/iris indices. The RGB sink doesn't read landmarks
        // in this test (samples come from the stream's script, not from a
        // Bitmap), so skin-region landmarks are irrelevant here — only the
        // Layer-2 detectors and Layer-5 micro-movement reads matter.
        pts[1]   = FaceLandmark(0.50f, 0.50f) // nose tip (gaze detector)
        pts[33]  = FaceLandmark(0.40f, 0.50f) // left eye outer
        pts[133] = FaceLandmark(0.50f, 0.50f) // left eye inner
        pts[145] = FaceLandmark(0.45f, 0.50f) // left eye lower
        pts[159] = FaceLandmark(0.45f, 0.45f) // left eye upper (open)
        pts[234] = FaceLandmark(0.30f, 0.50f) // face left anchor
        pts[454] = FaceLandmark(0.70f, 0.50f) // face right anchor
        return FaceLandmarks(pts)
    }

    /**
     * Scripted stream that, on each [nextFrame] call, both yields a
     * landmark frame to the sequencer AND pushes a per-frame RGBSample
     * into [rgbSink]. Models what the real [MediaPipeLandmarkStream.analyze]
     * does on-device: a single camera callback feeds both the MediaPipe
     * mesh and the Layer-3 skin-region tap.
     *
     * The `rgbChannelAt` lambda scripts the green channel's time series.
     * A pure 1 Hz G-channel sinusoid is the Layer3RPPG DFT test's
     * canonical input (see Layer3RPPGTest.feedSineG) — it projects after
     * POS to a clean single-bin peak at bin ⌊1/(fs/n)⌉, so the integration
     * test can pin the final BPM assertion within ~1 BPM.
     *
     * Timestamps step by `dt`; `nSamples` frames in total; `null` after.
     * We deliberately set `dt=0.02` (fs ≈ 50 Hz) so the full window is
     * 6 seconds — under the sequencer's 8s timeout, so the Layer-2 pump
     * drains the whole stream rather than cutting off at the timeout
     * boundary and leaving the Layer-3 buffer short of MIN_SAMPLES_NORMAL.
     */
    private class ScriptedPulseStream(
        private val nSamples: Int,
        private val dt: Double,
        private val face: FaceLandmarks,
        override val rgbSink: RGBSampleSink?,
        private val rgbChannelAt: (frameIndex: Int) -> RGBSample?,
    ) : LandmarkStream {
        private var i = 0
        override suspend fun nextFrame(): LandmarkFrame? {
            if (i >= nSamples) return null
            val idx = i
            i++
            // Push the scripted RGB first so the ordering matches the
            // production MediaPipeLandmarkStream.analyze path (which
            // dispatches rgbSink.onSample before returning the frame).
            rgbChannelAt(idx)?.let { rgbSink?.onSample(it) }
            return LandmarkFrame(face, idx * dt)
        }
    }

    @Test
    fun `runLiveness threads scripted pulse through tap to Layer3 to LayerScores`() = runBlocking {
        val fs = 50f           // nominal capture rate; derived from dt in the stream
        val n = Layer3RPPG.MIN_SAMPLES_NORMAL
        val freqHz = 1f        // 1 Hz = 60 BPM — centre of valid-user window
        val greenMean = 130f
        val greenAmp = 2.6f

        val layer3 = Layer3RPPG()
        val stream = ScriptedPulseStream(
            nSamples = n,
            dt = (1.0 / fs),
            face = neutralFace(),
            rgbSink = layer3,
            rgbChannelAt = { idx ->
                val t = idx * (1.0 / fs)
                val g = greenMean + greenAmp * sin(2.0 * PI * freqHz * t).toFloat()
                RGBSample(red = 100f, green = g, blue = 90f, timestampSeconds = t)
            }
        )

        val session = CDLSession(
            payload = payload(),
            context = context,
            playIntegrity = playIntegrity,
            landmarkStream = stream,
            layer3 = layer3,
        )

        val (scores, challengeResult) = session.runLiveness(AmbientConditions())

        // Layer 2 must NOT have completed — the neutral face never triggers
        // a blink, so the challenge result is a stream-ended ChallengeResult
        // with completed=false. This half of the test is load-bearing: it
        // proves that Layer-3 scoring is NOT gated on Layer-2 passing, which
        // is what the orchestrator relies on when deciding "inconclusive"
        // on mixed-signal sessions.
        assertNotNull(challengeResult)
        assertFalse(challengeResult!!.completed, "neutral stream must not complete blink")
        assertFalse(scores.layer2ChallengeCompleted)
        assertEquals(0f, scores.layer2ChallengeScore, 1e-6f)

        // Layer 3 must have fired cleanly on the scripted sinusoid. These
        // assertions mirror the Layer3RPPGTest's "pure G-channel 1Hz
        // detects at 60 BPM" test, but reach them through the integration
        // chain — if any hop (sink wiring, stream drain, close, compute,
        // publish) drops the signal, this will see confidence=0.
        assertTrue(scores.layer3PulseDetected, "pure 1Hz sine must register as a pulse")
        assertTrue(scores.layer3BpmInRange, "60 BPM must land inside [50, 110]")
        // Pure-sine SNR is bounded by 10·log10(in-band bin count). At
        // fs=50 Hz, n=300 the in-band mask (0.7-3.0 Hz) covers ~15 bins
        // → SNR ~11.8 dB → confidence ~0.88 after the (snrDb−3)/10 ramp.
        // We deliberately don't match the fs=30 unit test's saturating
        // 1.0 (that case covers ~24 in-band bins) because this test's
        // fs must stay above 37.5 Hz to keep 300 samples under the
        // sequencer's 8s timeout. The lower bound is 0.80 — far enough
        // above the pre-Phase-4 stub constant (0.85 would be flaky) to
        // prove we're reading real DFT output, with headroom for float
        // round-off in the DFT.
        assertTrue(
            scores.layer3RppgConfidence > 0.80f,
            "confidence must reflect real DFT output; got ${scores.layer3RppgConfidence}"
        )

        // Layer 4 stub constants must be untouched (Phase 4 explicitly
        // leaves Layer 4 at 0.85f until Phase 5 bundles the TFLite model).
        // A regression that lets Layer 3's publishing path accidentally
        // overwrite Layer 4 would surface here.
        assertEquals(0.85f, scores.layer4LbpScore, 1e-6f)
        assertEquals(0.85f, scores.layer4FourierScore, 1e-6f)

        // Layer 5 sees every landmark frame; on a static neutral face the
        // micro-movement score is low and no microsaccade fires. We don't
        // pin the exact score (it's a function of the per-frame nose/iris
        // positions, which are static here) but assert the flags report
        // "not detected", which is the expected output for a frozen face.
        assertFalse(scores.layer5MicrosaccadeDetected,
            "static iris landmarks must not trip the microsaccade detector")

        // The Layer3RPPG buffer should be fully drained (300 samples from
        // the stream, all accepted — no late races, no finalisation drops).
        // sampleCount == n is also the "no cross-session leak" assertion:
        // runLiveness's layer3.reset() at session start cleared the fresh
        // instance to 0, and nothing but the scripted frames contributed.
        assertEquals(n, layer3.sampleCount,
            "layer3 must have received exactly the scripted sample count")
    }

    @Test
    fun `runLiveness publishes honest Layer 3 failure when stream yields no RGB samples`() = runBlocking {
        // Mirror of the happy-path test, but the rgbChannelAt lambda
        // returns null — so the stream drives landmarks (for Layer 2 /
        // Layer 5) but the Layer-3 sink receives zero samples. compute()
        // must return null and the published LayerScores must stamp the
        // honest-failure values (false / false / 0f) rather than falling
        // back to the pre-Phase-4 optimistic stub (0.85 confidence,
        // pulseDetected=true). That fallback would make a Layer-3
        // completely-broken SDK look like a working one on the wire.
        val layer3 = Layer3RPPG()
        val stream = ScriptedPulseStream(
            nSamples = 50,                     // few frames; irrelevant to Layer 3
            dt = 1.0 / 30.0,
            face = neutralFace(),
            rgbSink = layer3,
            rgbChannelAt = { null }            // no samples flow
        )
        val session = CDLSession(
            payload = payload(),
            context = context,
            playIntegrity = playIntegrity,
            landmarkStream = stream,
            layer3 = layer3,
        )

        val (scores, _) = session.runLiveness(AmbientConditions())

        assertEquals(0, layer3.sampleCount, "no RGB samples should have flowed")
        assertFalse(scores.layer3PulseDetected,
            "compute() returning null must publish pulseDetected=false, not fall back to stub")
        assertFalse(scores.layer3BpmInRange)
        assertEquals(0f, scores.layer3RppgConfidence, 1e-6f)
    }
}
