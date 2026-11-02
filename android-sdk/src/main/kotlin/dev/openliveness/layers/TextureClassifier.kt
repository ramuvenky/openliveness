package dev.openliveness.layers

import android.content.Context
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.Interpreter

/**
 * Loads `lbp_classifier.tflite` from the SDK assets and exposes a single
 * `score(FloatArray(59)) -> Float` call for Layer 4. The model is the
 * fused Dense(59→5) sigmoid + Dense(5→1) linear-mean produced by
 * `core/models/export_tflite.py`; see that file for the math fuse.
 *
 * **Why a dedicated class and not a top-level fun**: TFLite's
 * `Interpreter` is `Closeable` and holds a native handle, so it needs a
 * lifecycle. [Layer4Texture] takes a `TextureClassifier?` constructor
 * param so unit tests can skip the native load and production code can
 * `use { classifier -> ... }` around the session.
 *
 * **Thread-safety**: `Interpreter.run` is NOT thread-safe. Callers must
 * serialize access — in practice the camera analyzer thread owns the
 * only reference, so this is a non-issue, but don't share a classifier
 * across threads without external locking.
 */
class TextureClassifier private constructor(
    private val interpreter: Interpreter,
) : Closeable {

    /**
     * Run inference on a 59-bin L1-normalized LBP histogram and return
     * the probability-of-live in [0, 1]. The input must match the
     * histogram produced by [Layer4Texture.lbpHistogramFor]; feeding a
     * raw count histogram (not normalized) silently returns a very wrong
     * score because the trained Dense layer expects sum(x) == 1.
     */
    fun score(histogram: FloatArray): Float {
        require(histogram.size == FEATURE_SIZE) {
            "histogram must have $FEATURE_SIZE bins, got ${histogram.size}"
        }
        // Model signature from export_tflite.py:
        //   input:  shape [1, 59], dtype float32
        //   output: shape [1, 1],  dtype float32
        // We allocate direct ByteBuffers because TFLite's Java API treats
        // non-direct buffers as a slow path (per-element copy into a
        // native scratch region).
        val input = ByteBuffer.allocateDirect(FEATURE_SIZE * 4).order(ByteOrder.nativeOrder())
        for (v in histogram) input.putFloat(v)
        input.rewind()
        val output = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        interpreter.run(input, output)
        output.rewind()
        return output.float
    }

    override fun close() {
        interpreter.close()
    }

    companion object {
        /** 59 bins = `method='uniform'` LBP's output range (0..9 populated,
         *  10..58 structurally zero) — matches the training-time feature
         *  vector in `core/algorithms/layer4_texture.py::lbp_histogram`. */
        const val FEATURE_SIZE = 59

        /** Asset file name — bundled by `./gradlew assembleRelease` from
         *  `src/main/assets/`. Changed here means the exporter output
         *  path in `core/models/export_tflite.py` has to move too. */
        const val ASSET_NAME = "lbp_classifier.tflite"

        /**
         * Load the bundled model from app assets. Pulls the whole model
         * into a direct ByteBuffer up front so the Interpreter doesn't
         * mmap through the AssetManager at inference time; the file is
         * ~3 KB so the copy is free.
         */
        @JvmStatic
        fun fromAssets(context: Context): TextureClassifier {
            val bytes = context.assets.open(ASSET_NAME).use { it.readBytes() }
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            return TextureClassifier(Interpreter(buf))
        }

        /** Test-only constructor that takes a raw flatbuffer byte array
         *  (e.g. loaded from a test resource) so unit tests can score
         *  without an Android `Context`. */
        @JvmStatic
        internal fun fromBytes(bytes: ByteArray): TextureClassifier {
            val buf = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            return TextureClassifier(Interpreter(buf))
        }
    }
}
