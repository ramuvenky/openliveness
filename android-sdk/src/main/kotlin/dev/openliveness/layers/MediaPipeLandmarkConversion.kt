package dev.openliveness.layers

/**
 * Build FaceLandmarks from raw (x,y,z) triples. Factored out of
 * MediaPipeLandmarkStream so a JVM unit test can drive the mirror and
 * count-mismatch branches without touching MediaPipe types — the
 * tasks-vision AAR carries native .so files that aren't on the JVM
 * unit-test classpath, so any transitive load of a MediaPipe class
 * from a test crashes with UnsatisfiedLinkError.
 *
 * Returns null for empty input or when the count doesn't match the
 * 478-point MediaPipe Face Mesh contract. When [mirror] is true,
 * x-coords are flipped to `1 - x` — the front-camera convention the
 * gesture detectors assume (see MediaPipeLandmarkStream kdoc).
 */
internal fun faceLandmarksFromXYZ(
    xyz: List<Triple<Float, Float, Float>>,
    mirror: Boolean,
): FaceLandmarks? {
    if (xyz.size != FaceLandmarks.EXPECTED_COUNT) return null
    val converted = ArrayList<FaceLandmark>(xyz.size)
    for ((x, y, z) in xyz) {
        val mx = if (mirror) 1f - x else x
        converted.add(FaceLandmark(mx, y, z))
    }
    return FaceLandmarks.tryFrom(converted)
}
