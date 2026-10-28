package dev.openliveness

import dev.openliveness.layers.FaceLandmarks
import dev.openliveness.layers.faceLandmarksFromXYZ
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers the MediaPipe→FaceLandmarks adapter's pure half: count
 * validation + the front-camera mirror flip. The MediaPipe-dependent
 * other half (unwrapping NormalizedLandmark + routing a single face
 * from a multi-face result) is exercised by instrumented tests and the
 * on-device sample, not here — tasks-vision's native .so files aren't
 * on the JVM test classpath so loading NormalizedLandmark crashes.
 */
class MediaPipeLandmarkConversionTest {

    private fun fullMesh(fill: (Int) -> Triple<Float, Float, Float>): List<Triple<Float, Float, Float>> =
        List(FaceLandmarks.EXPECTED_COUNT) { fill(it) }

    @Test
    fun `wrong point count returns null`() {
        // 477 isn't the 478-point mesh — this is the "someone wired a
        // different task file" guard. Null means the runner skips the
        // frame, which is better than a crash deep in a gesture detector.
        val short = List(FaceLandmarks.EXPECTED_COUNT - 1) { Triple(0.5f, 0.5f, 0f) }
        assertNull(faceLandmarksFromXYZ(short, mirror = false))
        assertNull(faceLandmarksFromXYZ(emptyList(), mirror = false))
    }

    @Test
    fun `mirror off preserves x`() {
        val input = fullMesh { _ -> Triple(0.25f, 0.5f, 0f) }
        val fl = faceLandmarksFromXYZ(input, mirror = false)
            ?: error("expected FaceLandmarks")
        assertEquals(0.25f, fl[0].x, 1e-6f)
        assertEquals(0.25f, fl[477].x, 1e-6f)
    }

    @Test
    fun `mirror on flips x to 1 minus x`() {
        // GazeDetector.LEFT only fires when nose.x < face_center.x after
        // normalization. With the raw front-camera stream, user looking
        // left produces nose.x > center, so a correctly-wired adapter
        // must flip x. Verify the flip arithmetic is exact at endpoints
        // and at a mid-value — any rounding here propagates into the
        // 0.15 threshold check.
        val input = fullMesh { i ->
            val x = when (i) {
                0 -> 0.0f
                1 -> 1.0f
                2 -> 0.3f
                else -> 0.5f
            }
            Triple(x, 0.5f, 0f)
        }
        val fl = faceLandmarksFromXYZ(input, mirror = true)
            ?: error("expected FaceLandmarks")
        assertEquals(1.0f, fl[0].x, 1e-6f)
        assertEquals(0.0f, fl[1].x, 1e-6f)
        assertEquals(0.7f, fl[2].x, 1e-6f)
        // Non-mirrored axes untouched — the mirror is x-only.
        assertEquals(0.5f, fl[0].y, 1e-6f)
    }

    @Test
    fun `z is passed through unchanged`() {
        // z carries pseudo-depth; no detector reads it today but future
        // 3D nod/pitch estimators will. Mirror must not touch it.
        val input = fullMesh { _ -> Triple(0.5f, 0.5f, 0.123f) }
        val mirrored = faceLandmarksFromXYZ(input, mirror = true)
            ?: error("expected FaceLandmarks")
        assertEquals(0.123f, mirrored[0].z, 1e-6f)
    }
}
