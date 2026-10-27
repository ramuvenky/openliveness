package dev.openliveness.layers

/**
 * One frame as the gesture detectors see it: a 478-point mesh and the
 * timestamp (seconds, arbitrary monotonic epoch) that produced it.
 *
 * Replaces the earlier `Pair<FaceLandmarks, Double>` so callers can read
 * `.landmarks` / `.timestampSeconds` instead of `.first` / `.second`.
 * Sequencer-side math still only looks at timestamp deltas, so the epoch
 * remains caller-defined.
 */
data class LandmarkFrame(
    val landmarks: FaceLandmarks,
    val timestampSeconds: Double,
)
