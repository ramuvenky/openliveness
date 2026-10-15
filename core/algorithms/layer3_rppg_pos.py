"""Layer 3 — rPPG (Plane-Orthogonal-to-Skin algorithm, Wang et al. 2015)."""


def pos_rppg(rgb_signal):
    """TODO Phase 5: POS rPPG implementation (normalize, project, bandpass, FFT, SNR)."""
    raise NotImplementedError


def bandpass_filter(signal, low, high, fs):
    """TODO Phase 5: 4th-order Butterworth bandpass."""
    raise NotImplementedError


def estimate_organic_hf(magnitude):
    """TODO Phase 5: baseline HF energy from mid-frequency ring."""
    raise NotImplementedError
