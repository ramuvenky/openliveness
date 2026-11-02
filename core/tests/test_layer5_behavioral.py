"""Layer 5 behavioral parity tests."""

from __future__ import annotations

import pytest

from core.algorithms.layer5_behavioral import (
    DISPLACEMENT_BORDERLINE_UPPER,
    DISPLACEMENT_NATURAL_UPPER,
    DISPLACEMENT_SUSPICIOUS_UPPER,
    MICRO_MOVEMENT_WINDOW_FRAMES,
    MICROSACCADE_VELOCITY_THRESHOLD,
    SCORE_BORDERLINE,
    SCORE_EXCESSIVE,
    SCORE_NATURAL,
    SCORE_SUSPICIOUS,
    detect_microsaccades,
    micro_movement_score,
)

# Enough indices for both nose-bridge (max 10) and left iris (max 472).
_MIN_LANDMARK_COUNT = 478


def _frame(positions: dict[int, tuple[float, float]]):
    """Build a frame as a list of (x, y) tuples. Only populate indices
    the detectors read; defaults elsewhere to (0.0, 0.0) are fine
    because no production detector touches them."""
    frame = [(0.0, 0.0)] * _MIN_LANDMARK_COUNT
    for idx, xy in positions.items():
        frame[idx] = xy
    return frame


def _still_frames(n: int):
    """n copies of the same frame (perfect stillness → suspicious)."""
    pos = {6: (0.5, 0.4), 8: (0.5, 0.42), 9: (0.5, 0.44), 10: (0.5, 0.46)}
    pos.update({l: (0.3, 0.5) for l in range(468, 473)})
    return [_frame(pos) for _ in range(n)]


def _natural_jitter_frames(n: int, step: float = 0.005):
    """n frames with per-frame nose-bridge drift that lands in the
    'natural' bucket (0.001 < mean_disp <= 0.015)."""
    frames = []
    for i in range(n):
        dx = step * ((i % 2) * 2 - 1)  # alternating ±step
        pos = {
            6: (0.5 + dx, 0.4),
            8: (0.5 + dx, 0.42),
            9: (0.5 + dx, 0.44),
            10: (0.5 + dx, 0.46),
        }
        pos.update({l: (0.3, 0.5) for l in range(468, 473)})
        frames.append(_frame(pos))
    return frames


# ---- micro-movement scoring ---------------------------------------------

def test_single_frame_returns_zero():
    assert micro_movement_score(_still_frames(1)) == 0.0


def test_still_face_scores_suspicious():
    score = micro_movement_score(_still_frames(MICRO_MOVEMENT_WINDOW_FRAMES))
    assert score == SCORE_SUSPICIOUS


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
    # Pick a step whose displacement lands in [SUSPICIOUS_UPPER, BORDERLINE_UPPER).
    # Adjacent-frame displacement = sqrt((2*step)**2) = 2*step.
    target_disp = (DISPLACEMENT_SUSPICIOUS_UPPER + DISPLACEMENT_BORDERLINE_UPPER) / 2
    step = target_disp / 2
    frames = _natural_jitter_frames(MICRO_MOVEMENT_WINDOW_FRAMES, step=step)
    score = micro_movement_score(frames)
    assert score == SCORE_BORDERLINE


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
        pos = {6: (0.5, 0.4), 8: (0.5, 0.42), 9: (0.5, 0.44), 10: (0.5, 0.46)}
        pos.update({l: (x, 0.5) for l in range(468, 473)})
        frames.append(_frame(pos))
    assert detect_microsaccades(frames) is True


def test_isolated_spike_does_not_fire():
    """A single high-velocity frame = 1 qualifying pair at most, below
    the min-pair-count of 2."""
    small_step = MICROSACCADE_VELOCITY_THRESHOLD / 10
    big_jump = MICROSACCADE_VELOCITY_THRESHOLD * 5
    # positions produce velocities: [small, big, small, small] — zero
    # qualifying pairs (both-ends-over-threshold).
    xs = [0.3, 0.3 + small_step, 0.3 + small_step + big_jump,
          0.3 + small_step + big_jump + small_step, 0.3 + small_step + big_jump + 2 * small_step]
    frames = []
    for x in xs:
        pos = {6: (0.5, 0.4), 8: (0.5, 0.42), 9: (0.5, 0.44), 10: (0.5, 0.46)}
        pos.update({l: (x, 0.5) for l in range(468, 473)})
        frames.append(_frame(pos))
    assert detect_microsaccades(frames) is False
