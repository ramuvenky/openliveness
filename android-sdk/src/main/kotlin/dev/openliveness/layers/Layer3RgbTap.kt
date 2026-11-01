package dev.openliveness.layers

/**
 * One frame's mean R/G/B across the combined skin regions. Floats so the
 * downstream Layer 3 POS pipeline (Phase 4 Step 4) can run its channel
 * normalisation + projection math without re-widening from Byte.
 *
 * Values are 0..255; we don't scale to [0,1] here because the Python
 * reference in Spec §1364-1399 operates on raw byte-domain means and
 * the server-side re-run (Phase 5+) will divide by frame-mean too. A
 * scaled client would make the division cancel differently — not
 * numerically identical to Python despite looking equivalent.
 */
data class RGBSample(
    val red: Float,
    val green: Float,
    val blue: Float,
    val timestampSeconds: Double,
)

/**
 * Receiver for per-frame skin-region RGB samples. Implemented by Layer 3
 * in Step 4 — Step 3 (this file) establishes only the tap plumbing so
 * the capture pipeline can start producing samples before the POS
 * detector lands.
 *
 * **Threading:** `onSample` is invoked on the CameraX analyzer executor
 * (same thread Layer 2's `MediaPipeLandmarkStream.analyze` runs on).
 * Implementations must be non-blocking — a slow sink stalls the capture
 * pipeline and starves MediaPipe's frame delivery. Append to a lock-free
 * ring or atomic buffer; defer heavy FFT work to the Layer 3 finaliser.
 */
fun interface RGBSampleSink {
    fun onSample(sample: RGBSample)
}

/**
 * Extracts the mean R/G/B across three skin ROIs (forehead + both
 * cheeks) for one frame. Pure int-array / float-math code: no Android
 * Bitmap or MediaPipe types referenced, so JVM unit tests can exercise
 * the geometry + averaging without the native dependencies.
 *
 * The region centroids and ROI size are the on-device source of truth
 * for Layer 3 — the Phase 5 Python mirror (`core/algorithms/layer3_rppg_pos.py`)
 * will inherit these literal indices + the face-width-relative size so
 * the server's re-run from `layer_scores` reproduces the client's bpm /
 * SNR within rounding tolerance. Any index tweak here needs the Swift
 * sibling (iOS Phase 4 mirror) + the Python port updated in lockstep.
 */
object SkinRegionExtractor {

    /** MediaPipe FaceMesh landmark indices used as centroids for each ROI.
     *  Chosen from the stable "skin" vertices of the 478-point mesh:
     *   - 10  — top of forehead midline (between eyebrows, well above
     *           the hairline for most faces)
     *   - 101 — left cheekbone, below the eye and above the nasolabial
     *           fold — fleshy skin with reliable rPPG signal
     *   - 330 — right cheekbone mirror of 101
     *  Picked because all three sit on broad flat skin regions and are
     *  rarely occluded by hair, glasses frames, or facial-hair lines. */
    const val FOREHEAD_LANDMARK = 10
    const val LEFT_CHEEK_LANDMARK = 101
    const val RIGHT_CHEEK_LANDMARK = 330

    /** Face-width anchors — identical to the ones Layer 2's NodDetector
     *  uses for distance normalisation, so a face-tracking regression
     *  that moves these indices breaks both layers' calibration at once
     *  (which is what we want — loud, not silent). */
    const val FACE_LEFT_ANCHOR = 234
    const val FACE_RIGHT_ANCHOR = 454

    // Hoisted out of meanSkinRgb so the per-frame hot path doesn't
    // reallocate a 3-int array on every call. At 30fps × 10s of capture
    // that's 300 wasted allocations per session — immaterial individually,
    // but the whole point of the extractor is to keep its per-frame cost
    // under the camera cadence budget.
    private val ROI_LANDMARK_INDICES = intArrayOf(
        FOREHEAD_LANDMARK, LEFT_CHEEK_LANDMARK, RIGHT_CHEEK_LANDMARK
    )

    /** Half-side of each square ROI, as a fraction of face_width. 0.05
     *  → a 10%-of-face-width square at each centroid. At face_width 0.4
     *  (typical arm's-length framing) each ROI is ~0.04 of the frame
     *  edge, ≈43×43 px on a 1080p stream — ~1800 pixels per region,
     *  5400 pixels total mean per frame. Large enough that integer-byte
     *  averaging doesn't lose signal to quantization; small enough that
     *  the ROI stays well inside skin even on expressive faces. */
    const val ROI_HALF_SIZE_FACE_WIDTH_FRAC = 0.05f

    /**
     * Compute mean R/G/B across all three ROIs combined (not per-region
     * — Spec §1364-1365's `rgb_signal` is a single Nx3 time series, not
     * three parallel ones). Pixels are read as packed ARGB_8888 ints:
     * `(A<<24) | (R<<16) | (G<<8) | B` — the format
     * `Bitmap.getPixels(..., ARGB_8888)` writes into the destination
     * IntArray.
     *
     * Returns null when any of these degenerate conditions fire — we'd
     * rather drop the frame than feed a NaN or bogus-weighted mean into
     * the POS pipeline:
     *   - face_width ≤ 0 (face lost; both anchors collapsed to the
     *     same x) — ROI size would collapse to 0 and the integer coerce
     *     could produce a 0×0 window
     *   - every ROI clipped entirely outside the image bounds (face
     *     off-frame)
     *   - combined pixel count = 0 (all three regions degenerate)
     */
    fun meanSkinRgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        landmarks: FaceLandmarks,
        /** True when [landmarks] came from a mirrored front-camera flow
         *  (the Layer-2 default via `faceLandmarksFromXYZ(xyz, mirror=true)`)
         *  BUT [pixels] is sensor-raw (un-mirrored). We un-flip x on the
         *  read side here so ROIs land on the correct side of the raw
         *  bitmap. The combined mean over three symmetric ROIs would be
         *  numerically unaffected by a left↔right swap of the two cheek
         *  ROIs — but a future per-region split (Phase 5 Python port)
         *  would be, and that mismatch would be a silent client/server
         *  divergence. Pay the one subtraction per index now rather than
         *  bake in the ambiguity. */
        flipX: Boolean = false,
    ): FloatArray? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size < width * height) return null

        val leftX = if (flipX) 1f - landmarks[FACE_RIGHT_ANCHOR].x else landmarks[FACE_LEFT_ANCHOR].x
        val rightX = if (flipX) 1f - landmarks[FACE_LEFT_ANCHOR].x else landmarks[FACE_RIGHT_ANCHOR].x
        val faceWidth = rightX - leftX
        if (faceWidth <= 0f) return null
        val half = ROI_HALF_SIZE_FACE_WIDTH_FRAC * faceWidth

        // Longs on purpose: a 1080p ROI × 3 regions × 255 max channel
        // value is ~1.5e9 — safely under Long.MAX but overflowing a
        // signed Int at ~2.1e9 if someone bumps the ROI frac upward.
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var count = 0L

        for (idx in ROI_LANDMARK_INDICES) {
            val lm = landmarks[idx]
            val cx = if (flipX) 1f - lm.x else lm.x
            // Normalised coords → pixel bounds, clamped to the image.
            // The outer coerce keeps clipped regions legal; the inner
            // "x0 > x1" check (post-clip) skips a region that fell
            // entirely outside the frame.
            val x0 = ((cx - half) * width).toInt().coerceAtLeast(0)
            val y0 = ((lm.y - half) * height).toInt().coerceAtLeast(0)
            val x1 = ((cx + half) * width).toInt().coerceAtMost(width - 1)
            val y1 = ((lm.y + half) * height).toInt().coerceAtMost(height - 1)
            if (x0 > x1 || y0 > y1) continue

            for (y in y0..y1) {
                val rowStart = y * width
                for (x in x0..x1) {
                    val p = pixels[rowStart + x]
                    sumR += ((p shr 16) and 0xFF).toLong()
                    sumG += ((p shr 8) and 0xFF).toLong()
                    sumB += (p and 0xFF).toLong()
                    count++
                }
            }
        }
        if (count == 0L) return null
        val n = count.toFloat()
        return floatArrayOf(sumR / n, sumG / n, sumB / n)
    }
}
