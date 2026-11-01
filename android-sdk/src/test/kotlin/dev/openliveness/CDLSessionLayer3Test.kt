package dev.openliveness

import android.content.Context
import dev.openliveness.attestation.PlayIntegrityClient
import dev.openliveness.layers.LandmarkFrame
import dev.openliveness.layers.LandmarkStream
import dev.openliveness.layers.Layer3RPPG
import dev.openliveness.layers.RGBSample
import dev.openliveness.layers.RGBSampleSink
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase 4 Step 5 — Layer-3 wiring + per-session reset tests. Covers the
 * two integrator-facing footguns the review flagged as High:
 *
 *  - H1 — a Layer3RPPG passed to CDLSession must be the SAME instance as
 *    `landmarkStream.rgbSink`. Pre-fix, a wiring mismatch silently
 *    routed samples to one instance and read scores from the other,
 *    producing a session that always reported confidence=0 /
 *    pulseDetected=false. The init-time check throws instead.
 *
 *  - H2 — a residual Layer3RPPG sample buffer from an earlier aborted
 *    session cannot leak into a new session's compute(). `runLiveness`
 *    calls `layer3?.reset()` at the top so each session starts clean.
 */
class CDLSessionLayer3Test {

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

    // Fake stream that immediately ends. Overrides `rgbSink` to echo
    // back the sink it was constructed with (default null) — the H1
    // wiring check only reads this property.
    private class FakeStream(
        override val rgbSink: RGBSampleSink? = null,
    ) : LandmarkStream {
        override suspend fun nextFrame(): LandmarkFrame? = null
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

    // MARK: - H1 wiring check (static helper, exercised directly)

    @Test
    fun `checkLayer3Wiring is a no-op when layer3 is null`() {
        // Pre-Phase-4 harness that doesn't opt into rPPG: the stream
        // may still have a non-null rgbSink (e.g., set for a diagnostic
        // sink). We never touch it because there's no layer3 to
        // reconcile.
        val stream = FakeStream(rgbSink = Layer3RPPG())
        assertDoesNotThrow { CDLSession.checkLayer3Wiring(layer3 = null, stream = stream) }
    }

    @Test
    fun `checkLayer3Wiring is a no-op when stream is null`() {
        // Stub-mode session: layer3 is held but no stream. The wiring
        // check has nothing to reconcile against; the stub path never
        // produces samples anyway.
        assertDoesNotThrow { CDLSession.checkLayer3Wiring(layer3 = Layer3RPPG(), stream = null) }
    }

    @Test
    fun `checkLayer3Wiring throws when stream rgbSink is null`() {
        // Integrator forgot to wire the Layer3RPPG into
        // MediaPipeLandmarkStream.fromAsset(rgbSink = ...) — the stream
        // has no sink, so no samples will flow and compute() would
        // silently return null forever. Catch at construction time.
        val layer3 = Layer3RPPG()
        val stream = FakeStream(rgbSink = null)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            CDLSession.checkLayer3Wiring(layer3 = layer3, stream = stream)
        }
        assertTrue(
            ex.message?.contains("rgbSink") == true,
            "wiring error must name rgbSink so the integrator can find the fix; was: ${ex.message}"
        )
    }

    @Test
    fun `checkLayer3Wiring throws when stream rgbSink is a different Layer3RPPG`() {
        // The specific bug the fix is designed around: two distinct
        // instances, one on the stream and one on CDLSession. Pre-fix
        // the session would silently report confidence=0.
        val wiredToStream = Layer3RPPG()
        val wiredToSession = Layer3RPPG()
        assertNotEquals(wiredToStream, wiredToSession, "sanity: two Layer3RPPGs are not equal")
        val stream = FakeStream(rgbSink = wiredToStream)
        assertThrows(IllegalArgumentException::class.java) {
            CDLSession.checkLayer3Wiring(layer3 = wiredToSession, stream = stream)
        }
    }

    @Test
    fun `checkLayer3Wiring passes when layer3 is the stream rgbSink`() {
        // The correct wiring: the one Layer3RPPG the integrator built
        // was passed to both the stream (as rgbSink) and the session
        // (as layer3). Reference-identical → no throw.
        val layer3 = Layer3RPPG()
        val stream = FakeStream(rgbSink = layer3)
        assertDoesNotThrow { CDLSession.checkLayer3Wiring(layer3 = layer3, stream = stream) }
    }

    @Test
    fun `checkLayer3Wiring throws when rgbSink is an arbitrary non-Layer3 sink`() {
        // Reference-equality is what we care about — a plain
        // RGBSampleSink that happens to NOT be the layer3 is still a
        // mis-wiring. Catches a diagnostic-tap harness that forgot to
        // chain Layer3 into the sink.
        val layer3 = Layer3RPPG()
        val otherSink = RGBSampleSink { _: RGBSample -> /* discard */ }
        val stream = FakeStream(rgbSink = otherSink)
        assertThrows(IllegalArgumentException::class.java) {
            CDLSession.checkLayer3Wiring(layer3 = layer3, stream = stream)
        }
    }

    // MARK: - H2 per-session reset

    @Test
    fun `runLiveness resets layer3 at session start to drop stale samples`() = runBlocking {
        // H2 scenario: a prior aborted session left samples on the
        // Layer3RPPG. Pre-fix, those samples would carry into the new
        // session's compute() and contribute to a different (and
        // potentially passing) BPM than what the current session's
        // frames alone would produce. The fix is `layer3?.reset()` at
        // the top of runLiveness; this test proves it fires.
        val layer3 = Layer3RPPG()
        // Pre-populate with a full normal-mode buffer so compute()
        // would succeed if reset DIDN'T fire. We don't even need to
        // call compute() first — reset() must clear raw samples
        // regardless of finalise state.
        for (i in 0 until Layer3RPPG.MIN_SAMPLES_NORMAL) {
            layer3.onSample(RGBSample(red = 100f, green = 130f, blue = 90f, timestampSeconds = i / 30.0))
        }
        assertEquals(Layer3RPPG.MIN_SAMPLES_NORMAL, layer3.sampleCount)

        val stream = FakeStream(rgbSink = layer3)
        val session = CDLSession(
            payload = payload(),
            context = context,
            playIntegrity = playIntegrity,
            landmarkStream = stream,
            layer3 = layer3,
        )

        // runLiveness pulls `nextFrame()` which immediately returns null
        // → Layer2Runner.run() yields a not-completed ChallengeResult.
        // The point of the test is layer3 state BEFORE compute, so we
        // only need the reset to have fired.
        session.runLiveness(AmbientConditions())

        // Post-run: sampleCount must be 0 because the stream pushed no
        // new samples (nextFrame returned null) and reset() cleared the
        // residual MIN_SAMPLES_NORMAL. If the reset had been skipped,
        // sampleCount would still be MIN_SAMPLES_NORMAL.
        assertEquals(0, layer3.sampleCount,
            "layer3.reset() must fire at runLiveness start to drop residual samples")
    }
}
