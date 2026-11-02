"""Layer 3 — rPPG (Plane-Orthogonal-to-Skin algorithm, Wang et al. 2015).

Reference implementation. The Android (`Layer3RPPG.kt`) and iOS mirrors
re-derive the same bpm/SNR/confidence from the same R/G/B stream; the
server re-runs this module from `layer_scores` before issuing a JWT.
Numeric parity across all three is the whole point — any drift in
thresholds here needs the sibling edits in both Kotlin and Swift.
"""

from __future__ import annotations

import math
from typing import Sequence

import numpy as np
from scipy.signal import butter, filtfilt

# Spec §1385 — valid pulse band.
LOW_FREQ_HZ = 0.7
HIGH_FREQ_HZ = 3.0

# Spec §1397 — valid-user bpm window.
BPM_IN_RANGE_LO = 50.0
BPM_IN_RANGE_HI = 110.0

# Spec §1396 + §1403-1404. Low light raises the camera noise floor, so
# we both relax the detection threshold and scale down the confidence.
SNR_THRESHOLD_DB_NORMAL = 3.0
SNR_THRESHOLD_DB_LOW_LIGHT = 2.0
LOW_LIGHT_CONFIDENCE_SCALE = 0.7

# Spec §1360 / §1403 — minimum capture window.
MIN_SAMPLES_NORMAL = 300
MIN_SAMPLES_LOW_LIGHT = 600

# Spec §1398 — 10 dB above the detection threshold maps to confidence=1.0.
CONFIDENCE_RAMP_DB = 10.0


def bandpass_filter(signal: Sequence[float], low: float, high: float, fs: float) -> np.ndarray:
    """4th-order Butterworth bandpass, zero-phase via filtfilt.

    Mirrors Spec §1370-1375. filtfilt handles edge effects by reflecting
    the signal, which keeps the FFT peak location stable across the
    short (300-sample) windows the mobile capture produces.
    """
    x = np.asarray(signal, dtype=np.float64)
    nyq = 0.5 * fs
    b, a = butter(4, [low / nyq, high / nyq], btype="band")
    return filtfilt(b, a, x)


def estimate_organic_hf(magnitude: np.ndarray) -> float:
    """Baseline high-frequency energy — the ring just above the pulse band.

    Used as the noise floor against which the in-band peak is compared.
    Returns `mean(|X[k]|)` for k in the ring between HIGH_FREQ_HZ and
    2*HIGH_FREQ_HZ. Caller passes a magnitude spectrum already indexed
    on a known frequency axis; here we just slice the matching bins.
    """
    if magnitude.size == 0:
        return 0.0
    return float(np.mean(magnitude))


def confidence_for_snr(snr_db: float, low_light: bool) -> float:
    """Pure dB→confidence mapping, anchored to the active threshold.

    Anchoring to the SAME threshold `pulse_detected` compares against
    guarantees `confidence > 0` implies `pulse_detected = True` for a
    given session.
    """
    threshold = SNR_THRESHOLD_DB_LOW_LIGHT if low_light else SNR_THRESHOLD_DB_NORMAL
    c = max(0.0, min(1.0, (snr_db - threshold) / CONFIDENCE_RAMP_DB))
    if low_light:
        c *= LOW_LIGHT_CONFIDENCE_SCALE
    return c


def pos_rppg(rgb_signal, fs: float = 30.0, low_light: bool = False):
    """Plane-Orthogonal-to-Skin rPPG per Wang et al. 2015, Spec §1356-1405.

    Parameters
    ----------
    rgb_signal : array_like, shape (N, 3)
        Per-frame mean (R, G, B) of the skin ROI. N must be at least
        MIN_SAMPLES_NORMAL (300) in normal light, MIN_SAMPLES_LOW_LIGHT
        (600) in low light.
    fs : float
        Capture frame rate in Hz.
    low_light : bool
        Reads from AmbientConditions.low_light_detected. Relaxes the
        detection threshold and scales confidence down.

    Returns
    -------
    dict | None
        `None` if the window is too short or the signal is degenerate
        (zero mean on a channel → black frame; zero S2 std → tracking
        failure). Otherwise a dict with:
            bpm, pulse_detected, bpm_in_range, confidence, snr_db.
    """
    rgb = np.asarray(rgb_signal, dtype=np.float64)
    if rgb.ndim != 2 or rgb.shape[1] != 3:
        return None
    n = rgb.shape[0]
    min_samples = MIN_SAMPLES_LOW_LIGHT if low_light else MIN_SAMPLES_NORMAL
    if n < min_samples:
        return None

    # Step 1: normalize each channel by its temporal mean.
    means = rgb.mean(axis=0)
    if np.any(means <= 0):
        return None
    normalized = rgb / means

    r = normalized[:, 0]
    g = normalized[:, 1]
    b = normalized[:, 2]

    # Step 2: project onto skin-orthogonal plane (Wang 2015 Eq. 10).
    s1 = 3.0 * r - 2.0 * g
    s2 = 1.5 * r + g - 1.5 * b

    # Step 3: alpha-weighted subtraction removes specular artefacts.
    std_s2 = s2.std()
    if std_s2 <= 0:
        return None
    alpha = s1.std() / std_s2
    p = s1 - alpha * s2

    # Step 4: Butterworth bandpass to the physiological band.
    try:
        p_filt = bandpass_filter(p, LOW_FREQ_HZ, HIGH_FREQ_HZ, fs)
    except ValueError:
        return None

    # Step 5: real-signal FFT, mask to the valid band.
    spectrum = np.fft.rfft(p_filt - p_filt.mean())
    powers = (spectrum * spectrum.conj()).real
    freqs = np.fft.rfftfreq(n, d=1.0 / fs)
    band_mask = (freqs >= LOW_FREQ_HZ) & (freqs <= HIGH_FREQ_HZ)
    if not np.any(band_mask):
        return None
    band_powers = powers[band_mask]
    band_freqs = freqs[band_mask]

    peak_idx = int(np.argmax(band_powers))
    peak_power = float(band_powers[peak_idx])
    mean_power = float(band_powers.mean())
    peak_freq = float(band_freqs[peak_idx])
    bpm = peak_freq * 60.0

    # Step 6: SNR in dB; guard the denominator so a static image can't
    # produce +Inf and silently pass.
    snr_db = 10.0 * math.log10(peak_power / max(mean_power, 1e-10))
    threshold = SNR_THRESHOLD_DB_LOW_LIGHT if low_light else SNR_THRESHOLD_DB_NORMAL
    return {
        "bpm": bpm,
        "pulse_detected": snr_db > threshold,
        "bpm_in_range": BPM_IN_RANGE_LO <= bpm <= BPM_IN_RANGE_HI,
        "confidence": confidence_for_snr(snr_db, low_light),
        "snr_db": snr_db,
    }
