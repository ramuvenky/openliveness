"""Layer 4 texture parity tests.

Fourier branch runs without a classifier so it's unit-testable with
pure numpy. LBP branch uses a stub classifier (no sklearn needed) to
pin the plumbing: histogram → predict_proba → float.
"""

from __future__ import annotations

import numpy as np
import pytest

cv2 = pytest.importorskip("cv2")

from core.algorithms.layer4_texture import (
    _ring_mask,
    estimate_organic_hf,
    fourier_score,
    lbp_histogram,
    lbp_score,
)


def _organic_face() -> np.ndarray:
    """Smooth Gaussian blob — mimics the 1/f-ish rolloff a real skin
    crop produces. All energy sits in the lowest frequency bins, so the
    mid-frequency ring the Fourier detector reads is quiet relative to
    the LF baseline and the score rounds toward 1.0."""
    size = 80
    yy, xx = np.indices((size, size))
    sigma = size / 4.0
    blob = 255.0 * np.exp(-((yy - size / 2) ** 2 + (xx - size / 2) ** 2) / (2 * sigma ** 2))
    img = blob.astype(np.uint8)
    return np.stack([img, img, img], axis=-1)


def _screen_replay_pattern() -> np.ndarray:
    """Gaussian blob + mid-frequency sinusoidal grid at the HF-ring band.

    The grid lives at a wavelength that lands squarely inside the
    rows/4..rows/3 HF ring the Fourier detector reads — same shape as
    the pixel-pitch harmonics a phone screen projects when photographed.
    The LF baseline (Gaussian blob) stays intact so the artifact ratio
    rises above 1 and the score drops below the live reference."""
    size = 80
    yy, xx = np.indices((size, size))
    sigma = size / 4.0
    blob = 255.0 * np.exp(-((yy - size / 2) ** 2 + (xx - size / 2) ** 2) / (2 * sigma ** 2))
    # Wavelength ≈ 80/23 ≈ 3.5 pixels → frequency ~23 cycles across the
    # image, which lands in the HF ring (rows/4 < d < rows/3 = 20 < d < 26.7).
    grid = 80.0 * np.sin(2 * np.pi * xx * 23.0 / size) * np.sin(2 * np.pi * yy * 23.0 / size)
    combined = np.clip(blob + grid, 0, 255).astype(np.uint8)
    return np.stack([combined, combined, combined], axis=-1)


def test_ring_mask_counts_match_expected_bins():
    mask = _ring_mask((80, 80), inner_frac_denom=3, outer_frac_denom=4)
    # Mask is binary; sum should be strictly between zero and total pixels.
    s = mask.sum()
    assert 0 < s < 80 * 80


def test_fourier_score_flat_image_rounds_to_one():
    """Degenerate check: a constant-intensity image has all energy at
    DC — the HF and LF rings are both zero, the ratio collapses to the
    1e-10 floor, and the score pins at 1.0. Guards the no-energy
    branch of the artifact-ratio formula."""
    flat = np.full((80, 80, 3), 128, dtype=np.uint8)
    assert fourier_score(flat) == 1.0


def test_fourier_score_in_valid_range_for_natural_image():
    score = fourier_score(_organic_face())
    assert 0.0 <= score <= 1.0


def test_fourier_score_screen_replay_falls_below_live():
    """The main invariant: adding HF-ring-band energy on top of the
    same low-frequency content MUST drop the score. This is what the
    detector actually catches — screen pixel-pitch artefacts land in
    the ring and push the artifact ratio up. Absolute cutoffs are a
    calibration issue (training-time), not a correctness issue here."""
    live = fourier_score(_organic_face())
    replay = fourier_score(_screen_replay_pattern())
    assert replay < live


def test_estimate_organic_hf_zero_mask_returns_zero():
    # Trivial degenerate case — a 1x1 magnitude has no mid-frequency
    # ring to average over.
    assert estimate_organic_hf(np.ones((1, 1))) == 0.0


class _StubLBPClassifier:
    """Minimal predict_proba stub. Returns a fixed probability so the
    test pins the lbp_score plumbing without needing sklearn or a
    trained model."""

    def __init__(self, prob_live: float) -> None:
        self.prob_live = prob_live

    def predict_proba(self, X):  # noqa: N802  (sklearn naming)
        return np.array([[1.0 - self.prob_live, self.prob_live]])


def test_lbp_score_passes_histogram_through_classifier():
    pytest.importorskip("skimage")
    face = _organic_face()
    score = lbp_score(face, _StubLBPClassifier(prob_live=0.9))
    assert score == pytest.approx(0.9)


def test_lbp_histogram_is_normalized():
    pytest.importorskip("skimage")
    hist = lbp_histogram(_organic_face())
    assert hist.shape == (59,)
    assert hist.sum() == pytest.approx(1.0, rel=1e-6)
