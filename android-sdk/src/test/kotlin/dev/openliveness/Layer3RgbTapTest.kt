package dev.openliveness

import dev.openliveness.layers.FaceLandmark
import dev.openliveness.layers.FaceLandmarks
import dev.openliveness.layers.SkinRegionExtractor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 4 Step 3 — SkinRegionExtractor unit tests. The extractor is the
 * only non-trivial piece of the RGB-tap plumbing that doesn't require
 * MediaPipe / CameraX / an Android Bitmap, so these tests pin:
 *   - region-centroid math (ROI centred on each landmark, sized by
 *     face_width, correct pixel bounds under both mirror flags)
 *   - the degenerate-input null returns (bad dims, zero face_width,
 *     fully off-frame ROIs)
 *   - averaging correctness (single ROI → that color; 3 equal ROIs →
 *     their mean; one ROI partially clipped → still averages correctly
 *     over the surviving pixels)
 *
 * The iOS sibling (Phase 4 iOS mirror, pending Apple Dev enrollment)
 * must reproduce the same ROI geometry so Phase 5's Python port lands
 * on identical pixel sets.
 */
class Layer3RgbTapTest {

    // Packed ARGB_8888 int, matching the format Bitmap.getPixels writes.
    private fun argb(r: Int, g: Int, b: Int, a: Int = 0xFF): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /**
     * Build a canonical face layout in a WxH frame. All three ROI
     * centroids are inside the frame with room on every side so the
     * default 0.05-face-width ROI never clips.
     *   - Face anchors (234 / 454): face_width = 0.40
     *   - Forehead  (10):  (0.50, 0.20)
     *   - L cheek  (101):  (0.35, 0.50)
     *   - R cheek  (330):  (0.65, 0.50)
     * ROI half-size = 0.05 * 0.40 = 0.02 → each ROI is 0.04 x 0.04 of
     * the frame (e.g. ~4×4 pixels on a 100×100 fixture).
     */
    private fun canonicalLandmarks(): FaceLandmarks {
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        pts[SkinRegionExtractor.FACE_LEFT_ANCHOR]    = FaceLandmark(0.30f, 0.50f)
        pts[SkinRegionExtractor.FACE_RIGHT_ANCHOR]   = FaceLandmark(0.70f, 0.50f)
        pts[SkinRegionExtractor.FOREHEAD_LANDMARK]   = FaceLandmark(0.50f, 0.20f)
        pts[SkinRegionExtractor.LEFT_CHEEK_LANDMARK] = FaceLandmark(0.35f, 0.50f)
        pts[SkinRegionExtractor.RIGHT_CHEEK_LANDMARK] = FaceLandmark(0.65f, 0.50f)
        return FaceLandmarks(pts)
    }

    /** Allocate a flat solid-color pixel buffer. */
    private fun solidPixels(w: Int, h: Int, color: Int): IntArray {
        val pixels = IntArray(w * h) { color }
        return pixels
    }

    // MARK: - Happy path

    @Test
    fun `uniform color returns that same color`() {
        // A solid 100×100 red frame → every ROI pixel is (255, 0, 0) →
        // mean is exactly (255, 0, 0). Sanity check that the averaging
        // and bit-unpacking are both correct.
        val w = 100
        val h = 100
        val pixels = solidPixels(w, h, argb(255, 0, 0))
        val rgb = SkinRegionExtractor.meanSkinRgb(pixels, w, h, canonicalLandmarks())
        assertNotNull(rgb)
        assertEquals(255f, rgb!![0], 0f)
        assertEquals(0f, rgb[1], 0f)
        assertEquals(0f, rgb[2], 0f)
    }

    @Test
    fun `mean of three equal regions equals the shared color`() {
        // All three ROIs sit on identical-color pixels (because the
        // frame is uniform). Mean over 3 equal regions = that color.
        // Different numeric value than the above test to catch a
        // channel-mix-up regression.
        val w = 100
        val h = 100
        val pixels = solidPixels(w, h, argb(10, 20, 30))
        val rgb = SkinRegionExtractor.meanSkinRgb(pixels, w, h, canonicalLandmarks())!!
        assertEquals(10f, rgb[0], 0f)
        assertEquals(20f, rgb[1], 0f)
        assertEquals(30f, rgb[2], 0f)
    }

    @Test
    fun `half-split frame averages correctly across regions`() {
        // Left half of the frame is pure green (0, 255, 0); right half
        // is pure blue (0, 0, 255). Forehead ROI (centroid at x=0.50)
        // straddles the split boundary — this test pins that the
        // averaging reads actual per-pixel values, not just the
        // centroid pixel. L cheek lands in green; R cheek lands in
        // blue; forehead is a mix.
        val w = 100
        val h = 100
        val mid = w / 2
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < mid) argb(0, 255, 0) else argb(0, 0, 255)
        }
        val rgb = SkinRegionExtractor.meanSkinRgb(pixels, w, h, canonicalLandmarks())!!
        // Red channel is 0 everywhere.
        assertEquals(0f, rgb[0], 0f)
        // Green + blue must sum to 255 regardless of exact split — every
        // pixel is either pure-green or pure-blue, so their mean is a
        // mix adding to 255.
        assertEquals(255f, rgb[1] + rgb[2], 0.1f)
        // Both channels are non-trivially non-zero (both sides sampled).
        assertTrue(rgb[1] > 0f && rgb[2] > 0f)
    }

    // MARK: - Mirror handling

    @Test
    fun `asymmetric flipX produces observably different combined mean`() {
        // Review L3: the pre-existing `flipX swaps left-right ROIs` test
        // uses symmetric cheek centroids (0.35 / 0.65) so a left↔right
        // swap of their ROIs is numerically invisible — the test passes
        // whether flipX works or no-ops entirely. This asymmetric case
        // places the L cheek near the frame edge (x=0.02) so without
        // flipX the L-cheek ROI clips to ~2 columns of pixels, while
        // WITH flipX the (un-flipped) centroid lands at x=0.98 which
        // clips to a different width. Combined with a left-half-vs-
        // right-half color split, the two combined means land on
        // distinguishable values.
        val w = 100
        val h = 100
        // Left half red; right half blue.
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) argb(255, 0, 0) else argb(0, 0, 255)
        }
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        // Face occupies x in [0, 0.40]: anchors at 0.00 and 0.40 →
        // face_width = 0.40 → ROI half = 0.02. Forehead and R cheek
        // sit well inside the left-red half; L cheek sits at x=0.02
        // where the ROI clips the frame edge.
        pts[SkinRegionExtractor.FACE_LEFT_ANCHOR]    = FaceLandmark(0.00f, 0.50f)
        pts[SkinRegionExtractor.FACE_RIGHT_ANCHOR]   = FaceLandmark(0.40f, 0.50f)
        pts[SkinRegionExtractor.FOREHEAD_LANDMARK]   = FaceLandmark(0.20f, 0.20f)
        pts[SkinRegionExtractor.LEFT_CHEEK_LANDMARK] = FaceLandmark(0.02f, 0.50f)
        pts[SkinRegionExtractor.RIGHT_CHEEK_LANDMARK] = FaceLandmark(0.30f, 0.50f)
        val lm = FaceLandmarks(pts)
        val plain = SkinRegionExtractor.meanSkinRgb(pixels, w, h, lm, flipX = false)!!
        val flipped = SkinRegionExtractor.meanSkinRgb(pixels, w, h, lm, flipX = true)!!
        // Un-flipped: every ROI centroid is inside x<0.40 → all pure red.
        assertEquals(255f, plain[0], 1e-3f)
        assertEquals(0f, plain[2], 1e-3f)
        // Flipped: every ROI centroid un-flips to the right half of
        // the frame (forehead→0.80, L cheek→0.98, R cheek→0.70) and
        // all land in the blue half. The combined mean MUST shift from
        // pure red to pure blue — if flipX silently no-ops the result
        // equals `plain` and the mirror handling is broken.
        assertEquals(0f, flipped[0], 1e-3f,
            "flipped red must be 0; plain=${plain.toList()} flipped=${flipped.toList()}")
        assertEquals(255f, flipped[2], 1e-3f,
            "flipped blue must be 255")
    }

    @Test
    fun `flipX swaps left-right ROIs`() {
        // Paint left half blue, right half green. Without flipX, L
        // cheek centroid (0.35, mirrored-frame) reads from pixels at
        // x≈35 → blue, R cheek (0.65) → green. With flipX, centroids
        // un-flip to (0.65) and (0.35) → L cheek pulls green, R cheek
        // pulls blue. Combined mean is unchanged (symmetric), so this
        // test asserts individual-pixel behavior through forehead
        // which stays centred: forehead ROI pulls the SAME pixels in
        // both modes. Instead we assert that the geometry runs without
        // raising on the flipX branch and still produces a valid mean.
        val w = 100
        val h = 100
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            if (x < w / 2) argb(0, 0, 255) else argb(0, 255, 0)
        }
        val lm = canonicalLandmarks()
        val plain = SkinRegionExtractor.meanSkinRgb(pixels, w, h, lm, flipX = false)!!
        val flipped = SkinRegionExtractor.meanSkinRgb(pixels, w, h, lm, flipX = true)!!
        // Both complete without null; the symmetric-ROI mean is the
        // same because left↔right cheek ROIs swap pixel sets but both
        // sides contribute to the same combined mean.
        assertEquals(plain[0], flipped[0], 1e-5f)
        assertEquals(plain[1], flipped[1], 0.5f)
        assertEquals(plain[2], flipped[2], 0.5f)
    }

    // MARK: - Degenerate inputs

    @Test
    fun `null on zero face width`() {
        // Face anchors collapsed to the same x (tracking lost). ROI
        // size would be 0 → every ROI collapses to a single pixel at
        // best. We return null so the sink doesn't accumulate noise.
        val w = 100
        val h = 100
        val pixels = solidPixels(w, h, argb(128, 128, 128))
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        pts[SkinRegionExtractor.FACE_LEFT_ANCHOR] = FaceLandmark(0.50f, 0.50f)
        pts[SkinRegionExtractor.FACE_RIGHT_ANCHOR] = FaceLandmark(0.50f, 0.50f)
        pts[SkinRegionExtractor.FOREHEAD_LANDMARK] = FaceLandmark(0.50f, 0.20f)
        pts[SkinRegionExtractor.LEFT_CHEEK_LANDMARK] = FaceLandmark(0.35f, 0.50f)
        pts[SkinRegionExtractor.RIGHT_CHEEK_LANDMARK] = FaceLandmark(0.65f, 0.50f)
        assertNull(SkinRegionExtractor.meanSkinRgb(pixels, w, h, FaceLandmarks(pts)))
    }

    @Test
    fun `null on inverted face width`() {
        // L and R anchors swapped — face_width is negative. Would
        // produce a negative ROI half-size and the integer-coerce math
        // would yield malformed bounds. Null is safer than guessing.
        val w = 100
        val h = 100
        val pixels = solidPixels(w, h, argb(128, 128, 128))
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        pts[SkinRegionExtractor.FACE_LEFT_ANCHOR] = FaceLandmark(0.70f, 0.50f)
        pts[SkinRegionExtractor.FACE_RIGHT_ANCHOR] = FaceLandmark(0.30f, 0.50f)
        pts[SkinRegionExtractor.FOREHEAD_LANDMARK] = FaceLandmark(0.50f, 0.20f)
        pts[SkinRegionExtractor.LEFT_CHEEK_LANDMARK] = FaceLandmark(0.35f, 0.50f)
        pts[SkinRegionExtractor.RIGHT_CHEEK_LANDMARK] = FaceLandmark(0.65f, 0.50f)
        assertNull(SkinRegionExtractor.meanSkinRgb(pixels, w, h, FaceLandmarks(pts)))
    }

    @Test
    fun `null on zero width or height`() {
        val pixels = IntArray(0)
        val lm = canonicalLandmarks()
        assertNull(SkinRegionExtractor.meanSkinRgb(pixels, 0, 100, lm))
        assertNull(SkinRegionExtractor.meanSkinRgb(pixels, 100, 0, lm))
    }

    @Test
    fun `null on under-sized pixel buffer`() {
        // Guard against a caller passing a width/height mismatched
        // with the buffer length — a silent out-of-bounds read on the
        // JVM is actually fine but would produce garbage pixel data.
        // Null forces the mis-wiring to surface.
        val w = 100
        val h = 100
        val tooSmall = IntArray(w * h - 1)
        assertNull(SkinRegionExtractor.meanSkinRgb(tooSmall, w, h, canonicalLandmarks()))
    }

    @Test
    fun `null when all ROIs fall outside the frame`() {
        // Face positioned entirely off-screen (centroids beyond 1.0).
        // Every ROI clips to empty → pixel count stays 0 → null.
        val w = 100
        val h = 100
        val pixels = solidPixels(w, h, argb(50, 60, 70))
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        pts[SkinRegionExtractor.FACE_LEFT_ANCHOR] = FaceLandmark(1.50f, 0.50f)
        pts[SkinRegionExtractor.FACE_RIGHT_ANCHOR] = FaceLandmark(1.90f, 0.50f)
        pts[SkinRegionExtractor.FOREHEAD_LANDMARK] = FaceLandmark(1.70f, 1.70f)
        pts[SkinRegionExtractor.LEFT_CHEEK_LANDMARK] = FaceLandmark(1.55f, 1.50f)
        pts[SkinRegionExtractor.RIGHT_CHEEK_LANDMARK] = FaceLandmark(1.85f, 1.50f)
        assertNull(SkinRegionExtractor.meanSkinRgb(pixels, w, h, FaceLandmarks(pts)))
    }

    @Test
    fun `partial clip still averages over surviving pixels`() {
        // Position the face at the frame edge so one ROI clips but the
        // other two stay inside. Mean must come from the surviving
        // pixels only, with no NaN or divide-by-zero.
        val w = 100
        val h = 100
        val pixels = solidPixels(w, h, argb(100, 100, 100))
        val pts = MutableList(FaceLandmarks.EXPECTED_COUNT) { FaceLandmark(0f, 0f) }
        // face_width = 0.40, ROI half = 0.02. L anchor at x=0.00
        // leaves the L cheek centroid at x=0.05 — fully inside the
        // frame on a 100px wide stream. Push the face left so L cheek
        // partly clips. Centroid at x=0.00 means the left half of its
        // ROI (x in [-0.02, 0.00)) clips, right half survives.
        pts[SkinRegionExtractor.FACE_LEFT_ANCHOR]    = FaceLandmark(-0.05f, 0.50f)
        pts[SkinRegionExtractor.FACE_RIGHT_ANCHOR]   = FaceLandmark(0.35f, 0.50f)
        pts[SkinRegionExtractor.FOREHEAD_LANDMARK]   = FaceLandmark(0.15f, 0.20f)
        pts[SkinRegionExtractor.LEFT_CHEEK_LANDMARK] = FaceLandmark(0.00f, 0.50f)
        pts[SkinRegionExtractor.RIGHT_CHEEK_LANDMARK] = FaceLandmark(0.30f, 0.50f)
        val rgb = SkinRegionExtractor.meanSkinRgb(pixels, w, h, FaceLandmarks(pts))!!
        // Uniform frame — mean must still equal the fill color.
        assertEquals(100f, rgb[0], 0f)
        assertEquals(100f, rgb[1], 0f)
        assertEquals(100f, rgb[2], 0f)
    }
}
