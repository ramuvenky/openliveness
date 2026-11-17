package dev.openliveness

import dev.openliveness.layers.FaceCropSink
import dev.openliveness.layers.LandmarkFrame
import dev.openliveness.layers.LandmarkStream
import dev.openliveness.layers.Layer3RPPG
import dev.openliveness.layers.Layer4Texture
import dev.openliveness.layers.RGBSampleSink

/**
 * Instrumented-test fakes — mirror of src/test/.../TestFakes.kt. Kept
 * separate because androidTest and test source sets don't share a
 * classpath; keep both copies in lock-step on edits.
 */

class NoopLandmarkStream(
    override val rgbSink: RGBSampleSink? = null,
    override val faceCropSink: FaceCropSink? = null,
) : LandmarkStream {
    override suspend fun nextFrame(): LandmarkFrame? = null
}

fun fakeWiring(): Triple<NoopLandmarkStream, Layer3RPPG, Layer4Texture> {
    val layer3 = Layer3RPPG()
    val layer4 = Layer4Texture(executor = Layer4Texture.DIRECT_EXECUTOR)
    val stream = NoopLandmarkStream(rgbSink = layer3, faceCropSink = layer4)
    return Triple(stream, layer3, layer4)
}
