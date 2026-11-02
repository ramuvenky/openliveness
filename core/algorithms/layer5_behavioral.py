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


def _landmark_xy(landmark) -> tuple[float, float]:
    """Access (x, y) from either a MediaPipe landmark proto (`.x`/`.y`
    attributes) or a plain `(x, y)`/`[x, y]` pair. Tests pass tuples;
    production passes the proto."""
    if hasattr(landmark, "x") and hasattr(landmark, "y"):
        return float(landmark.x), float(landmark.y)
    return float(landmark[0]), float(landmark[1])


def _score_for_mean_displacement(mean_disp: float) -> float:
    if mean_disp < DISPLACEMENT_SUSPICIOUS_UPPER:
        return SCORE_SUSPICIOUS
    if mean_disp < DISPLACEMENT_BORDERLINE_UPPER:
        return SCORE_BORDERLINE
    if mean_disp <= DISPLACEMENT_NATURAL_UPPER:
        return SCORE_NATURAL
    return SCORE_EXCESSIVE


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
    return _score_for_mean_displacement(mean_disp)


def detect_microsaccades(landmarks_history: Sequence, fps: int = 30) -> bool:
    """Spec §1494-1515. Return True once ≥ 2 adjacent-frame pairs both
    exceed `MICROSACCADE_VELOCITY_THRESHOLD` within the window.

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
        cur_xy = np.mean(
            [_landmark_xy(cur_frame[l]) for l in IRIS_LEFT_INDICES], axis=0
        )
        prev_xy = np.mean(
            [_landmark_xy(prev_frame[l]) for l in IRIS_LEFT_INDICES], axis=0
        )
        velocities.append(float(np.linalg.norm(cur_xy - prev_xy)))

    count = sum(
        1
        for i in range(len(velocities) - 1)
        if velocities[i] > MICROSACCADE_VELOCITY_THRESHOLD
        and velocities[i + 1] > MICROSACCADE_VELOCITY_THRESHOLD
    )
    return count >= MICROSACCADE_MIN_PAIR_COUNT
