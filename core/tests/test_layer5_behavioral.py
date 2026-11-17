"""Layer 5 behavioral parity tests."""

from __future__ import annotations

import numpy as np
import pytest

from core.algorithms.layer5_behavioral import (
    DISPLACEMENT_BORDERLINE_UPPER,
    DISPLACEMENT_NATURAL_UPPER,
    DISPLACEMENT_SUSPICIOUS_UPPER,
    LIVE_VARIANCE_UPPER,
    MICRO_MOVEMENT_WINDOW_FRAMES,
    MICROSACCADE_VELOCITY_THRESHOLD,
    NOSE_BRIDGE_INDICES,
    SCORE_BORDERLINE,
    SCORE_EXCESSIVE,
    SCORE_NATURAL,
    SCORE_SUSPICIOUS,
    VARIANCE_GATE_DISPLACEMENT_FLOOR,
    VARIANCE_SAMPLE_INDICES,
    _score_for_mean_displacement,
    detect_microsaccades,
    micro_movement_score,
)

# Enough indices for both nose-bridge (max 10) and left iris (max 472).
_MIN_LANDMARK_COUNT = 478

# Canonical neutral positions for every landmark the variance gate reads.
# Mirrors `_CANONICAL_LANDMARKS` in `generate_golden_fixtures.py` and the
# `canonical` map in `Layer5BehavioralTest.kt`. Keeping these three in
# lockstep is what makes the rigid-body vs articulated discrimination
# testable without constructing full 478-point frames by hand.
_CANONICAL_LANDMARKS: dict[int, tuple[float, float]] = {
    1:   (0.50, 0.45),
    6:   (0.50, 0.40),
    8:   (0.50, 0.42),
    9:   (0.50, 0.44),
    10:  (0.50, 0.30),
    13:  (0.50, 0.60),
    14:  (0.50, 0.62),
    33:  (0.35, 0.50),
    133: (0.45, 0.50),
    263: (0.65, 0.50),
    362: (0.55, 0.50),
    61:  (0.40, 0.65),
    291: (0.60, 0.65),
    152: (0.50, 0.80),
    234: (0.25, 0.50),
    454: (0.75, 0.50),
    468: (0.40, 0.50),
    469: (0.41, 0.50),
    470: (0.40, 0.51),
    471: (0.39, 0.50),
    472: (0.40, 0.49),
    473: (0.60, 0.50),
}


def _frame(positions: dict[int, tuple[float, float]]):
    """Build a frame as a list of (x, y) tuples with the canonical face
    layout as background, then overlay per-index overrides. The canonical
    fill means every VARIANCE_SAMPLE_INDICES landmark has a plausible
    position, so synthetic frames exercise the rigid-body gate the same
    way production landmark streams do."""
    frame = [(0.0, 0.0)] * _MIN_LANDMARK_COUNT
    for idx, xy in _CANONICAL_LANDMARKS.items():
        frame[idx] = xy
    for idx, xy in positions.items():
        frame[idx] = xy
    return frame


def _articulation_for(amplitude: float, seed: int, frame_index: int):
    """Deterministic per-landmark jitter for non-nose-bridge indices.
    Zeros out nose-bridge (6, 8, 9, 10) so tests can set displacement
    independently via the nose-bridge overrides."""
    if amplitude <= 0:
        return {}
    rng = np.random.default_rng(seed * 10_000 + frame_index)
    out: dict[int, tuple[float, float]] = {}
    for lid in VARIANCE_SAMPLE_INDICES:
        if lid in (6, 8, 9, 10):
            continue
        cx, cy = _CANONICAL_LANDMARKS[lid]
        jx = float(rng.standard_normal()) * amplitude
        jy = float(rng.standard_normal()) * amplitude
        out[lid] = (cx + jx, cy + jy)
    return out


def _still_frames(n: int):
    """n copies of the same canonical frame (perfect stillness → routed
    to NATURAL by the displacement-guarded ladder's branch 2)."""
    return [_frame({}) for _ in range(n)]


def _translated_frames(
    n: int, step: float, articulation: float, seed: int
):
    """n frames where every sampled landmark shifts by (±step, 0) each
    frame (rigid head translation component giving the nose-bridge its
    displacement signal), plus per-landmark Gaussian jitter of amplitude
    `articulation` on non-nose-bridge landmarks. Mirrors the fixture
    generator's `_landmark_sequence` physics.

    - Mild articulation (≈0.003): variance stays below
      LIVE_VARIANCE_UPPER=3e-5 (live-face signature).
    - Large articulation (≈0.01): variance balloons well above the
      threshold (waved-photo tracker-thrash signature).
    """
    frames = []
    for i in range(n):
        dx = step * ((i % 2) * 2 - 1)
        rng = np.random.default_rng(seed * 10_000 + i) if articulation > 0 else None
        pos: dict[int, tuple[float, float]] = {}
        for lid, (cx, cy) in _CANONICAL_LANDMARKS.items():
            if lid in (6, 8, 9, 10) or rng is None:
                pos[lid] = (cx + dx, cy)
            else:
                jx = float(rng.standard_normal()) * articulation
                jy = float(rng.standard_normal()) * articulation
                pos[lid] = (cx + dx + jx, cy + jy)
        frames.append(_frame(pos))
    return frames


def _natural_jitter_frames(
    n: int, step: float = 0.005, articulation: float = 0.003, seed: int = 1
):
    """Live-face signature: head translates, non-nose landmarks jitter
    mildly. Variance stays below LIVE_VARIANCE_UPPER=3e-5."""
    return _translated_frames(n, step, articulation, seed)


def _waved_photo_frames(n: int, step: float, articulation: float = 0.01, seed: int = 3):
    """Photo-attack signature: head translates AND non-nose landmarks
    jitter chaotically (MediaPipe thrashing on a 2D photo). Variance
    balloons above LIVE_VARIANCE_UPPER=3e-5."""
    return _translated_frames(n, step, articulation, seed)


# ---- score ladder (direct) ----------------------------------------------

def test_ladder_still_bypasses_variance_gate():
    # Below SUSPICIOUS_UPPER: branch 2 → NATURAL regardless of variance.
    assert _score_for_mean_displacement(0.0001, 1e-9) == SCORE_NATURAL


def test_ladder_borderline_displacement_scores_borderline_ignoring_variance():
    # Displacement in [SUSPICIOUS_UPPER, BORDERLINE_UPPER): branch 3 fires
    # and the variance gate is NOT consulted — the branch result is the
    # same regardless of what variance happens to be.
    mid = (DISPLACEMENT_SUSPICIOUS_UPPER + DISPLACEMENT_BORDERLINE_UPPER) / 2
    assert _score_for_mean_displacement(mid, 1e-10) == SCORE_BORDERLINE
    assert _score_for_mean_displacement(mid, LIVE_VARIANCE_UPPER * 10) == SCORE_BORDERLINE


def test_ladder_below_variance_gate_floor_scores_natural():
    # Displacement in [BORDERLINE_UPPER, VARIANCE_GATE_DISPLACEMENT_FLOOR):
    # subject moving a bit but not clearly enough to trust the gate. Must
    # route to NATURAL regardless of variance — otherwise mild-motion live
    # users get false-rejected.
    disp = (DISPLACEMENT_BORDERLINE_UPPER + VARIANCE_GATE_DISPLACEMENT_FLOOR) / 2
    assert _score_for_mean_displacement(disp, 1e-10) == SCORE_NATURAL
    assert _score_for_mean_displacement(disp, 1.0) == SCORE_NATURAL


def test_ladder_above_gate_floor_high_variance_scores_suspicious():
    # Waved-photo signature: motion above floor AND variance above
    # LIVE_VARIANCE_UPPER (MediaPipe tracker thrashing on a 2D photo).
    # Nov 2026 N=14 photo minimum: 5.57e-5.
    disp = (VARIANCE_GATE_DISPLACEMENT_FLOOR + DISPLACEMENT_NATURAL_UPPER) / 2
    assert _score_for_mean_displacement(disp, 5.57e-5) == SCORE_SUSPICIOUS
    assert _score_for_mean_displacement(disp, 2.81e-4) == SCORE_SUSPICIOUS


def test_ladder_above_gate_floor_low_variance_scores_natural():
    # Live-face signature: motion above floor AND variance at or below
    # LIVE_VARIANCE_UPPER (rigid skull constrains per-landmark deltas
    # close to their mean). Nov 2026 N=14 live maximum: 2.38e-5.
    disp = (VARIANCE_GATE_DISPLACEMENT_FLOOR + DISPLACEMENT_NATURAL_UPPER) / 2
    assert _score_for_mean_displacement(disp, 2.38e-5) == SCORE_NATURAL
    assert _score_for_mean_displacement(disp, 1e-10) == SCORE_NATURAL


def test_ladder_excessive_overrides_variance():
    assert (
        _score_for_mean_displacement(DISPLACEMENT_NATURAL_UPPER * 2, 0.0)
        == SCORE_EXCESSIVE
    )


# ---- micro-movement scoring ---------------------------------------------

def test_single_frame_returns_zero():
    assert micro_movement_score(_still_frames(1)) == 0.0


def test_still_face_scores_natural():
    # Nov 2026 displacement-guarded ladder: a still subject routes to
    # branch 2 (NATURAL) before the variance gate is consulted. L2
    # challenges are what catch still photos — Layer 5 is explicitly
    # NOT asked to split live-still from photo-still. Was SUSPICIOUS
    # under the old unconditional-ladder rule.
    score = micro_movement_score(_still_frames(MICRO_MOVEMENT_WINDOW_FRAMES))
    assert score == SCORE_NATURAL


def test_waved_photo_above_gate_floor_scores_suspicious():
    # Photo-attack regression: a 2D printed photo waved in front of the
    # camera produces motion ABOVE VARIANCE_GATE_DISPLACEMENT_FLOOR with
    # chaotic per-landmark deltas (MediaPipe's tracker thrashes trying
    # to fit a 3D mesh to a flat surface). Nov 2026 session-14 field
    # case: meanDisp=9.33e-3, variance=6.19e-5 — leaked through the
    # pre-inversion low-variance gate as L5=1.0.
    disp = (VARIANCE_GATE_DISPLACEMENT_FLOOR + DISPLACEMENT_NATURAL_UPPER) / 2
    frames = _waved_photo_frames(
        MICRO_MOVEMENT_WINDOW_FRAMES, step=disp / 2, articulation=0.01
    )
    assert micro_movement_score(frames) == SCORE_SUSPICIOUS


def test_natural_jitter_scores_natural():
    frames = _natural_jitter_frames(MICRO_MOVEMENT_WINDOW_FRAMES, step=0.005)
    score = micro_movement_score(frames)
    assert score == SCORE_NATURAL


def test_excessive_motion_scores_excessive():
    # Step so large that mean_disp > DISPLACEMENT_NATURAL_UPPER.
    frames = _natural_jitter_frames(MICRO_MOVEMENT_WINDOW_FRAMES, step=0.1)
    score = micro_movement_score(frames)
    assert score == SCORE_EXCESSIVE


def test_borderline_jitter_scores_borderline():
    # Pick a nose-bridge motion whose displacement lands in
    # [SUSPICIOUS_UPPER, BORDERLINE_UPPER). Alternate 0 ↔ step so the
    # per-pair delta equals `step` directly (no doubling). Branch 3
    # fires regardless of variance, so articulation amplitude here
    # doesn't matter.
    step = (DISPLACEMENT_SUSPICIOUS_UPPER + DISPLACEMENT_BORDERLINE_UPPER) / 2
    frames = []
    for i in range(MICRO_MOVEMENT_WINDOW_FRAMES):
        dx = step if i % 2 == 1 else 0.0
        pos = {
            6:  (0.50 + dx, 0.40),
            8:  (0.50 + dx, 0.42),
            9:  (0.50 + dx, 0.44),
            10: (0.50 + dx, 0.30),
        }
        pos.update(_articulation_for(0.003, seed=2, frame_index=i))
        frames.append(_frame(pos))
    assert micro_movement_score(frames) == SCORE_BORDERLINE


# ---- microsaccade detection ---------------------------------------------

def test_still_iris_no_saccades():
    assert detect_microsaccades(_still_frames(10)) is False


def test_two_adjacent_high_velocity_pairs_fire():
    """Need a run of 3 consecutive over-threshold velocity frames to
    yield 2 qualifying pairs. Construct 4 iris positions with 3 big
    jumps between them."""
    big_jump = MICROSACCADE_VELOCITY_THRESHOLD * 5
    xs = [0.3, 0.3 + big_jump, 0.3 + 2 * big_jump, 0.3 + 3 * big_jump]
    frames = []
    for x in xs:
        pos = {l: (x, 0.5) for l in range(468, 473)}
        frames.append(_frame(pos))
    assert detect_microsaccades(frames) is True


def test_rigid_body_translation_no_saccade():
    """Photo-attack regression: when the whole face translates (iris and
    nose-bridge move together with no relative eye motion), the head-
    relative velocity is 0 and the detector must not fire — even when the
    absolute iris velocity is many times the threshold. Pre-head-relative
    detector failed this case and leaked L5 = 1.0 on a printed-photo
    attack held in a moving hand (Nov 2026 incident)."""
    big_jump = MICROSACCADE_VELOCITY_THRESHOLD * 5
    frames = []
    for i in range(4):
        shift_x = big_jump * i
        pos = {
            6:  (0.50 + shift_x, 0.40),
            8:  (0.50 + shift_x, 0.42),
            9:  (0.50 + shift_x, 0.44),
            10: (0.50 + shift_x, 0.30),
        }
        for lid in range(468, 473):
            base_x, base_y = _CANONICAL_LANDMARKS[lid]
            pos[lid] = (base_x + shift_x, base_y)
        frames.append(_frame(pos))
    assert detect_microsaccades(frames) is False


def test_isolated_spike_does_not_fire():
    """A single high-velocity frame = 1 qualifying pair at most, below
    the min-pair-count of 2."""
    small_step = MICROSACCADE_VELOCITY_THRESHOLD / 10
    big_jump = MICROSACCADE_VELOCITY_THRESHOLD * 5
    # positions produce velocities: [small, big, small, small] — zero
    # qualifying pairs (both-ends-over-threshold).
    xs = [0.3, 0.3 + small_step, 0.3 + small_step + big_jump,
          0.3 + small_step + big_jump + small_step,
          0.3 + small_step + big_jump + 2 * small_step]
    frames = []
    for x in xs:
        pos = {l: (x, 0.5) for l in range(468, 473)}
        frames.append(_frame(pos))
    assert detect_microsaccades(frames) is False
