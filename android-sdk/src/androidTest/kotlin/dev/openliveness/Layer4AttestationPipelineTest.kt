package dev.openliveness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openliveness.attestation.AttestationBuilder
import dev.openliveness.layers.FaceCropExtractor
import dev.openliveness.layers.FaceCropSink
import dev.openliveness.layers.FaceLandmark
import dev.openliveness.layers.FaceLandmarks
import dev.openliveness.layers.LandmarkStream
import dev.openliveness.layers.Layer4Texture
import dev.openliveness.layers.TextureClassifier
import kotlin.math.abs
import kotlin.math.sin
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end verification for the Phase-5-Step-3 Layer-4
 * wiring:
 *
 *   synthetic ARGB_8888 buffer + canned FaceLandmarks
 *     → FaceCropExtractor.extractGrayFaceCrop
 *     → Layer4Texture (as FaceCropSink).onFrame
 *     → TFLite inference against the shipped lbp_classifier.tflite asset
 *     → AttestationBuilder.build serialized payload
 *
 * The important load-bearing claim: the attestation payload now carries
 * the dynamic Layer-4 output, replacing the historical 0.85 stub that
 * [AttestationBuilder.stubLayerScores] still provides as a fallback.
 * Prior to Phase 5 Step 3 the on-wire `layer4_lbp_score` was exactly
 * 0.85 regardless of what the camera saw.
 *
 * Why instrumented and not JVM: TFLite's `Interpreter` ships ops as a
 * native .so inside the AAR (see TextureClassifierInstrumentedTest).
 * The JVM `GoldenFixtureParityTest` already covers the LBP histogram
 * parity on 3 seeded fixtures; this test closes the real-on-device
 * pipeline-plus-attestation gap.
 */
@RunWith(AndroidJUnit4::class)
class Layer4AttestationPipelineTest {

    @Test
    fun syntheticCropMovesLbpOffStubAndFlowsIntoAttestation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val width = 320
        val height = 240
        val pixels = syntheticArgbFace(width, height)
        val landmarks = centeredFaceLandmarks(
            minX = 0.30f, maxX = 0.70f,
            minY = 0.20f, maxY = 0.80f,
        )

        val gray = FaceCropExtractor.extractGrayFaceCrop(
            pixels = pixels,
            width = width,
            height = height,
            landmarks = landmarks,
            flipX = false,
        )
        assertNotNull("extractor should return a non-null crop for in-bounds landmarks", gray)
        assertEquals(
            "crop must be OUT_SIZE × OUT_SIZE",
            FaceCropExtractor.OUT_SIZE * FaceCropExtractor.OUT_SIZE,
            gray!!.size,
        )

        TextureClassifier.fromAssets(context).use { classifier ->
            val layer4 = Layer4Texture(classifier)
            assertEquals(
                "pre-frame baseline must be the 0.85 stub (contract with stubLayerScores)",
                0.85f, layer4.lbpScore, 1e-6f,
            )

            // Exercise the FaceCropSink SAM path specifically — the camera
            // pipeline reaches Layer4Texture through this method, not
            // directly through analyzeFrame.
            val sink: FaceCropSink = layer4
            sink.onFrame(gray, FaceCropExtractor.OUT_SIZE)

            val realLbp = layer4.lbpScore
            val realFourier = layer4.fourierScore
            assertTrue("lbp score must be in [0, 1], got $realLbp", realLbp in 0f..1f)
            assertTrue("fourier score must be in [0, 1], got $realFourier", realFourier in 0f..1f)
            // A real sigmoid on a non-degenerate 59-bin histogram almost
            // never lands on exactly 0.85 — a float equal to the stub
            // after inference means the sink was never actually invoked
            // (dead-code wiring regression).
            assertNotEquals(
                "lbp score should have moved off the 0.85 stub after a real on-device inference",
                0.85f, realLbp,
            )

            // Build the signed attestation and verify the real score ends
            // up on the wire. Mirrors the shape CDLSession.start() produces
            // but without a WebSocket round-trip — the Layer-4 hookup is
            // what we're testing, and the WS path is covered by
            // CDLSessionE2ETest.
            val scores = AttestationBuilder.stubLayerScores().copy(
                layer4LbpScore = realLbp,
                layer4FourierScore = realFourier,
            )
            val session = CDLSession(
                payload = minimalPayload(),
                context = context,
                allowStubMode = true,
            )
            val wireBytes = AttestationBuilder.build(
                context = context,
                session = session,
                layerScores = scores,
                ambient = AmbientConditions(),
                stubMode = false,
            )
            val wireJson = JSONObject(String(wireBytes, Charsets.UTF_8))
            val layerScoresJson = wireJson.getJSONObject("layer_scores")
            val wireLbp = layerScoresJson.getDouble("layer4_lbp_score")
            val wireFourier = layerScoresJson.getDouble("layer4_fourier_score")

            // Canonical JSON serializes floats via toString(double) — the
            // round trip is lossless to double precision. Tolerance of
            // 1e-6 covers the float→double cast only.
            assertTrue(
                "wire layer4_lbp_score ($wireLbp) must match the real inference ($realLbp)",
                abs(wireLbp - realLbp.toDouble()) <= 1e-6,
            )
            assertTrue(
                "wire layer4_fourier_score ($wireFourier) must match the real inference ($realFourier)",
                abs(wireFourier - realFourier.toDouble()) <= 1e-6,
            )
            assertNotEquals(
                "wire layer4_lbp_score must have moved off the 0.85 stub",
                0.85, wireLbp, 1e-9,
            )
        }
    }

    @Test
    fun cdlSessionAcceptsLayer4WhenFaceCropSinkMatches() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TextureClassifier.fromAssets(context).use { classifier ->
            val layer4 = Layer4Texture(classifier)
            val fakeStream = object : LandmarkStream {
                override suspend fun nextFrame() = null
                override val faceCropSink: FaceCropSink = layer4
            }
            assertSame(
                "fake stream must publish the same Layer4Texture reference it holds",
                layer4, fakeStream.faceCropSink,
            )

            // Must construct without IllegalArgumentException. Prior to
            // Phase 5 Step 3 CDLSession had no `layer4` param at all;
            // after it, checkLayer4Wiring enforces the reference-equality
            // invariant that prevents the silent 0.85-forever bug.
            CDLSession(
                payload = minimalPayload(),
                context = context,
                landmarkStream = fakeStream,
                layer4 = layer4,
                allowStubMode = true,
            )
        }
    }

    @Test
    fun cdlSessionRejectsLayer4WhenFaceCropSinkDiffers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        TextureClassifier.fromAssets(context).use { classifier ->
            val wired = Layer4Texture(classifier)
            val mismatched = Layer4Texture(classifier)
            val fakeStream = object : LandmarkStream {
                override suspend fun nextFrame() = null
                override val faceCropSink: FaceCropSink = wired
            }
            var thrown: IllegalArgumentException? = null
            try {
                CDLSession(
                    payload = minimalPayload(),
                    context = context,
                    landmarkStream = fakeStream,
                    layer4 = mismatched,
                    allowStubMode = true,
                )
            } catch (e: IllegalArgumentException) {
                thrown = e
            }
            assertNotNull(
                "constructing CDLSession with a Layer4Texture that doesn't match stream.faceCropSink must throw",
                thrown,
            )
            assertTrue(
                "exception should name the mismatch, got: ${thrown?.message}",
                thrown!!.message!!.contains("faceCropSink"),
            )
        }
    }

    private fun minimalPayload(): QRPayload = QRPayload(
        session_id = "sess-layer4-${System.currentTimeMillis()}",
        challenge_id = "ch-1",
        challenge_sequence = listOf("blink"),
        relay_ws = "ws://127.0.0.1:1/x",
        relay_http = "http://127.0.0.1:1",
        expires_at = "2099-01-01T00:00:00Z",
        version = "1.0",
    )

    /**
     * Build an ARGB_8888 buffer with a bright elliptical "face" over a
     * dark background and ring banding inside the ellipse. The banding
     * is what makes the LBP histogram carry real transitions — a
     * uniform fill collapses onto bin 0 and the downstream sigmoid
     * saturates at an uninteresting value, which would pass the
     * "moved off 0.85" check for the wrong reason.
     */
    private fun syntheticArgbFace(width: Int, height: Int): IntArray {
        val pixels = IntArray(width * height)
        val cx = width / 2.0
        val cy = height / 2.0
        val a = width * 0.25
        val b = height * 0.35
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - cx) / a
                val dy = (y - cy) / b
                val d2 = dx * dx + dy * dy
                val luma = if (d2 > 1.0) {
                    40
                } else {
                    (128 + 80.0 * sin(d2 * 12.0)).toInt().coerceIn(0, 255)
                }
                pixels[y * width + x] =
                    (0xFF shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }
        return pixels
    }

    /**
     * 478 FaceLandmark points occupying the given normalized bbox. The
     * extractor only consumes min/max of x and y across all points
     * (see FaceCropExtractor), so a deterministic jittered cloud with
     * the extremes pinned to the bbox corners is sufficient — no need
     * to replicate MediaPipe's full face-mesh topology for the extractor
     * contract.
     */
    private fun centeredFaceLandmarks(
        minX: Float, maxX: Float,
        minY: Float, maxY: Float,
    ): FaceLandmarks {
        val points = ArrayList<FaceLandmark>(FaceLandmarks.EXPECTED_COUNT)
        var seed = 1_337L
        fun next(): Float {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            return (((seed ushr 32) and 0xFFFFFFFFL).toFloat() / 4294967296.0f)
        }
        repeat(FaceLandmarks.EXPECTED_COUNT) {
            val fx = minX + (maxX - minX) * next()
            val fy = minY + (maxY - minY) * next()
            points.add(FaceLandmark(fx, fy, 0f))
        }
        // Pin the extremes so bbox min/max are exact — the jittered
        // cloud alone would otherwise land slightly inside the target
        // and shift the extractor's bbox each test run (not a stability
        // problem, but makes failure diagnosis murkier).
        points[0] = FaceLandmark(minX, minY, 0f)
        points[1] = FaceLandmark(maxX, maxY, 0f)
        return FaceLandmarks(points)
    }
}
