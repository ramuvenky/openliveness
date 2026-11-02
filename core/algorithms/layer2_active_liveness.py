"""Layer 2 — Active liveness (challenge-response using MediaPipe Face Mesh).

Reference implementation. Mirrors `Layer2ActiveLiveness.kt` detector-
for-detector; the server re-runs this when verifying attestations, so
every threshold and index MUST match the Kotlin and Swift mirrors. See
Spec §1356-1403 and the Kotlin file for anti-abuse rationale behind the
specific thresholds (H-3 face-width normalization, H-4 median baseline).
"""

from __future__ import annotations

import math
from collections import deque
from enum import Enum
from typing import Sequence

EXPECTED_LANDMARK_COUNT = 478

# BLINK (Kotlin BlinkDetector).
BLINK_CLOSED_THRESHOLD = 0.2
BLINK_OPEN_THRESHOLD = 0.25
BLINK_MIN_CLOSED_FRAMES = 2
BLINK_EYE_WIDTH_INDICES = (33, 133)
BLINK_EYE_HEIGHT_INDICES = (159, 145)

# GAZE (Kotlin GazeDetector).
GAZE_OFFSET_THRESHOLD = 0.15
GAZE_MIN_CONSECUTIVE_FRAMES = 3
GAZE_NOSE_INDEX = 1
GAZE_FACE_LEFT_INDEX = 234
GAZE_FACE_RIGHT_INDEX = 454

# SMILE (Kotlin SmileDetector).
SMILE_WIDTH_MULTIPLIER = 1.2
SMILE_MIN_CONSECUTIVE_FRAMES = 5
SMILE_CALIBRATION_FRAMES = 10
SMILE_MOUTH_INDICES = (61, 291)

# NOD (Kotlin NodDetector).
NOD_PITCH_DELTA_THRESHOLD = 0.15
NOD_WINDOW_SECONDS = 1.0
NOD_MAX_SAMPLES = 240
NOD_CHIN_INDEX = 152
NOD_FOREHEAD_INDEX = 10


class Challenge(str, Enum):
    BLINK = "BLINK"
    LOOK_LEFT = "LOOK_LEFT"
    LOOK_RIGHT = "LOOK_RIGHT"
    SMILE = "SMILE"
    NOD = "NOD"


def _xy(landmark) -> tuple[float, float]:
    if hasattr(landmark, "x") and hasattr(landmark, "y"):
        return float(landmark.x), float(landmark.y)
    return float(landmark[0]), float(landmark[1])


def _distance_2d(a, b) -> float:
    ax, ay = _xy(a)
    bx, by = _xy(b)
    return math.sqrt((ax - bx) ** 2 + (ay - by) ** 2)


class BlinkDetector:
    """EAR = eye_height / eye_width. Fires on closed→reopened transition."""

    def __init__(self) -> None:
        self._closed_frames = 0
        self._waiting_for_reopen = False

    def update(self, landmarks) -> bool:
        eye_width = _distance_2d(
            landmarks[BLINK_EYE_WIDTH_INDICES[0]],
            landmarks[BLINK_EYE_WIDTH_INDICES[1]],
        )
        if eye_width <= 0:
            # Face-tracking loss: clear the latch so a tracking gap can't
            # fire a fake blink when tracking resumes.
            self._closed_frames = 0
            self._waiting_for_reopen = False
            return False

        ear = _distance_2d(
            landmarks[BLINK_EYE_HEIGHT_INDICES[0]],
            landmarks[BLINK_EYE_HEIGHT_INDICES[1]],
        ) / eye_width

        if self._waiting_for_reopen:
            if ear > BLINK_OPEN_THRESHOLD:
                self._waiting_for_reopen = False
                self._closed_frames = 0
                return True
            return False

        if ear < BLINK_CLOSED_THRESHOLD:
            self._closed_frames += 1
            if self._closed_frames >= BLINK_MIN_CLOSED_FRAMES:
                self._waiting_for_reopen = True
        else:
            self._closed_frames = 0
        return False

    def reset(self) -> None:
        self._closed_frames = 0
        self._waiting_for_reopen = False


class GazeDirection(str, Enum):
    LEFT = "LEFT"
    RIGHT = "RIGHT"


class GazeDetector:
    """Offset of nose tip from face center, normalized by face width."""

    def __init__(self, direction: GazeDirection) -> None:
        self.direction = direction
        self._consecutive_frames = 0

    def update(self, landmarks) -> bool:
        lx, _ = _xy(landmarks[GAZE_FACE_LEFT_INDEX])
        rx, _ = _xy(landmarks[GAZE_FACE_RIGHT_INDEX])
        face_center_x = (lx + rx) / 2.0
        face_width = abs(rx - lx)
        if face_width <= 0:
            return False
        nose_x, _ = _xy(landmarks[GAZE_NOSE_INDEX])
        offset = (nose_x - face_center_x) / face_width

        if self.direction == GazeDirection.LEFT:
            meets = offset < -GAZE_OFFSET_THRESHOLD
        else:
            meets = offset > GAZE_OFFSET_THRESHOLD

        if meets:
            self._consecutive_frames += 1
            if self._consecutive_frames >= GAZE_MIN_CONSECUTIVE_FRAMES:
                self._consecutive_frames = 0
                return True
        else:
            self._consecutive_frames = 0
        return False

    def reset(self) -> None:
        self._consecutive_frames = 0


class SmileDetector:
    """mouth_width > neutral * 1.2 for 5+ frames.

    Neutral baseline is the MEDIAN mouth width across the first 10
    frames (H-4 fix): an eager user who smiles during warmup can't drag
    the baseline up on an attacker's behalf.
    """

    def __init__(self) -> None:
        self._neutral_samples: list[float] = []
        self._consecutive_frames = 0

    @property
    def neutral_mouth_width(self) -> float | None:
        if len(self._neutral_samples) < SMILE_CALIBRATION_FRAMES:
            return None
        s = sorted(self._neutral_samples)
        n = len(s)
        if n % 2 == 1:
            return s[n // 2]
        return (s[n // 2 - 1] + s[n // 2]) / 2.0

    def update(self, landmarks) -> bool:
        mouth_width = _distance_2d(
            landmarks[SMILE_MOUTH_INDICES[0]],
            landmarks[SMILE_MOUTH_INDICES[1]],
        )
        if len(self._neutral_samples) < SMILE_CALIBRATION_FRAMES:
            if mouth_width <= 0:
                return False
            self._neutral_samples.append(mouth_width)
            return False

        neutral = self.neutral_mouth_width
        if neutral is None or neutral <= 0:
            return False
        if mouth_width > neutral * SMILE_WIDTH_MULTIPLIER:
            self._consecutive_frames += 1
            if self._consecutive_frames >= SMILE_MIN_CONSECUTIVE_FRAMES:
                self._consecutive_frames = 0
                return True
        else:
            self._consecutive_frames = 0
        return False

    def reset(self) -> None:
        self._neutral_samples.clear()
        self._consecutive_frames = 0


class NodDetector:
    """pitch_proxy = (chin.y - forehead.y) / face_width. Fires when
    (max - min) within a 1s window exceeds 0.15 face-widths."""

    def __init__(self) -> None:
        self._buffer: deque[tuple[float, float]] = deque()

    def update(self, landmarks, timestamp: float) -> bool:
        lx, _ = _xy(landmarks[GAZE_FACE_LEFT_INDEX])
        rx, _ = _xy(landmarks[GAZE_FACE_RIGHT_INDEX])
        face_width = abs(rx - lx)
        if face_width <= 0:
            return False
        _, chin_y = _xy(landmarks[NOD_CHIN_INDEX])
        _, fore_y = _xy(landmarks[NOD_FOREHEAD_INDEX])
        pitch = (chin_y - fore_y) / face_width

        self._buffer.append((timestamp, pitch))
        cutoff = timestamp - NOD_WINDOW_SECONDS
        # Drop stale samples (filter instead of a simple popleft because
        # pathological backward-walking timestamps may require pruning
        # from either end).
        while self._buffer and self._buffer[0][0] < cutoff:
            self._buffer.popleft()
        # Belt-and-suspenders cap: same intent as Kotlin MAX_SAMPLES
        # — a timestamp storm must not grow the buffer without bound.
        while len(self._buffer) > NOD_MAX_SAMPLES:
            self._buffer.popleft()

        if not self._buffer:
            return False
        pitches = [p for _, p in self._buffer]
        if max(pitches) - min(pitches) > NOD_PITCH_DELTA_THRESHOLD:
            self._buffer.clear()
            return True
        return False

    def reset(self) -> None:
        self._buffer.clear()


def _detector_for(challenge: Challenge):
    if challenge == Challenge.BLINK:
        return BlinkDetector()
    if challenge == Challenge.LOOK_LEFT:
        return GazeDetector(GazeDirection.LEFT)
    if challenge == Challenge.LOOK_RIGHT:
        return GazeDetector(GazeDirection.RIGHT)
    if challenge == Challenge.SMILE:
        return SmileDetector()
    if challenge == Challenge.NOD:
        return NodDetector()
    raise ValueError(f"Unknown challenge: {challenge!r}")


def detect_challenge(
    landmarks_history: Sequence,
    challenge: Challenge | str,
    timestamps: Sequence[float] | None = None,
) -> bool:
    """Return True iff the detector for `challenge` fires anywhere in
    `landmarks_history`. The sequence is one landmark set per frame; for
    NOD, `timestamps` is required (same shape as `landmarks_history`).

    This mirrors `ChallengeSequencer`'s run-until-fire semantics: the
    detector is fed frame-by-frame and the first True short-circuits.
    """
    challenge_enum = Challenge(challenge) if isinstance(challenge, str) else challenge
    detector = _detector_for(challenge_enum)

    if challenge_enum == Challenge.NOD:
        if timestamps is None or len(timestamps) != len(landmarks_history):
            raise ValueError("NOD challenge requires timestamps aligned with landmarks_history")
        for frame, ts in zip(landmarks_history, timestamps):
            if detector.update(frame, ts):
                return True
        return False

    for frame in landmarks_history:
        if detector.update(frame):
            return True
    return False
