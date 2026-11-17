"""Layer 5 — Behavioral biometrics (micro-movements + microsaccades).

Reference implementation per Spec §1465-1515. The Android mirror lives
in `Layer5Behavioral.kt` — thresholds here MUST match that file. The
server re-runs this module from `layer_scores` before issuing a JWT.
"""

from __future__ import annotations

from typing import Sequence

import numpy as np

# Spec §1487 — 60 frames ≈ 2s at 30fps.
MICRO_MOVEMENT_WINDOW_FRAMES = 60

# Spec §1488 — nose bridge landmarks are stable under expression changes
# so their displacement reflects involuntary head micro-tremor only.
NOSE_BRIDGE_INDICES = (6, 8, 9, 10)

# Spec §1497 — left-iris landmarks. The Python ref uses only the left
# iris; the Kotlin mirror does too. Keep in sync.
IRIS_LEFT_INDICES = (468, 469, 470, 471, 472)

# Spec §1523.
MICROSACCADE_VELOCITY_THRESHOLD = 0.005

# Spec §1524 — count adjacent-pair over-threshold instances.
MICROSACCADE_MIN_PAIR_COUNT = 2

# Spec §1488-1491 displacement-to-score ladder.
DISPLACEMENT_SUSPICIOUS_UPPER = 0.0005
DISPLACEMENT_BORDERLINE_UPPER = 0.001
DISPLACEMENT_NATURAL_UPPER = 0.015

SCORE_SUSPICIOUS = 0.1
SCORE_BORDERLINE = 0.5
SCORE_NATURAL = 1.0
SCORE_EXCESSIVE = 0.6

# Variance-based photo-attack gate (Nov 2026). Sampled landmarks span
# face regions whose per-frame motion vectors stay close to their mean
# on a live user (rigid skull constrains them) but go chaotic on a 2D
# photo waved in front of the camera (MediaPipe's tracker thrashes
# trying to fit a 3D mesh to a flat surface). Keep in sync with
# VARIANCE_SAMPLE_INDICES in `android-sdk/.../Layer5Behavioral.kt` —
# the server re-runs this module from `layer_scores` and a drift
# silently makes attestations fail verification.
VARIANCE_SAMPLE_INDICES = (
    1,            # nose tip
    6, 8, 9, 10,  # nose bridge
    13, 14,       # upper + lower lip center
    33, 133,      # left eye outer + inner corner
    263, 362,     # right eye outer + inner corner
    61, 291,      # mouth corners
    152,          # chin
    234, 454,     # face anchors (cheeks)
    468, 473,     # left + right iris centers
)

# Threshold ABOVE which per-landmark motion variance indicates a
# photo-attack (MediaPipe tracker thrash) rather than articulated live
# motion. Only consulted when nose-bridge displacement clears
# VARIANCE_GATE_DISPLACEMENT_FLOOR — see that constant for the rationale.
# Nov 2026 on-device N=14 calibration: live variance peaked at 2.38e-5,
# photo variance bottomed at 5.57e-5. 3e-5 sits with ~1.3× margin on
# the live side and ~1.9× margin on the photo side.
# Keep in sync with LIVE_VARIANCE_UPPER in Layer5Behavioral.kt.
LIVE_VARIANCE_UPPER = 3e-5

# Displacement floor below which the variance gate is NOT consulted.
# Live users holding mostly still accumulate 0.0005..0.002 nose-bridge
# drift (camera noise + micro-tremor) with variance anywhere up to
# 2.38e-5. Only firing the gate above 0.002 carves out still-live-with-
# drift users; above the floor the subject is unambiguously in motion
# and photo-attack thrashing is distinguishable from live articulation.
# Keep in sync with VARIANCE_GATE_DISPLACEMENT_FLOOR in Layer5Behavioral.kt.
VARIANCE_GATE_DISPLACEMENT_FLOOR = 0.002


def _landmark_xy(landmark) -> tuple[float, float]:
    """Access (x, y) from either a MediaPipe landmark proto (`.x`/`.y`
    attributes) or a plain `(x, y)`/`[x, y]` pair. Tests pass tuples;
    production passes the proto."""
    if hasattr(landmark, "x") and hasattr(landmark, "y"):
        return float(landmark.x), float(landmark.y)
    return float(landmark[0]), float(landmark[1])


def _score_for_mean_displacement(mean_disp: float, motion_variance: float) -> float:
    """Score ladder combining nose-bridge displacement with per-landmark
    motion variance. Mirrors ``Layer5Behavioral.scoreForMeanDisplacement``
    in the Kotlin SDK — a drift breaks the server's `layer_scores` re-run.

    Branch order:

      1. ``mean_disp > NATURAL_UPPER``   → EXCESSIVE (too much motion)
      2. ``mean_disp < SUSPICIOUS_UPPER`` → NATURAL (still subject; L2
         catches still-photo cases, so L5 doesn't try to split there)
      3. ``mean_disp < BORDERLINE_UPPER`` → BORDERLINE (drift-level
         motion, variance gate NOT consulted)
      4. ``mean_disp < VARIANCE_GATE_DISPLACEMENT_FLOOR`` → NATURAL
         (subject moving a bit but not clearly enough to trust the gate)
      5. ``motion_variance > LIVE_VARIANCE_UPPER`` → SUSPICIOUS (subject
         is unambiguously in motion AND per-landmark deltas are chaotic
         — MediaPipe tracker thrashing on a waved 2D photo)
      6. otherwise                       → NATURAL
    """
    if mean_disp > DISPLACEMENT_NATURAL_UPPER:
        return SCORE_EXCESSIVE
    if mean_disp < DISPLACEMENT_SUSPICIOUS_UPPER:
        return SCORE_NATURAL
    if mean_disp < DISPLACEMENT_BORDERLINE_UPPER:
        return SCORE_BORDERLINE
    if mean_disp < VARIANCE_GATE_DISPLACEMENT_FLOOR:
        return SCORE_NATURAL
    if motion_variance > LIVE_VARIANCE_UPPER:
        return SCORE_SUSPICIOUS
    return SCORE_NATURAL


def _motion_variance(landmarks_history: Sequence) -> float:
    """Mean per-pair variance of landmark motion vectors across
    VARIANCE_SAMPLE_INDICES. Returns inf for an under-length window —
    note this would false-fire the high-variance gate, but the caller
    gates on ``mean_disp >= VARIANCE_GATE_DISPLACEMENT_FLOOR`` which is
    unreachable with < 2 frames, so the inf never reaches the branch.
    """
    if landmarks_history is None or len(landmarks_history) < 2:
        return float("inf")
    variances = []
    for i in range(1, len(landmarks_history)):
        prev_frame = landmarks_history[i - 1]
        cur_frame = landmarks_history[i]
        deltas = []
        for lid in VARIANCE_SAMPLE_INDICES:
            px, py = _landmark_xy(prev_frame[lid])
            cx, cy = _landmark_xy(cur_frame[lid])
            deltas.append((cx - px, cy - py))
        deltas_arr = np.asarray(deltas, dtype=np.float64)
        mean = deltas_arr.mean(axis=0)
        resid = deltas_arr - mean
        # mean over N of ||resid||² = mean of (dx-μx)² + (dy-μy)².
        variances.append(float((resid * resid).sum(axis=1).mean()))
    return float(np.mean(variances))


def micro_movement_score(landmarks_history: Sequence) -> float:
    """Score the mean nose-bridge displacement over a frame window.

    Returns 0.0 for an under-length window; the orchestrator reads this
    as "no evidence either way" (safe default: adjacent weights absorb
    the slack via the normal weighted sum).
    """
    if landmarks_history is None or len(landmarks_history) < 2:
        return 0.0

    displacements = []
    for i in range(1, len(landmarks_history)):
        prev_frame = landmarks_history[i - 1]
        cur_frame = landmarks_history[i]
        frame_disp = []
        for lid in NOSE_BRIDGE_INDICES:
            px, py = _landmark_xy(prev_frame[lid])
            cx, cy = _landmark_xy(cur_frame[lid])
            frame_disp.append(np.sqrt((cx - px) ** 2 + (cy - py) ** 2))
        # Spec §1483 — per-frame mean across the 4 nose-bridge indices,
        # then mean-of-means across pairs. Matches the Kotlin reduction.
        displacements.append(float(np.mean(frame_disp)))

    mean_disp = float(np.mean(displacements))
    motion_variance = _motion_variance(landmarks_history)
    return _score_for_mean_displacement(mean_disp, motion_variance)


def detect_microsaccades(landmarks_history: Sequence, fps: int = 30) -> bool:
    """Spec §1494-1515. Return True once ≥ 2 adjacent-frame pairs both
    exceed `MICROSACCADE_VELOCITY_THRESHOLD` within the window.

    Velocity is measured in the **head frame**: per-frame iris centroid
    delta MINUS per-frame nose-bridge centroid delta. A photo physically
    translated in front of the camera produces iris_delta ≈ nose_delta,
    so head-relative velocity ≈ 0 and the detector doesn't fire — this
    is the fix for the Nov 2026 photo attack that scored L5 = 1.0 because
    the earlier absolute-coordinate detector read rigid-body motion as
    iris saccades. For a live user with a stationary head, nose_delta ≈ 0
    and the head-relative velocity equals the absolute velocity, so the
    0.005 threshold keeps its original meaning on genuine saccades.

    `fps` is accepted for signature parity with the spec but not used —
    the threshold is a per-frame normalized value (matching the Kotlin
    comment at `Layer5Behavioral.kt:49-50`).
    """
    _ = fps
    if landmarks_history is None or len(landmarks_history) < 2:
        return False

    velocities = []
    for i in range(1, len(landmarks_history)):
        prev_frame = landmarks_history[i - 1]
        cur_frame = landmarks_history[i]
        cur_iris = np.mean(
            [_landmark_xy(cur_frame[l]) for l in IRIS_LEFT_INDICES], axis=0
        )
        prev_iris = np.mean(
            [_landmark_xy(prev_frame[l]) for l in IRIS_LEFT_INDICES], axis=0
        )
        cur_nose = np.mean(
            [_landmark_xy(cur_frame[l]) for l in NOSE_BRIDGE_INDICES], axis=0
        )
        prev_nose = np.mean(
            [_landmark_xy(prev_frame[l]) for l in NOSE_BRIDGE_INDICES], axis=0
        )
        iris_delta = cur_iris - prev_iris
        nose_delta = cur_nose - prev_nose
        velocities.append(float(np.linalg.norm(iris_delta - nose_delta)))

    count = sum(
        1
        for i in range(len(velocities) - 1)
        if velocities[i] > MICROSACCADE_VELOCITY_THRESHOLD
        and velocities[i + 1] > MICROSACCADE_VELOCITY_THRESHOLD
    )
    return count >= MICROSACCADE_MIN_PAIR_COUNT
