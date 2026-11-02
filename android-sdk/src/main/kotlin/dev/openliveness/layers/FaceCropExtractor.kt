package dev.openliveness.layers

/**
 * Receiver for per-frame 80×80 grayscale face crops. [Layer4Texture]
 * implements this directly so a single instance can serve as both the
 * stream's crop consumer and the score accumulator read by
 * [dev.openliveness.CDLSession] at attestation time — same pattern as
 * [RGBSampleSink] / [Layer3RPPG].
 *
 * **Threading:** `onFrame` is invoked on the CameraX analyzer executor
 * (the same thread Layer 2's landmark detection runs on). Implementations
 * must be non-blocking enough to keep up with the stream's rate limit
 * (~1 Hz in production — see [MediaPipeLandmarkStream]); a slow sink
 * stalls MediaPipe frame delivery because the single-thread executor
 * serializes `analyze()`.
 */
fun interface FaceCropSink {
    /** Called with an `outSize * outSize` grayscale buffer per invocation.
     *  Pixels are in [0, 255] (floats, not uint8) — row-major, same shape
     *  [Layer4Texture.analyzeFrame] expects. */
    fun onFrame(gray: FloatArray, size: Int)
}

/**
 * Extracts an 80×80 grayscale face crop from an ARGB_8888 bitmap buffer
 * and a MediaPipe face landmark set, matching the preprocessing step
 * `core/algorithms/layer4_texture.py::lbp_histogram` does with
 * `cv2.resize(face, (80, 80))` + `cv2.cvtColor(face, COLOR_BGR2GRAY)`.
 *
 * Pure int-array / float-math (same shape as [SkinRegionExtractor]):
 * no Android Bitmap or MediaPipe types in the inner loop so JVM unit
 * tests can exercise the geometry + luma conversion without native deps.
 *
 * **Why this file and not SkinRegionExtractor**: SkinRegionExtractor
 * samples three fixed sub-regions and reduces them to one 3-tuple per
 * frame for rPPG — a very different operator than "crop the whole face
 * bbox and resample to 80×80". Mixing the two would obscure both.
 */
object FaceCropExtractor {

    /** The side length (in pixels) the LBP classifier was trained
     *  against. Mirrors `cv2.resize(face, (80, 80))` in
     *  `core/algorithms/layer4_texture.py`; changing it here without
     *  retraining would silently reshape the LBP histogram feature
     *  space and shift every Dense output. Exposed as a public constant
     *  so callers (notably [MediaPipeLandmarkStream.tapFaceCrop])
     *  forward the exact size to the sink and don't drift from the
     *  default on this method. */
    const val OUT_SIZE: Int = 80

    /** Pad the landmark bbox by this fraction of its own dimension before
     *  cropping. Mirrors the "generous crop" Silent-Face-Anti-Spoofing
     *  uses before `cv2.resize((80,80))` so the LBP's border rows see
     *  skin texture rather than empty frame. 15% is enough to recover
     *  the forehead that MediaPipe's mesh clips above landmark 10. */
    const val BBOX_PADDING_FRAC = 0.15f

    /** OpenCV `cvtColor(BGR2GRAY)` coefficients. The training data in
     *  `core/models/synthetic_dataset.py` ran through that exact call;
     *  swapping to a different luma formula would silently reinterpret
     *  the LBP histogram and move the Dense-layer output. */
    private const val LUMA_R = 0.299f
    private const val LUMA_G = 0.587f
    private const val LUMA_B = 0.114f

    /**
     * Returns an `outSize * outSize` FloatArray of luma values in [0, 255],
     * row-major, or null when the bbox is degenerate (face lost / fully
     * off-frame).
     *
     * @param pixels ARGB_8888 packed ints, same layout
     *   `android.graphics.Bitmap.getPixels` writes.
     * @param width width of [pixels] in pixels.
     * @param height height of [pixels] in pixels.
     * @param landmarks the 478-point face mesh. Must come from the same
     *   frame as [pixels] (or at most one frame behind — see
     *   `MediaPipeLandmarkStream.latestLandmarks` for the staleness bound).
     * @param flipX `true` when [landmarks] came from a mirrored-front-
     *   camera flow and [pixels] is sensor-raw. The bbox is computed in
     *   normalized landmark space, then un-flipped for pixel addressing —
     *   same convention [SkinRegionExtractor.meanSkinRgb] uses.
     * @param outSize square side of the output crop. Defaults to 80 to
     *   match the Python `cv2.resize(face, (80, 80))` reference.
     * @param paddingFrac bbox padding fraction; see [BBOX_PADDING_FRAC].
     */
    fun extractGrayFaceCrop(
        pixels: IntArray,
        width: Int,
        height: Int,
        landmarks: FaceLandmarks,
        flipX: Boolean,
        outSize: Int = OUT_SIZE,
        paddingFrac: Float = BBOX_PADDING_FRAC,
    ): FloatArray? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size < width * height) return null
        if (outSize <= 0) return null

        // Find landmark bbox in normalized coords. Scanning all 478
        // points is O(N) which is well under the per-frame budget — no
        // need to pre-compute a smaller face-outline subset. Un-flip x
        // here (one subtraction per point) so the final bbox is in the
        // sensor-raw frame of reference the pixel buffer expects.
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (lm in landmarks.points) {
            val x = if (flipX) 1f - lm.x else lm.x
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (lm.y < minY) minY = lm.y
            if (lm.y > maxY) maxY = lm.y
        }
        val bboxW = maxX - minX
        val bboxH = maxY - minY
        if (bboxW <= 0f || bboxH <= 0f) return null

        val padX = bboxW * paddingFrac
        val padY = bboxH * paddingFrac

        // Clamp into pixel bounds. The outer coerce protects the lerp
        // against negative indices; the inner "x0 >= x1" guard catches
        // the case where the whole padded bbox fell outside the frame.
        val x0f = ((minX - padX) * width).coerceAtLeast(0f)
        val y0f = ((minY - padY) * height).coerceAtLeast(0f)
        val x1f = ((maxX + padX) * width).coerceAtMost((width - 1).toFloat())
        val y1f = ((maxY + padY) * height).coerceAtMost((height - 1).toFloat())
        if (x0f >= x1f || y0f >= y1f) return null

        val srcW = x1f - x0f
        val srcH = y1f - y0f
        val out = FloatArray(outSize * outSize)

        // Half-pixel bilinear resample — matches OpenCV's
        // `cv2.resize(..., INTER_LINEAR)` sampling convention. Rows and
        // cols computed per-output-pixel; cheaper would be to precompute
        // row/col index tables, but 80² = 6400 iterations total — the
        // inner lerp dominates and the table trick saves <1 ms.
        for (dy in 0 until outSize) {
            val sy = (dy + 0.5f) * srcH / outSize - 0.5f + y0f
            val fy = sy.toInt().coerceIn(0, height - 1)
            val cy = (fy + 1).coerceAtMost(height - 1)
            val wy = (sy - fy).coerceIn(0f, 1f)
            val rowStart0 = fy * width
            val rowStart1 = cy * width
            for (dx in 0 until outSize) {
                val sx = (dx + 0.5f) * srcW / outSize - 0.5f + x0f
                val fx = sx.toInt().coerceIn(0, width - 1)
                val cx = (fx + 1).coerceAtMost(width - 1)
                val wx = (sx - fx).coerceIn(0f, 1f)
                val tl = pixels[rowStart0 + fx]
                val tr = pixels[rowStart0 + cx]
                val bl = pixels[rowStart1 + fx]
                val br = pixels[rowStart1 + cx]
                val tlY = luma(tl)
                val trY = luma(tr)
                val blY = luma(bl)
                val brY = luma(br)
                val top = tlY + (trY - tlY) * wx
                val bot = blY + (brY - blY) * wx
                out[dy * outSize + dx] = top + (bot - top) * wy
            }
        }
        return out
    }

    /** ARGB_8888 packed int → luma (0..255). Channels extracted the
     *  same way `SkinRegionExtractor.meanSkinRgb` does, with the standard
     *  BT.601 coefficients OpenCV uses. */
    private fun luma(argb: Int): Float {
        val r = ((argb shr 16) and 0xFF).toFloat()
        val g = ((argb shr 8) and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        return LUMA_R * r + LUMA_G * g + LUMA_B * b
    }
}
