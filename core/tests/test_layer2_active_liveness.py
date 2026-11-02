"""Layer 2 active liveness parity tests.

Builds synthetic landmark sequences and runs them through the
per-challenge detectors. Thresholds and indices must match the Kotlin
and Swift mirrors — a drift here means a user who passes on Android
could fail on the server re-run.
"""

from __future__ import annotations

import pytest

from core.algorithms.layer2_active_liveness import (
    BlinkDetector,
    Challenge,
    EXPECTED_LANDMARK_COUNT,
    GazeDetector,
    GazeDirection,
    NodDetector,
    SMILE_CALIBRATION_FRAMES,
    SmileDetector,
    detect_challenge,
)


def _frame(positions):
    frame = [(0.0, 0.0)] * EXPECTED_LANDMARK_COUNT
    for idx, xy in positions.items():
        frame[idx] = xy
    return frame


# ---- BLINK ---------------------------------------------------------------

def _eye_frame(ear: float):
    """Build a frame whose EAR = eye_height / eye_width matches `ear`.
    Eye width is landmarks 33↔133; eye height is 159↔145."""
    eye_width = 0.08  # realistic normalized eye width
    eye_height = ear * eye_width
    return _frame({
        33: (0.4, 0.3),
        133: (0.4 + eye_width, 0.3),
        159: (0.4 + eye_width / 2, 0.3),
        145: (0.4 + eye_width / 2, 0.3 + eye_height),
    })


def test_blink_fires_on_close_then_reopen():
    detector = BlinkDetector()
    # Open eye (above both thresholds).
    assert detector.update(_eye_frame(0.3)) is False
    # Two consecutive closed frames.
    assert detector.update(_eye_frame(0.15)) is False
    assert detector.update(_eye_frame(0.15)) is False
    # Reopen above OPEN_THRESHOLD → fire.
    assert detector.update(_eye_frame(0.3)) is True


def test_blink_single_closed_frame_does_not_fire():
    detector = BlinkDetector()
    detector.update(_eye_frame(0.15))  # one closed frame
    assert detector.update(_eye_frame(0.3)) is False


def test_blink_face_tracking_loss_clears_latch():
    """Zero-width eye = face tracking loss. The latch must clear so a
    recovered frame can't fire a fake blink."""
    detector = BlinkDetector()
    detector.update(_eye_frame(0.15))
    detector.update(_eye_frame(0.15))  # waiting_for_reopen=True
    # Face tracking loss.
    bad = _frame({33: (0.4, 0.3), 133: (0.4, 0.3)})  # zero eye width
    detector.update(bad)
    # Now an open frame must NOT fire — the state was cleared.
    assert detector.update(_eye_frame(0.3)) is False


# ---- GAZE ----------------------------------------------------------------

def _gaze_frame(offset_fraction: float):
    """Build a frame where (nose.x - center) / face_width = offset_fraction."""
    face_left_x = 0.3
    face_right_x = 0.7
    face_width = face_right_x - face_left_x
    center_x = (face_left_x + face_right_x) / 2
    nose_x = center_x + offset_fraction * face_width
    return _frame({
        1: (nose_x, 0.5),
        234: (face_left_x, 0.5),
        454: (face_right_x, 0.5),
    })


def test_gaze_left_fires_after_three_frames():
    detector = GazeDetector(GazeDirection.LEFT)
    frames = [_gaze_frame(-0.2) for _ in range(3)]
    results = [detector.update(f) for f in frames]
    assert results == [False, False, True]


def test_gaze_right_requires_consecutive_frames():
    detector = GazeDetector(GazeDirection.RIGHT)
    # Interleave past-threshold and neutral frames — the counter resets
    # on every miss, so no three-in-a-row means no fire.
    frames = [
        _gaze_frame(0.2),
        _gaze_frame(0.0),
        _gaze_frame(0.2),
        _gaze_frame(0.0),
    ]
    assert all(detector.update(f) is False for f in frames)


# ---- SMILE ---------------------------------------------------------------

def _smile_frame(mouth_width: float):
    """Mouth corners landmarks 61↔291."""
    return _frame({
        61: (0.5 - mouth_width / 2, 0.6),
        291: (0.5 + mouth_width / 2, 0.6),
    })


def test_smile_calibrates_then_fires_above_multiplier():
    detector = SmileDetector()
    # 10 neutral calibration frames.
    for _ in range(SMILE_CALIBRATION_FRAMES):
        assert detector.update(_smile_frame(0.08)) is False
    # Now five frames at > 1.2x neutral.
    wide = 0.08 * 1.5
    results = [detector.update(_smile_frame(wide)) for _ in range(5)]
    assert results == [False, False, False, False, True]


def test_smile_median_baseline_resists_eager_calibration():
    """H-4 fix: median baseline. A user who smiles for 4 of 10 calibration
    frames must not drag the baseline up — the median picks the relaxed
    middle value."""
    detector = SmileDetector()
    # 6 relaxed + 4 smiley during calibration.
    for _ in range(6):
        detector.update(_smile_frame(0.08))
    for _ in range(4):
        detector.update(_smile_frame(0.14))  # smile during calibration
    # Baseline should be the median of these 10 → one of the relaxed
    # 0.08 values (median of sorted = 0.08).
    assert detector.neutral_mouth_width == pytest.approx(0.08)


# ---- NOD -----------------------------------------------------------------

def _nod_frame(chin_y: float, forehead_y: float = 0.2):
    return _frame({
        152: (0.5, chin_y),
        10: (0.5, forehead_y),
        234: (0.3, 0.5),
        454: (0.7, 0.5),
    })


def test_nod_fires_within_one_second_window():
    detector = NodDetector()
    # face_width = 0.4; pitch_delta threshold = 0.15 face-widths = 0.06
    # in raw y. Chin oscillates by 0.08 which crosses the threshold.
    assert detector.update(_nod_frame(0.7), timestamp=0.0) is False
    assert detector.update(_nod_frame(0.8), timestamp=0.5) is True


def test_nod_stale_samples_outside_window_dropped():
    detector = NodDetector()
    # Big delta at t=0 but the next sample is 2s later — the window is
    # 1s so the stale sample is dropped before compare → no fire.
    assert detector.update(_nod_frame(0.7), timestamp=0.0) is False
    assert detector.update(_nod_frame(0.8), timestamp=2.0) is False


# ---- top-level detect_challenge -----------------------------------------

def test_detect_challenge_runs_blink_end_to_end():
    frames = [
        _eye_frame(0.3),  # open
        _eye_frame(0.15),  # closing
        _eye_frame(0.15),  # closed
        _eye_frame(0.3),   # reopened → fire
    ]
    assert detect_challenge(frames, Challenge.BLINK) is True


def test_detect_challenge_runs_nod_requires_timestamps():
    with pytest.raises(ValueError):
        detect_challenge([_nod_frame(0.7)], Challenge.NOD)
