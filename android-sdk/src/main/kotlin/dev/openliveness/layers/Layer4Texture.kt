package dev.openliveness.layers

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Layer 4 texture analysis — LBP + Fourier. See Spec §1404-1462 and the
 * Python reference at `core/algorithms/layer4_texture.py` for the
 * authoritative math. This file is the Kotlin mirror; the iOS side is
 * `Sources/OpenLiveness/Layers/Layer4Texture.swift`. Any threshold or
 * ring-geometry change needs the sibling edit in both languages, since
 * the server re-runs the same math from `layer_scores`.
 *
 * **Fourier branch** (this file): deterministic ring-energy ratio FFT.
 * Fully portable, so we run it directly in Kotlin — no TFLite model.
 *
 * **LBP branch**: computes a uniform LBP (P=8, R=1) histogram and runs
 * it through a bundled TFLite classifier ([TextureClassifier]). The
 * histogram port is parity-tested against skimage via the
 * `layer4_lbp.json` golden fixture — a drift there would feed the
 * trained Dense weights a different feature vector and silently shift
 * `lbpScore` away from the server's re-run.
 *
 * **No-classifier mode**: constructing with `classifier = null` keeps
 * the historical 0.85 stub for `lbpScore`. CDLSession still routes
 * through `AttestationBuilder.stubLayerScores()` today — camera-pipeline
 * wire-up (feeding real face crops into [analyzeFrame]) is a separate
 * follow-on. Unit tests that don't want the native TFLite dep can also
 * use the null path.
 */
class Layer4Texture(
    private val classifier: TextureClassifier? = null,
) : FaceCropSink {
    /** Live-skin probability from the LBP + classifier pipeline. Stays
     *  at the 0.85 neutral stub while [classifier] is null OR until
     *  the first [analyzeFrame] call lands a real inference. */
    var lbpScore = 0.85f
        internal set

    /** 1.0 = no screen artefacts (live); 0.0 = strong artefacts (replay).
     *  Updated by [analyzeFrame]. */
    var fourierScore = 0.85f
        internal set

    /**
     * Analyze a single grayscale face crop (0..255 uint8 pixels, row-
     * major) and update [fourierScore] + [lbpScore]. The crop MUST be
     * square; the caller is responsible for resizing to the 80×80 shape
     * the Python reference uses — passing a different size is still
     * numerically defined but the ring geometry shifts proportionally
     * AND the trained LBP model expects an 80² input distribution, so
     * parity with the server breaks either way.
     *
     * This method is pure (modulo the field writes). It is safe to call
     * from the camera analyzer thread **as long as** the owned
     * [TextureClassifier] isn't shared with another thread —
     * `Interpreter.run` is not reentrant.
     */
    fun analyzeFrame(gray: FloatArray, size: Int) {
        require(gray.size == size * size) {
            "gray buffer must be size*size (${size * size}), got ${gray.size}"
        }
        fourierScore = fourierScoreFor(gray, size)
        classifier?.let {
            val hist = lbpHistogramFor(gray, size)
            lbpScore = it.score(hist)
        }
    }

    /**
     * [FaceCropSink] entry point so a [MediaPipeLandmarkStream] can hand
     * its 80×80 crops straight to this instance without the caller
     * adapting between two shapes. Delegates to [analyzeFrame]; the
     * two names coexist because test code and the direct Kotlin API
     * (CDLSession replay fixtures, Layer2Runner harnesses) call
     * `analyzeFrame` by name, while the camera pipeline calls through
     * the SAM for drift-prevention via reference equality.
     */
    override fun onFrame(gray: FloatArray, size: Int) = analyzeFrame(gray, size)

    companion object {
        /**
         * Uniform LBP (P=8, R=1) 59-bin L1-normalized histogram.
         *
         * Mirrors `core/algorithms/layer4_texture.py::lbp_histogram`
         * which calls `skimage.feature.local_binary_pattern` with
         * `method='uniform'`. The output values of that call are integer
         * in [0, 9] (0..P for uniform patterns = popcount of the code,
         * P+1 for non-uniform), so bins 10..58 are structurally zero.
         * We still return a 59-element vector because the trained TFLite
         * Dense layer expects that exact feature size — see Phase 5
         * Step 3 export comments.
         *
         * **Parity notes vs. skimage**:
         *  - Neighbor offsets: `dr = -sin(2π·p/8)`, `dc = cos(2π·p/8)`
         *    for p ∈ 0..7. Cardinal points (p = 0, 2, 4, 6) land on
         *    integer offsets; diagonals use bilinear interpolation.
         *  - Out-of-bounds samples (border pixels) use 0.0, matching
         *    skimage's `_bilinear_interpolation(…, 'C', 0.)`.
         *  - Comparison: neighbor ≥ center → bit 1 (sign-equivalent to
         *    skimage's `signed_texture[p] = texture[p] >= image[r,c]`).
         *  - Transitions ≤ 2 → uniform; the LBP value is the popcount
         *    of the 8-bit code. Otherwise LBP value = P + 1 = 9.
         *
         * The `layer4_lbp.json` golden fixture replays this against the
         * skimage reference on 3 seeded 80×80 images — if any of the
         * three disagrees, the fixture test flips red before TFLite
         * inference drift sneaks into a release.
         */
        @JvmStatic
        fun lbpHistogramFor(gray: FloatArray, size: Int): FloatArray {
            require(gray.size == size * size) {
                "gray buffer must be size*size (${size * size}), got ${gray.size}"
            }
            val hist = FloatArray(TextureClassifier.FEATURE_SIZE)
            var total = 0
            for (r in 0 until size) {
                for (c in 0 until size) {
                    val center = gray[r * size + c]
                    var code = 0
                    for (p in 0 until 8) {
                        val angle = 2.0 * Math.PI * p / 8.0
                        val dr = -sin(angle)
                        val dc = cos(angle)
                        val rr = r + dr
                        val cc = c + dc
                        val sample = bilinearSample(gray, size, rr, cc)
                        if (sample >= center) code = code or (1 shl p)
                    }
                    val lbpValue = uniformLbpValue(code)
                    hist[lbpValue] += 1f
                    total += 1
                }
            }
            if (total > 0) {
                val inv = 1f / total
                for (i in hist.indices) hist[i] *= inv
            }
            return hist
        }

        /** Bilinear sample of `gray[rr, cc]` with out-of-bounds → 0.0.
         *  Matches skimage's `_bilinear_interpolation(…, mode='C',
         *  cval=0.0)`. Snaps to a direct lookup when the offset is
         *  exactly integer (floor == ceil); this is the common case for
         *  p ∈ {0, 2, 4, 6} and avoids an unnecessary 4-way blend. */
        private fun bilinearSample(gray: FloatArray, size: Int, rr: Double, cc: Double): Float {
            val minR = floor(rr).toInt()
            val minC = floor(cc).toInt()
            val maxR = if (rr == minR.toDouble()) minR else minR + 1
            val maxC = if (cc == minC.toDouble()) minC else minC + 1
            val dr = rr - minR
            val dc = cc - minC

            val tl = pixelAt(gray, size, minR, minC)
            val tr = pixelAt(gray, size, minR, maxC)
            val bl = pixelAt(gray, size, maxR, minC)
            val br = pixelAt(gray, size, maxR, maxC)

            val top = tl * (1.0 - dc) + tr * dc
            val bot = bl * (1.0 - dc) + br * dc
            return (top * (1.0 - dr) + bot * dr).toFloat()
        }

        private fun pixelAt(gray: FloatArray, size: Int, r: Int, c: Int): Double {
            if (r < 0 || r >= size || c < 0 || c >= size) return 0.0
            return gray[r * size + c].toDouble()
        }

        /** Rotation-invariant uniform mapping (skimage `method='U'`):
         *  if the circular bit sequence has ≤ 2 transitions, the output
         *  is the popcount of the 8-bit code; otherwise P + 1 (= 9). */
        private fun uniformLbpValue(code: Int): Int {
            // Count transitions in the circular 8-bit sequence.
            var transitions = 0
            var prev = code and 1
            for (i in 1 until 8) {
                val curr = (code shr i) and 1
                if (curr != prev) transitions++
                prev = curr
            }
            // Wrap: bit 7 → bit 0.
            if ((code and 1) != ((code shr 7) and 1)) transitions++
            if (transitions > 2) return 9  // P + 1 = non-uniform bucket
            // Popcount of the 8-bit code.
            return Integer.bitCount(code and 0xFF)
        }

        /**
         * Pure Fourier score computation, exposed for unit tests and
         * for the golden-fixture replay harness. Reads `size × size`
         * grayscale pixels in [0, 255] and returns a score in [0, 1].
         */
        @JvmStatic
        fun fourierScoreFor(gray: FloatArray, size: Int): Float {
            val magnitude = logMagnitudeSpectrum(gray, size)
            val hfEnergy = ringMean(magnitude, size, innerFracDenom = 3, outerFracDenom = 4)
            val lfEnergy = ringMean(magnitude, size, innerFracDenom = 4, outerFracDenom = 8)
            val expected = lfEnergy / 4f
            val artifactRatio = hfEnergy / max(expected, 1e-10f)
            // Same clamp-at-zero shape as the Python ref: a quieter-than-
            // organic ring must not push the score above 1.0.
            return 1f / (1f + max(artifactRatio - 1f, 0f) * 2f)
        }

        /** 2D DFT (O(N⁴) direct form) → `log(|X| + 1)`. 80×80 runs in
         *  ~30ms on a mid-range phone; invoked at most a few times per
         *  session so the simplicity vs. an FFT is worth it. Returns
         *  values indexed as `[row * size + col]`, DC centered at
         *  `(size/2, size/2)`. */
        private fun logMagnitudeSpectrum(gray: FloatArray, size: Int): FloatArray {
            val n = size
            val out = FloatArray(n * n)
            val twoPiOverN = 2.0 * Math.PI / n
            for (u in 0 until n) {
                for (v in 0 until n) {
                    var re = 0.0
                    var im = 0.0
                    for (y in 0 until n) {
                        for (x in 0 until n) {
                            val angle = -twoPiOverN * (u * y + v * x)
                            val px = gray[y * n + x]
                            re += px * cos(angle)
                            im += px * sin(angle)
                        }
                    }
                    val mag = sqrt(re * re + im * im)
                    // Center DC via fftshift index: (u + n/2) % n, (v + n/2) % n.
                    val su = (u + n / 2) % n
                    val sv = (v + n / 2) % n
                    out[su * n + sv] = ln(mag + 1.0).toFloat()
                }
            }
            return out
        }

        /** Mean of `magnitude` over the annulus `size/outer < d < size/inner`
         *  from image center. Matches `_ring_mask` in the Python ref,
         *  including the "keyed off rows (not min(rows, cols))" choice —
         *  the ring geometry only matches on a square crop. */
        private fun ringMean(
            magnitude: FloatArray,
            size: Int,
            innerFracDenom: Int,
            outerFracDenom: Int
        ): Float {
            val cr = size / 2
            val cc = size / 2
            val inner = size.toFloat() / outerFracDenom
            val outer = size.toFloat() / innerFracDenom
            val innerSq = inner * inner
            val outerSq = outer * outer
            var sum = 0.0
            var count = 0
            for (y in 0 until size) {
                val dy = (y - cr).toFloat()
                val dySq = dy * dy
                for (x in 0 until size) {
                    val dx = (x - cc).toFloat()
                    val dSq = dySq + dx * dx
                    if (dSq > innerSq && dSq < outerSq) {
                        sum += magnitude[y * size + x]
                        count++
                    }
                }
            }
            if (count == 0) return 0f
            return (sum / count).toFloat()
        }
    }
}
