package dev.openliveness

import dev.openliveness.layers.FaceCropSink
import dev.openliveness.layers.LandmarkFrame
import dev.openliveness.layers.LandmarkStream
import dev.openliveness.layers.Layer3RPPG
import dev.openliveness.layers.Layer4Texture
import dev.openliveness.layers.RGBSampleSink

/**
 * Shared test fakes. After the stub-path removal, CDLSession requires
 * `landmarkStream` + `layer3` + `layer4` at construction; tests that
 * don't exercise detection logic use these no-op fakes to satisfy the
 * init-time wiring checks without spinning up native MediaPipe / TFLite.
 */

/**
 * A [LandmarkStream] that immediately ends (no frames) and echoes back
 * whatever sinks it was constructed with so the reference-equality
 * wiring checks in [CDLSession] pass.
 */
class NoopLandmarkStream(
    override val rgbSink: RGBSampleSink? = null,
    override val faceCropSink: FaceCropSink? = null,
) : LandmarkStream {
    override suspend fun nextFrame(): LandmarkFrame? = null
}

/**
 * Convenience triple: a fresh Layer3RPPG + Layer4Texture (classifier=null,
 * neutral 0.85 scores) + a NoopLandmarkStream pre-wired to both. Use in
 * tests that only care that the [CDLSession] constructor returns, not
 * what the detectors do.
 */
fun fakeWiring(): Triple<NoopLandmarkStream, Layer3RPPG, Layer4Texture> {
    val layer3 = Layer3RPPG()
    val layer4 = Layer4Texture(executor = Layer4Texture.DIRECT_EXECUTOR)
    val stream = NoopLandmarkStream(rgbSink = layer3, faceCropSink = layer4)
    return Triple(stream, layer3, layer4)
}
