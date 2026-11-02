"""Layer 3 POS rPPG parity tests."""

from __future__ import annotations

import math

import numpy as np
import pytest

from core.algorithms.layer3_rppg_pos import (
    BPM_IN_RANGE_HI,
    BPM_IN_RANGE_LO,
    MIN_SAMPLES_NORMAL,
    SNR_THRESHOLD_DB_LOW_LIGHT,
    SNR_THRESHOLD_DB_NORMAL,
    confidence_for_snr,
    pos_rppg,
)


def _synthetic_pulse_rgb(bpm: float, n: int, fs: float = 30.0, snr: float = 20.0) -> np.ndarray:
    """Build an R/G/B stream where G has a strong pulse-band sinusoid.

    POS mixes R/G/B via `3R - 2G` + `1.5R + G - 1.5B`, so a G-only
    oscillation projects into both s1 and s2 and produces a clean peak
    at the expected bpm — same shape a real cheek crop would show.
    """
    rng = np.random.default_rng(42)
    t = np.arange(n) / fs
    freq_hz = bpm / 60.0
    pulse = np.sin(2 * np.pi * freq_hz * t)
    r = 150.0 + 0.5 * rng.standard_normal(n)
    g = 120.0 + snr * pulse + 0.5 * rng.standard_normal(n)
    b = 100.0 + 0.5 * rng.standard_normal(n)
    return np.stack([r, g, b], axis=1)


def test_pulse_in_valid_window_detected():
    rgb = _synthetic_pulse_rgb(bpm=72.0, n=MIN_SAMPLES_NORMAL)
    result = pos_rppg(rgb, fs=30.0)
    assert result is not None
    assert result["pulse_detected"] is True
    assert result["bpm_in_range"] is True
    assert BPM_IN_RANGE_LO <= result["bpm"] <= BPM_IN_RANGE_HI
    # Confidence must be positive when pulse_detected; the anchor
    # guarantees this per `confidence_for_snr`.
    assert result["confidence"] > 0.0


def test_too_few_samples_returns_none():
    rgb = _synthetic_pulse_rgb(bpm=72.0, n=MIN_SAMPLES_NORMAL - 1)
    assert pos_rppg(rgb, fs=30.0) is None


def test_zero_mean_channel_returns_none():
    """A black-frame channel (mean <= 0) collapses POS normalization."""
    n = MIN_SAMPLES_NORMAL
    rgb = _synthetic_pulse_rgb(bpm=72.0, n=n)
    rgb[:, 2] = 0.0  # kill the B channel
    assert pos_rppg(rgb, fs=30.0) is None


def test_low_light_requires_longer_window():
    """MIN_SAMPLES_LOW_LIGHT=600 — a 300-sample window must None out."""
    rgb = _synthetic_pulse_rgb(bpm=72.0, n=MIN_SAMPLES_NORMAL)
    assert pos_rppg(rgb, fs=30.0, low_light=True) is None


def test_confidence_mapping_anchors_to_active_threshold():
    """A SNR exactly at the normal threshold yields confidence 0.0;
    at threshold+ramp, confidence 1.0; low_light scales down."""
    assert confidence_for_snr(SNR_THRESHOLD_DB_NORMAL, low_light=False) == 0.0
    assert confidence_for_snr(SNR_THRESHOLD_DB_NORMAL + 10, low_light=False) == 1.0
    assert confidence_for_snr(SNR_THRESHOLD_DB_LOW_LIGHT + 10, low_light=True) == pytest.approx(0.7)
    assert confidence_for_snr(0.0, low_light=False) == 0.0  # clamped from negative


def test_out_of_physio_range_pulse_flagged():
    """A 150 BPM pulse (2.5 Hz — above the valid-user ceiling 110 but
    inside the frequency band 42-180) must be pulse_detected=True but
    bpm_in_range=False. The split is what lets the orchestrator reject
    a pulse-like signal that doesn't match human physiology."""
    rgb = _synthetic_pulse_rgb(bpm=150.0, n=MIN_SAMPLES_NORMAL)
    result = pos_rppg(rgb, fs=30.0)
    assert result is not None
    assert result["pulse_detected"] is True
    assert result["bpm_in_range"] is False
