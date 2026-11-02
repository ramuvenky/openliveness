package dev.openliveness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openliveness.layers.Layer4Texture
import dev.openliveness.layers.TextureClassifier
import kotlin.math.abs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device parity test for the shipped `lbp_classifier.tflite` asset.
 *
 * The JVM `GoldenFixtureParityTest` can't exercise this path — TFLite's
 * ops ship as a native `.so` inside the AAR and `Interpreter.<init>`
 * throws `UnsatisfiedLinkError` outside Android. This test closes that
 * gap by running the real [TextureClassifier.fromAssets] load + inference
 * on the emulator/device against the same 3 golden LBP fixtures the
 * export-time 1e-4 check used.
 *
 * **Scope**: proves (a) the asset is actually bundled into the AAR, (b)
 * `TextureClassifier.fromAssets` loads it without a native-link failure,
 * (c) `TextureClassifier.score` on the Kotlin-computed histogram agrees
 * with the Python/fused-numpy ground truth within 1e-4. A drift means
 * either the asset was corrupted during Gradle packaging or TFLite
 * runtime version drift re-wrote the Dense ops.
 */
@RunWith(AndroidJUnit4::class)
class TextureClassifierInstrumentedTest {

    @Test
    fun scoreMatchesFusedNumpyGroundTruthWithin1e4() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // targetContext sees both the SDK's main assets (lbp_classifier.tflite)
        // and the androidTest assets (layer4_lbp.json) in a library-module
        // test APK, since AGP merges them. Using one context for both
        // keeps the loader symmetry.
        val context = instrumentation.targetContext

        val fixtureJson = context.assets.open("layer4_lbp.json").use {
            String(it.readBytes(), Charsets.UTF_8)
        }
        val cases = JSONObject(fixtureJson).getJSONArray("cases")

        TextureClassifier.fromAssets(context).use { classifier ->
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val name = case.getString("name")
                val inputs = case.getJSONObject("inputs")
                val size = inputs.getInt("size")
                val grayJson = inputs.getJSONArray("gray")
                val gray = FloatArray(grayJson.length()) { grayJson.getInt(it).toFloat() }

                val hist = Layer4Texture.lbpHistogramFor(gray, size)
                assertEquals("$name/histogram size", TextureClassifier.FEATURE_SIZE, hist.size)

                val expected = case.getJSONObject("expected").getDouble("lbp_score")
                val actual = classifier.score(hist).toDouble()
                val delta = abs(expected - actual)
                assertTrue(
                    "$name/lbp_score python=$expected device=$actual Δ=$delta (>1e-4)",
                    delta <= 1e-4
                )
            }
        }
    }
}
